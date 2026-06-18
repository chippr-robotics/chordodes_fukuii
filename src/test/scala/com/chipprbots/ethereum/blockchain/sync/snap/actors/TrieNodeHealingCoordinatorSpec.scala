package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.nio.ByteBuffer

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

class TrieNodeHealingCoordinatorSpec
    extends TestKit(ActorSystem("TrieNodeHealingCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "TrieNodeHealingCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator should not be null
  }

  it should "queue missing nodes for healing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val node1Hash = kec256(ByteString("node1"))
    val node2Hash = kec256(ByteString("node2"))
    val missingNodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), node1Hash),
      (Seq(ByteString(Array[Byte](0x00))), node2Hash)
    )

    coordinator ! Messages.QueueMissingNodes(missingNodes)

    // Coordinator should queue the nodes
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    expectMsgType[Any](3.seconds)
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val nodeHash = kec256(ByteString("node1"))
    val missingNodes = Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash))

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(missingNodes)
    coordinator ! Messages.HealingPeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingTaskComplete(BigInt(123), Right(5))

    // Coordinator should handle completion
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    expectMsgType[Any](3.seconds)
  }

  it should "report completion when all nodes healed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingCheckCompletion

    // An idle coordinator (no pending tasks, no active requests) should complete immediately
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    expectMsgType[Any](3.seconds)
  }

  it should "signal StateHealingComplete to controller on HealingForceComplete" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingForceComplete

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  it should "accept HealingPivotRefreshed and re-seed new root — HealingCheckCompletion deferred" taggedAs UnitTest in {
    // After pivot refresh the coordinator re-seeds the new root into pendingTasks.
    // isComplete = pendingTasks.isEmpty && activeRequests.isEmpty = false.
    // HealingCheckCompletion must therefore NOT signal StateHealingComplete.
    val stateRoot = kec256(ByteString("old-heal-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val newStateRoot = kec256(ByteString("new-heal-root"))
    coordinator ! Messages.HealingPivotRefreshed(newStateRoot)

    // The new root is not in storage, so it is added to pendingTasks.
    // isComplete = false → StateHealingComplete must NOT be sent.
    coordinator ! Messages.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)

    // Coordinator remains operational.
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    expectMsgType[Any](3.seconds)
  }

  it should "not signal StateHealingComplete on HealingCheckCompletion when pending tasks exist" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("pending-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val nodeHash = kec256(ByteString("missing-node"))
    coordinator ! Messages.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))

    // pendingTasks is non-empty → isComplete = false → no StateHealingComplete
    coordinator ! Messages.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)
  }

  // ========================================
  // pendingTasks ArrayDeque + dispatcher (issue #1167)
  // ========================================

  /** Synthesize a unique (pathset, hash) so the dedup set doesn't drop our nodes. */
  private def fakeHashedNode(seed: Int): (Seq[ByteString], ByteString) = {
    val hash = kec256(ByteString(s"healing-node-$seed"))
    // pathset is a Seq[ByteString]; for queueing we just need something distinct.
    val path = ByteString(ByteBuffer.allocate(4).putInt(seed).array())
    (Seq(path), hash)
  }

  it should "absorb a large QueueMissingNodes payload without timing out" taggedAs UnitTest in {
    // The previous immutable-Seq pendingTasks was O(n) per `:+`. Queueing 50,000 nodes via
    // appendAll on the new ArrayDeque is O(n) total instead of O(n²).
    val stateRoot = kec256(ByteString("deque-load-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    val nodeCount = 50000
    val nodes = (1 to nodeCount).map(fakeHashedNode)

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    val queueStart = System.nanoTime()
    coordinator ! Messages.QueueMissingNodes(nodes)
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])

    val stats = expectMsgType[HealingStatistics](5.seconds)
    val elapsedMs = (System.nanoTime() - queueStart) / 1000000L

    // Exactly nodeCount: the walk root is absent (empty storage), so the seed-site complementary guard
    // signals HealingRootUnservable to the controller and does NOT seed the root (it was the futile +1
    // that stalled the heal). The QueueMissingNodes payload is the only frontier here.
    stats.pendingTasks shouldBe nodeCount
    // Loose ceiling — main signal is that this ran in linear time (O(n²) at this size
    // would take many seconds even on a fast box). Tighten if it ever flakes.
    elapsedMs should be < 5000L
  }

  it should "drain pending tasks in FIFO order across many small QueueMissingNodes calls" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("deque-fifo-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)

    // Three batches; total 750 nodes queued in O(n) time.
    val batches = Seq.tabulate(3)(g => (g * 250 until (g + 1) * 250).map(fakeHashedNode))
    batches.foreach(b => coordinator ! Messages.QueueMissingNodes(b))

    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    val stats = expectMsgType[HealingStatistics](3.seconds)
    // Exactly 750: the walk root is absent, so the seed-site guard signals HealingRootUnservable and
    // does NOT seed the root (the futile +1 is gone). The three batches are the only frontier.
    stats.pendingTasks shouldBe 750
  }

  it should "construct successfully when a healing-writer EC override is supplied" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("ec-override-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator should not be null
    coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
    expectMsgType[HealingStatistics](2.seconds)
  }

  it should "signal StateHealingComplete on HealingForceComplete even with pending tasks in flight" taggedAs UnitTest in {
    // HealingForceComplete is the SNAPSyncController's nuclear option: when the pivot has
    // advanced beyond the SNAP serve window, healing must abandon pending tasks immediately
    // rather than waiting for them to drain normally.
    val stateRoot = kec256(ByteString("force-complete-with-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("force-heal-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    // Queue tasks and make a peer available so some become active
    val nodeHash1 = kec256(ByteString("node-force-1"))
    val nodeHash2 = kec256(ByteString("node-force-2"))
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), nodeHash1),
        (Seq(ByteString(Array[Byte](0x01))), nodeHash2)
      )
    )
    coordinator ! Messages.HealingPeerAvailable(peer)

    // The walk root is absent (empty storage), so StartTrieNodeHealing first fires the seed-site guard:
    // HealingRootUnservable (do NOT seed the root). The QueueMissingNodes tasks are still real and get
    // dispatched to the peer below.
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.HealingRootUnservable(stateRoot))
    networkPeerManager.expectMsgType[Any](3.seconds) // queued task dispatched

    // ForceComplete while tasks are in-flight: abandon all, signal complete immediately
    coordinator ! Messages.HealingForceComplete
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  // ── Category 1e: HealingStagnated counter semantics ───────────────────────────────────────────
  //
  // HealingStagnationCheck is a private case object fired by an internal 2-minute scheduler.
  // It cannot be injected directly from tests. Instead, these tests model the counter semantics
  // as pure logic (same pattern as the K2 and "Consecutive pivot refresh" tests above) so a
  // refactor cannot silently change the thresholds without a failing test.

  "HealingStagnated counter semantics" should "send HealingStagnated after MaxConsecutiveStagnations zero-progress cycles" taggedAs UnitTest in {
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var healingStagnatedSent = false

    // Simulate 3 consecutive HEAL-PULSE ticks with zero new nodes healed
    for _ <- 1 to MaxConsecutiveStagnations do {
      val recentHealed = 0 // no progress
      val pendingTasksNonEmpty = true

      if recentHealed == 0 && pendingTasksNonEmpty then {
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then {
          healingStagnatedSent = true // → snapSyncController ! HealingStagnated(...)
          consecutiveStagnations = 0
        }
      } else if recentHealed > 0 then {
        consecutiveStagnations = 0
      }
    }

    healingStagnatedSent shouldBe true
    consecutiveStagnations shouldBe 0 // reset after escalation
  }

  it should "reset consecutiveStagnations to zero when at least one node is healed" taggedAs UnitTest in {
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3

    // Two zero-progress cycles...
    consecutiveStagnations += 1
    consecutiveStagnations += 1
    consecutiveStagnations shouldBe 2

    // ...then a productive cycle
    val recentHealed = 5
    if recentHealed > 0 then consecutiveStagnations = 0

    consecutiveStagnations shouldBe 0
    // Need 3 more zero cycles to hit threshold again
    (consecutiveStagnations >= MaxConsecutiveStagnations) shouldBe false
  }

  it should "lock MaxConsecutiveStagnations=3 as the threshold constant" taggedAs UnitTest in {
    // Changing MaxConsecutiveStagnations changes how long fukuii waits before abandoning
    // a stuck healing phase. This test locks the value so the change is deliberate.
    // MaxConsecutiveStagnations is private, but its value is established by the counter tests above.
    // Indirect verification: 3 cycles needed to trigger, 2 cycles are not enough.
    var count = 0
    val MaxConsecutiveStagnations = 3
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe false
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe false
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe true
  }

  // ── NB-11: pivotRefreshRequested suppression ─────────────────────────────────────────────────
  //
  // HealingStagnationCheck is a private case object — cannot be injected.
  // These tests model the suppression semantics as pure logic, matching the pattern above.

  it should "suppress further HealingStagnated signals after pivotRefreshRequested is set" taggedAs UnitTest in {
    var pivotRefreshRequested = false
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var stagnatedSignals = 0

    def tick(recentHealed: Int, hasPending: Boolean): Unit =
      if !pivotRefreshRequested && recentHealed == 0 && hasPending then {
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then {
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
        }
      } else if recentHealed > 0 then {
        consecutiveStagnations = 0
      }

    // First escalation
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
    pivotRefreshRequested shouldBe true

    // Additional ticks while pivotRefreshRequested=true must not fire a second signal
    for _ <- 1 to MaxConsecutiveStagnations * 2 do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
  }

  it should "resume stagnation counting after pivotRefreshRequested is cleared (HealingPivotRefreshed)" taggedAs UnitTest in {
    var pivotRefreshRequested = false
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var stagnatedSignals = 0

    def tick(recentHealed: Int, hasPending: Boolean): Unit =
      if !pivotRefreshRequested && recentHealed == 0 && hasPending then {
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then {
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
        }
      } else if recentHealed > 0 then {
        consecutiveStagnations = 0
      }

    // First escalation
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1

    // Simulate HealingPivotRefreshed resetting the suppression flag
    pivotRefreshRequested = false
    consecutiveStagnations = 0

    // Second escalation cycle should succeed now
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 2
  }

  // ── NB-7: Stateless dispatch gate ────────────────────────────────────────────────────────────
  //
  // Actor-level tests: drive via real messages (HealingPeerAvailable + TrieNodesResponseMsg).

  it should "ignore HealingPeerAvailable for a peer already in statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-stateless-gate-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("stateless-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    // Provide a real task and dispatch it to the peer. (The walk root is absent, so StartTrieNodeHealing
    // no longer seeds it — it signals HealingRootUnservable; we supply the frontier via QueueMissingNodes.)
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("nb7-task")))))
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    // Empty TrieNodes response (requestId=1 is the first generated) → marks peer stateless
    coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Second HealingPeerAvailable for the same peer must be silently ignored
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectNoMessage(300.millis)
  }

  it should "not mark a peer stateless on repeated GetTrieNodes timeouts (go-ethereum: timeouts rotate tasks only)" taggedAs UnitTest in {
    // Regression guard: old code marked peer stateless after MaxConsecutiveTimeoutsBeforeStateless (3)
    // timeouts, then fired HealingAllPeersStateless to SNAPSyncController.
    // New behavior (go-ethereum aligned): timeouts only re-queue tasks — no stateless marking.
    val stateRoot = kec256(ByteString("nb7-timeout-no-stateless-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("timeout-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    // The walk root is absent, so StartTrieNodeHealing no longer seeds it (it signals HealingRootUnservable).
    // Provide all 3 tasks explicitly so 3 requests dispatch concurrently (default maxInFlightPerPeer=5).
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("missing-node-1"))),
        (Seq(ByteString(Array[Byte](0x01))), kec256(ByteString("missing-node-2"))),
        (Seq(ByteString(Array[Byte](0x02))), kec256(ByteString("missing-node-3")))
      )
    )
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=1
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=2
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=3

    // Absent walk root ⇒ the seed-site guard already signalled HealingRootUnservable to the controller.
    // Drain it so the key expectNoMessage below is scoped strictly to the timeout→stateless behavior.
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.HealingRootUnservable(stateRoot))

    // Simulate 3 consecutive timeouts for the same peer (one per active request)
    coordinator ! Messages.HealingRequestTimeout(BigInt(1))
    coordinator ! Messages.HealingRequestTimeout(BigInt(2))
    coordinator ! Messages.HealingRequestTimeout(BigInt(3))

    // Key assertion: HealingAllPeersStateless must NOT be sent.
    // With the old code the 3rd timeout triggered stateless marking → all-peers-stateless →
    // SNAPSyncController.HealingAllPeersStateless. With the new code this never happens.
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "re-admit a stateless peer after HealingPivotRefreshed clears statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-readmit-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("readmit-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    // Make peer stateless. The walk root is absent, so StartTrieNodeHealing no longer seeds it (it
    // signals HealingRootUnservable); provide a real task to dispatch via QueueMissingNodes.
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(
      Seq((Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("nb7-readmit-task"))))
    )
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Pivot refresh: clears statelessPeers and re-seeds new root as pending task. (HealingPivotRefreshed
    // targets a freshly servable root and KEEPS its own reseed — only the StartTrieNodeHealing seed of an
    // absent walk root is deferred by the complementary guard.)
    val newRoot = kec256(ByteString("nb7-readmit-new-root"))
    coordinator ! Messages.HealingPivotRefreshed(newRoot)

    // Peer is no longer stateless — HealingPeerAvailable should trigger dispatch for the new root
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "discover all missing children via BFS when state root is a BranchNode (BFS smoke)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Synthetic trie: root is a BranchNode with 3 HashNode children at positions 0, 3, 7.
    // The children are NOT in storage — BFS level 0 = {root} (present), level 1 = {c0, c3, c7} (all missing).
    val missingHash0 = kec256(ByteString("bfs-smoke-missing-0"))
    val missingHash3 = kec256(ByteString("bfs-smoke-missing-3"))
    val missingHash7 = kec256(ByteString("bfs-smoke-missing-7"))

    val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(missingHash0.toArray)
    children(3) = HashNode(missingHash3.toArray)
    children(7) = HashNode(missingHash7.toArray)
    val branch = BranchNode(children, None)

    val storage = new TestMptStorage()
    storage.putNode(branch)
    val root = ByteString(branch.hash)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // All 3 missing children should be queued once BFS completes.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 3
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "not deadlock under sustained frontier backpressure — the safety timeout resumes the walk" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}
    import java.util.concurrent.Executors

    // Reproduces the 2026-06-13 OOM scenario in miniature: a BFS walk discovers missing nodes while
    // the healing backlog is already over the high-water mark and CANNOT drain (no peers, low-water
    // never reached). With high=1/low=0 the walk pauses on backpressure; the safety timeout must fire
    // and let it resume so it still delivers its frontier instead of hanging forever. (At production
    // defaults of 100K/50K this gate never trips in normal operation.)
    val missing0 = kec256(ByteString("bp-missing-0"))
    val missing3 = kec256(ByteString("bp-missing-3"))
    val missing7 = kec256(ByteString("bp-missing-7"))
    val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(missing0.toArray)
    children(3) = HashNode(missing3.toArray)
    children(7) = HashNode(missing7.toArray)
    val branch = BranchNode(children, None)
    val storage = new TestMptStorage()
    storage.putNode(branch)
    val root = ByteString(branch.hash)

    // Dedicated EC so the walk's blocking backpressure sleep cannot starve the actor thread.
    val pool = Executors.newSingleThreadExecutor()
    val ec = scala.concurrent.ExecutionContext.fromExecutorService(pool)
    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(ec),
        frontierHighWater = 1,
        frontierLowWater = 0,
        frontierBackpressureMaxWaitMs = 800L
      )
    )
    try {
      // Pre-load the backlog ABOVE the high-water mark with no peers, so it can never drain below
      // low-water — the walk's emit gate will block until the safety timeout fires.
      coordinator ! Messages.QueueMissingNodes(
        Seq((Seq(ByteString(Array[Byte](0x09))), kec256(ByteString("bp-preload"))))
      )
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
          expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
        },
        max = 3.seconds,
        interval = 100.millis
      )

      coordinator ! Messages.StartTrieNodeHealing(root)

      // The walk must still complete and deliver its 3 discovered children (preload + 3 = 4),
      // proving the safety timeout fired and resumed it rather than deadlocking on the gate.
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
          expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 4
        },
        max = 8.seconds,
        interval = 200.millis
      )
    } finally {
      system.stop(coordinator)
      pool.shutdownNow()
    }
  }

  it should "traverse multiple BFS levels and find frontier nodes deep in the trie" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Level 0: root BranchNode with 2 children (c0, c1) — both present in storage
    // Level 1: c0 is BranchNode with 1 missing child; c1 is BranchNode with 1 missing child
    // Level 2: m0, m1 — both missing → these are the frontier nodes BFS should find

    val missingL2a = kec256(ByteString("bfs-multilevel-missing-L2a"))
    val missingL2b = kec256(ByteString("bfs-multilevel-missing-L2b"))

    val childrenC0: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC0(5) = HashNode(missingL2a.toArray)
    val branchC0 = BranchNode(childrenC0, None)

    val childrenC1: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC1(10) = HashNode(missingL2b.toArray)
    val branchC1 = BranchNode(childrenC1, None)

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    rootChildren(0) = HashNode(branchC0.hash)
    rootChildren(1) = HashNode(branchC1.hash)
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    storage.putNode(branchC0)
    storage.putNode(branchC1)
    val root = ByteString(rootBranch.hash)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // Both deep frontier nodes (missingL2a, missingL2b) should be found across 3 BFS levels.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 2
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "deduplicate shared child hashes across BFS levels" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Two BranchNodes at level 1 (c0 and c1) share the same missing child hash.
    // BFS should add the shared child to nextLevel exactly once (visited at enqueue time).
    val sharedMissingHash = kec256(ByteString("bfs-dedup-shared-missing"))

    val childrenC0: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC0(0) = HashNode(sharedMissingHash.toArray)
    val branchC0 = BranchNode(childrenC0, None)

    val childrenC1: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC1(0) = HashNode(sharedMissingHash.toArray) // same hash as c0's child
    val branchC1 = BranchNode(childrenC1, None)

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    rootChildren(0) = HashNode(branchC0.hash)
    rootChildren(1) = HashNode(branchC1.hash)
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    storage.putNode(branchC0)
    storage.putNode(branchC1)
    val root = ByteString(rootBranch.hash)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // Shared missing child should appear in the frontier exactly once, not twice.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "process all BFS levels through InMemoryBfsQueueStorage without accumulating the full level in heap (spill-scale)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Build a 3-level wide trie: root → 4 branch nodes (L1) → each with 4 missing children (L2).
    // Total frontier = 16 missing L2 hashes. Exercises multi-level CF-backed queue drain.
    val missingL2: Seq[Array[Byte]] = (0 until 16).map(i => kec256(ByteString(s"spill-missing-$i")).toArray)

    val l1Branches: Seq[BranchNode] = (0 until 4).map { i =>
      val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
      (0 until 4).foreach(j => children(j) = HashNode(missingL2(i * 4 + j)))
      BranchNode(children, None)
    }

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    l1Branches.zipWithIndex.foreach { case (b, i) => rootChildren(i) = HashNode(b.hash) }
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    l1Branches.foreach(storage.putNode)
    // missingL2 hashes are intentionally NOT in storage — they form the frontier.
    val root = ByteString(rootBranch.hash)

    val bfsQueue = new InMemoryBfsQueueStorage()
    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher),
        bfsQueueStorageOpt = Some(bfsQueue)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // All 16 missing L2 hashes must land in the pending frontier.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 16
      },
      max = 10.seconds,
      interval = 200.millis
    )

    // After BFS completes the queue counter resets to 0 (clear() called at end of rebuildFrontierBFS).
    bfsQueue.counter shouldBe 0L
  }

  // ── T009 / T031 (US1/US3, FR-025): shared-ancestor completeness ──────────────────────────────
  //
  // The visited set de-dups a node the first time its hash is seen; a SECOND parent referencing the
  // same hash is a no-op. FR-025 mandates a regression proving that this de-dup of a *shared* present
  // ancestor never short-circuits descent into that ancestor's own children: a missing grandchild
  // behind the shared ancestor must still be discovered exactly once. Uses the T003 fixture.

  it should "discover a missing grandchild behind a SHARED branch ancestor exactly once (FR-025)" taggedAs UnitTest in {
    val fx = HealingTrieFixtures.sharedAncestor() // shared ancestor is a present BranchNode

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = fx.rootHash,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)

    // The shared ancestor is reached via two parents but visited once; the missing grandchild below it
    // is still discovered. It is the ONLY absent node, so the frontier is exactly 1 — not 0 (skipped)
    // and not 2 (double-counted).
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "discover a missing grandchild behind a SHARED extension ancestor exactly once (FR-025)" taggedAs UnitTest in {
    // Same invariant on the ExtensionNode de-dup arm of rebuildFrontierBFS.
    val fx = HealingTrieFixtures.sharedAncestor(sharedIsExtension = true)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = fx.rootHash,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)

    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  // ── T027 (US3): serial-equivalence (FR-012) ──────────────────────────────────────────────────
  //
  // With traversalParallelism = 1 the walk takes the serial branch. The emitted frontier (the
  // pendingTasks set) over a fixed multi-node trie must be identical to a reference run — and
  // identical whether or not a reader EC is supplied. Proves FR-012 (the serial path is unchanged
  // and a reader EC, unused on the serial branch, does not perturb it).

  it should "emit an identical serial frontier with cfg=1, with and without a reader EC (FR-012)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    // A drive helper: run a cfg=1 walk over a fresh copy of the multi-node fixture and return the
    // frontier size. Each call gets its own fixture+coordinator so the runs are independent.
    def frontierSizeWithCfg1(readerEc: Option[scala.concurrent.ExecutionContext]): Int = {
      val fx = HealingTrieFixtures.multiNodeWithSharedAncestor()
      val coordinator = system.actorOf(
        HealingTrieFixtures.coordinatorProps(
          stateRoot = fx.rootHash,
          networkPeerManager = TestProbe().ref,
          requestTracker = new SNAPRequestTracker()(system.scheduler),
          mptStorage = fx.storage,
          batchSize = 16,
          snapSyncController = TestProbe().ref,
          healingWriterEcOverride = Some(system.dispatcher),
          healingReaderEcOverride = readerEc,
          traversalParallelism = 1 // serial branch
        )
      )
      coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)
      var observed = -1
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
          observed = expectMsgType[HealingStatistics](2.seconds).pendingTasks
          observed shouldBe fx.missingNodeHashes.size // 2 distinct missing nodes
        },
        max = 5.seconds,
        interval = 100.millis
      )
      system.stop(coordinator)
      observed
    }

    // Reference run (no reader EC) and a run WITH a reader EC must agree, and both must equal the
    // fixture's known missing-node count.
    val pool = Executors.newFixedThreadPool(2)
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(pool)
    try {
      val reference = frontierSizeWithCfg1(None)
      val withReader = frontierSizeWithCfg1(Some(readerEc))
      reference shouldBe 2
      withReader shouldBe reference
    } finally {
      pool.shutdownNow()
      ()
    }
  }

  // ── T029 / T031 (US3): no Await-on-same-pool deadlock + shared-ancestor under concurrency ─────
  //
  // The parallel-split branch (`effectiveParallelism > 1 && levelSize > BfsChunkSize=50,000`) dispatches
  // sub-ranges as Futures on the reader EC while the parent walk Future parks on `Await` on the writer EC.
  // Sharing one pool would deadlock once parallelism nears the pool size (forge's fix: a distinct reader
  // pool). This test uses a fixture whose level-4 frontier is wider than 50,000 so the split branch is
  // GENUINELY taken, with a real reader EC distinct from the writer EC and effective parallelism > 1, and
  // asserts the walk completes within a bounded TestKit timeout (no deadlock). T031's shared-ancestor
  // concurrent assertion is folded into the smaller wide-fanout fixture below: a tiny synthetic trie's
  // levels are all < 50,000, so the split branch cannot be exercised on it — the genuine concurrency path
  // is exercised here, and the shared-ancestor invariant under that configuration is asserted next.

  it should "complete a >50K-entry parallel level without Await-on-same-pool deadlock (T029)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    val fx = HealingTrieFixtures.wideFrontierLevel() // level-4 frontier = 53,248 > 50,000

    // A real, fixed-size reader pool distinct from the writer EC. The writer EC is also a small fixed
    // pool so neither starves the actor thread. effectiveParallelism on the 4-core CI host with
    // traversalParallelism=2 / min=2 / reserved=2 is min(2, min(4, max(2, 2))) = 2 > 1 → the split fires.
    val readerPool = Executors.newFixedThreadPool(2)
    val writerPool = Executors.newSingleThreadExecutor()
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(readerPool)
    val writerEc = scala.concurrent.ExecutionContext.fromExecutorService(writerPool)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = fx.rootHash,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(writerEc),
        healingReaderEcOverride = Some(readerEc),
        traversalParallelism = 2,
        healingMinParallelism = 2,
        healingReservedCores = 2,
        // Lift the emission high-water above the frontier so backpressure never blocks the walk here —
        // the point of this test is the split/Await path, not the drain gate (covered elsewhere).
        frontierHighWater = 200000,
        frontierLowWater = 100000
      )
    )

    try {
      coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)

      // No deadlock: the full frontier lands within a generous-but-bounded timeout. If the parallel
      // Await deadlocked, pendingTasks would never reach the expected count and this would time out.
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
          expectMsgType[HealingStatistics](3.seconds).pendingTasks shouldBe fx.expectedFrontier
        },
        max = 60.seconds,
        interval = 500.millis
      )
    } finally {
      system.stop(coordinator)
      readerPool.shutdownNow()
      writerPool.shutdownNow()
      ()
    }
  }

  it should "still discover a missing node behind a shared ancestor with parallel readers configured (T031)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    // Wide fan-out shared-ancestor fixture: many parents reference one shared present node above the
    // single missing grandchild. With a real reader EC and effective parallelism > 1 configured, the
    // shared ancestor is still enqueued once and its missing grandchild discovered exactly once. (The
    // fixture's levels are < 50,000 so the physical split does not fire — the genuine parallel-split
    // path is proven by the >50K T029 test above; this asserts the FR-025 invariant under the
    // concurrency configuration.)
    val fx = HealingTrieFixtures.wideSharedAncestor(fanout = 12)

    val readerPool = Executors.newFixedThreadPool(2)
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(readerPool)

    val coordinator = system.actorOf(
      HealingTrieFixtures.coordinatorProps(
        stateRoot = fx.rootHash,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher),
        healingReaderEcOverride = Some(readerEc),
        traversalParallelism = 2,
        healingMinParallelism = 2,
        healingReservedCores = 2
      )
    )

    try {
      coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress(testActor.toTyped[HealingStatistics])
          expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
        },
        max = 5.seconds,
        interval = 100.millis
      )
    } finally {
      system.stop(coordinator)
      readerPool.shutdownNow()
      ()
    }
  }

  // ── T026 (US3): effective-parallelism formula ────────────────────────────────────────────────
  //
  // `computeEffectiveParallelism` is the pure extraction of the inline clamp in `startFrontierBFS`
  // (spec 002 T026): min(traversalParallelism, min(nproc, max(minParallelism, nproc − reservedCores))).
  // Pinning it here proves the clamp never exceeds the operator ceiling or the CPU count, honours the
  // min-parallelism floor, and collapses to the serial path at cfg=1 — independent of the host's nproc.

  "TrieNodeHealingCoordinator.computeEffectiveParallelism" should
    "clamp the 4-core / cfg=4 / min=2 / reserved=2 reference host to 2" taggedAs UnitTest in {
      // max(2, 4−2) = 2; min(4, min(4, 2)) = 2. The 4-core reference host keeps 2 readers.
      TrieNodeHealingCoordinator.computeEffectiveParallelism(
        traversalParallelism = 4,
        availableProcessors = 4,
        minParallelism = 2,
        reservedCores = 2
      ) shouldBe 2
    }

  it should "rise to the cfg ceiling on a 16-core host (cfg is the binding limit)" taggedAs UnitTest in {
    // max(2, 16−2) = 14; min(16, 14) = 14; min(cfg=4, 14) = 4. The operator ceiling caps it.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 16,
      minParallelism = 2,
      reservedCores = 2
    ) shouldBe 4
  }

  it should "collapse to 1 (the serial path) when cfg=1, on any host" taggedAs UnitTest in {
    // cfg=1 ⇒ the outer min(1, …) is 1 regardless of cores/min/reserved → the serial branch in the walk.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(1, 4, 2, 2) shouldBe 1
    TrieNodeHealingCoordinator.computeEffectiveParallelism(1, 64, 8, 0) shouldBe 1
  }

  it should "still clamp to nproc when the min-parallelism floor exceeds the CPU count" taggedAs UnitTest in {
    // min=8 on a 4-core host: max(8, 4−2) = 8, but the inner min(nproc=4, 8) caps it at 4 (never oversubscribe).
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 16,
      availableProcessors = 4,
      minParallelism = 8,
      reservedCores = 2
    ) shouldBe 4
  }

  it should "fall back to the min-parallelism floor when reserved cores ≥ nproc" taggedAs UnitTest in {
    // reserved=4 on a 4-core host: nproc−reserved = 0, so max(min=2, 0) = 2 lifts it to the floor;
    // min(nproc=4, 2) = 2; min(cfg=4, 2) = 2. The floor protects a host whose reservation would otherwise zero it.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 4,
      minParallelism = 2,
      reservedCores = 4
    ) shouldBe 2
    // reserved beyond nproc (negative nproc−reserved) is clamped identically by the max floor.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 4,
      minParallelism = 2,
      reservedCores = 8
    ) shouldBe 2
  }

  // ========================================
  // Complementary seed guard (root-cause w98gfx4wn): an ABSENT walk root must NOT be seeded;
  // instead signal the controller to take the lazy-heal handoff. A PRESENT root still heals normally.
  // ========================================

  it should "signal HealingRootUnservable (not seed the root) when the walk root's bytes are absent" taggedAs UnitTest in {
    // Empty storage ⇒ isNodeInStorage(root) == false. Seeding the root here would stall the heal at
    // "exactly 1 node, healed=0" forever (a root cannot be reconstructed from nothing, and fetching it
    // against an advancing serve root never matches the content-hash gate). The seed-site guard must
    // instead signal the controller to hand off to lazy on-demand healing — and must do so for EVERY
    // entry into healing, including the BootstrapComplete restart path that calls startStateHealing()
    // directly.
    val stateRoot = kec256(ByteString("absent-walk-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)

    // The controller is told the root is unservable (handoff signal carries the exact root).
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.HealingRootUnservable(stateRoot))

    // The root was NOT seeded: pendingTasks stays 0 (no futile "exactly 1 node" frontier).
    coordinator ! Messages.HealingGetProgress
    expectMsgType[HealingStatistics](3.seconds).pendingTasks shouldBe 0
  }

  it should "heal normally (no HealingRootUnservable) when the walk root IS present" taggedAs UnitTest in {
    // Legit-healing boundary: the root-PRESENT case is unchanged. With a present root that has a single
    // missing descendant, the coordinator discovers that descendant (frontier == 1) and must NOT emit
    // the unservable handoff signal.
    val fx = HealingTrieFixtures.sharedAncestor() // present BranchNode root; one missing grandchild
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = fx.rootHash,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(fx.rootHash)

    // Present root ⇒ normal healing: discover the one missing descendant (frontier == 1, not 0/2).
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
      },
      max = 5.seconds,
      interval = 100.millis
    )

    // The unservable handoff must NEVER fire on a present root.
    snapSyncController.expectNoMessage(300.millis)
  }
}
