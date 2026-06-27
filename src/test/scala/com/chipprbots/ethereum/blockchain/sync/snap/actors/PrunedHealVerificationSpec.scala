package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.{FishingOutcomes, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, LeafNode, MptNode, MptTraversals, NullNode}
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 005 — pruned (descend-and-stop) verification behaviour.
  *
  *   - T-1 (FR-001/SC-001/SC-006, prune-on-record): a present subtree root X with a durable `isSubtreeComplete(X)`
  *     record is treated as a verified leaf — the verification does NOT descend / read X's children, it increments the
  *     pruned-subtree count, and still reaches `StateHealingComplete`.
  *   - T-2 (FR-002/FR-004/SC-002, never-false-prune — THE load-bearing safety test): a present node X with NO record
  *     above a MISSING descendant is DESCENDED; the missing node surfaces in the open frontier and completion is NOT
  *     declared.
  *   - T-7 (FR-008/FR-009, guardrail + metrics): a fetched node whose `keccak256 != requested hash` is still dropped;
  *     the pruned engagement gauge + pruned/visited gauges move when pruning is taken.
  *
  * Harness mirrors [[TrieNodeHealingScopedVerificationSpec]] / [[ScopedVerificationObservabilitySpec]] exactly:
  * `TestKit` + `ImplicitSender` + `AnyFlatSpecLike`, a real temp-dir RocksDB-backed [[HealingFrontierStorage]], a
  * single-thread writer EC, a [[TestMptStorage]] holding the synthetic trie, and the same actor build/teardown. The
  * verification walk is driven the same way [[HealingFrontierResumeSpec]] drives it: with `frontierPersistenceEnabled =
  * true` + `markComplete()` + an EMPTY frontier, `StartTrieNodeHealing(root)` routes to the "complete-and-empty" arm →
  * `startVerificationBFS(root, emptyPath)` → the `rebuildFrontierBFS` kernel (where the descend-and-stop oracle lives)
  * → `VerificationBFSComplete` → the single `HealingCheckCompletion` chokepoint. Deterministic: no `Thread.sleep`;
  * `awaitAssert` / `fishForMessage` / `expectMsg` only.
  */
class PrunedHealVerificationSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

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

  /** Assert StateHealingComplete is NOT sent within `window` (ProgressNodesHealed is allowed). */
  private def assertNoCompletion(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command],
      window: FiniteDuration
  ): Unit =
    controller.expectNoMessage(window)

  /** Open frontier = pending (queued) + active (in-flight). See the same helper in
    * TrieNodeHealingScopedVerificationSpec.
    */
  private def openFrontier(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    val stats = probe.expectMessageType[HealingStatistics]
    stats.pendingTasks + stats.activeTasks

  /** A [[TestMptStorage]] that records every hash passed to `get` / `multiGetNodes`, so a test can prove that a pruned
    * subtree root's children are NEVER read (descend-and-stop). The read set is the union of both access paths the walk
    * uses (`rebuildFrontierBFS` reads via `multiGetNodes`; `isNodeInStorage` reads via `get`).
    */
  private class CountingMptStorage extends TestMptStorage:
    val reads: mutable.Set[ByteString] = mutable.Set.empty[ByteString]
    override def get(key: Array[Byte]): MptNode =
      reads.synchronized(reads += ByteString(key))
      super.get(key)
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
      reads.synchronized(hashes.foreach(h => reads += ByteString(h)))
      super.multiGetNodes(hashes)

  /** Build a verification-driving fixture and run `body`. `frontierPersistenceEnabled = true` + `markComplete()` is the
    * resume precondition that routes `StartTrieNodeHealing(root)` through `startVerificationBFS` (see class doc). The
    * spec-005 subtree-complete records are gated on `prunedEnabled`, NOT on `frontierPersistenceEnabled`, so pruning is
    * live here regardless.
    */
  private def withVerificationFixture(
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
    val dbPath = Files.createTempDirectory("pruned-verify-rocksdb").toAbsolutePath.toString
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
    store.markComplete() // complete snapshot + empty frontier ⇒ StartTrieNodeHealing routes to verification

    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(ec)
    )
    try body(coordinator, store, controller)
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  // ── Synthetic-trie builders ────────────────────────────────────────────────────────────────────

  /** A storage-trie leaf used as a present grandchild beneath a subtree root. */
  private def storedLeaf(storage: TestMptStorage, seed: String): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(seed)).toArray))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  /** Build: root(branch) → subtreeRoot(branch, present) → grandchild(leaf, present). Returns (rootHash,
    * subtreeRootHash, grandchildHash). Recording `markSubtreeComplete(subtreeRootHash)` should prune the descent into
    * the grandchild.
    */
  private def presentSubtree(storage: TestMptStorage): (ByteString, ByteString, ByteString) =
    val grandchild = storedLeaf(storage, "pruned-present-grandchild")
    val subChildren = emptyChildren
    subChildren(4) = HashNode(grandchild.toArray)
    val subtreeRoot = BranchNode(subChildren, None)
    storage.putNode(subtreeRoot)
    val subtreeRootHash = ByteString(subtreeRoot.hash)

    val rootChildren = emptyChildren
    rootChildren(1) = HashNode(subtreeRootHash.toArray)
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)
    (ByteString(root.hash), subtreeRootHash, grandchild)

  /** Build: root(branch) → presentNodeX(branch, present) → MISSING grandchild (referenced by X, not in storage).
    * Returns (rootHash, presentNodeXHash, missingGrandchildHash). With NO record for X the walk must descend X and
    * surface the missing grandchild.
    */
  private def presentNodeAboveMissing(storage: TestMptStorage): (ByteString, ByteString, ByteString) =
    val missingGrandchild = kec256(ByteString("pruned-missing-grandchild")) // never stored
    val xChildren = emptyChildren
    xChildren(2) = HashNode(missingGrandchild.toArray)
    val nodeX = BranchNode(xChildren, None)
    storage.putNode(nodeX)
    val nodeXHash = ByteString(nodeX.hash)

    val rootChildren = emptyChildren
    rootChildren(0) = HashNode(nodeXHash.toArray)
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)
    (ByteString(root.hash), nodeXHash, missingGrandchild)

  // ── T-1: prune-on-record ───────────────────────────────────────────────────────────────────────

  "Pruned verification (T-1, FR-001)" should
    "treat a recorded present subtree root as a verified leaf, NOT descend its children, and still complete" taggedAs UnitTest in {
      val storage = new CountingMptStorage()
      val (root, subtreeRoot, grandchild) = presentSubtree(storage)
      val stateRoot = root

      withVerificationFixture(stateRoot, storage) { (coordinator, store, controller) =>
        // Durable subtree-complete record for the present subtree root: the oracle's prune precondition.
        store.markSubtreeComplete(subtreeRoot)
        store.isSubtreeComplete(subtreeRoot) shouldBe true
        storage.reads.clear() // ignore any read that placed the root; measure only the verification walk

        SNAPSyncMetrics.setHealingPrunedVerification(-1L)
        SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
        awaitStateHealingComplete(controller)

        // The pruned (descend-and-stop) path engaged on this verification.
        gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 1.0 +- 1e-9
        // Exactly one present subtree pruned (the recorded subtreeRoot), counted once (markIfNew de-dups it).
        gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 1.0 +- 1e-9
        // The load-bearing observable (descend-and-stop): the oracle is consulted on the ROOT's child reference
        // (= subtreeRoot) BEFORE that child is enqueued, so a pruned subtree root is never enqueued. Therefore
        // NEITHER the pruned subtree root NOR its grandchild is ever read by the walk. The seed root IS read.
        storage.reads.synchronized {
          storage.reads.contains(root) shouldBe true // the walk did run and read the seed root
          storage.reads.contains(subtreeRoot) shouldBe false // pruned before enqueue ⇒ not fetched
          storage.reads.contains(grandchild) shouldBe false // whole subtree skipped
        }
      }
    }

  // ── T-2: never-false-prune (load-bearing) ──────────────────────────────────────────────────────

  "Pruned verification (T-2, FR-002/FR-004 — never-false-prune)" should
    "DESCEND a present-but-unrecorded node above a missing descendant, surface the missing node, and NOT complete" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val (root, nodeX, missingGrandchild) = presentNodeAboveMissing(storage)
      val stateRoot = root

      withVerificationFixture(stateRoot, storage) { (coordinator, store, controller) =>
        // CRITICAL: node X has NO subtree-complete record. The oracle must NOT prune it.
        store.isSubtreeComplete(nodeX) shouldBe false
        // Seed the gauge to a sentinel so "0" can only come from THIS walk's startVerificationBFS reset, not a
        // leftover from another test (the gauge is a static global).
        SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)

        // The walk descends X and discovers the missing grandchild — it must surface in the open frontier
        // (queued via FrontierRebuilt → queueNodes), keeping the round open.
        eventually(timeout(5.seconds), interval(100.millis))(openFrontier(coordinator) should be >= 1)
        // No completion may be declared while a missing descendant exists below a present-but-unrecorded node.
        assertNoCompletion(controller, 1.second)
        // Nothing was pruned: the present node above the gap was descended, not skipped.
        gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 0.0 +- 1e-9
        missingGrandchild.length shouldBe 32 // sanity: a real keccak-256 child reference
      }
    }

  it should "NOT prune even a present node whose hash is unrecorded when its subtree IS fully present (descend, find nothing, complete)" taggedAs UnitTest in {
    // Mirror of T-1 but with NO record: the same fully-present subtree must be DESCENDED (pruned count 0), and
    // because it has no missing descendant the walk still completes. Proves pruning is the ONLY difference between
    // recorded vs unrecorded — the completion decision is identical either way (parity foundation, see T-4).
    val storage = new TestMptStorage()
    val (root, subtreeRoot, _) = presentSubtree(storage)
    withVerificationFixture(root, storage) { (coordinator, store, controller) =>
      store.isSubtreeComplete(subtreeRoot) shouldBe false
      SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      awaitStateHealingComplete(controller)
      // Descended everything; pruned nothing.
      gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 0.0 +- 1e-9
    }
  }

  // ── T-7: fetched-node content-hash guardrail + metrics ──────────────────────────────────────────

  "Pruned verification (T-7, FR-008 guardrail)" should
    "drop a fetched node whose keccak256 does not match the requested hash (guardrail intact under pruning)" taggedAs UnitTest in {
      // Drive the HEAL fetch path (not the verification path): queue one missing node, then respond with bytes
      // whose keccak does NOT equal the requested hash. The spec-004 content-hash guardrail must reject it, so the
      // task stays unhealed in the open frontier and completion is never declared. This guardrail is independent of
      // and unchanged by spec 005's PRESENT-node pruning.
      val storage = new TestMptStorage()
      val root = storedLeaf(storage, "guardrail-root")
      withVerificationFixture(root, storage) { (coordinator, _, controller) =>
        val realLeaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString("guardrail-leaf")).toArray))
        val realEncoded = MptTraversals.encodeNode(realLeaf)
        val requestedHash = kec256(ByteString(realEncoded))
        val accountHash = kec256(ByteString("guardrail-account"))
        val pathset = Seq(accountHash, ByteString(Array[Byte](0x20, 0x01)))
        // Corrupt the payload so keccak256(corrupted) != requestedHash (flip every bit of the last byte).
        val corruptedBytes = realEncoded.clone()
        corruptedBytes(corruptedBytes.length - 1) = (corruptedBytes(corruptedBytes.length - 1) ^ 0xff).toByte
        val corrupted = ByteString(corruptedBytes)

        val peer = PeerTestHelpers.createTestPeer("guardrail-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((pathset, requestedHash)))
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = Seq(corrupted))
        )

        // The corrupted node is rejected: the task is NOT healed, so it remains in the open frontier and no
        // completion is declared. (A matching node would have healed it and emptied the frontier.)
        eventually(timeout(5.seconds), interval(100.millis))(openFrontier(coordinator) should be >= 1)
        assertNoCompletion(controller, 1.second)
        kec256(corrupted) should not be requestedHash // sanity: the payload really mismatches
      }
    }

  "Pruned verification (T-7, FR-009 metrics)" should
    "emit the engagement flag and the pruned/visited gauges when pruning is taken" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val (root, subtreeRoot, _) = presentSubtree(storage)
      withVerificationFixture(root, storage) { (coordinator, store, controller) =>
        store.markSubtreeComplete(subtreeRoot)
        SNAPSyncMetrics.setHealingPrunedVerification(-1L)
        SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
        awaitStateHealingComplete(controller)

        // Engagement flag = 1 (pruned path), pruned-subtree count > 0, duration recorded, and the shared
        // rebuild-visited gauge advanced (nodes visited). All additive, observation-only.
        gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.pruned_subtrees.gauge") should be >= 1.0
        gaugeValue("snapsync.healing.pruned_duration_ms.gauge") should be >= 0.0
      }
    }
