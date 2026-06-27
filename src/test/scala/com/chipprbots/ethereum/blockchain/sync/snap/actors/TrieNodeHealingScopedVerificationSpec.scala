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
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** T010 (V2) + T011 (V3 / FR-006): scoped post-heal verification behaviour.
  *
  *   - V2: with the completeness marker proven and a small CLEAN healed set, the completion gate engages the scoped
  *     path (gauge=1), the scoped walk re-walks only the healed subtrees, and the coordinator reaches
  *     StateHealingComplete.
  *   - V3: a healed node with a deeper MISSING descendant must NOT declare completion — the gap surfaces in the open
  *     frontier (queued or in-flight) and the round stays open until it is clean (FR-006).
  */
class TrieNodeHealingScopedVerificationSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  /** A clean storage-trie leaf (no children → scoped walk emits no frontier). Returns (pathset, hash, encoded). */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"clean-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"clean-leaf-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))

  /** A storage-trie BRANCH node whose only child is a MISSING hash (not in storage). Healing it leaves a gap below the
    * healed node that the scoped walk must surface. Returns (pathset, hash, encoded, missingChildHash).
    */
  private def branchWithMissingChild(seed: Int): (Seq[ByteString], ByteString, ByteString, ByteString) =
    val missingChild = kec256(ByteString(s"gap-below-missing-child-$seed"))
    val children = emptyChildren
    children(3) = HashNode(missingChild.toArray)
    val branch = BranchNode(children, None)
    val encoded = MptTraversals.encodeNode(branch)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"gap-below-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded), missingChild)

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  /** The OPEN frontier = pending (queued) + active (in-flight). A missing descendant discovered inline at the heal site
    * is enqueued to `pendingTasks`, then — because the heal response was non-empty so the peer stays eligible — the
    * same `handleResponse` call pipelines it straight into an in-flight `GetTrieNodes` request via
    * `dispatchIfPossible`, moving it from `pendingTasks` into `activeTasks`. Which bucket it lands in is a
    * non-deterministic pipelining detail; the FR-006 invariant is that it stays in the open frontier (pending OR
    * active), keeping the round open (`isComplete == false`) so no completion is declared while the gap is unhealed.
    */
  private def openFrontier(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    val stats = probe.expectMessageType[HealingStatistics]
    stats.pendingTasks + stats.activeTasks

  /** Wait for StateHealingComplete, ignoring interleaved ProgressNodesHealed messages. */
  private def awaitStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
      case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
      case _                                         => FishingOutcomes.continueAndIgnore
    }

  /** Assert StateHealingComplete is NOT sent within `window` (ProgressNodesHealed is allowed). */
  private def assertNoCompletion(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command],
      window: FiniteDuration
  ): Unit =
    val completionReceived =
      try
        controller.fishForMessage(window) {
          case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
          case _                                       => FishingOutcomes.continueAndIgnore
        }
        true
      catch case _: AssertionError => false
    if completionReceived then
      fail("StateHealingComplete was declared while a healed node still had a missing descendant (FR-006)")

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
    val dbPath = Files.createTempDirectory("scoped-verify-rocksdb").toAbsolutePath.toString
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

  // ── T010 (V2): scoped completion ──────────────────────────────────────────────────────────────

  "Scoped verification (V2)" should
    "engage the scoped path and reach StateHealingComplete on a clean healed set" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("scoped-verify-clean-root"))
      val storage = new TestMptStorage()
      val nodes = (0 until 4).map(cleanLeaf)

      withMarkerCompleteFixture(stateRoot, storage) { (coordinator, store, controller) =>
        store.isComplete shouldBe true
        val peer = PeerTestHelpers.createTestPeer("scoped-clean-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
        )

        awaitStateHealingComplete(controller)
        // The scoped path engaged (gauge=1), not the full-root fallback (which sets gauge=0).
        gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.scoped_subtrees.gauge") shouldBe nodes.size.toDouble +- 1e-9
        // The marker is preserved (re-set via the verified arm, the single chokepoint).
        store.isComplete shouldBe true
      }
    }

  // ── T011 (V3 / FR-006): gap below a healed node ───────────────────────────────────────────────

  "Scoped verification (V3 / FR-006)" should
    "NOT declare completion when a healed node has a missing descendant" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("scoped-verify-gap-root"))
      val storage = new TestMptStorage()
      val (pathset, hash, encoded, missingChild) = branchWithMissingChild(1)

      withMarkerCompleteFixture(stateRoot, storage) { (coordinator, _, controller) =>
        val peer = PeerTestHelpers.createTestPeer("scoped-gap-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((pathset, hash)))
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        // Heal the branch — its only child is missing, so a gap remains below the healed node.
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = Seq(encoded))
        )

        // The missing descendant must surface in the OPEN frontier (pending OR in-flight); the round MUST stay open.
        // Inline discovery enqueues it, then the non-empty heal response pipelines it straight into an active
        // request — so it is in `activeTasks`, not necessarily `pendingTasks`. Either keeps `isComplete` false.
        eventually(timeout(5.seconds), interval(100.millis))(openFrontier(coordinator) should be >= 1)
        // No completion is declared while the gap is unhealed (FR-006). ProgressNodesHealed is allowed.
        assertNoCompletion(controller, 1.second)
        missingChild.length shouldBe 32 // sanity: the gap hash is a real keccak-256 child reference
      }
    }
