package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, LeafNode, MptNode, NullNode}
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.testing.Tags.*

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 005 — T-5 (FR-003/SC-001, fresh-node seeding).
  *
  * On a fresh post-SNAP node, subtree-complete records are SEEDED during the SNAP/heal write path: a SNAP StackTrie
  * fragment that durably commits records its fragment root, and a heal closure that confirms a node has zero
  * still-missing children records that node. This test simulates that seeding by recording the relevant subtree roots
  * directly (the production seeding sites — AccountRangeCoordinator finalize and discoverMissingChildren — are unit-
  * tested for their write-ordering in [[PrunedHealCrashSafetySpec]]; here we assert the DOWNSTREAM consequence the
  * seeding exists to deliver): the FIRST completeness verification on that fresh node prunes the recorded subtrees with
  * NO prior full walk having run.
  *
  * "No prior full walk" is structurally guaranteed by the harness: the coordinator is freshly created, given no heal
  * work, and the very first thing it does is `StartTrieNodeHealing(root)` → the verification pass. Any pruning observed
  * is therefore on the first walk. Harness mirrors [[PrunedHealVerificationSpec]] / [[HealingFrontierResumeSpec]].
  */
class SubtreeCompleteSeedingSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

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

  /** Read-counting storage so we can prove a seeded subtree root's bytes are NOT fetched on the first verification. */
  private class CountingMptStorage extends TestMptStorage:
    val reads: mutable.Set[ByteString] = mutable.Set.empty[ByteString]
    override def get(key: Array[Byte]): MptNode =
      reads.synchronized(reads += ByteString(key))
      super.get(key)
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
      reads.synchronized(hashes.foreach(h => reads += ByteString(h)))
      super.multiGetNodes(hashes)

  private def storedLeaf(storage: TestMptStorage, seed: String): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(seed)).toArray))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  /** Build root(branch) with two present subtree children, each a branch over a present leaf. Returns (rootHash,
    * Seq(subtreeRootHash), Seq(grandchildHash)).
    */
  private def twoPresentSubtrees(storage: TestMptStorage): (ByteString, Seq[ByteString], Seq[ByteString]) =
    val built = (0 until 2).map { i =>
      val grandchild = storedLeaf(storage, s"seed-grandchild-$i")
      val children = emptyChildren
      children(i + 3) = HashNode(grandchild.toArray)
      val subtree = BranchNode(children, None)
      storage.putNode(subtree)
      (ByteString(subtree.hash), grandchild)
    }
    val rootChildren = emptyChildren
    built.zipWithIndex.foreach { case ((subHash, _), i) => rootChildren(i) = HashNode(subHash.toArray) }
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)
    (ByteString(root.hash), built.map(_._1), built.map(_._2))

  private def withFreshNode(
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
    val dbPath = Files.createTempDirectory("subtree-seeding-rocksdb").toAbsolutePath.toString
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
    // markComplete() + empty frontier ⇒ StartTrieNodeHealing routes straight to the verification pass (the FIRST
    // and only walk this fresh node runs).
    store.markComplete()

    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager =
        testKit.createTestProbe[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command]().ref,
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

  "Fresh-node seeding (T-5, FR-003)" should
    "prune subtrees seeded by SNAP-fragment + heal-closure records on the FIRST verification, with no prior full walk" taggedAs UnitTest in {
      val storage = new CountingMptStorage()
      val (root, subtreeRoots, grandchildren) = twoPresentSubtrees(storage)

      withFreshNode(root, storage) { (coordinator, store, controller) =>
        // Simulate the SNAP/heal seeding: a fragment commit records subtreeRoots(0), a heal closure records
        // subtreeRoots(1). On a real node these are written by AccountRangeCoordinator finalize / discoverMissingChildren.
        store.markSubtreeComplete(subtreeRoots(0)) // SNAP fragment-root seed (C3a)
        store.markSubtreeComplete(subtreeRoots(1)) // heal zero-missing-closure seed (C3b)
        subtreeRoots.foreach(h => store.isSubtreeComplete(h) shouldBe true)
        storage.reads.clear()

        SNAPSyncMetrics.setHealingPrunedVerification(-1L)
        SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
        // FIRST verification — the only walk this fresh node has run.
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
        awaitStateHealingComplete(controller)

        // Both seeded subtrees pruned on the first pass; pruned path engaged.
        gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe subtreeRoots.size.toDouble +- 1e-9
        // O(missing) = O(0) below the seeded roots: none of the seeded subtree roots or their grandchildren were
        // fetched. The first verification cost is bounded by the (here, empty) missing frontier, not the trie size.
        storage.reads.synchronized {
          (subtreeRoots ++ grandchildren).foreach(h => storage.reads.contains(h) shouldBe false)
        }
      }
    }
