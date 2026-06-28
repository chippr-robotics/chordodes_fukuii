package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer

/** Carrier for the `PeerWithInfo` data class shared across the (now fully Typed) sync actors.
  *
  * The former self-typed Classic trait (`self: Actor with ActorLogging`) that held the peer-list management behaviour
  * was removed in CAPSTONE Phase 2d: every actor that once mixed it in is now Typed and composes [[PeerListHelper]]
  * instead. Only the `PeerWithInfo` data class survives, still referenced widely.
  */
object PeerListSupportNg:
  final case class PeerWithInfo(peer: Peer, peerInfo: PeerInfo)
