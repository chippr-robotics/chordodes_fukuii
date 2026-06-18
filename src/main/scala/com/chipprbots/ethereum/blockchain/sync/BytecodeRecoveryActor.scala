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

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MptVisitors.*

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose bytecodes are missing from
  * evmCodeStorage (due to the Bug 20 phase handoff timeout). Collects missing codeHashes and downloads them via SNAP
  * protocol using ByteCodeCoordinator.
  *
  * Pekko Typed actor (`Behavior[Any]`): SyncController (Classic parent) sends both `ByteCodePeerAvailable` and
  * `ByteCodeSyncComplete` / `ProgressBytecodesDownloaded` to this actor, which stays a Classic-visible ref via
  * `.toClassic`. Using `Any` as the message type accepts those heterogeneous Classic messages without a bridge adapter
  * while still providing all Typed machinery (named behavior functions, `Behaviors.withTimers`, `watchWith`).
  *
  * Lifecycle:
  *   1. Walk state trie, collect missing codeHashes (deduplicated) 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via ByteCodeCoordinator, then mark done
  */
object BytecodeRecoveryActor {

  // Internal self-messages (opaque to Classic senders)
  private case class ScanResult(missingCodeHashes: Seq[ByteString])
  private case class CheckAbandon(progressSeq: Long)
  // Delivered via watchWith when ByteCodeCoordinator terminates unexpectedly
  private case object CoordinatorTerminated

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = None,
    coordinatorForTesting = None
  )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing codeHashes (produced by
    * the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def applyPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString]
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
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
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[ByteString]] = None,
      coordinatorForTesting: Option[ActorRef] = None
  ): Behavior[Any] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
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
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[ByteString]],
      coordinatorForTesting: Option[ActorRef]
  ): Behavior[Any] =
    Behaviors.setup { ctx =>
      preloaded match {
        case Some(missing) =>
          ctx.self ! ScanResult(missing)
        case None =>
          ctx.log.info(
            s"BytecodeRecoveryActor starting: scanning state trie for missing bytecodes " +
              s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
          )
          ctx.pipeToSelf(
            Future(scanForMissingBytecodes(stateRoot, stateStorage, evmCodeStorage, pivotBlockNumber, ctx.log))(
              ctx.executionContext
            )
          ) {
            case Success(result) => ScanResult(result)
            case Failure(ex) =>
              ctx.log.error("Bytecode recovery scan failed", ex)
              ScanResult(Seq.empty)
          }
      }
      Behaviors.receiveMessage {
        case ScanResult(missing) =>
          if (missing.isEmpty) {
            ctx.log.info("Bytecode recovery: all contract bytecodes present. Marking recovery complete.")
            RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
            appStateStorage.bytecodeRecoveryDone().commit()
            syncController.tell(RecoveryComplete, org.apache.pekko.actor.ActorRef.noSender)
            Behaviors.stopped
          } else {
            ctx.log.warn(
              s"Bytecode recovery: found ${missing.size} missing bytecodes. Starting download..."
            )
            RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseDownloading)
            val coordinator: ActorRef = coordinatorForTesting.getOrElse {
              val requestTracker = new snap.SNAPRequestTracker()(ctx.system.classicSystem.scheduler)
              ctx.toClassic.actorOf(
                snap.actors.ByteCodeCoordinator
                  .props(
                    evmCodeStorage = evmCodeStorage,
                    networkPeerManager = networkPeerManager,
                    requestTracker = requestTracker,
                    batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE,
                    snapSyncController = ctx.self.toClassic
                  )
                  .withDispatcher("sync-dispatcher"),
                "bytecode-recovery-coordinator"
              )
            }
            ctx.watchWith(coordinator.toTyped[Nothing], CoordinatorTerminated)
            coordinator.tell(
              snap.actors.Messages.StartByteCodeSync(missing),
              org.apache.pekko.actor.ActorRef.noSender
            )
            downloading(ctx, coordinator, missing.size, syncController, appStateStorage, snapSyncConfig)
          }

        case _ => Behaviors.unhandled
      }
    }

  private def downloading(
      ctx: ActorContext[Any],
      coordinator: ActorRef,
      expectedCount: Int,
      syncController: ActorRef,
      appStateStorage: AppStateStorage,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Any] = {
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastBytecodeRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateDownloaded = 0L
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    Behaviors.withTimers { timers =>
      timers.startSingleTimer("abandon", CheckAbandon(0L), abandonAfter)

      def recordProgress(): Unit = {
        progressSeq += 1
        timers.cancel("abandon")
      }

      def finishRecovery(): Behavior[Any] = {
        timers.cancel("abandon")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.bytecodeRecoveryDone().commit()
        syncController.tell(RecoveryComplete, org.apache.pekko.actor.ActorRef.noSender)
        Behaviors.stopped
      }

      Behaviors.receiveMessage {
        case snap.actors.Messages.ByteCodePeerAvailable(peer) =>
          coordinator.tell(
            snap.actors.Messages.ByteCodePeerAvailable(peer),
            org.apache.pekko.actor.ActorRef.noSender
          )
          Behaviors.same

        case snap.SNAPSyncController.ByteCodeSyncComplete =>
          ctx.log.info(
            s"[SNAP-PROGRESS] BYTECODE-RECOVERY 100% — $expectedCount / $expectedCount bytecodes recovered — COMPLETE"
          )
          RecoveryMetrics.setBytecodeDownloaded(expectedCount.toLong)
          RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
          finishRecovery()

        case snap.SNAPSyncController.ProgressBytecodesDownloaded(_) =>
          downloadedCount += 1
          RecoveryMetrics.setBytecodeDownloaded(downloadedCount)
          recordProgress()
          downloadedCount += 1
          val (newM, crossed) =
            ProgressMilestones.crossed(downloadedCount, expectedCount.toLong, lastBytecodeRecoveryMilestone)
          lastBytecodeRecoveryMilestone = newM
          crossed.foreach { m =>
            val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
            val rate = if (elapsedSecs > 0) ((downloadedCount - lastRateDownloaded) / elapsedSecs).toLong else 0L
            if (m % 10 == 0 || m <= 5 || m >= 95) {
              lastRateNanos = System.nanoTime()
              lastRateDownloaded = downloadedCount
            }
            ctx.log.info(
              s"[SNAP-PROGRESS] BYTECODE-RECOVERY $m% — $downloadedCount / $expectedCount bytecodes | $rate bytecodes/s"
            )
          }
          Behaviors.same

        case CheckAbandon(progressAtSchedule) =>
          if (progressAtSchedule == progressSeq) {
            ctx.log.warn(
              "Bytecode recovery abandoned: no download progress for {}s. " +
                "Regular sync will fetch missing bytecodes on-demand via GetTrieNodes.",
              abandonAfter.toSeconds
            )
            finishRecovery()
          } else {
            Behaviors.same
          }

        case CoordinatorTerminated =>
          ctx.log.error(
            "ByteCodeCoordinator crashed unexpectedly. Marking bytecode recovery done to unblock sync."
          )
          finishRecovery()

        case _ =>
          // Drop unrecognised messages (e.g. ByteCodeBackpressureChanged directed at this actor
          // by the coordinator — not meaningful in recovery mode)
          Behaviors.same
      }
    }
  }

  private def scanForMissingBytecodes(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Seq[ByteString] = {
    RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[ByteString]
    val seen = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L

    val onLeaf: LeafNode => Unit = { leafNode =>
      accountCount += 1
      if (accountCount % 100_000 == 0) {
        RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
      }
      if (accountCount % 1_000_000 == 0) {
        log.info(
          s"Bytecode recovery scan: $accountCount accounts, $contractCount contracts, ${missing.size} missing"
        )
      }

      Account(leafNode.value) match {
        case Success(account) =>
          if (account.codeHash != Account.EmptyCodeHash && !seen.contains(account.codeHash)) {
            seen += account.codeHash
            contractCount += 1
            if (evmCodeStorage.get(account.codeHash).isEmpty) {
              missing += account.codeHash
            }
          }
        case Failure(_) => // Skip malformed account RLP
      }
    }

    try {
      val visitor = new LeafWalkVisitor(mptStorage, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing bytecodes",
          e
        )
    }

    log.info(
      s"Bytecode recovery scan complete: $accountCount accounts, $contractCount contracts, ${missing.size} missing bytecodes"
    )
    RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
  }
}
