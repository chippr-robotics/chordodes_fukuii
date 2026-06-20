package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed.{ActorRef as TypedActorRef, Behavior}
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.reflect.ClassTag

import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MaintainedPeersChanged
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MaintainedPeersClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.utils.Config.SyncConfig

class PeersClient(
    networkPeerManager: ActorRef,
    peerEventBus: TypedActorRef[PeerEventBusCommand],
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    scheduler: Scheduler // kept for props() backward compat; Typed core uses withTimers
) extends Actor {
  import PeersClient.*

  private val core = context.spawn(
    PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig),
    "core",
    DispatcherSelector.sameAsParent()
  )

  override def receive: Receive = {
    case req: Request[?] =>
      core ! RequestCmd(
        req.message.asInstanceOf[Message],
        req.peerSelector,
        req.toSerializable.asInstanceOf[Message => MessageSerializable],
        sender()
      )
    case BlacklistPeer(peerId, reason) => core ! BlacklistPeerCmd(peerId, reason)
    case RecordNodeDataFailure(peerId) => core ! RecordNodeDataFailureCmd(peerId)
    case PrintStatus                   => core ! PrintStatusCmd
  }
}

object PeersClient {

  def props(
      networkPeerManager: ActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      scheduler: Scheduler
  ): Props =
    Props(new PeersClient(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler))

  // ---- Command ADT (Typed core only) ----

  sealed trait Command

  final private case class RequestCmd(
      message: Message,
      peerSelector: PeerSelector,
      toSerializable: Message => MessageSerializable,
      replyTo: ActorRef
  ) extends Command
  final private case class BlacklistPeerCmd(peerId: PeerId, reason: BlacklistReason) extends Command
  final private case class RecordNodeDataFailureCmd(peerId: PeerId) extends Command
  private case object PrintStatusCmd extends Command
  private case object ScanPeersTick extends Command
  private case object PrintStatusTick extends Command
  final private case class HandshakedPeersCmd(peers: Map[Peer, PeerInfo]) extends Command
  final private case class PeerDisconnectedCmd(peerId: PeerId) extends Command
  final private case class MaintainedPeersChangedCmd(nodeIds: Set[String]) extends Command
  final private case class PRHResultCmd(id: Int, result: PeerRequestHandler.Result) extends Command

  // ---- Typed core behavior ----

  private def behavior(
      networkPeerManager: ActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      blacklist: Blacklist,
      syncConfig: SyncConfig
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val handshakedPeersAdapter =
        ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers] {
          case NetworkPeerManagerActor.HandshakedPeers(peers) => HandshakedPeersCmd(peers)
        }
      val peerDisconnectedAdapter =
        ctx.messageAdapter[PeerDisconnected] { case PeerDisconnected(peerId) => PeerDisconnectedCmd(peerId) }
      val maintainedAdapter =
        ctx.messageAdapter[MaintainedPeersChanged] { case MaintainedPeersChanged(nodeIds) =>
          MaintainedPeersChangedCmd(nodeIds)
        }

