package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.EventFilter
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
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

/** T018 (US3, V6 / FR-010): observability of the scoped post-heal verification path.
  *
  * A scoped run MUST emit the `[HEAL-VERIFY-SCOPED]` engagement log and move the additive `scoped_*` gauges; a run with
  * scoping disabled by config MUST emit the once-per-round "scoping disabled" log and take the full-root walk (mode
  * gauge 0). Logs are asserted deterministically via Pekko's `TestEventListener` + `EventFilter`; gauges via the static
  * registry. The actor system is configured with the test event listener so `EventFilter.intercept` can capture INFO
  * logs.
  */
class ScopedVerificationObservabilitySpec
    extends TestKit(
      ActorSystem(
        "ScopedVerificationObservabilitySpec",
        ConfigFactory
          .parseString(
            """pekko.loggers = ["org.apache.pekko.testkit.TestEventListener"]
              |pekko.loglevel = "INFO"
              |""".stripMargin
          )
          .withFallback(ConfigFactory.load())
      )
    )
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
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"obs-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"obs-account-$seed"))
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

  private def withFixture(scoped: Boolean)(body: (ActorRef, HealingFrontierStorage, TestProbe) => Unit): Unit = {
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("scoped-obs-rocksdb").toAbsolutePath.toString
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

    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val controller = TestProbe()
    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = controller.ref,
        healingFrontierStorage = Some(store),
        healingWriterEcOverride = Some(ec),
        scopedHealVerification = scoped
      )
    )
    val death = TestProbe()
    death.watch(coordinator)
    try body(coordinator, store, controller)
    finally {
      system.stop(coordinator)
      death.expectTerminated(coordinator, 5.seconds)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))
    }
  }

  private def driveHeal(coordinator: ActorRef, peerName: String): Int = {
    val nodes = (0 until 3).map(cleanLeaf)
    val peer = PeerTestHelpers.createTestPeer(peerName, TestProbe().ref)
    coordinator ! Messages.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
    coordinator.tell(Messages.HealingPeerAvailable(peer), TestProbe().ref)
    coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3)))
    nodes.size
  }

  "Scoped verification observability" should
    "emit the engagement log and move the scoped_* gauges when scoping engages" taggedAs UnitTest in {
      withFixture(scoped = true) { (coordinator, _, controller) =>
        SNAPSyncMetrics.setHealingScopedVerification(-1L)
        val n =
          EventFilter.info(start = "[HEAL-VERIFY-SCOPED] Scoped verification engaged", occurrences = 1).intercept {
            val seeded = driveHeal(coordinator, "obs-scoped-peer")
            awaitStateHealingComplete(controller)
            seeded
          }
        gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.scoped_subtrees.gauge") shouldBe n.toDouble +- 1e-9
        gaugeValue("snapsync.healing.scoped_duration_ms.gauge") should be >= 0.0
      }
    }

  it should "emit the 'scoping disabled' log and take the full-root path when disabled by config" taggedAs UnitTest in {
    withFixture(scoped = false) { (coordinator, _, controller) =>
      SNAPSyncMetrics.setHealingScopedVerification(-1L)
      EventFilter
        .info(start = "[HEAL-VERIFY-SCOPED] scoped verification disabled by config", occurrences = 1)
        .intercept {
          driveHeal(coordinator, "obs-disabled-peer")
          awaitStateHealingComplete(controller)
        }
      // Full-root path sets the mode gauge to 0 (scoped would set 1).
      gaugeValue("snapsync.healing.scoped_verification.gauge") shouldBe 0.0 +- 1e-9
    }
  }
}
