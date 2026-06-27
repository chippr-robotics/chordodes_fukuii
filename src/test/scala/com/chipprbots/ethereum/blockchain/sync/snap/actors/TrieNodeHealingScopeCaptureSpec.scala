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
import org.scalatest.concurrent.Eventually
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

/** T009 (US1, V1 / FR-001): scope-capture completeness for spec 003 scoped post-heal verification.
  *
  * The healed-paths set MUST contain EXACTLY the nodes for which `totalNodesHealed` was incremented this round — no
  * skip, dedup by hash — so the scoped verification re-walks every healed subtree (R5 V1). The set is private actor
  * state; its size is observed through the `scoped_subtrees` gauge, which `startScopedVerification` sets to the seed
  * count (= captured-set size) on the actor thread when the completion gate engages the scoped path. Driving N distinct
  * heals against a marker-complete root and asserting the gauge equals N proves the capture is complete; re-serving a
  * duplicate hash and asserting the count is unchanged proves dedup.
  */
class TrieNodeHealingScopeCaptureSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  /** Build a storage-trie leaf so the heal site's `discoverMissingChildren` takes the no-children `case _` arm
    * (`pathset.size > 1`), keeping `isComplete` true after the response. Returns the (storage-trie pathset, hash, raw
    * encoded bytes) such that `kec256(encoded) == hash`, exactly what `handleResponse` matches on.
    */
  private def healableNode(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val value = ByteString(kec256(ByteString(s"scope-capture-value-$seed")).toArray)
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), value)
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"scope-capture-account-$seed"))
    val compactStoragePath = ByteString(Array[Byte](0x20, seed.toByte))
    (Seq(accountHash, compactStoragePath), hash, ByteString(encoded))

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  /** Wait for StateHealingComplete, ignoring the interleaved ProgressNodesHealed progress messages the controller probe
    * also receives from `handleResponse`.
    */
  private def awaitStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
      case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
      case _                                         => FishingOutcomes.continueAndIgnore
    }

  /** Real RocksDB-backed HealingFrontierStorage with the completeness marker pre-set (the scoped precondition), wired
    * into a coordinator with a single-thread EC, plus safe teardown (stop actor → drain EC → destroy DataSource).
    */
  private def withMarkerCompleteFixture(
      stateRoot: ByteString,
      storage: TestMptStorage
  )(
      body: (
          ActorRef[TrieNodeHealingCoordinator.Command],
          HealingFrontierStorage,
          org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
      ) => Unit
  ): Unit =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scope-capture-rocksdb").toAbsolutePath.toString
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
    store.markComplete() // simulate a prior full-trie clean walk against this root (E3 precondition)

    val controllerProbe = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controllerProbe.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(ec)
    )
    try body(coordinator, store, controllerProbe)
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  "Scoped heal verification scope capture" should
    "capture exactly the N healed nodes as scoped seeds (no skip)" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("scope-capture-root-A"))
      val storage = new TestMptStorage()
      val n = 5
      val nodes = (0 until n).map(healableNode)

      withMarkerCompleteFixture(stateRoot, storage) { (coordinator, _, controller) =>
        val peer = PeerTestHelpers.createTestPeer("scope-capture-peer-A", testKit.createTestProbe[Any]().ref.toClassic)
        // Queue the N healable nodes and make a peer available so they dispatch as one request (reqId=1).
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

        // Heal all N in a single TrieNodes response (the first generated requestId is 1).
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
        )

        // The healed nodes are storage-trie leaves with no children, so the round drains clean and the
        // completion gate engages the scoped path, seeding exactly the N captured subtrees.
        awaitStateHealingComplete(controller)
        gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.scoped_subtrees.gauge") shouldBe n.toDouble +- 1e-9
      }
    }

  it should "dedup a re-served node by hash (no double-count)" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("scope-capture-root-B"))
    val storage = new TestMptStorage()
    val n = 3
    val nodes = (0 until n).map(healableNode)

    withMarkerCompleteFixture(stateRoot, storage) { (coordinator, _, controller) =>
      val peer = PeerTestHelpers.createTestPeer("scope-capture-peer-B", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

      // Respond with the N node bodies PLUS a duplicate of the first — the duplicate matches the same
      // task hash (already captured), so the dedup-by-hash guard must NOT grow the captured set.
      val withDuplicate = nodes.map(_._3) :+ nodes.head._3
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = 1, nodes = withDuplicate)
      )

      awaitStateHealingComplete(controller)
      gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
      // Exactly n distinct subtrees despite the duplicate node in the response.
      gaugeValue("snapsync.healing.scoped_subtrees.gauge") shouldBe n.toDouble +- 1e-9
    }
  }

  it should "not leave pending tasks after healing storage-trie leaves (clean round)" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("scope-capture-root-C"))
    val storage = new TestMptStorage()
    val nodes = (0 until 2).map(healableNode)

    withMarkerCompleteFixture(stateRoot, storage) { (coordinator, _, _) =>
      val peer = PeerTestHelpers.createTestPeer("scope-capture-peer-C", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
      )
      eventually(timeout(5.seconds), interval(100.millis))(pendingTasks(coordinator) shouldBe 0)
    }
  }
