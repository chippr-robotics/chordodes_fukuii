package com.chipprbots.ethereum.testing

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId

/** Test utilities for creating mock Peer instances in unit tests */
object PeerTestHelpers:

  /** Create a test peer with a dummy InetSocketAddress
    *
    * @param id
    *   Peer identifier string
    * @param ref
    *   Classic ActorRef for the peer (converted to Typed internally)
    * @return
    *   A properly constructed Peer instance for testing
    */
  def createTestPeer(id: String, ref: ActorRef): Peer =
    Peer(
      id = PeerId(id),
      remoteAddress = new InetSocketAddress("127.0.0.1", 30303),
      ref = ref.toTyped[PeerActor.Command],
      incomingConnection = false
    )
