package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MptVisitors.*

/** Storage recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose storage tries are missing from
  * MptStorage (due to the Bug 20 phase handoff timeout). Collects missing (accountHash, storageRoot) pairs and
  * downloads them via SNAP protocol using StorageRangeCoordinator.
  *
  * Runs concurrently with BytecodeRecoveryActor — they target different storage backends (MptStorage vs EvmCodeStorage)
  * with no data dependency.
  *
  * Pekko Typed actor (`Behavior[Any]`): SyncController (Classic parent) sends `StoragePeerAvailable`, `RecentRoot`, and
  * coordinator messages all arrive via this actor's Classic proxy. Using `Any` accepts those heterogeneous Classic
  * messages while providing all Typed machinery (named behavior functions, `Behaviors.withTimers`, `watchWith`).
  *
  * Lifecycle:
  *   1. Walk state trie, find contracts with missing storage tries 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via StorageRangeCoordinator, then mark done
  */
object StorageRecoveryActor {

  // Internal self-messages
  private case class ScanResult(missingStorage: Seq[(ByteString, ByteString)])
  // Delayed self-ping for Bug 30b abandon path. Carries the progress counter current at arm time;
  // if progressSeq still matches on fire, nothing moved and we give up.
  private[sync] case class CheckAbandon(progressAtSchedule: Long)
  // Delivered via watchWith when StorageRangeCoordinator terminates unexpectedly
  private case object CoordinatorTerminated

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  /** Recovery → SyncController: the saved pivot root has aged out of every peer's snapshot serve window, so storage
    * downloads are returning empty. Please fetch a RECENT canonical header from a peer and reply with [[RecentRoot]] so
    * the download can roll onto a root peers can still serve.
    */
  case object RequestRecentRoot

