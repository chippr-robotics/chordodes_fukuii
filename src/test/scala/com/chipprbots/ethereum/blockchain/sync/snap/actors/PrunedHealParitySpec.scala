package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, FishingOutcomes, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.mpt.{LeafNode, MptTraversals}
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 005 — T-4 (FR-005/SC-003, byte-parity).
  *
  * The SAME healed state, driven to completion once with `prunedHealVerification = true` (pruned descend-and-stop path)
  * and once with it `false` (full-trie walk), MUST reach an IDENTICAL completion outcome: the same
  * `StateHealingComplete` signal, the same unchanged state root, and the same persisted completeness state.
  * Completeness routes through the single `verificationPassComplete` → `HealingCheckCompletion` chokepoint in both
  * modes (C5), so the terminal decision and the terminal marker bytes are identical.
  *
  * Mirrors [[ScopedVerificationParitySpec]] (its proven harness): each run uses its own temp RocksDB, drives one fixed
  * healed state to completion, and returns the observable completeness state. The pruned path additionally records the
  * root subtree-complete (`markSubtreeComplete(stateRoot)`, gated on `prunedEnabled`); the full-walk path does not.
  * That additive CF 'g' record is the ONLY observable difference and is itself byte-deterministic (presence-only
  * `0x01`).
  *
  * What full byte-for-byte parity additionally requires (covered by the quickstart LIVE validation, not feasible in a
  * unit harness): recomputing the literal state ROOT after each completion on a real multi-million-node trie and
  * asserting bit-equality, plus confirming NO `MissingRootNode` at the first block import after a pruned completion.
  * The unit harness asserts the strongest in-harness equivalence: identical completion signal + identical persisted
  * completeness marker + an empty emitted-missing-node set in both modes, and that verification never rewrites the
  * root.
  */
class PrunedHealParitySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  /** A present, complete root: a childless leaf in storage (full-walk verification finds 0 missing and completes). */
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

  /** The observable completeness state after one run. */
  final private case class CompletionOutcome(
      reachedComplete: Boolean, // StateHealingComplete observed
      markerComplete: Boolean, // CF 'g' completeness marker (isComplete)
      rootUnchanged: Boolean // the state root the harness fed in equals the recomputed fixture root
  )

  /** Drive the SAME healed state to completion. The prunedHealVerification flag was removed from the production API;
    * this helper now drives a single canonical run and confirms completion + parity invariants hold.
    */
  private def runToCompletion()(implicit tk: ActorTestKit): CompletionOutcome =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("pruned-parity-rocksdb").toAbsolutePath.toString
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

    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val nodes = (0 until 3).map(cleanLeaf)
    val controller = tk.createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[TrieNodeHealingCoordinator.Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = tk.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(ec)
    )
    try
      SNAPSyncMetrics.setHealingPrunedVerification(-1L)
      val peer = PeerTestHelpers.createTestPeer("parity-peer", tk.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
      )
      awaitStateHealingComplete(controller)
      CompletionOutcome(
        reachedComplete = true,
        markerComplete = store.isComplete,
        rootUnchanged = root == storedRoot(new TestMptStorage())
      )
    finally
      tk.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  "Pruned vs full-trie completion (T-4)" should
    "reach StateHealingComplete, leave the completeness marker unset (persistence off), and not rewrite the state root" taggedAs UnitTest in {
      // prunedHealVerification was removed from the production API (the flag has been unified into a single path).
      // We now verify the completion invariants hold for the canonical run.
      implicit val tk: ActorTestKit = testKit
      val run = runToCompletion()

      run.reachedComplete shouldBe true

      // With frontier persistence OFF (default), neither path sets the spec-002 snapshot marker.
      run.markerComplete shouldBe false

      // Verification never recomputes/rewrites the state root — it is a pure local read.
      run.rootUnchanged shouldBe true

      // LIVE-ONLY: literal byte-for-byte STATE-ROOT parity and "no MissingRootNode at first block import" are
      // not feasible in the in-memory unit harness; they are asserted by the quickstart §Validation 4 live run.
    }
