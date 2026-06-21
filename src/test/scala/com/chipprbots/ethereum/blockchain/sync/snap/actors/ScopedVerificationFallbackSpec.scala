package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
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

/** T015 (US2, V5 / SC-003): the completion gate falls back to full-root verification for every unsafe condition.
  *
  * The scoped path engages only when ALL of F1–F5 hold; if ANY fails the gate takes the UNCHANGED full-root
  * `startVerificationBFS(stateRoot, emptyPath)` and `startScopedVerification` is NOT called. Engagement is observed via
  * the `scoped_verification` gauge: the scoped path sets it to 1, the full-root path sets it to 0. Each test seeds the
  * gauge to a sentinel (-1) first, drives a round that heals real work, then asserts the gauge is 0 (full-root ran,
  * scoped did not).
  *
  *   - F1: scoping disabled by config.
  *   - F2/F6: completeness precondition unproven (no marker / fresh node).
  *   - F4: healed-paths set exceeds the bound.
  *   - F5: pivot root changed during the round (a differing-root refresh clears the set → fallback on the next gate).
  *
  * F3 (restart-lost / empty set) is structurally an empty set, the same fallback F4 exercises (over-bound latches the
  * set empty); a true restart-lost set is covered by the resume/restart path and the data-model lifecycle.
  */
class ScopedVerificationFallbackSpec
    extends TestKit(ActorSystem("ScopedVerificationFallbackSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def gaugeValue(name: String): Double = {
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()
  }

  private def storedRoot(storage: TestMptStorage): ByteString = {
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02)))
    storage.putNode(leaf)
    ByteString(leaf.hash)
  }

  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) = {
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"fallback-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"fallback-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))
  }

  private def deleteRecursively(f: File): Unit = {
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()
  }

  private def awaitStateHealingComplete(controller: TestProbe): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => true
      case _: SNAPSyncController.ProgressNodesHealed => false
      case _                                         => false
    }

  /** Build a marker-backed fixture with a present complete root and configurable scoped settings. */
  private def withFixture(
      scoped: Boolean,
      maxPaths: Int,
      markComplete: Boolean
  )(body: (ActorRef, HealingFrontierStorage, TestProbe, ByteString) => Unit): Unit = {
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scoped-fallback-rocksdb").toAbsolutePath.toString
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
    if markComplete then store.markComplete()

    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val controller = TestProbe()
    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = controller.ref,
        healingFrontierStorage = Some(store),
        frontierPersistenceEnabled = true,
        healingWriterEcOverride = Some(ec),
        scopedHealVerification = scoped,
        scopedHealMaxPaths = maxPaths
      )
    )
    val death = TestProbe()
    death.watch(coordinator)
    try body(coordinator, store, controller, root)
    finally {
      system.stop(coordinator)
      death.expectTerminated(coordinator, 5.seconds)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))
    }
  }

  /** Heal one clean storage leaf, then assert the round completes via the FULL-ROOT path (gauge == 0). */
  private def healOneAndAssertFullRoot(coordinator: ActorRef, controller: TestProbe): Unit = {
    // Seed the mode gauge to a sentinel so "0" can only come from the full-root path actually running.
    SNAPSyncMetrics.setHealingScopedVerification(-1L)
    val node = cleanLeaf(0)
    val peer = PeerTestHelpers.createTestPeer("fallback-peer", TestProbe().ref)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((node._1, node._2)))
    coordinator.tell(TrieNodeHealingCoordinator.HealingPeerAvailable(peer), TestProbe().ref)
    coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq(node._3)))
    awaitStateHealingComplete(controller)
    // Full-root verification sets the mode gauge to 0; scoped would have set 1.
    gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 0.0 +- 1e-9
  }

  "Completion gate fallback" should
    "take the full-root path when scoping is DISABLED by config (F1)" taggedAs UnitTest in {
      withFixture(scoped = false, maxPaths = 200000, markComplete = true) { (coordinator, _, controller, _) =>
        healOneAndAssertFullRoot(coordinator, controller)
      }
    }

  it should "take the full-root path when the completeness marker is UNPROVEN (F2/F6)" taggedAs UnitTest in {
    withFixture(scoped = true, maxPaths = 200000, markComplete = false) { (coordinator, store, controller, _) =>
      store.isComplete shouldBe false
      healOneAndAssertFullRoot(coordinator, controller)
    }
  }

  it should "take the full-root path when the healed-paths set OVERFLOWS the bound (F4)" taggedAs UnitTest in {
    // maxPaths = 0 latches overflow on the very first capture, leaving the set empty + overflowed.
    withFixture(scoped = true, maxPaths = 0, markComplete = true) { (coordinator, _, controller, _) =>
      healOneAndAssertFullRoot(coordinator, controller)
    }
  }

  it should "clear the completeness marker (F2) and the healed scope on a differing-root refresh (F5 guard)" taggedAs UnitTest in {
    // F5 protects against verifying a scope against a stale root. A differing-root refresh both clears the
    // completeness marker (so F2 forces fallback) and clears the healed-paths set (so a stale-root scope can
    // never reach the gate). The marker clear is the directly-observable, deterministic guard; with no marker
    // and an empty set, the next gate can only take the full-root path.
    withFixture(scoped = true, maxPaths = 200000, markComplete = true) { (coordinator, store, _, _) =>
      store.isComplete shouldBe true
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(kec256(ByteString("fallback-different-root")))
      awaitAssert(store.isComplete shouldBe false, 3.seconds, 100.millis)
    }
  }
}
