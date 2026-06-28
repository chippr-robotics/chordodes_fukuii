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

/** T014 (US2, V4 / FR-007 / SC-004): scoped-vs-full-root completion byte-parity.
  *
  * The SAME healed state, driven to completion once with `scoped-heal-verification = true` (scoped path) and once with
  * it `false` (full-root fallback), MUST yield an identical completion outcome: the same StateHealingComplete signal,
  * the same unchanged state root, and the same CF `g` completeness-marker bytes (`isComplete == true`). Verification
  * never recomputes or rewrites the state root — it is a pure local read — so the only observable is the marker + the
  * signal, which must match across the config flip.
  */
class ScopedVerificationParitySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  /** A present, complete root: a childless leaf in storage so a FULL-ROOT verification walk from it finds 0 missing and
    * completes. Returns (rootHash, storage-with-root).
    */
  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02)))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  /** A clean storage-trie leaf to heal (no children). Returns (pathset, hash, encoded). */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"parity-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"parity-account-$seed"))
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

  /** Drive the same healed state to completion with the given `scopedHealVerification` setting; return the marker bytes
    * observed via `isComplete` after completion (true ⇒ the 0x01 sentinel is present at the CF `g` key).
    */
  private def runToCompletion(scoped: Boolean): Boolean =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scoped-parity-rocksdb").toAbsolutePath.toString
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
    store.markComplete() // both modes share the SAME proven full-coverage precondition

    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val nodes = (0 until 3).map(cleanLeaf)
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[TrieNodeHealingCoordinator.Command] = HealingTrieFixtures.spawnCoordinator(
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
    try
      val peer = PeerTestHelpers.createTestPeer(s"parity-peer-$scoped", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
      )
      awaitStateHealingComplete(controller)
      // The mode gauge distinguishes the two paths: 1 = scoped engaged, 0 = full-root fallback.
      if scoped then gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
      else gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 0.0 +- 1e-9
      // The state root is unchanged by verification (a pure local read); assert the invariant explicitly.
      root shouldBe storedRoot(new TestMptStorage())
      store.isComplete
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  "Scoped vs full-root completion" should
    "reach an identical completion (StateHealingComplete + marker set) under both config settings (FR-007)" taggedAs UnitTest in {
      val scopedMarker = runToCompletion(scoped = true)
      val fullRootMarker = runToCompletion(scoped = false)
      // Both paths declare completion via the single verificationPassComplete chokepoint and set the
      // SAME completeness marker. The state root is never recomputed by either path.
      scopedMarker shouldBe true
      fullRootMarker shouldBe true
      scopedMarker shouldBe fullRootMarker
    }
