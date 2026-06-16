package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, LeafNode, MptNode, MptTraversals, NullNode}
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** T010 (V2) + T011 (V3 / FR-006): scoped post-heal verification behaviour.
  *
  *   - V2: with the completeness marker proven and a small CLEAN healed set, the completion gate engages the scoped
  *     path (gauge=1), the scoped walk re-walks only the healed subtrees, and the coordinator reaches
  *     StateHealingComplete.
  *   - V3: a healed node with a deeper MISSING descendant must NOT declare completion — the gap surfaces as a pending
  *     frontier and the round stays open until it is clean (FR-006).
  */
class TrieNodeHealingScopedVerificationSpec
    extends TestKit(ActorSystem("TrieNodeHealingScopedVerificationSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def gaugeValue(name: String): Double = {
    val gauge = Metrics.get().registry.find(name).gauge()
    if (gauge == null) Double.NaN else gauge.value()
  }

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  /** A clean storage-trie leaf (no children → scoped walk emits no frontier). Returns (pathset, hash, encoded). */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) = {
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"clean-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"clean-leaf-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))
  }

  /** A storage-trie BRANCH node whose only child is a MISSING hash (not in storage). Healing it leaves a gap below the
    * healed node that the scoped walk must surface. Returns (pathset, hash, encoded, missingChildHash).
    */
  private def branchWithMissingChild(seed: Int): (Seq[ByteString], ByteString, ByteString, ByteString) = {
    val missingChild = kec256(ByteString(s"gap-below-missing-child-$seed"))
    val children = emptyChildren
    children(3) = HashNode(missingChild.toArray)
    val branch = BranchNode(children, None)
    val encoded = MptTraversals.encodeNode(branch)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"gap-below-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded), missingChild)
  }

  private def deleteRecursively(f: File): Unit = {
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()
  }

  private def pendingTasks(coordinator: ActorRef): Int = {
    val probe = TestProbe()
    coordinator.tell(Messages.HealingGetProgress, probe.ref)
    probe.expectMsgType[HealingStatistics](2.seconds).pendingTasks
  }

  /** Wait for StateHealingComplete, ignoring interleaved ProgressNodesHealed messages. */
  private def awaitStateHealingComplete(controller: TestProbe): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => true
      case _: SNAPSyncController.ProgressNodesHealed => false
      case _                                         => false
    }

  /** Assert StateHealingComplete is NOT sent within `window` (ProgressNodesHealed is allowed). */
  private def assertNoCompletion(controller: TestProbe, window: FiniteDuration): Unit = {
    val deadline = window.fromNow
    while (deadline.hasTimeLeft())
      controller.receiveOne(deadline.timeLeft) match {
        case SNAPSyncController.StateHealingComplete =>
          fail("StateHealingComplete was declared while a healed node still had a missing descendant (FR-006)")
        case _ => () // ProgressNodesHealed or nothing — keep watching
      }
  }

  private def withMarkerCompleteFixture(
      stateRoot: ByteString,
      storage: TestMptStorage
  )(body: (ActorRef, HealingFrontierStorage, TestProbe) => Unit): Unit = {
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scoped-verify-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig {
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      },
      Namespaces.nsSeq
    )
    val store = new HealingFrontierStorage(dataSource)
    store.markComplete()

    val controllerProbe = TestProbe()
    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = controllerProbe.ref,
        healingFrontierStorage = Some(store),
        healingWriterEcOverride = Some(ec)
      )
    )
    val death = TestProbe()
    death.watch(coordinator)
    try body(coordinator, store, controllerProbe)
    finally {
      system.stop(coordinator)
      death.expectTerminated(coordinator, 5.seconds)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))
    }
  }

  // ── T010 (V2): scoped completion ──────────────────────────────────────────────────────────────

  "Scoped verification (V2)" should
    "engage the scoped path and reach StateHealingComplete on a clean healed set" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("scoped-verify-clean-root"))
      val storage = new TestMptStorage()
      val nodes = (0 until 4).map(cleanLeaf)

      withMarkerCompleteFixture(stateRoot, storage) { (coordinator, store, controller) =>
        store.isComplete shouldBe true
        val peer = PeerTestHelpers.createTestPeer("scoped-clean-peer", TestProbe().ref)
        coordinator ! Messages.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
        coordinator.tell(Messages.HealingPeerAvailable(peer), TestProbe().ref)
        coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3)))

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
        val peer = PeerTestHelpers.createTestPeer("scoped-gap-peer", TestProbe().ref)
        coordinator ! Messages.QueueMissingNodes(Seq((pathset, hash)))
        coordinator.tell(Messages.HealingPeerAvailable(peer), TestProbe().ref)
        // Heal the branch — its only child is missing, so a gap remains below the healed node.
        coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq(encoded)))

        // The missing descendant must surface as a pending frontier entry; the round MUST stay open.
        awaitAssert(pendingTasks(coordinator) should be >= 1, 5.seconds, 100.millis)
        // No completion is declared while the gap is unhealed (FR-006). ProgressNodesHealed is allowed.
        assertNoCompletion(controller, 1.second)
        missingChild.length shouldBe 32 // sanity: the gap hash is a real keccak-256 child reference
      }
    }
}
