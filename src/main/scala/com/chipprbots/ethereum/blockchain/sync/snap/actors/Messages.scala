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
    * TNHC still non-sealed; this will be removed after TNHC phase.
    */
  case class UpdateMaxInFlightPerPeer(newLimit: Int)
      extends TrieNodeHealingCoordinator.Command

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
