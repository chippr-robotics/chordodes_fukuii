package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.utils.Config.SyncConfig

object BlockBroadcasterActor:
  sealed trait BroadcasterMsg
  case class BroadcastBlock(block: BlockToBroadcast) extends BroadcasterMsg
  case class BroadcastBlocks(blocks: List[BlockToBroadcast]) extends BroadcasterMsg

  /** Sent by RegularSync when a CL-canonical head advance (post-merge `forkchoiceUpdated`) should be announced to all
    * currently-connected eth peers. The actor already owns the up-to-date handshaked-peer map via `PeerListHelper`, so
    * it can immediately delegate to `BlockBroadcast.announceCanonicalHead` without any peer-map round-trip.
    *
    * The header is ALSO retained as `latestCanonicalHead` so that peers discovered LATER (via the periodic peer scan)
    * are announced to as soon as they appear. This closes a race that broke the Hive "fukuii as sync server" test: the
    * single post-merge `forkchoiceUpdated` fires within milliseconds of RegularSync (and this actor) starting, before
    * the first `GetHandshakedPeersCmd` reply has populated the peer map — so the immediate announce reaches zero peers.
    * A downloader that handshaked BEFORE the FCU (at genesis) would then never learn fukuii advanced. Re-announcing to
    * newly-scanned peers delivers the head the moment that peer is observed.
    */
  case class AnnounceCanonicalHead(header: BlockHeader) extends BroadcasterMsg

  private case class WrappedHandshakedPeers(peers: Map[Peer, PeerInfo]) extends BroadcasterMsg
  private case class WrappedPeerDisconnected(event: PeerDisconnected) extends BroadcasterMsg
  private case object ScanPeers extends BroadcasterMsg
  private val ScanKey = "BlockBroadcasterScanPeers"

  def apply(
      broadcast: BlockBroadcast,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blacklist: Blacklist,
      syncConfig: SyncConfig
  ): Behavior[BroadcasterMsg] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case pd: PeerDisconnected => WrappedPeerDisconnected(pd)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }
        val handshakedPeersAdapter =
          ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](msg => WrappedHandshakedPeers(msg.peers))

        val peerListHelper = new PeerListHelper(
          peerEventBus = peerEventBus,
          blacklist = blacklist,
          peerDisconnectedAdapter = peerDisconnectedAdapter,
          log = org.slf4j.LoggerFactory.getLogger(classOf[BlockBroadcasterImpl])
        )

        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)

        running(peerListHelper, broadcast, networkPeerManager, handshakedPeersAdapter, latestCanonicalHead = None)
      }
    }

  private def running(
      peerListHelper: PeerListHelper,
      broadcast: BlockBroadcast,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers],
      latestCanonicalHead: Option[BlockHeader]
  ): Behavior[BroadcasterMsg] =
    Behaviors.receiveMessage {
      case ScanPeers =>
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        Behaviors.same

      case WrappedHandshakedPeers(peers) =>
        // Capture the peer set BEFORE the update so we can detect peers that appear in THIS scan. If a CL head has
        // already been announced (latestCanonicalHead), push it to the newly-observed peers immediately — this is the
        // recovery path for the FCU-before-peer-map race (see AnnounceCanonicalHead doc). Peers already in the map were
        // announced to at FCU time (or a prior scan), so we never re-spam them here.
        val knownBefore = peerListHelper.handshakedPeers.keySet
        peerListHelper.handleHandshakedPeers(peers)
        latestCanonicalHead.foreach { head =>
          val newlyObserved = peerListHelper.handshakedPeers.filterNot { case (peerId, _) =>
            knownBefore.contains(peerId)
          }
          if newlyObserved.nonEmpty then broadcast.announceCanonicalHead(head, newlyObserved)
        }
        Behaviors.same

      case WrappedPeerDisconnected(event) =>
        peerListHelper.handlePeerDisconnected(event.peerId)
        Behaviors.same

      case BroadcastBlock(newBlock) =>
        broadcast.broadcastBlock(newBlock, peerListHelper.handshakedPeers)
        Behaviors.same

      case BroadcastBlocks(blocks) =>
        blocks.foreach(broadcast.broadcastBlock(_, peerListHelper.handshakedPeers))
        Behaviors.same

      case AnnounceCanonicalHead(header) =>
        // Announce to peers already known now, AND retain the head so peers discovered by a later scan get it too.
        broadcast.announceCanonicalHead(header, peerListHelper.handshakedPeers)
        running(
          peerListHelper,
          broadcast,
          networkPeerManager,
          handshakedPeersAdapter,
          latestCanonicalHead = Some(header)
        )
    }

// Logger name anchor — never instantiated
final private class BlockBroadcasterImpl
