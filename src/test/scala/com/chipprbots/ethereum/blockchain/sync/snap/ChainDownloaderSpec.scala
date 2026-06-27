package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.testing.Tags.*

/** Unit tests for the Pekko Typed `ChainDownloader` (Group S6 migration).
  *
  * The actor is a `Behavior[Any]`, so the previous white-box assertions via `TestActorRef#underlyingActor` are no
  * longer possible. Behaviour is exercised through the public protocol (`Start` / `UpdateTarget` / `YieldToRegularSync`
  * / `GetProgress`) and observable side effects on `AppStateStorage` (the persisted backfill target). `GetProgress` is
  * used as a synchronisation barrier and as a liveness probe: a reply proves the dispatch loop did not wedge.
  */
class ChainDownloaderSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with org.scalamock.scalatest.MockFactory
    with TestSyncConfig:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  // Regression for #1162: chain backfill keeps running in the background after SNAPSyncController
  // emits SnapSyncFinalized, but at lower priority. ChainDownloader needs a `YieldToRegularSync(n)`
  // message that mirrors `BoostConcurrency(n)` but downward — the SNAP controller sends it in
  // finalizeSnapSync() so backfill yields peer slots to forward sync.
  "ChainDownloader.YieldToRegularSync" should "carry the new concurrency budget" taggedAs UnitTest in {
    ChainDownloader.YieldToRegularSync(7).maxConcurrent shouldBe 7
  }

  it should "be a distinct message type from BoostConcurrency" taggedAs UnitTest in {
    val yielded: Any = ChainDownloader.YieldToRegularSync(7)
    val boosted: Any = ChainDownloader.BoostConcurrency(7)
    (yielded should not).equal(boosted)
  }

  // Behavioural test: the downloader's dispatch loop wedges if `maxConcurrentRequests` ever drops to
  // zero (the slot check `inFlightCount >= maxConcurrentRequests` would be true forever), stranding
  // the parent `SNAPSyncController` waiting for `ChainDownloader.Done` indefinitely. The handler clamps
  // to >=1. With no internal-state accessor on a Typed behavior, we assert the contract the clamp
  // protects: after YieldToRegularSync(0) the actor stays live and still answers GetProgress.
  "ChainDownloader" should "stay responsive after YieldToRegularSync(0) (clamp prevents dispatch wedge)" taggedAs UnitTest in {
    val (downloader, _, _) = newDownloader()
    downloader ! ChainDownloader.Start(BigInt(19_250_000))

    downloader ! ChainDownloader.YieldToRegularSync(0)
    expectProgress(downloader)

    downloader ! ChainDownloader.YieldToRegularSync(5)
    expectProgress(downloader)

    testKit.stop(downloader)
  }

  it should "stay responsive after BoostConcurrency then a downward YieldToRegularSync" taggedAs UnitTest in {
    val (downloader, _, _) = newDownloader()
    downloader ! ChainDownloader.Start(BigInt(19_250_000))

    downloader ! ChainDownloader.BoostConcurrency(16)
    expectProgress(downloader)

    downloader ! ChainDownloader.YieldToRegularSync(2)
    expectProgress(downloader)

    testKit.stop(downloader)
  }

  // Issue #1169: persist a BackfillTarget at Start so that a node killed mid-backfill can
  // resume standalone after restart.
  it should "persist BackfillTarget on Start so a restart can resume backfill" taggedAs UnitTest in {
    val (downloader, appStateStorage, _) = newDownloader()

    appStateStorage.getBackfillTarget() shouldBe BigInt(0)

    val target = BigInt(19_250_000)
    downloader ! ChainDownloader.Start(target)
    expectProgress(downloader) // barrier: Start has been processed

    appStateStorage.getBackfillTarget() shouldBe target

    testKit.stop(downloader)
  }

  // Issue #1169: an UpdateTarget message during the downloading phase persists the bumped
  // target so a restart resumes against the latest target rather than a stale one.
  it should "persist a bumped BackfillTarget on UpdateTarget" taggedAs UnitTest in {
    val (downloader, appStateStorage, _) = newDownloader()

    downloader ! ChainDownloader.Start(BigInt(1000))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1000)

    downloader ! ChainDownloader.UpdateTarget(BigInt(1500))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    // A target lower than the current one is ignored.
    downloader ! ChainDownloader.UpdateTarget(BigInt(1200))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    testKit.stop(downloader)
  }

  /** Sends `GetProgress` and waits for the `Progress` reply — both a synchronisation barrier (the actor has drained its
    * mailbox up to this point) and a liveness probe (a reply means the behavior did not wedge or stop).
    */
  private def expectProgress(
      downloader: TypedActorRef[ChainDownloader.Command]
  ): ChainDownloader.Progress =
    val probe = TestProbe()
    val typedProbe: TypedActorRef[ChainDownloader.Progress] = probe.ref.toTyped[ChainDownloader.Progress]
    downloader ! ChainDownloader.GetProgress(typedProbe)
    probe.expectMsgType[ChainDownloader.Progress](3.seconds)

  /** Spawn a Typed ChainDownloader (converted to a Classic ref for `!`). `findBestStoredHeader` probes block 1; mocking
    * it as missing makes the binary search return 0 immediately, leaving the actor in `downloading` with empty queues —
    * no real blockchain or peer infrastructure required. `peersScanInterval` is 1h in TestSyncConfig, so the periodic
    * peer scan never fires during a test (the immediate startup poll lands harmlessly on a fresh probe).
    */
  private def newDownloader(): (TypedActorRef[ChainDownloader.Command], AppStateStorage, TestProbe) =
    val blockchainReader = mock[BlockchainReader]
    val blockchainWriter = mock[BlockchainWriter]
    val appStateStorage = new AppStateStorage(EphemDataSource())
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    blockchainReader.getBlockHeaderByNumber
      .expects(BigInt(1))
      .returning(None)
      .anyNumberOfTimes()

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = blockchainReader,
          blockchainWriter = blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = defaultSyncConfig,
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-${System.nanoTime()}"
      )

    (downloader, appStateStorage, networkPeerManager)
