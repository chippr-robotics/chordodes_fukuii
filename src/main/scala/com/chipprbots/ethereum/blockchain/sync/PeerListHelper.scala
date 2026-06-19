package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.util.encoders.Hex
import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.PeerId

/** Typed-compatible replacement for the responsibilities of the Classic, self-typed `PeerListSupportNg` trait.
  *
  * Pekko Typed migration (Group PLN): a stateful plain class (no self-type, no `Actor` dependency) that an enclosing
  * Typed actor composes. It owns the handshaked-peer map, the `PeerEventBus` subscribe/unsubscribe lifecycle, blacklist
  * filtering, and the `PeerRateTracker`.
  *
  * The enclosing Typed actor is responsible for:
  *   - the periodic `GetHandshakedPeers` poll (it owns the timer and sends to `networkPeerManager` with a
  *     `HandshakedPeers` message adapter as the reply target), and
  *   - bridging `PeerDisconnected` PeerEventBus events into its Command ADT via a `context.messageAdapter`.
  *
  * `peerEventBus` stays Classic for now — `PeerEventBusActor.Subscribe` registers the `sender()` as the subscriber, so
  * we pass `peerDisconnectedAdapter.toClassic` as the subscriber (the Classic ref that delivers into the Typed actor).
  * The whole PeerEventBus subsystem migrates with Group NET.
  */
class PeerListHelper(
    peerEventBus: ActorRef,
    blacklist: Blacklist,
    peerDisconnectedAdapter: TypedActorRef[PeerDisconnected],
    log: Logger
) {

  private val bigIntReverseOrdering: Ordering[BigInt] = Ordering[BigInt].reverse

  private var peers: Map[PeerId, PeerWithInfo] = Map.empty

  private val ethRateTracker: PeerRateTracker = new PeerRateTracker()

  /** Read-only accessor for the current handshaked peers (needed by `FastSyncBranchResolverActor`). */
  def handshakedPeers: Map[PeerId, PeerWithInfo] = peers

  def globalMedianRttMs: Long = ethRateTracker.currentMedianRTT

  /** Subscribers may override to exempt maintained peers from blacklisting (Besu alignment). Empty by default. */
  protected def maintainedNodeIdHexes: Set[String] = Set.empty

  /** Handle `NetworkPeerManagerActor.HandshakedPeers` — refresh the peer map and tune the rate tracker. */
  def handleHandshakedPeers(handshaked: Map[Peer, PeerInfo]): Unit = {
    updatePeers(handshaked)
    ethRateTracker.tune()
  }

  /** Handle `PeerDisconnected` — drop the peer and unsubscribe from its disconnect events. */
  def handlePeerDisconnected(peerId: PeerId): Unit = removePeerById(peerId)

  def peersToDownloadFrom: Map[PeerId, PeerWithInfo] = {
    val available = peers
      .filter { case (_, p) => p.peerInfo.forkAccepted }
      .filterNot { case (peerId, _) =>
        val isBlacklisted = blacklist.isBlacklisted(peerId)
        if isBlacklisted then {
          log.debug("Peer {} is blacklisted and excluded from download peers", peerId)
        }
        isBlacklisted
      }
    log.debug("peersToDownloadFrom: {} available out of {} handshaked peers", available.size, peers.size)
    available
  }

  def getPeerById(peerId: PeerId): Option[Peer] = peers.get(peerId).map(_.peer)

  def getPeerWithHighestBlock: Option[PeerWithInfo] =
    peersToDownloadFrom.values.toList.sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering).headOption

  def getSnapPeerWithHighestBlock: Option[PeerWithInfo] =
    peersToDownloadFrom.values.toList
      .filter(_.peerInfo.remoteStatus.supportsSnap)
      .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
      .headOption

  def blacklistIfHandshaked(peerId: PeerId, duration: FiniteDuration, reason: BlacklistReason): Unit =
    peers.get(peerId) match {
      case Some(peerWithInfo) =>
        val isMaintained = peerWithInfo.peer.nodeId.exists { nodeId =>
          maintainedNodeIdHexes.contains(Hex.toHexString(nodeId.toArray))
        }
        val skipBlacklist = isMaintained && !reason.isInstanceOf[BlacklistReason.RegularSyncRequestFailed]
        if skipBlacklist then {
          log.debug("Skipping blacklist for maintained peer {} (reason: {})", peerId, reason)
        } else {
          if isMaintained then log.warn("Blacklisting maintained peer {} (will reconnect). Reason: {}", peerId, reason)
          else
            log.debug(
              "Blacklisting peer {} ({}) for {} ms. Reason: {}",
              peerId,
              peerWithInfo.peer.remoteAddress,
              duration.toMillis,
              reason
            )
          blacklist.add(peerId, duration, reason)
        }
      case None =>
        log.debug("Attempted to blacklist non-handshaked peer {}", peerId)
    }

  private def updatePeers(handshaked: Map[Peer, PeerInfo]): Unit = {
    val updated = handshaked.map { case (peer, peerInfo) =>
      (peer.id, PeerWithInfo(peer, peerInfo))
    }

    val newPeers = updated.filterNot(p => peers.keySet.contains(p._1))
    if newPeers.nonEmpty then {
      log.debug("Adding {} new handshaked peers", newPeers.size)
      newPeers.foreach { case (peerId, peerWithInfo) =>
        log.debug(
          "New peer {} ({}) - ready: {}, maxBlock: {}",
          peerId,
          peerWithInfo.peer.remoteAddress,
          peerWithInfo.peerInfo.forkAccepted,
          peerWithInfo.peerInfo.maxBlockNumber
        )
        log.debug("Peer {} chainWeight: {}", peerId, peerWithInfo.peerInfo.chainWeight)
        peerEventBus.tell(
          Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId))),
          peerDisconnectedAdapter.toClassic
        )
      }
    }

    if peers.size != updated.size then {
      log.debug("Handshaked peers changed: {} -> {} peers", peers.size, updated.size)
    }

    val newPeerIds = updated.keySet -- peers.keySet
    val removedPeerIds = peers.keySet -- updated.keySet
    newPeerIds.foreach(id => ethRateTracker.addPeer(id.value))
    removedPeerIds.foreach(id => ethRateTracker.removePeer(id.value))

    peers = updated
    onPeerListUpdated(updated.values)
  }

  /** Called after `handshakedPeers` is refreshed. No-op by default; subclasses may override. */
  protected def onPeerListUpdated(currentPeers: Iterable[PeerWithInfo]): Unit = ()

  private def removePeerById(peerId: PeerId): Unit =
    if peers.keySet.contains(peerId) then {
      val peerInfo = peers(peerId)
      log.debug("Removing disconnected peer {} ({})", peerId, peerInfo.peer.remoteAddress)
      peerEventBus.tell(
        Unsubscribe(Some(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))),
        peerDisconnectedAdapter.toClassic
      )
      ethRateTracker.removePeer(peerId.value)
      blacklist.remove(peerId)
      log.debug("Removed peer {} from blacklist", peerId)
      peers = peers - peerId
      log.debug("Remaining handshaked peers: {}", peers.size)
    } else {
      log.debug("Attempted to remove non-existent peer {}", peerId)
    }

}
