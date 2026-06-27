package com.chipprbots.ethereum.blockchain.sync
import com.chipprbots.ethereum.domain.Block

object SyncProtocol:

  /** Marker trait for messages that SNAPSyncController sends up to SyncController. Not sealed because the companion
    * message types live in SNAPSyncController.scala (a separate file). SyncController receives them via a
    * `messageAdapter[SyncControllerReply]`, narrowing the `ActorRef[Any]` that SSC previously held.
    */
  trait SyncControllerReply

  /** All direct subtypes are defined in this file — sealed is now valid (W17 fix). Direct subtypes: Start, GetStatus,
    * MinedBlock, RegularSyncStuck (below), FetcherStatusTick, PrintStatusTick, ProgressProtocol (all in this file).
    */
  sealed trait RegularSyncCommand

  /** Internal timer ticks used only by RegularSync (in the `sync.regular` sub-package). These are `sealed` variants so
    * external callers cannot accidentally send them; they are not part of the public SyncProtocol API surface.
    */
  case object FetcherStatusTick extends RegularSyncCommand
  case object PrintStatusTick extends RegularSyncCommand

  /** Progress messages sent from BlockFetcher / BlockImporter back to RegularSync. */
  sealed trait ProgressProtocol extends RegularSyncCommand
  object ProgressProtocol:
    case object StartedFetching extends ProgressProtocol
    case class StartingFrom(blockNumber: BigInt) extends ProgressProtocol
    case class GotNewBlock(blockNumber: BigInt) extends ProgressProtocol
    case class ImportedBlock(blockNumber: BigInt, internally: Boolean) extends ProgressProtocol

  sealed trait SyncProtocolMsg
  case object Start extends SyncProtocolMsg with RegularSyncCommand
  final case class GetStatus(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.Status])
      extends SyncProtocolMsg
      with RegularSyncCommand
  case class MinedBlock(block: Block) extends SyncProtocolMsg with RegularSyncCommand

  /** Clears persisted fast-sync markers so the next start can enter fast sync again. This is intentionally a "soft"
    * reset: it does not wipe the chain DB.
    */
  final case class ResetFastSync(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.ResetFastSyncResponse])
      extends SyncProtocolMsg
  final case class ResetFastSyncResponse(reset: Boolean) extends SyncProtocolMsg

  /** Requests a safe in-process restart of fast sync. The controller will apply a circuit-breaker cool-off period to
    * avoid thrashing.
    */
  final case class RestartFastSync(
      replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.RestartFastSyncResponse]
  ) extends SyncProtocolMsg
  final case class RestartFastSyncResponse(started: Boolean, cooldownUntilMillis: Long) extends SyncProtocolMsg

  /** Signals that regular sync has hit a wall — repeated state-node fetch exhaustion on the same block, with no peer
    * able to serve our parent stateRoot (typical when the node is many thousands of blocks behind canonical tip and the
    * snap-serve window of every connected peer has moved far past us). The controller responds by clearing the
    * SnapSyncDone flag and re-running SNAP sync from a recent pivot, which is the only viable recovery path.
    */
  final case class RegularSyncStuck(blockNumber: BigInt, missingHash: String)
      extends SyncProtocolMsg
      with RegularSyncCommand

  /** Delivered to RegularSync via watchWith when BlockFetcher terminates (RF-2 Option A). RegularSync re-spawns both
    * BlockFetcher and BlockImporter: BlockImporter holds a captured fetcher ref in its constructor, so it must be
    * replaced together with BlockFetcher to avoid a dead-letter sink.
    */
  case object BlockFetcherStopped extends RegularSyncCommand

  /** Signals that SNAP finalization detected a state root mismatch (snapStateRoot != pivotHeader.stateRoot).
    * SyncController responds by clearing SnapSyncDone and restarting SNAP with a fresh pivot. Mirrors Besu BUG-008
    * class recovery: abort finalization rather than commit a broken state.
    */
  case object HealingImpossible extends SyncProtocolMsg with SyncControllerReply

  /** Sent by NetworkPeerManagerActor to SyncController when a TD-PROXY-GAP is detected at peer handshake.
    * SyncController uses the peer's STATUS-message TD and block number to interpolate and write the correct cumulative
    * TD for the current best block, replacing the genesis-proxy value stored by SNAP finalization when no ETH68 peers
    * were available. Only fired when peerMaxBlock > 0.
    */
  final case class CalibrateChainWeightFromPeer(peerTD: BigInt, peerMaxBlock: BigInt) extends SyncProtocolMsg

  sealed trait Status:
    def syncing: Boolean = this match
      case Status.Syncing(_, _, _) => true
      case Status.NotSyncing       => false
      case Status.SyncDone         => false

    def notSyncing: Boolean = !syncing
  object Status:
    case class Progress(current: BigInt, target: BigInt):
      val isEmpty: Boolean = current == 0 && target == 0
      val nonEmpty: Boolean = !isEmpty
    object Progress:
      val empty: Progress = Progress(0, 0)
    case class Syncing(
        startingBlockNumber: BigInt,
        blocksProgress: Progress,
        stateNodesProgress: Option[Progress] // relevant only in fast sync, but is required by RPC spec
    ) extends Status

    case object NotSyncing extends Status
    case object SyncDone extends Status