      // Besu alignment: subscribe at startup so updates arrive before any BlacklistPeer message.
      peerEventBus ! SubscribeCmd(MaintainedPeersClassifier, maintainedAdapter.toClassic)

      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay("scan-peers", ScanPeersTick, 0.seconds, syncConfig.peersScanInterval)
        timers.startTimerWithFixedDelay(
          "print-status",
          PrintStatusTick,
          syncConfig.printStatusInterval,
          syncConfig.printStatusInterval
        )
        new Impl(
          ctx,
          networkPeerManager,
          peerEventBus,
          blacklist,
          syncConfig,
          handshakedPeersAdapter,
          peerDisconnectedAdapter
        ).running(Map.empty)
      }
    }

  // ---- Typed core Impl ----

  private class Impl(
      ctx: ActorContext[Command],
      networkPeerManager: ActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers],
      peerDisconnectedAdapter: TypedActorRef[PeerDisconnected]
  ) {

    private var _maintainedNodeIdHexes: Set[String] = Set.empty
    private val nodeDataCooldownUntilMs = mutable.Map.empty[PeerId, Long]
    private val nodeDataConsecutiveFailures = mutable.Map.empty[PeerId, Int]
    private var nextPrhId: Int = 0

    private val peerHelper = new PeerListHelper(peerEventBus, blacklist, peerDisconnectedAdapter, ctx.log) {
      override protected def maintainedNodeIdHexes: Set[String] = _maintainedNodeIdHexes
    }

    def running(requesters: Map[Int, ActorRef]): Behavior[Command] =
      Behaviors.receiveMessage {
        case ScanPeersTick =>
          networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, handshakedPeersAdapter.toClassic)
          Behaviors.same

        case HandshakedPeersCmd(peers) =>
          peerHelper.handleHandshakedPeers(peers)
          Behaviors.same

        case PeerDisconnectedCmd(peerId) =>
          // Intentionally do NOT clear nodeData cooldown on disconnect. A peer that closes
          // the connection when asked for GetNodeData (e.g. BONSAI Besu) will immediately
          // reconnect and repeat the same failure if we reset its state here. The time-based
          // cooldown must be allowed to expire naturally so the peer stays suppressed.
          peerHelper.handlePeerDisconnected(peerId)
          Behaviors.same

        case MaintainedPeersChangedCmd(nodeIds) =>
          _maintainedNodeIdHexes = nodeIds
          ctx.log.debug("Updated maintained peer node IDs: {} peers", nodeIds.size)
          Behaviors.same

        case BlacklistPeerCmd(peerId, reason) =>
          peerHelper.blacklistIfHandshaked(peerId, syncConfig.blacklistDuration, reason)
          Behaviors.same

        case RecordNodeDataFailureCmd(peerId) =>
          val count = nodeDataConsecutiveFailures.getOrElse(peerId, 0) + 1
          nodeDataConsecutiveFailures(peerId) = count
          val cooldownMs = if count >= 3 then 3_600_000L else count * 30_000L
          nodeDataCooldownUntilMs(peerId) = System.currentTimeMillis() + cooldownMs
          ctx.log.debug("Peer {} GetNodeData failure #{} — cooldown {}ms", peerId, count, Long.box(cooldownMs))
          Behaviors.same

        case PrintStatusCmd | PrintStatusTick =>
          printStatus(requesters)
          Behaviors.same

        case RequestCmd(message, peerSelector, toSerializable, replyTo) =>
          ctx.log.debug(
            "Received request for message type {} using selector {}",
            message.getClass.getSimpleName,
            peerSelector
          )
          ctx.log.debug(
            "Total handshaked peers: {}, Available peers (not blacklisted): {}",
            peerHelper.handshakedPeers.size,
            peerHelper.peersToDownloadFrom.size
          )

          if peerHelper.peersToDownloadFrom.isEmpty && peerHelper.handshakedPeers.nonEmpty then {
            ctx.log.debug("All {} handshaked peers are blacklisted", peerHelper.handshakedPeers.size)
            peerHelper.handshakedPeers.foreach { case (peerId, peerInfo) =>
              ctx.log.debug(
                "Peer {} ({}): blacklisted={}",
                peerId,
                peerInfo.peer.remoteAddress,
                blacklist.isBlacklisted(peerId)
              )
            }
          }

          selectPeer(peerSelector) match {
            case Some(peer) =>
              ctx.log.debug(
                "Selected peer {} with address {} for request",
                peer.id,
                peer.remoteAddress.getHostString
              )
              val adaptedMsg = adaptMessageForPeer(message)
              val adaptedToSer: Message => MessageSerializable = msg =>
                msg match {
                  case s: MessageSerializable => s
                  case _                      => toSerializable(message) // fallback to original
                }
              val id = nextPrhId; nextPrhId += 1
              val prhAdapter = ctx.messageAdapter[PeerRequestHandler.Result](r => PRHResultCmd(id, r))
              issueSpawn(
                peer,
                adaptedMsg,
                responseMsgCode(adaptedMsg),
                adaptedToSer,
                prhAdapter,
                id,
                responseClassTag(adaptedMsg)
              )
              running(requesters + (id -> replyTo))

            case None =>
              ctx.log.debug(
                "No suitable peer found to issue a request (handshaked: {}, available: {})",
                peerHelper.handshakedPeers.size,
                peerHelper.peersToDownloadFrom.size
              )
              replyTo.tell(NoSuitablePeer, ActorRef.noSender)
              Behaviors.same
          }

        case PRHResultCmd(id, result) =>
          requesters.get(id) match {
            case Some(replyTo) =>
              result match {
                case PeerRequestHandler.ResponseReceived(peer, message, timeTaken) =>
                  val (msgType, itemCount) = message match {
                    case ETHPackets.BlockHeaders(_, headers) => (PeerRateTracker.MsgGetBlockHeaders, headers.size)
                    case ETHPackets.BlockBodies(_, bodies)   => (PeerRateTracker.MsgGetBlockBodies, bodies.size)
                    case ETHPackets.Receipts68(_, receipts)  => (PeerRateTracker.MsgGetReceipts, receipts.items.size)
                    case _                                   => (-1, 0)
                  }
                  if msgType >= 0 then peerHelper.updateEthRate(peer.id.value, msgType, timeTaken, itemCount)
                  replyTo.tell(Response(peer, message.asInstanceOf[Message]), ActorRef.noSender)

                case PeerRequestHandler.RequestFailed(peer, reason) =>
                  ctx.log.warn(s"Request to peer ${peer.remoteAddress} failed - reason: $reason")
                  replyTo.tell(
                    RequestFailed(peer, BlacklistReason.RegularSyncRequestFailed(reason)),
                    ActorRef.noSender
                  )
              }
            case None =>
              ctx.log.debug("PRHResultCmd: unknown id={} — already handled or timed out", id)
          }
          running(requesters - id)
      }

    // Existential capture: extract the runtime ClassTag from ClassTag[? <: Message] into a fresh
    // local type R so that PRH.behavior[Message, R] gets a concrete ClassTag for pattern matching.
    private def issueSpawn(
        peer: Peer,
        msg: Message,
        code: Int,
        toSer: Message => MessageSerializable,
        prhAdapter: TypedActorRef[PeerRequestHandler.Result],
        id: Int,
        ct: ClassTag[? <: Message]
    ): Unit = {
      type R <: Message
      implicit val ctR: ClassTag[R] = ct.asInstanceOf[ClassTag[R]]
      implicit val toSerializer: Message => MessageSerializable = toSer
      ctx.spawn(
        PeerRequestHandler.behavior[Message, R](
          peer,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          msg,
          code,
          prhAdapter
        ),
        s"prh-$id"
      )
    }

    private def selectPeer(peerSelector: PeerSelector): Option[Peer] =
      peerSelector match {
        case BestPeer =>
          ctx.log.debug("Selecting best peer from {} available peers", peerHelper.peersToDownloadFrom.size)
          bestPeer(peerHelper.peersToDownloadFrom, ctx.log)

        case BestSnapPeer =>
          val snapPeers = peerHelper.peersToDownloadFrom.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.remoteStatus.supportsSnap
          }
          ctx.log.debug(
            "Selecting best SNAP-capable peer from {} available peers ({} SNAP-capable)",
            peerHelper.peersToDownloadFrom.size,
            snapPeers.size
          )
          bestPeer(snapPeers, ctx.log)

        case BestNodeDataPeer =>
          val now = System.currentTimeMillis()
          val nodeDataPeers = peerHelper.peersToDownloadFrom.filter { case (peerId, _) =>
            !nodeDataCooldownUntilMs.get(peerId).exists(_ > now)
          }
          ctx.log.debug(
            "Selecting best GetNodeData-capable peer from {} available peers ({} capable, {} on cooldown)",
            peerHelper.peersToDownloadFrom.size,
            nodeDataPeers.size,
            nodeDataCooldownUntilMs.count { case (_, exp) => exp > now }
          )
          bestPeer(nodeDataPeers, ctx.log)

        case ExcludingPeers(exclude) =>
          val filteredPeers = peerHelper.peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
          ctx.log.debug(
            "Selecting best peer excluding {} peers from {} available ({} remaining)",
            exclude.size,
            peerHelper.peersToDownloadFrom.size,
            filteredPeers.size
          )
          bestPeer(filteredPeers, ctx.log)

        case BestPeerWithMinBlock(minBlock) =>
          // Two-tier selection: peers with known maxBlockNumber >= minBlock are
          // strictly better than peers with maxBlockNumber == 0 (unknown chain
          // state). Try the known-good tier first; if empty, fall back to the
          // unknown tier — which is correct behaviour for ETH/64-68 peers whose
          // maxBlockNumber stays at 0 because their STATUS doesn't carry a
          // block number and we don't receive block messages from them post-merge.
          val knownAheadPeers = peerHelper.peersToDownloadFrom.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.maxBlockNumber >= minBlock
          }
          if knownAheadPeers.nonEmpty then {
            ctx.log.debug(
              "BestPeerWithMinBlock({}): {} peers have known maxBlockNumber >= target",
              minBlock,
              knownAheadPeers.size
            )
            bestPeer(knownAheadPeers, ctx.log)
          } else {
            val unknownChainHeadPeers = peerHelper.peersToDownloadFrom.filter { case (_, peerWithInfo) =>
              peerWithInfo.peerInfo.maxBlockNumber == 0
            }
            ctx.log.debug(
              s"BestPeerWithMinBlock($minBlock): no peer with known maxBlockNumber >= target; " +
                s"falling back to ${unknownChainHeadPeers.size} peer(s) with maxBlockNumber=0 (chain state unknown)"
            )
            bestPeer(unknownChainHeadPeers, ctx.log)
          }

        case BestPeerWithMinBlockExcluding(minBlock, exclude) =>
          val eligible = peerHelper.peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
          val knownAheadPeers = eligible.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.maxBlockNumber >= minBlock
          }
          if knownAheadPeers.nonEmpty then {
            ctx.log.debug(
              "BestPeerWithMinBlockExcluding({}): {} eligible after excluding {} tried peer(s)",
              minBlock,
              knownAheadPeers.size,
              exclude.size
            )
            bestPeer(knownAheadPeers, ctx.log)
          } else {
            val unknownHeadPeers = eligible.filter { case (_, peerWithInfo) =>
              peerWithInfo.peerInfo.maxBlockNumber == 0
            }
            ctx.log.debug(
              "BestPeerWithMinBlockExcluding({}): no known-ahead peers after exclusion; {} unknown-chain-state remain",
              minBlock,
              unknownHeadPeers.size
            )
            bestPeer(unknownHeadPeers, ctx.log)
          }

        case BestSnapPeerExcluding(exclude) =>
          val snapPeers = peerHelper.peersToDownloadFrom.filter { case (peerId, peerWithInfo) =>
            !exclude.contains(peerId) && peerWithInfo.peerInfo.remoteStatus.supportsSnap
          }
          ctx.log.debug(
            "Selecting best SNAP peer excluding {} tried peers ({} SNAP remaining)",
            exclude.size,
            snapPeers.size
          )
          bestPeer(snapPeers, ctx.log)

        case BestSnapPeerWithMinBlockExcluding(minBlock, exclude) =>
          val eligible = peerHelper.peersToDownloadFrom.filter { case (peerId, peerWithInfo) =>
            !exclude.contains(peerId) && peerWithInfo.peerInfo.remoteStatus.supportsSnap
          }
          val knownAheadPeers = eligible.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.maxBlockNumber >= minBlock
          }
          if knownAheadPeers.nonEmpty then {
            ctx.log.debug(
              "BestSnapPeerWithMinBlockExcluding({}): {} SNAP peers at target after excluding {} tried",
              minBlock,
              knownAheadPeers.size,
              exclude.size
            )
            bestPeer(knownAheadPeers, ctx.log)
          } else {
            val unknownHeadPeers = eligible.filter { case (_, peerWithInfo) =>
              peerWithInfo.peerInfo.maxBlockNumber == 0
            }
            ctx.log.debug(
              "BestSnapPeerWithMinBlockExcluding({}): no known-ahead SNAP peers; {} with unknown chain state remain",
              minBlock,
              unknownHeadPeers.size
            )
            bestPeer(unknownHeadPeers, ctx.log)
          }

        case BestNodeDataPeerExcluding(exclude) =>
          val now = System.currentTimeMillis()
          val nodeDataPeers = peerHelper.peersToDownloadFrom.filter { case (peerId, _) =>
            !exclude.contains(peerId) &&
            !nodeDataCooldownUntilMs.get(peerId).exists(_ > now)
          }
          ctx.log.debug(
            "Selecting best GetNodeData peer excluding {} tried peers ({} capable remaining)",
            exclude.size,
            nodeDataPeers.size
          )
          bestPeer(nodeDataPeers, ctx.log)
      }

    /** Adapts message format based on peer's negotiated capability. ETH68+ always uses request-id — no adaptation
      * needed.
      */
    private def adaptMessageForPeer[RequestMsg <: Message](message: RequestMsg): Message = message

    private def responseClassTag[RequestMsg <: Message](requestMsg: RequestMsg): ClassTag[? <: Message] =
      requestMsg match {
        case _: ETHPackets.GetBlockHeaders       => implicitly[ClassTag[ETHPackets.BlockHeaders]]
        case _: ETHPackets.GetBlockBodies        => implicitly[ClassTag[ETHPackets.BlockBodies]]
        case _: ETHPackets.GetReceipts           => implicitly[ClassTag[ETHPackets.Receipts68]]
        case _: ETHPackets.GetPooledTransactions => implicitly[ClassTag[ETHPackets.PooledTransactions]]
        case _: GetTrieNodes                     => implicitly[ClassTag[TrieNodes]]
        case _: GetByteCodes                     => implicitly[ClassTag[ByteCodes]]
      }

    private def responseMsgCode[RequestMsg <: Message](requestMsg: RequestMsg): Int =
      requestMsg match {
        case _: ETHPackets.GetBlockHeaders       => Codes.BlockHeadersCode
        case _: ETHPackets.GetBlockBodies        => Codes.BlockBodiesCode
        case _: ETHPackets.GetReceipts           => Codes.ReceiptsCode
        case _: ETHPackets.GetPooledTransactions => Codes.PooledTransactionsCode
        case _: GetTrieNodes                     => SNAP.Codes.TrieNodesCode
        case _: GetByteCodes                     => SNAP.Codes.ByteCodesCode
      }

    private def printStatus(requesters: Map[Int, ActorRef]): Unit = {
      ctx.log.debug(
        "Request status: requests in progress: {}, available peers: {}",
        requesters.size,
        peerHelper.peersToDownloadFrom.size
      )
      lazy val handshakedPeersStatus = peerHelper.handshakedPeers.map { case (peerId, peerWithInfo) =>
        val peerNetworkStatus = PeerNetworkStatus(peerWithInfo.peer, isBlacklisted = blacklist.isBlacklisted(peerId))
        (peerNetworkStatus, peerWithInfo.peerInfo)
      }
      ctx.log.debug(s"Handshaked peers status (number of peers: ${handshakedPeersStatus.size}): $handshakedPeersStatus")
    }
  }

  // ---- Public API (unchanged from Classic) ----

  type Requesters = Map[ActorRef, ActorRef]

  sealed trait PeersClientMessage
  case class BlacklistPeer(peerId: PeerId, reason: BlacklistReason) extends PeersClientMessage
  case class RecordNodeDataFailure(peerId: PeerId) extends PeersClientMessage
  case class Request[RequestMsg <: Message](
      message: RequestMsg,
      peerSelector: PeerSelector,
      toSerializable: RequestMsg => MessageSerializable
  ) extends PeersClientMessage

  object Request {

    def create[RequestMsg <: Message](message: RequestMsg, peerSelector: PeerSelector)(implicit
        toSerializable: RequestMsg => MessageSerializable
    ): Request[RequestMsg] =
      Request(message, peerSelector, toSerializable)
  }

  case class PeerNetworkStatus(peer: Peer, isBlacklisted: Boolean) {
    override def toString: String =
      s"PeerNetworkStatus {" +
        s" RemotePeerAddress: ${peer.remoteAddress}," +
        s" ConnectionDirection: ${if peer.incomingConnection then "Incoming" else "Outgoing"}," +
        s" Is blacklisted?: $isBlacklisted" +
        s" }"
  }
  case object PrintStatus extends PeersClientMessage

  sealed trait ResponseMessage
  case object NoSuitablePeer extends ResponseMessage
  case class RequestFailed(peer: Peer, reason: BlacklistReason) extends ResponseMessage
  case class Response[T <: Message](peer: Peer, message: T) extends ResponseMessage

  sealed trait PeerSelector
  case object BestPeer extends PeerSelector
  case object BestSnapPeer extends PeerSelector
  case object BestNodeDataPeer extends PeerSelector
  case class ExcludingPeers(exclude: Set[PeerId]) extends PeerSelector
  case class BestSnapPeerExcluding(exclude: Set[PeerId]) extends PeerSelector
  case class BestNodeDataPeerExcluding(exclude: Set[PeerId]) extends PeerSelector

  /** Pick a peer whose advertised chain head is at least `minBlock`. Use this for absolute-block-number requests (e.g.
    * PivotHeaderBootstrap targeting a specific pivot) where peers behind that height literally have nothing to return.
    *
    * ETH/69 peers report `latestBlock` in STATUS, so `maxBlockNumber` reflects their true chain head. ETH/64-68 peers
    * don't carry a block number in STATUS and their `maxBlockNumber` stays at `0` post-merge (no incoming block
    * messages to update it via `peerHasUpdatedBestBlock`). We therefore include `maxBlockNumber == 0` peers as a
    * fallback — they MAY have the block but we can't tell.
    */
  case class BestPeerWithMinBlock(minBlock: BigInt) extends PeerSelector

  /** Like BestPeerWithMinBlock but skips peers in `exclude`. Used by PivotHeaderBootstrap to rotate through the peer
    * pool across attempts, modelling Besu's `waitForPeer((p) -> !peersUsed.contains(p))` predicate and go-ethereum's
    * idle-pool exclusion in `skeleton.assignTasks()`.
    */
  case class BestPeerWithMinBlockExcluding(minBlock: BigInt, exclude: Set[PeerId]) extends PeerSelector

  /** Like BestPeerWithMinBlockExcluding but restricted to SNAP-capable peers. Mirrors Besu's two-stage pivot pattern:
    * `PivotSelectorFromPeers` pre-filters by `estimatedChainHeight >= pivot`, then `PivotBlockConfirmer` excludes used
    * peers. Fukuii combines both concerns here since PivotHeaderBootstrap has no upstream height pre-filter.
    */
  case class BestSnapPeerWithMinBlockExcluding(minBlock: BigInt, exclude: Set[PeerId]) extends PeerSelector

  def bestPeer(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      log: Logger
  ): Option[Peer] = {
    log.debug("Evaluating {} peers to find best peer", peersToDownloadFrom.size)

    // Filter out peers whose bestHash == genesisHash. These peers have nothing to
    // serve and silently return empty responses to GetBlockHeaders, GetBlockBodies,
    // GetReceipts etc. — masking sync wedges as transient timeouts.
    //
    // Bug #1201 (Sepolia): half the post-fork-fix peer pool was Sepolia bootnodes
    // sitting at genesis (`bestHash == genesisHash`, TD=131072). PivotHeaderBootstrap's
    // BestPeer selection round-robined into them and reported "no header returned"
    // for blocks they literally don't have. forkAccepted=true is necessary but not
    // sufficient — the peer must also have advanced past genesis.
    //
    // Use maxBlockNumber > 0 rather than !isAtGenesis (bestHash == genesisHash): ETC and
    // ETH mainnet share genesis hash d4e56740..., so isAtGenesis is unreliable as a
    // cross-chain discriminator. Block-number-based filtering matches go-ethereum and Besu
    // peer selection semantics (both filter by peerHeadBlockHeader.getNumber() > 0).
    val peersToUse = peersToDownloadFrom.values
      .map { case PeerWithInfo(peer, peerInfo) =>
        val isReady = peerInfo.forkAccepted && peerInfo.maxBlockNumber > 0
        log.debug(
          s"Peer ${peer.id} (${peer.remoteAddress}) - ready: $isReady, " +
            s"maxBlock: ${peerInfo.maxBlockNumber}"
        )
        log.debug("Peer {} chainWeight: {}", peer.id, peerInfo.chainWeight)
        (peer, peerInfo, isReady)
      }
      .collect { case (peer, peerInfo, true) =>
        log.debug("Peer {} is ready and eligible for selection", peer.id)
        log.debug("Peer {} chainWeight: {}", peer.id, peerInfo.chainWeight)
        (peer, peerInfo.chainWeight)
      }

    if peersToUse.nonEmpty then {
      val (peer, chainWeight) = peersToUse.maxBy(_._2)
      log.debug("Selected best peer {} with chainWeight {}", peer.id, chainWeight)
      Some(peer)
    } else {
      log.debug("No ready peers available for selection from {} total peers", peersToDownloadFrom.size)
      None
    }
  }

  // Legacy method for backward compatibility — kept in sync with the logger-aware
  // overload above: skip forkRejected peers AND skip peers stuck at genesis.
  // NOTE: used as a utility by bestPeerWithMinBlock which explicitly passes maxBlockNumber==0
  // peers as a fallback for ETH/68 peers whose block height is unknown. Do NOT add a
  // maxBlockNumber > 0 guard here — that would silently break the ETH/68 fallback path.
  def bestPeer(peersToDownloadFrom: Map[PeerId, PeerWithInfo]): Option[Peer] = {
    val peersToUse = peersToDownloadFrom.values
      .collect {
        case PeerWithInfo(peer, peerInfo) if peerInfo.forkAccepted && !peerInfo.isAtGenesis =>
          (peer, peerInfo.chainWeight)
      }

    if peersToUse.nonEmpty then {
      val (peer, _) = peersToUse.maxBy(_._2)
      Some(peer)
    } else {
      None
    }
  }

  /** Static helper mirroring the BestPeerWithMinBlock selector for unit testing. */
  def bestPeerWithMinBlock(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      minBlock: BigInt
  ): Option[Peer] = {
    val knownAheadPeers = peersToDownloadFrom.filter { case (_, peerWithInfo) =>
      peerWithInfo.peerInfo.maxBlockNumber >= minBlock
    }
    if knownAheadPeers.nonEmpty then bestPeer(knownAheadPeers)
    else
      bestPeer(peersToDownloadFrom.filter { case (_, peerWithInfo) =>
        peerWithInfo.peerInfo.maxBlockNumber == 0
      })
  }

  /** Static helper mirroring the BestPeerWithMinBlockExcluding selector for unit testing. */
  def bestPeerWithMinBlockExcluding(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      minBlock: BigInt,
      exclude: Set[PeerId]
  ): Option[Peer] = {
    val eligible = peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
    val knownAheadPeers = eligible.filter { case (_, peerWithInfo) =>
      peerWithInfo.peerInfo.maxBlockNumber >= minBlock
    }
    if knownAheadPeers.nonEmpty then bestPeer(knownAheadPeers)
    else bestPeer(eligible.filter { case (_, peerWithInfo) => peerWithInfo.peerInfo.maxBlockNumber == 0 })
  }
}
