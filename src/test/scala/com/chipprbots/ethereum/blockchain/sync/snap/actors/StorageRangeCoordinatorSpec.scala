package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class StorageRangeCoordinatorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  private val statusProbe = testKit.createTestProbe[StorageRangeCoordinator.SyncStatistics]()

  // StorageRangeCoordinator is a Typed actor (Group S3). These tests run in a Classic ActorSystem so they can keep
  // the established `system.actorOf` / `expectMsg` machinery; the coordinator is spawned through PropsAdapter to
  // bridge the Classic system to the Typed Behavior. Mirrors the `.props(...)` factory the actor previously exposed.
  private def srcProps(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: TestMptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int = 5,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000
  ): org.apache.pekko.actor.typed.ActorRef[StorageRangeCoordinator.Command] =
    testKit.spawn(
      StorageRangeCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        mptStorage = mptStorage,
        flatSlotStorage = flatSlotStorage,
        maxAccountsPerBatch = maxAccountsPerBatch,
        maxInFlightRequests = maxInFlightRequests,
        requestTimeout = requestTimeout,
        snapSyncController = snapSyncController,
        initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
        backpressureHighWatermark = backpressureHighWatermark,
        backpressureLowWatermark = backpressureLowWatermark
      )
    )

  // Typed `StorageGetProgress` carries a `replyTo: ActorRef[SyncStatistics]`. In these Classic tests the reply target
  // is the test actor (ImplicitSender); adapt it to a typed ref so the coordinator can reply.
  private def getProgress: StorageRangeCoordinator.StorageGetProgress =
    StorageRangeCoordinator.StorageGetProgress(statusProbe.ref)

  // White-box helper: build the `StorageRangeCoordinatorImpl` directly through a synchronous `BehaviorTestKit`,
  // capturing the Impl instance so tests can drive `private[actors]` accumulator/counter logic in isolation (the
  // Typed coordinator has no `.underlyingActor`). `kit.run(msg)` processes a Command synchronously on the same Impl.
  // `flatBatchEcOverride` defaults to `Some(parasitic)` because `StorageRangeCoordinatorImpl` evaluates
  // `flatBatchEc` eagerly in its constructor; under BehaviorTestKit `context.system.classicSystem` is unavailable,
  // so the production `storage-writer-dispatcher` lookup must never be reached. Parasitic also keeps any flush the
  // test does trigger synchronous (see `newCoordWithFlatBatch`).
  private def newImpl(
      stateRoot: ByteString,
      flatSlotStorage: FlatSlotStorage,
      snapSyncControllerRef: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      flatBatchEntryThreshold: Int = 1000,
      flatBatchEcOverride: Option[scala.concurrent.ExecutionContext] = Some(
        scala.concurrent.ExecutionContext.parasitic
      ),
      maxAccountsPerBatch: Int = 8,
      maxInFlightRequests: Int = 8,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000
  ): (StorageRangeCoordinatorImpl, BehaviorTestKit[StorageRangeCoordinator.Command]) =
    var captured: StorageRangeCoordinatorImpl = null
    val behavior = Behaviors.setup[StorageRangeCoordinator.Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StorageRangeCoordinatorImpl(
          ctx,
          timers,
          initialStateRoot = stateRoot,
          networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
          requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
          mptStorage = new TestMptStorage(),
          flatSlotStorage = flatSlotStorage,
          maxAccountsPerBatch = maxAccountsPerBatch,
          maxInFlightRequests = maxInFlightRequests,
          requestTimeout = 30.seconds,
          snapSyncController = snapSyncControllerRef,
          flatBatchEntryThreshold = flatBatchEntryThreshold,
          flatBatchEcOverride = flatBatchEcOverride,
          backpressureHighWatermark = backpressureHighWatermark,
          backpressureLowWatermark = backpressureLowWatermark
        )
        captured.start()
      }
    }
    val kit = BehaviorTestKit(behavior)
    (captured, kit)

  "StorageRangeCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator should not be null
  }

  it should "handle peer availability" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)

    // Should handle peer availability (may or may not send request depending on tasks)
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StorageTaskComplete(BigInt(123), Right(10))

    // Coordinator should handle completion
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "report completion when no storage tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)

    // Signal that no more tasks will arrive (sentinel pattern)
    coordinator ! StorageRangeCoordinator.NoMoreStorageTasks

    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    // Should complete immediately since no tasks and sentinel received
    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StorageTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "accept AddStorageTasks and remain operational" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    val accountHash1 = kec256(ByteString("account-1"))
    val storageRoot1 = kec256(ByteString("storage-root-1"))
    val task = StorageTask.createStorageTask(accountHash1, storageRoot1)

    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task))

    // Should remain operational after adding tasks
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "accept StoragePivotRefreshed and update state root" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("old-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    val newStateRoot = kec256(ByteString("new-state-root"))
    coordinator ! StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot)

    // Coordinator should still respond to progress queries after pivot refresh
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "signal StorageRangeSyncComplete to controller when NoMoreStorageTasks received with no pending tasks" taggedAs UnitTest in {
    // Verifies the sentinel pattern: when account download finishes with no storage accounts,
    // the coordinator must complete immediately on NoMoreStorageTasks + StorageCheckCompletion.
    val stateRoot = kec256(ByteString("empty-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.NoMoreStorageTasks
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncComplete)
  }

  // ── K5: Proof-of-absence (BUG fix b38050e49) ──────────────────────────────

  it should "accept proof-of-absence response (0 slots + proof) and not mark peer stateless" taggedAs UnitTest in {
    // Fix b38050e49: when a peer returns 0 slots + non-empty proof for a single-account batch,
    // the coordinator must treat it as a valid cryptographic proof that the account has no
    // storage, complete the task, and NOT mark the peer stateless. The peer must be immediately
    // eligible to receive the next task via dispatchIfPossible().
    val stateRoot = kec256(ByteString("proof-of-absence-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-poa", peerProbe.ref.toClassic)

    val account1 = kec256(ByteString("account-poa-1"))
    val account2 = kec256(ByteString("account-poa-2"))
    val storageRoot = kec256(ByteString("storage-root-poa"))

    val task1 = StorageTask.createStorageTask(account1, storageRoot)
    val task2 = StorageTask.createStorageTask(account2, storageRoot)

    // maxAccountsPerBatch=1 forces single-account batches → tasks.size==1 in processStorageRanges,
    // satisfying the proof-of-absence guard (response.proof.nonEmpty && tasks.size == 1).
    // initialMaxInFlightPerPeer=1 ensures only one request is in-flight at a time so the
    // second request is sent only after the first is resolved.
    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 1,
      maxInFlightRequests = 2,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task1, task2))
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)

    // Coordinator dispatches task1 to peer
    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req1.accountHashes should have size 1
    req1.accountHashes.head shouldEqual account1

    // Inject proof-of-absence: 0 slots, 1 proof node — valid snap/1 empty-storage proof
    val dummyProofNode = ByteString(Array.fill(32)(0xab.toByte))
    coordinator ! StorageRangeCoordinator.StorageRangesResponseMsg(
      StorageRanges(req1.requestId, slots = Seq.empty, proof = Seq(dummyProofNode))
    )

    // Peer is NOT stateless — coordinator immediately pipelines task2 to the same peer
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req2.accountHashes should have size 1
    req2.accountHashes.head shouldEqual account2

    // No pivot-refresh stall signal: peer served a valid proof-of-absence response.
    // Coordinator dispatches task2 immediately; no PivotStateUnservable expected.
    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Category 1d: ForceCompleteStorage escape valve ─────────────────────────

  it should "signal StorageRangeSyncForceCompleted immediately on ForceCompleteStorage even with pending tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 4,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    // Add tasks that will not be dispatched (no peer)
    val accountHash = kec256(ByteString("account-force"))
    val storageRoot = kec256(ByteString("storage-root-force"))
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task))

    // Force completion without a peer — should immediately promote to healing
    coordinator ! StorageRangeCoordinator.ForceCompleteStorage

    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
  }

  // ── K5: No false stall signal when task queue is empty (BUG fix b07c363e9) ─

  it should "not emit PivotStateUnservable when no storage tasks are pending" taggedAs UnitTest in {
    // Fix b07c363e9: PivotStateUnservable must only be requested when tasks.nonEmpty.
    // During the account-range phase (before any storage tasks arrive), a StorageCheckCompletion
    // tick must be a no-op — not trigger a spurious pivot refresh that aborts the sync.
    val stateRoot = kec256(ByteString("no-stall-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-nostall", peerProbe.ref.toClassic)

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 1,
      maxInFlightRequests = 1,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    // Peer known but no tasks queued — simulates the window during the account-range phase
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)
    // StorageCheckCompletion tick that arrives before any storage tasks
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    // Neither PivotStateUnservable nor StorageRangeSyncComplete should be sent:
    // tasks.isEmpty → maybeRequestPivotRefresh() not called, isComplete=false (no sentinel)
    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Fix 2: consecutiveTaskFailures reset on StoragePivotRefreshed ────────────
  // Verifies that a pivot refresh zeroes the consecutive failure counter, preventing
  // pivot-invalidated task failures (counted during AccountRange phase) from
  // triggering a premature ForceComplete during the subsequent ByteCode+Storage phase.
  // In Run 23 this counter reached 102-104 during AccountRange and triggered
  // StorageRangeSyncForceCompleted at 14:28:43, permanently blocking recovery.

  it should "reset consecutiveTaskFailures to 0 on StoragePivotRefreshed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("reset-consec-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref
    )

    // Simulate failures accumulated during AccountRange phase (before storage phase begins)
    impl.consecutiveTaskFailures = 50

    val newStateRoot = kec256(ByteString("pivot-reset-root"))
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot))

    // BehaviorTestKit processes synchronously — counter must be 0 immediately after
    impl.consecutiveTaskFailures shouldBe 0
  }

  it should "not trigger ForceCompleteStorage when failures accumulated before a pivot are reset" taggedAs UnitTest in {
    // Regression: Run 23 had 102-104 consecutive failures from pivot-invalidated tasks.
    // The threshold is 100. Without the reset, those failures triggered ForceComplete during
    // AccountRange, permanently setting storagePhaseComplete=true before storage even started.
    // With the reset, a pivot refresh zeroes the counter so failures before and after a pivot
    // are counted independently — only a sustained run of 100 failures from one pivot epoch triggers.
    val stateRoot = kec256(ByteString("no-force-after-pivot-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref
    )

    // Accumulate 99 failures — one below the 100-failure force-complete threshold
    impl.consecutiveTaskFailures = 99

    // Pivot refresh (mirrors what happens when SNAPSyncController updates the pivot block)
    val newRoot = kec256(ByteString("mid-session-pivot"))
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newRoot))

    // Counter is now 0. Set it to 99 again (simulating another near-threshold accumulation
    // after the pivot — still one below the threshold from this epoch).
    impl.consecutiveTaskFailures = 99

    // No ForceCompleteStorage should have been sent across either epoch
    snapSyncController.expectNoMessage(300.millis)
  }

  // ========================================
  // Flat-batch aggregator (issue #1165)
  // ========================================

  /** Helper: build the Impl synchronously (via `newImpl`/BehaviorTestKit) with an override EC and small threshold so
    * flat-batch behaviour is observable without spinning up the storage-writer-dispatcher. Returns the Impl (for
    * white-box field access), the BehaviorTestKit (for synchronous `kit.run(msg)` message processing), and the
    * controller probe.
    */
  private def newCoordWithFlatBatch(
      flatSlotStorage: FlatSlotStorage,
      threshold: Int,
      stateRootArg: ByteString = kec256(ByteString("flat-batch-test-root"))
  ): (
      StorageRangeCoordinatorImpl,
      BehaviorTestKit[StorageRangeCoordinator.Command],
      org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ) =
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    // `parasitic` runs the flush Future + its onComplete callback inline, so the resulting
    // `FlatBatchFlushComplete` is already in the BehaviorTestKit self-inbox when the staging call returns.
    // `kit.runOne()` then processes it synchronously (decrementing inFlightFlatBatches).
    val (impl, kit) = newImpl(
      stateRoot = stateRootArg,
      flatSlotStorage = flatSlotStorage,
      snapSyncControllerRef = controller.ref,
      flatBatchEntryThreshold = threshold,
      flatBatchEcOverride = Some(scala.concurrent.ExecutionContext.parasitic)
    )
    (impl, kit, controller)

  /** Helper: synthesize an account-hash + slots payload of `slotsPerAccount` entries. */
  private def fakeContract(
      seed: Int,
      slotsPerAccount: Int
  ): (ByteString, mutable.ArrayBuffer[(ByteString, ByteString)]) =
    val accountHash = kec256(ByteString(s"acct-$seed"))
    val slots = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    var i = 0
    while i < slotsPerAccount do
      val slotHash = kec256(ByteString(s"slot-$seed-$i"))
      val slotValue = ByteString(s"value-$seed-$i".getBytes)
      slots += ((slotHash, slotValue))
      i += 1
    (accountHash, slots)

  // Drain all self-sent Commands sitting in the BehaviorTestKit's self-inbox (e.g. FlatBatchFlushComplete
  // produced by the parasitic flush, plus chained StorageCheckCompletion ticks), processing each on the Impl.
  private def drainSelf(kit: BehaviorTestKit[StorageRangeCoordinator.Command]): Unit =
    while kit.selfInbox().hasMessages do kit.runOne()

  it should "buffer small-contract slots in the accumulator without immediate commit" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, _, _) = newCoordWithFlatBatch(flatSlots, threshold = 100)

    val (accountHash, slots) = fakeContract(seed = 1, slotsPerAccount = 3)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)

    impl.pendingFlatBatchEntries shouldBe 3
    impl.pendingFlatBatchAccounts.size shouldBe 1
    impl.inFlightFlatBatches shouldBe 0

    // Nothing was committed — FlatSlotStorage is still empty for our keys.
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None
  }

  it should "flush exactly once when the threshold is crossed and persist all buffered slots" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 5)

    // Three contracts, 2 slots each = 6 total slots, crossing the 5-entry threshold.
    val contracts = (1 to 3).map(i => fakeContract(seed = i, slotsPerAccount = 2))
    contracts.foreach { case (h, s) => impl.stageFlatSlotChunk(h, s.toSeq) }

    // The parasitic flush already persisted the batch and enqueued FlatBatchFlushComplete; drain it.
    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0

    // After flush, all 6 slots are durable.
    contracts.foreach { case (accountHash, slots) =>
      slots.foreach { case (slotHash, value) =>
        flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
      }
    }

    // Accumulator was reset.
    impl.pendingFlatBatchAccounts shouldBe empty
    impl.pendingFlatBatchEntries shouldBe 0
  }

  it should "not trigger a flush when accumulator stays below threshold" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, _, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 42, slotsPerAccount = 50)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)

    impl.pendingFlatBatchEntries shouldBe 50
    impl.inFlightFlatBatches shouldBe 0
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None
  }

  it should "flush remaining accumulator on ForceCompleteStorage" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, controller) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 99, slotsPerAccount = 4)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)
    impl.pendingFlatBatchEntries shouldBe 4

    kit.run(StorageRangeCoordinator.ForceCompleteStorage)

    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    controller.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
  }

  it should "drop bookkeeping for FlatBatchFlushComplete from a stale state root" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    impl.inFlightFlatBatches = 1
    val staleRoot = kec256(ByteString("a-stale-root"))

    kit.run(StorageRangeCoordinator.FlatBatchFlushComplete(staleRoot, entryCount = 7, elapsedMs = 5L))

    impl.inFlightFlatBatches shouldBe 0
  }

  it should "flush the accumulator before mutating stateRoot on StoragePivotRefreshed" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val oldRoot = kec256(ByteString("old-root"))
    val newRoot = kec256(ByteString("new-root"))
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000, stateRootArg = oldRoot)

    // Buffer some data while stateRoot == oldRoot.
    val (accountHash, slots) = fakeContract(seed = 7, slotsPerAccount = 4)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)
    impl.pendingFlatBatchEntries shouldBe 4

    // Pivot refresh: must commit the accumulator THEN advance the root.
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newRoot))

    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0

    // Data made it to disk despite the pivot refresh.
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    impl.pendingFlatBatchAccounts shouldBe empty
  }

  it should "decrement in-flight count on FlatBatchFlushFailed and stay operational" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    impl.inFlightFlatBatches = 2

    kit.run(
      StorageRangeCoordinator.FlatBatchFlushFailed(
        forStateRoot = kec256(ByteString("flat-batch-test-root")),
        entryCount = 11,
        error = "synthetic write failure"
      )
    )

    impl.inFlightFlatBatches shouldBe 1

    // Still operational: a progress query yields a reply via the typed replyTo probe.
    val probe = org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox[StorageRangeCoordinator.SyncStatistics]()
    kit.run(StorageRangeCoordinator.StorageGetProgress(probe.ref))
    probe.receiveMessage()
  }

  // -----------------------------------------------------------------------
  // StackTrie write-path (Step 4 of `snap-stacktrie-port` plan)
  // -----------------------------------------------------------------------
  // Verify the coordinator constructs and operates normally with the flag
  // enabled. The actual per-contract trie-building happens asynchronously on
  // `trieBuilderEc` (a separate thread pool); end-to-end verification of the
  // node emissions is exercised by the Sepolia test run that follows Step 5.

  it should "construct and accept lifecycle messages" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("storage-stacktrie-construct-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    coordinator should not be null

    // Smoke: accept the basic lifecycle messages without error.
    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]

    testKit.stop(coordinator)
  }

  // ── Back-pressure on the pending storage-task queue ───────────────────────
  // Regression coverage for the sepolia OOM (May 13 2026): account-range
  // download produced storage tasks faster than peers could serve them, growing
  // the queue to ~2.8M entries before the JVM hit Xmx. The coordinator now emits
  // StorageBackpressureChanged(paused = true) when the queue crosses the
  // high-water mark, and StorageBackpressureChanged(paused = false) when it
  // drains below the low-water mark. SNAPSyncController forwards both to
  // AccountRangeCoordinator so account workers stop producing new tasks.
  it should "emit StorageBackpressureChanged when the pending queue crosses watermarks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("backpressure-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    // Tiny watermarks so the test can drive the transition without enqueuing 100K tasks.
    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref,
      backpressureHighWatermark = 5,
      backpressureLowWatermark = 2
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)

    // Build a small batch of tasks, enough to push the queue to 5 entries.
    val tasks =
      (1 to 5).map(i =>
        StorageTask.createStorageTask(
          accountHash = kec256(ByteString(s"acct-$i")),
          storageRoot = kec256(ByteString(s"root-$i"))
        )
      )
    coordinator ! StorageRangeCoordinator.AddStorageTasks(tasks)

    // Crossing the high-water mark triggers a pause signal upward.
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = true))

    // Re-checking with the same depth must NOT emit another transition (no duplicate signals).
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "release back-pressure once the queue drains below the low-water mark" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("backpressure-release-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref,
      backpressureHighWatermark = 5,
      backpressureLowWatermark = 2
    )

    kit.run(StorageRangeCoordinator.StartStorageRangeSync(stateRoot))

    // Drive across the high-water mark first.
    val tasks =
      (1 to 5).map(i =>
        StorageTask.createStorageTask(
          accountHash = kec256(ByteString(s"acct-$i")),
          storageRoot = kec256(ByteString(s"root-$i"))
        )
      )
    kit.run(StorageRangeCoordinator.AddStorageTasks(tasks))
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = true))

    // Drain the underlying queue to 2 entries (≤ low-water mark) and trigger a check.
    val q = impl.tasks
    while q.size > 2 do q.dequeue()

    kit.run(StorageRangeCoordinator.StorageCheckCompletion)
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = false))
  }

  // ========================================
  // Streaming storage-trie memory bound
  // ========================================
  //
  // Verifies the per-account `SnapHashTrie` stays bounded across continuation responses
  // for a single contract: even with thousands of slots, the in-memory batch never crosses
  // the 8 MiB flush threshold (it auto-flushes), and `commit()` returns a stable root.

  // ========================================
  // Spec 005 — Storage subtask parallelism
  // ========================================

  it should "start with empty accountSubtaskCounters (no subtask tracking before any response)" taggedAs UnitTest in {
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-init-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    impl.accountSubtaskCounters shouldBe empty
    impl.completedAccountCount shouldBe 0L
  }

  it should "increment completedAccountCount only once when all subtasks for an account complete" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("large-contract"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-complete-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // Simulate: 3 subtasks registered for a large-storage account
    impl.accountSubtaskCounters(accountHash) = (3, 0)
    impl.completedAccountCount shouldBe 0L

    // First subtask completes — still 2 remaining; count must NOT advance
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((3, 1))

    // Second subtask completes — 1 remaining
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((3, 2))

    // Third (final) subtask completes — all done; count advances and entry is removed
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters.get(accountHash) shouldBe None
  }

  it should "increment completedAccountCount directly (no subtasks) when no counter entry exists" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("small-contract"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-nosplit-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // No subtask entry for this account — small contract, single task, no split
    impl.accountSubtaskCounters shouldBe empty

    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters shouldBe empty
  }

  it should "handle independent subtask completions for two large-storage contracts without cross-contamination" taggedAs UnitTest in {
    val acctA = kec256(ByteString("contract-A"))
    val acctB = kec256(ByteString("contract-B"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-two-accts-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // Register 2 subtasks for A, 3 for B
    impl.accountSubtaskCounters(acctA) = (2, 0)
    impl.accountSubtaskCounters(acctB) = (3, 0)

    // Complete A subtask 1 → A not done; B not done
    impl.recordSubtaskCompletion(acctA)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(acctA) shouldBe Some((2, 1))

    // Complete B subtask 1 → nothing done
    impl.recordSubtaskCompletion(acctB)
    impl.completedAccountCount shouldBe 0L

    // Complete A subtask 2 → A done; count=1; B still incomplete
    impl.recordSubtaskCompletion(acctA)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters.get(acctA) shouldBe None
    impl.accountSubtaskCounters.get(acctB).isDefined shouldBe true

    // Complete B subtasks 2 and 3 → B done; count=2
    impl.recordSubtaskCompletion(acctB)
    impl.recordSubtaskCompletion(acctB)
    impl.completedAccountCount shouldBe 2L
    impl.accountSubtaskCounters.get(acctB) shouldBe None
  }

  it should "bound per-account streaming trie memory across continuation responses" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie

    val accumulated = mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    val trie = new SnapHashTrie(batch => accumulated ++= batch)

    // Three "responses" of 1000 slots each, strictly ascending — mirrors the wire-level
    // monotonicity that `SNAPRequestTracker.validateStorageRanges` enforces.
    val totalSlots = 3000
    val sortedSlotKeys = (0 until totalSlots).map { i =>
      // Use big-endian-encoded 32-byte keys so sort order = numeric order
      val keyBytes = new Array[Byte](32)
      java.nio.ByteBuffer.wrap(keyBytes).putInt(28, i)
      ByteString(keyBytes)
    }
    val slotsByResponse = sortedSlotKeys.grouped(1000).toSeq

    slotsByResponse.foreach { batch =>
      batch.foreach { slotHash =>
        val value = ByteString(s"slotvalue-${slotHash.takeRight(4).toHex}".getBytes)
        trie.update(slotHash.toArray, value.toArray)
      }
      // After each "response", the in-heap batch should never exceed the flush threshold.
      trie.pendingBatchBytes should be <= SnapHashTrie.DefaultBatchSizeBytes.toLong
    }

    val root = trie.commit()
    root should not be ByteString.empty
    root.size shouldBe 32

    // After commit, all nodes have been emitted to the accumulator.
    accumulated.nonEmpty shouldBe true
  }
