package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage

/** Thin Typed wrapper around [[CombinedRecoveryScanner]]: runs ONE parallel, resumable single-pass scan of the state
  * trie (checking both bytecode and storage per account), then emits both gap sets to the parent `SyncController` via
  * [[CombinedRecoveryScanActor.CombinedScanComplete]] and stops. The controller drives the downloads from there.
  *
  * Replaces the two legacy single-threaded full-trie walks (`BytecodeRecoveryActor` + `StorageRecoveryActor` scan
  * phases). The scan is read-only and runs on a bounded pool, so it doesn't block the actor; the result is piped back
  * through `pipeToSelf`. A scan failure (or an empty trie) emits empty gap sets — recovery then proceeds straight to
  * regular sync, which fetches any residue on-demand.
  */
object CombinedRecoveryScanActor:

  // Not marked private so the return type of `apply` doesn't leak a private type;
  // ScanDone (the only subtype) remains private so no caller can construct a Command.
  sealed trait Command
  private case class ScanDone(result: RecoveryScanResult) extends Command

  /** Sent to SyncController when the combined scan finishes. The controller spawns the (download-only) recovery actors
    * for whichever phases it still needs.
    */
  final case class CombinedScanComplete(
      missingBytecodes: Seq[ByteString],
      missingStorageTries: Seq[(ByteString, ByteString)]
  )

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      syncController: ActorRef[CombinedScanComplete],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val asyncLog = LoggerFactory.getLogger(getClass)
      ctx.log.info(
        s"CombinedRecoveryScanActor starting: parallel single-pass scan " +
          s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}..., " +
          s"concurrency=${snapSyncConfig.recoveryScanConcurrency}, shardDepth=${snapSyncConfig.recoveryScanShardDepth})"
      )
      ctx.pipeToSelf(
        Future {
          val scanner = new CombinedRecoveryScanner(
            scanRoot = stateRoot,
            storageForShard = () => stateStorage.getBackingStorage(pivotBlockNumber),
            evmCodeStorage = evmCodeStorage,
            appStateStorage = appStateStorage,
            concurrency = snapSyncConfig.recoveryScanConcurrency,
            shardDepth = snapSyncConfig.recoveryScanShardDepth
          )
          scanner.run()
        }(ctx.executionContext)
      ) {
        case Success(result) => ScanDone(result)
        case Failure(ex) =>
          asyncLog.error("Combined recovery scan failed — reporting no gaps; regular sync will fetch on-demand", ex)
          ScanDone(RecoveryScanResult(Vector.empty, Vector.empty))
      }
      Behaviors.receiveMessage { case ScanDone(result) =>
        ctx.log.info(
          s"Combined recovery scan complete: ${result.missingBytecodes.size} missing bytecodes, " +
            s"${result.missingStorageTries.size} missing storage tries"
        )
        syncController ! CombinedScanComplete(result.missingBytecodes, result.missingStorageTries)
        Behaviors.stopped
      }
    }
