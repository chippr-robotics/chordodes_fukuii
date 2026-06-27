package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.network.p2p.Message

final case class PeerId(value: String) extends BlacklistId

object PeerId:
  def fromRef(ref: typed.ActorRef[PeerActor.Command]): PeerId = PeerId(ref.path.name)

final case class Peer(
    id: PeerId,
    remoteAddress: InetSocketAddress,
    ref: typed.ActorRef[PeerActor.Command],
    incomingConnection: Boolean,
    source: Source[Message, NotUsed] = Source.empty,
    nodeId: Option[ByteString] = None,
    createTimeMillis: Long = System.currentTimeMillis
)
