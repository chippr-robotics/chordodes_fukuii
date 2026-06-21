package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*

/** Message protocols for SNAP sync actors */
object Messages {

  // ========================================
  // Global Coordinator Control Messages
  // ========================================

  /** Dynamically adjust per-peer concurrency budget for a coordinator. Sent by SNAPSyncController at phase transitions
    * to implement global per-peer request budgeting (Geth-aligned: total 5 requests per peer across all coordinators).
    *
    * BCC and SRC define their own sealed UpdateMaxInFlightPerPeer. Remaining: ARC, TNHC (still non-sealed).
    */
  case class UpdateMaxInFlightPerPeer(newLimit: Int)
      extends AccountRangeCoordinator.Command
      with TrieNodeHealingCoordinator.Command

  // ========================================
  // AccountRange Messages
  // ========================================

  // Extends AccountRangeCoordinator.Command (Group S3): the coordinator is now a Typed actor with a (non-sealed)
  // Command ADT. All AccountRangeCoordinatorMessage cases are therefore Commands; SSC (still Classic) sends them via
  // the Classic `!`.
  sealed trait AccountRangeCoordinatorMessage extends AccountRangeCoordinator.Command

  case class StartAccountRangeSync(stateRoot: ByteString) extends AccountRangeCoordinatorMessage
  case class PeerAvailable(peer: Peer) extends AccountRangeCoordinatorMessage
  case class TaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, com.chipprbots.ethereum.domain.Account)], Seq[ByteString])]
  ) extends AccountRangeCoordinatorMessage
  case class TaskFailed(requestId: BigInt, reason: String) extends AccountRangeCoordinatorMessage
  case class PeerUnavailable(peerId: String) extends AccountRangeCoordinatorMessage
  case object GetProgress extends AccountRangeCoordinatorMessage

  /** Typed status query: report account-range progress to `replyTo`. Replaces the Classic `sender()` reply on
    * `GetProgress` after the AccountRangeCoordinator migration to Pekko Typed. A distinct name (not `GetProgress`) is
    * required because Scala 3 forbids a `case class` and a `case object` sharing a name in the same scope — same
    * convention as `ByteCodeGetProgress` / `StorageGetProgress`.
    */
  case class AccountGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[AccountRangeStats])
      extends AccountRangeCoordinatorMessage
  case object GetContractAccounts extends AccountRangeCoordinatorMessage

  /** Typed status query: report contract accounts to `replyTo`. */
  case class AccountGetContractAccounts(replyTo: org.apache.pekko.actor.typed.ActorRef[ContractAccountsResponse])
      extends AccountRangeCoordinatorMessage
  case class ContractAccountsResponse(accounts: Seq[(ByteString, ByteString)]) extends AccountRangeCoordinatorMessage
  case object GetContractStorageAccounts extends AccountRangeCoordinatorMessage

  /** Typed status query: report contract-storage accounts to `replyTo`. */
  case class AccountGetContractStorageAccounts(
      replyTo: org.apache.pekko.actor.typed.ActorRef[ContractStorageAccountsResponse]
  ) extends AccountRangeCoordinatorMessage
  case class ContractStorageAccountsResponse(accounts: Seq[(ByteString, ByteString)])
      extends AccountRangeCoordinatorMessage

  /** Request unique codeHashes collected during account download (Bloom-filtered, file-backed). Returns ~2M entries
    * (~64MB) instead of 73.5M raw entries (4.7GB). Bug 20 fix.
    */
  case object GetUniqueCodeHashes extends AccountRangeCoordinatorMessage

  /** Typed status query: report unique codeHashes to `replyTo`. */
  case class AccountGetUniqueCodeHashes(replyTo: org.apache.pekko.actor.typed.ActorRef[UniqueCodeHashesResponse])
      extends AccountRangeCoordinatorMessage
  case class UniqueCodeHashesResponse(codeHashes: Seq[ByteString])

  /** Request storage file metadata for async streaming. Returns instantly (no file read). */
  case object GetStorageFileInfo extends AccountRangeCoordinatorMessage

  /** Typed status query: report storage file metadata to `replyTo`. */
  case class AccountGetStorageFileInfo(replyTo: org.apache.pekko.actor.typed.ActorRef[StorageFileInfoResponse])
      extends AccountRangeCoordinatorMessage
  case class StorageFileInfoResponse(filePath: java.nio.file.Path, count: Long)

  /** Request codeHashes file metadata for bytecode recovery. Returns instantly (no file read). */
  case object GetCodeHashesFileInfo extends AccountRangeCoordinatorMessage

  /** Typed status query: report codeHashes file metadata to `replyTo`. */
  case class AccountGetCodeHashesFileInfo(replyTo: org.apache.pekko.actor.typed.ActorRef[CodeHashesFileInfoResponse])
      extends AccountRangeCoordinatorMessage
  case class CodeHashesFileInfoResponse(filePath: java.nio.file.Path, count: Long)
  case object CheckCompletion extends AccountRangeCoordinatorMessage

  /** Sent by AccountRangeCoordinator to SNAPSyncController with progress for ALL ranges. Maps range `last` hash →
    * current `next` position. Used to resume partial ranges across SNAP sync restarts (core-geth parity: preserves
    * task.Next across pivot changes).
    */
  case class AccountRangeProgress(progress: Map[ByteString, ByteString]) extends AccountRangeCoordinatorMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected. Coordinator updates pending tasks with the new
    * root and resumes downloading.
    */
  case class PivotRefreshed(newStateRoot: ByteString) extends AccountRangeCoordinatorMessage

  /** Sent by `SNAPSyncController`'s 180 s account-stall watchdog (#1184) to ask the `AccountRangeCoordinator` to drain
    * `activeTasks` back to `pendingTasks` before the controller's `refreshPivotInPlace` runs. Coordinator-side
    * defensive drains in `PeerUnavailable` / `PivotRefreshed` / `CheckDispatchStalled` cover this independently; this
    * message is the explicit controller hook.
    */
  case object RecoverStalledAccountTasks extends AccountRangeCoordinatorMessage

  /** Sent by `StorageRangeCoordinator` (via `SNAPSyncController`) when its pending-task queue depth crosses a
    * watermark. `paused = true` is emitted on the high-water transition: AccountRangeCoordinator should stop
    * dispatching new account-range requests so it stops producing new storage tasks. `paused = false` is emitted on the
    * low-water transition once the storage queue drains: AccountRangeCoordinator resumes dispatching.
    *
    * Workers already in flight continue to completion regardless — this only gates the next-dispatch decision.
    */
  case class StorageQueuePressure(paused: Boolean) extends AccountRangeCoordinatorMessage

  /** Same shape as `StorageQueuePressure`, emitted by `ByteCodeCoordinator`. Account-range completions enqueue both
    * storage tasks AND bytecode tasks, so either downstream coordinator can independently trigger back-pressure on the
    * account dispatcher. AccountRangeCoordinator pauses dispatch whenever *any* downstream is over its high-water mark
    * and only resumes once all sources have released.
    */
  case class ByteCodeQueuePressure(paused: Boolean) extends AccountRangeCoordinatorMessage

  /** Self-message — periodic check for `activeTasks.nonEmpty` + no-activity wedges (#1184). Scheduled from
    * `AccountRangeCoordinator.preStart` at 30 s intervals via `scheduleAtFixedRate`; cancelled in `postStop`.
    */
  private[actors] case object CheckDispatchStalled extends AccountRangeCoordinatorMessage

  // Internal message for chunked account storage (avoids blocking the actor for minutes)
  private[actors] case class StoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, com.chipprbots.ethereum.domain.Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ) extends AccountRangeCoordinatorMessage

  sealed trait AccountRangeWorkerMessage
  case class FetchAccountRange(
      task: AccountTask,
      peer: Peer,
      requestId: BigInt,
      responseBytes: BigInt = BigInt(512 * 1024)
  ) extends AccountRangeWorkerMessage
  // Dual-typed: a worker message (sent coordinator → worker) AND an AccountRangeCoordinator.Command
  // (sent SNAPSyncController → coordinator, which forwards to the owning worker). Mirrors BCC's
  // `ByteCodesResponseMsg`. Lets the now-Typed coordinator receive it in its Command mailbox.
  case class AccountRangeResponseMsg(response: AccountRange)
      extends AccountRangeWorkerMessage
      with AccountRangeCoordinator.Command
  case class RequestTimeout(requestId: BigInt) extends AccountRangeWorkerMessage
  case class WorkerPeerDisconnected(peerId: String) extends AccountRangeWorkerMessage

  /** Cancellation from coordinator after a `drainActiveTasks` operation (#1184). The worker cancels its own
    * `SNAPRequestTracker` entry, clears `currentTask`, and returns to `idle` if the request id matches; no `TaskFailed`
    * is sent back because the coordinator has already re-queued the task. Idempotent: a second cancel is a no-op.
    */
  case class WorkerRequestCancelled(requestId: BigInt) extends AccountRangeWorkerMessage

  // ========================================
  // TrieNodeHealing Messages
  // ========================================

  // Extends TrieNodeHealingCoordinator.Command (Group S3): the coordinator is a Typed actor with a (non-sealed,
  // cross-file) Command ADT. All TrieNodeHealingCoordinatorMessage cases are therefore Commands; SSC sends them via
  // the Typed ActorRef[Command]. HealingStagnated (OUTBOUND from TNHC to SSC) lives in SNAPSyncController.Command.
  sealed trait TrieNodeHealingCoordinatorMessage extends TrieNodeHealingCoordinator.Command

  case class StartTrieNodeHealing(stateRoot: ByteString) extends TrieNodeHealingCoordinatorMessage

  /** Queue missing trie nodes for healing. Each entry is (pathset, hash) where pathset is the GetTrieNodes path
    * encoding:
    *   - Account trie node: Seq(compact_path)
    *   - Storage trie node: Seq(account_hash, compact_storage_path)
    */
  case class QueueMissingNodes(nodes: Seq[(Seq[ByteString], ByteString)]) extends TrieNodeHealingCoordinatorMessage
  case class HealingPeerAvailable(peer: Peer) extends TrieNodeHealingCoordinatorMessage
  case class HealingPeerUnavailable(peerId: String) extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskComplete(requestId: BigInt, result: Either[String, Int])
      extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskFailed(requestId: BigInt, reason: String) extends TrieNodeHealingCoordinatorMessage

  /** Typed status query: report healing progress to `replyTo`. Replaces the Classic `sender()` reply on the prior `case
    * object HealingGetProgress` after the TrieNodeHealingCoordinator migration to Pekko Typed. Mirrors the same
    * convention as `ByteCodeGetProgress` / `StorageGetProgress` / `AccountGetProgress`.
    */
  case class HealingGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[HealingStatistics])
      extends TrieNodeHealingCoordinatorMessage
  case object HealingCheckCompletion extends TrieNodeHealingCoordinatorMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected during healing. Coordinator updates state root,
    * clears pending tasks and stateless tracking. A new trie walk will re-populate tasks for the new root.
    */
  case class HealingPivotRefreshed(newStateRoot: ByteString) extends TrieNodeHealingCoordinatorMessage

  /** spec 004 (Decoupled Heal Serve-Root): advance the SERVE root used to fetch missing nodes (GetTrieNodes) WITHOUT
    * touching the completeness WALK root. The handler sets `serveRoot = newServeRoot` and resets the FR-006 per-task
    * attempt counters; it does NOTHING else — it MUST NOT mutate `stateRoot` (the walk root), clear pendingTasks /
    * frontier, reset `verificationPassComplete`, or re-seed the walk. No-op when `decoupledHealServeRoot` is disabled.
    * Contrast `HealingPivotRefreshed`, which deliberately mutates the walk root and resets the completeness state and
    * MUST NOT be reused for a serve-root advance.
    */
  final case class HealingServeRootRefresh(newServeRoot: ByteString) extends TrieNodeHealingCoordinatorMessage

  /** Sent by SNAPSyncController in reply to a `HealingStagnated` it chose NOT to act on by rolling the pivot
    * (`heal-hold-pivot-on-stagnation = true`). It tells the coordinator to clear the in-flight `pivotRefreshRequested`
    * latch (set when the coordinator fired `HealingStagnated`) and resume dispatching the existing pending tasks
    * against the held root. This breaks the post-SNAP healing livelock: a slow-but-servable verification pass is no
    * longer aborted by a stagnation-driven pivot roll, so a single walk can converge against one stable root. The
    * coordinator keeps the held root and its pending frontier — nothing is cleared. See SNAPSyncController's
    * HealingStagnated handler for the full rationale.
    */
  case object HealingResumeDispatch extends TrieNodeHealingCoordinatorMessage

  /** Sent by SNAPSyncController when pivot advanced beyond SNAP serve window during healing (Besu reloadTrieHeal
    * pattern). Coordinator abandons pending tasks and signals completion so a fresh coordinator + walk can start for
    * the new root.
    */
  case object HealingForceComplete extends TrieNodeHealingCoordinatorMessage
  case class WalkStateChanged(inProgress: Boolean) extends TrieNodeHealingCoordinatorMessage

  sealed trait TrieNodeHealingWorkerMessage
  case class FetchTrieNodes(task: HealingTask, peer: Peer) extends TrieNodeHealingWorkerMessage
  // Sent to the now-Typed coordinator (TrieNodeHealingWorker / SSC forward it via the Classic `!`), so it is also a
  // Command.
  case class TrieNodesResponseMsg(response: TrieNodes)
      extends TrieNodeHealingWorkerMessage
      with TrieNodeHealingCoordinator.Command
  // Self-sent by the coordinator from the request-tracker timeout callback (`self ! HealingRequestTimeout`), so it is
  // also a Command.
  case class HealingRequestTimeout(requestId: BigInt)
      extends TrieNodeHealingWorkerMessage
      with TrieNodeHealingCoordinator.Command
  case object HealingCheckIdle extends TrieNodeHealingWorkerMessage
}