  /** SyncController → recovery: a recent canonical `(blockNumber, stateRoot)`, or `stateRoot = None` if none could be
    * fetched (no peers / bootstrap failed).
    */
  final case class RecentRoot(blockNumber: BigInt, stateRoot: Option[ByteString])

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = None,
    coordinatorForTesting = None
  )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing storage tries (produced
    * by the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def applyPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[(ByteString, ByteString)]
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = Some(missing),
    coordinatorForTesting = None
  )

  /** Test entry point: exposes the preloaded-missing and coordinator-injection hooks. */
  private[sync] def testApply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[(ByteString, ByteString)]] = None,
      coordinatorForTesting: Option[ActorRef] = None
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded,
    coordinatorForTesting
  )

  private def scanning(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[(ByteString, ByteString)]],
      coordinatorForTesting: Option[ActorRef]
  ): Behavior[Any] =
    Behaviors.setup { ctx =>
      preloaded match {
        case Some(missing) =>
          ctx.self ! ScanResult(missing)
        case None =>
          ctx.log.info(
            s"StorageRecoveryActor starting: scanning state trie for missing contract storage " +
              s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
          )
          ctx.pipeToSelf(
            Future(scanForMissingStorage(stateRoot, stateStorage, pivotBlockNumber, ctx.log))(ctx.executionContext)
          ) {
            case Success(result) => ScanResult(result)
            case Failure(ex) =>
              ctx.log.error("Storage recovery scan failed", ex)
              ScanResult(Seq.empty)
          }
      }
      Behaviors.receiveMessage {
        case ScanResult(missing) =>
          if missing.isEmpty then {
            ctx.log.info("Storage recovery: all contract storage tries present. Marking recovery complete.")
            RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
            appStateStorage.storageRecoveryDone().commit()
            syncController.tell(RecoveryComplete, org.apache.pekko.actor.ActorRef.noSender)
            Behaviors.stopped
          } else {
            ctx.log.warn(
              s"Storage recovery: found ${missing.size} contracts with missing storage. Starting download..."
            )
            RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseDownloading)

            val coordinator: ActorRef = coordinatorForTesting.getOrElse {
              val requestTracker = new snap.SNAPRequestTracker()(ctx.system.classicSystem.scheduler)
              val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
              // S3: StorageRangeCoordinator is now Typed. Spawn it from this Typed context and adapt
              // the ref back to Classic so the rest of this actor (which stores ActorRef) is unchanged.
              ctx
                .spawn(
                  actors.StorageRangeCoordinator(
                    stateRoot = stateRoot,
                    networkPeerManager = networkPeerManager,
                    requestTracker = requestTracker,
                    mptStorage = mptStorage,
                    flatSlotStorage = flatSlotStorage,
                    maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                    maxInFlightRequests = snapSyncConfig.storageConcurrency,
                    requestTimeout = snapSyncConfig.timeout,
                    snapSyncController = ctx.self.toClassic,
                    initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                    minResponseBytes = snapSyncConfig.storageMinResponseBytes
                  ),
                  "storage-recovery-coordinator",
                  org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
                )
                .toClassic
            }

            ctx.watchWith(coordinator.toTyped[Nothing], CoordinatorTerminated)

            val batchSize = 10000
            var totalSent = 0
            missing.grouped(batchSize).foreach { batch =>
              val tasks = batch.map { case (accountHash, storageRoot) =>
                StorageTask.createStorageTask(accountHash, storageRoot)
              }
              coordinator.tell(actors.StorageRangeCoordinator.AddStorageTasks(tasks), org.apache.pekko.actor.ActorRef.noSender)
              totalSent += tasks.size
            }
            ctx.log.info(
              s"Sent $totalSent storage tasks to coordinator in ${(totalSent + batchSize - 1) / batchSize} batches"
            )

            downloading(
              ctx,
              coordinator,
              missing,
              stateRoot,
              stateStorage,
              pivotBlockNumber,
              syncController,
              appStateStorage,
              snapSyncConfig
            )
          }

        case _ => Behaviors.unhandled
      }
    }

  private def downloading(
      ctx: ActorContext[Any],
      coordinator: ActorRef,
      missing: Seq[(ByteString, ByteString)],
      stateRoot: ByteString,
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      syncController: ActorRef,
      appStateStorage: AppStateStorage,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Any] = {
    val expectedCount = missing.size
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastProgressNanos = System.nanoTime()
    var unservableCount = 0
    var recoveredCount = 0L
    var lastStorageRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateRecovered = 0L

    var currentRoot: ByteString = stateRoot
    var rollsAttempted: Int = 0
    var awaitingRoot: Boolean = false
    val maxRolls: Int = snapSyncConfig.storageRecoveryMaxRootRolls
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    Behaviors.withTimers { timers =>

      def recordProgress(): Unit = {
        progressSeq += 1
        lastProgressNanos = System.nanoTime()
        unservableCount = 0
        timers.cancel("abandon")
        rollsAttempted = 0
        awaitingRoot = false
      }

      def scheduleAbandonCheck(): Unit = {
        timers.cancel("abandon")
        timers.startSingleTimer("abandon", CheckAbandon(progressSeq), abandonAfter)
      }

      def finishRecovery(reason: String): Behavior[Any] = {
        timers.cancel("abandon")
        logResidualGaps(missing, stateStorage, pivotBlockNumber, ctx.log)
        RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.storageRecoveryDone().commit()
        ctx.log.info(s"Storage recovery finished ($reason).")
        syncController.tell(RecoveryComplete, org.apache.pekko.actor.ActorRef.noSender)
        Behaviors.stopped
      }

      Behaviors.receiveMessage {
        case actors.StorageRangeCoordinator.StoragePeerAvailable(peer) =>
          coordinator.tell(actors.StorageRangeCoordinator.StoragePeerAvailable(peer), org.apache.pekko.actor.ActorRef.noSender)
          Behaviors.same

        case SNAPSyncController.StorageRangeSyncComplete =>
          ctx.log.info(
            s"[SNAP-PROGRESS] STORAGE-RECOVERY 100% — $expectedCount / $expectedCount storage roots recovered — COMPLETE"
          )
          RecoveryMetrics.setStorageDownloaded(expectedCount.toLong)
          finishRecovery(s"downloaded storage for $expectedCount contracts")

        case SNAPSyncController.ProgressStorageSlotsSynced(_) =>
          downloadedCount += 1
          RecoveryMetrics.setStorageDownloaded(downloadedCount)
          recordProgress()
          recoveredCount += 1
          val (newM, crossed) =
            ProgressMilestones.crossed(recoveredCount, expectedCount.toLong, lastStorageRecoveryMilestone)
          lastStorageRecoveryMilestone = newM
          crossed.foreach { m =>
            val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
            val rate = if elapsedSecs > 0 then ((recoveredCount - lastRateRecovered) / elapsedSecs).toLong else 0L
            if m % 10 == 0 || m <= 5 || m >= 95 then {
              lastRateNanos = System.nanoTime()
              lastRateRecovered = recoveredCount
            }
            ctx.log.info(
              s"[SNAP-PROGRESS] STORAGE-RECOVERY $m% — $recoveredCount / $expectedCount storage roots | $rate roots/s"
            )
          }
          Behaviors.same

        case _: SNAPSyncController.PivotStateUnservable =>
          unservableCount += 1
          if unservableCount <= 3 || unservableCount % 100 == 0 then {
            ctx.log.info(
              "Storage recovery: coordinator reports root {} unservable ({} events, no progress for {}s).",
              currentRoot.take(4).toArray.map("%02x".format(_)).mkString,
              unservableCount,
              (System.nanoTime() - lastProgressNanos) / 1_000_000_000L
            )
          }
          if !timers.isTimerActive("abandon") then scheduleAbandonCheck()
          if !awaitingRoot && rollsAttempted < maxRolls then {
            awaitingRoot = true
            ctx.log.info(
              "Storage recovery: requesting a recent root to roll off the aged pivot (roll {} of {}).",
              rollsAttempted + 1,
              maxRolls
            )
            // Two-arg tell: sets ctx.self.toClassic as sender so SyncController's sender() captures this actor's ref
            syncController.tell(RequestRecentRoot, ctx.self.toClassic)
          } else if rollsAttempted >= maxRolls then {
            ctx.log.info(
              "Storage recovery: exhausted {} recent-root rolls; letting the abandon timer run for the residue.",
              maxRolls
            )
          }
          Behaviors.same

        case RecentRoot(_, _) if !awaitingRoot =>
          ctx.log.debug("Storage recovery: ignoring stale recent-root reply (no roll pending).")
          Behaviors.same

        case RecentRoot(blockNumber, rootOpt) =>
          awaitingRoot = false
          rootOpt match {
            case Some(root) if root != currentRoot =>
              val oldRoot = currentRoot
              rollsAttempted += 1
              currentRoot = root
              timers.cancel("abandon")
              unservableCount = 0
              val oldHex = oldRoot.take(4).toArray.map("%02x".format(_)).mkString
              val newHex = root.take(4).toArray.map("%02x".format(_)).mkString
              ctx.log.warn(
                s"Storage recovery: rolling download root $oldHex -> $newHex (block $blockNumber, " +
                  s"roll $rollsAttempted/$maxRolls). Re-queuing $expectedCount tasks."
              )
              coordinator.tell(actors.StorageRangeCoordinator.StoragePivotRefreshed(root), org.apache.pekko.actor.ActorRef.noSender)
            case Some(_) =>
              ctx.log.info(
                "Storage recovery: recent root equals current download root — no newer servable pivot. " +
                  "Letting the abandon timer run."
              )
            case None =>
              ctx.log.info("Storage recovery: no recent root available to roll to. Letting the abandon timer run.")
          }
          Behaviors.same

        case CheckAbandon(progressAtSchedule) =>
          if progressAtSchedule == progressSeq then {
            ctx.log.warn(
              "Storage recovery abandoning download: no slot progress for {}s after {} unservable events and {} " +
                "root roll(s). Remaining contract storage will be fetched on-demand via GetTrieNodes during regular sync.",
              abandonAfter.toSeconds,
              unservableCount,
              rollsAttempted
            )
            finishRecovery("abandoned: download stalled")
          } else {
            Behaviors.same
          }

        case CoordinatorTerminated =>
          ctx.log.error(
            "StorageRangeCoordinator crashed unexpectedly. Marking storage recovery done to unblock sync."
          )
          finishRecovery("coordinator crashed")

        case _ =>
          // Drop unrecognised messages (e.g. StorageBackpressureChanged directed at this actor)
          Behaviors.same
      }
    }
  }

  private def logResidualGaps(
      missing: Seq[(ByteString, ByteString)],
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Unit = {
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val residual = missing.count { case (_, storageRoot) =>
      try {
        mptStorage.get(storageRoot.toArray)
        false
      } catch {
        case _: MerklePatriciaTrie.MPTException => true
      }
    }
    if residual == 0 then log.info(s"Storage recovery: all ${missing.size} contract storage tries present on disk.")
    else
      log.warn(
        s"Storage recovery finishing: ${missing.size - residual} of ${missing.size} storage gaps filled, " +
          s"$residual residual (hot contracts changed since pivot, or never served). Regular sync will fetch " +
          s"these on-demand via GetTrieNodes when block execution reaches them."
      )
  }

  private def scanForMissingStorage(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Seq[(ByteString, ByteString)] = {
    RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    val seenRoots = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L
    var checkedCount = 0L

    val onLeaf: (ByteString, LeafNode) => Unit = { (accountHash, leafNode) =>
      accountCount += 1
      if accountCount % 100_000 == 0 then {
        RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
      }
      if accountCount % 1_000_000 == 0 then {
        log.info(
          s"Storage recovery scan: $accountCount accounts, $contractCount contracts, " +
            s"$checkedCount checked, ${missing.size} missing"
        )
      }

      Account(leafNode.value) match {
        case Success(account) =>
          if account.storageRoot != Account.EmptyStorageRootHash then {
            contractCount += 1
            if !seenRoots.contains(account.storageRoot) then {
              seenRoots += account.storageRoot
              checkedCount += 1
              try
                mptStorage.get(account.storageRoot.toArray)
              catch {
                case _: MerklePatriciaTrie.MPTException =>
                  missing += ((accountHash, account.storageRoot))
              }
            }
          }
        case Failure(_) => // Skip malformed account RLP
      }
    }

    try {
      val visitor = new PathTrackingLeafWalkVisitor(mptStorage, ByteString.empty, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing storage tries",
          e
        )
    }

    log.info(
      s"Storage recovery scan complete: $accountCount accounts, $contractCount contracts, " +
        s"$checkedCount unique storage roots checked, ${missing.size} missing"
    )
    RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
  }
}
