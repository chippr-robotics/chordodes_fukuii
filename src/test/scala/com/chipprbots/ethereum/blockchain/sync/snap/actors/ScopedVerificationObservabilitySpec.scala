package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** T018 (US3, V6 / FR-010): observability of the scoped post-heal verification path.
  *
  * A scoped run MUST move the additive `scoped_*` gauges (mode gauge 1); a run with scoping disabled by config MUST
  * take the full-root walk (mode gauge 0). After the S3 Pekko Typed migration the coordinator logs via `context.log`
  * (SLF4J), which does not flow through Pekko's `TestEventListener` event stream, so the engagement/disabled assertions
  * are made through the static gauge registry — the gauge is moved on the same code path that emits the log and is a
  * stronger, deterministic signal than the log text.
  */
class ScopedVerificationObservabilitySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02)))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"obs-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"obs-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  private def awaitStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
      case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
      case _                                         => FishingOutcomes.continueAndIgnore
    }

  private def withFixture(
      scoped: Boolean
  )(
      body: (
          ActorRef[TrieNodeHealingCoordinator.Command],
          HealingFrontierStorage,
          org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
      ) => Unit
  ): Unit =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scoped-obs-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig:
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      ,
      Namespaces.nsSeq
    )
    val store = new HealingFrontierStorage(dataSource)
    store.markComplete()

    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(ec),
      scopedHealVerification = scoped
    )
    try body(coordinator, store, controller)
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def driveHeal(coordinator: ActorRef[TrieNodeHealingCoordinator.Command], peerName: String): Int =
    val nodes = (0 until 3).map(cleanLeaf)
    val peer = PeerTestHelpers.createTestPeer(peerName, testKit.createTestProbe[Any]().ref.toClassic)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
      SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
    )
    nodes.size

  // S3 (Pekko Typed migration): the coordinator now logs via `context.log` (SLF4J), so its INFO lines no longer flow
  // through Pekko's `TestEventListener` event stream — `EventFilter.intercept` can no longer observe them. The
  // engagement/disabled assertions are therefore made through the additive `scoped_*` gauges, which the coordinator
  // moves on the SAME code path that emits the log (startScopedVerification / the "disabled" full-root arm). The gauge
  // value is a stronger, deterministic signal than the log text: scoped ⇒ mode gauge 1; full-root ⇒ mode gauge 0.
  "Scoped verification observability" should
    "move the scoped_* gauges when scoping engages" taggedAs UnitTest in {
      withFixture(scoped = true) { (coordinator, _, controller) =>
        SNAPSyncMetrics.setHealingScopedVerification(-1L)
        val n = driveHeal(coordinator, "obs-scoped-peer")
        awaitStateHealingComplete(controller)
        gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.scoped_subtrees.gauge") shouldBe n.toDouble +- 1e-9
        gaugeValue("snapsync.healing.scoped_duration_ms.gauge") should be >= 0.0
      }
    }

  it should "take the full-root path (mode gauge 0) when scoping is disabled by config" taggedAs UnitTest in {
    withFixture(scoped = false) { (coordinator, _, controller) =>
      SNAPSyncMetrics.setHealingScopedVerification(-1L)
      driveHeal(coordinator, "obs-disabled-peer")
      awaitStateHealingComplete(controller)
      // Full-root path sets the mode gauge to 0 (scoped would set 1).
      gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 0.0 +- 1e-9
    }
  }
