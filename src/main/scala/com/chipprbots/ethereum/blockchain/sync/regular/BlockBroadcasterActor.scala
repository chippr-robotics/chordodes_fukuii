package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.utils.Config.SyncConfig

object BlockBroadcasterActor {
  sealed trait BroadcasterMsg
  case class BroadcastBlock(block: BlockToBroadcast) extends BroadcasterMsg
  case class BroadcastBlocks(blocks: List[BlockToBroadcast]) extends BroadcasterMsg

  private case class WrappedHandshakedPeers(peers: Map[Peer, PeerInfo]) extends BroadcasterMsg
  private case class WrappedPeerDisconnected(event: PeerDisconnected) extends BroadcasterMsg
  private case object ScanPeers extends BroadcasterMsg
  private val ScanKey = "BlockBroadcasterScanPeers"

  def apply(
      broadcast: BlockBroadcast,
      peerEventBus: ActorRef,
      networkPeerManager: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig
  ): Behavior[BroadcasterMsg] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val peerDisconnectedAdapter = ctx.messageAdapter[PeerDisconnected](WrappedPeerDisconnected.apply)
        val handshakedPeersAdapter =
          ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](msg => WrappedHandshakedPeers(msg.peers))

        val peerListHelper = new PeerListHelper(
          networkPeerManager = networkPeerManager,
          peerEventBus = peerEventBus,
          blacklist = blacklist,
          syncConfig = syncConfig,
          peerDisconnectedAdapter = peerDisconnectedAdapter,
          log = org.slf4j.LoggerFactory.getLogger(classOf[BlockBroadcasterImpl])
        )

        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, handshakedPeersAdapter.toClassic)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)

        running(peerListHelper, broadcast, networkPeerManager, handshakedPeersAdapter)
      }
    }

  private def running(
      peerListHelper: PeerListHelper,
      broadcast: BlockBroadcast,
      networkPeerManager: ActorRef,
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]
  ): Behavior[BroadcasterMsg] =
    Behaviors.receiveMessage {
      case ScanPeers =>
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, handshakedPeersAdapter.toClassic)
        Behaviors.same

      case WrappedHandshakedPeers(peers) =>
        peerListHelper.handleHandshakedPeers(peers)
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
    }

}

// Logger name anchor — never instantiated
final private class BlockBroadcasterImpl
