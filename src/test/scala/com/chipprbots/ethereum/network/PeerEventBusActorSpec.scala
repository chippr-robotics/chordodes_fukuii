package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.WatchedActorTerminatedException
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.PublishCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.*
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong
import com.chipprbots.ethereum.testing.Tags.*

class PeerEventBusActorSpec
    extends TestKit(ActorSystem("PeerEventBusActorSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with NormalPatience {

  "PeerEventBusActor" should "relay messages received to subscribers" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {

    val probe1: TestProbe = TestProbe()(system)
    val probe2: TestProbe = TestProbe()(system)
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)
    peerEventBusActor ! SubscribeCmd(classifier1, probe1.ref)

    peerEventBusActor ! SubscribeCmd(classifier2, probe2.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe2.expectMsg(msgFromPeer)

    peerEventBusActor ! UnsubscribeCmd(classifier1, probe1.ref)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    probe1.expectNoMessage()
    probe2.expectMsg(msgFromPeer2)

  }

  it should "relay messages via streams" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)

    val seqOnTermination: Sink[MessageFromPeer, Future[Seq[MessageFromPeer]]] = Flow[MessageFromPeer]
      .recoverWithRetries(1, { case _: WatchedActorTerminatedException => Source.empty })
      .toMat(Sink.seq)(Keep.right)

    // Subscribe streams directly to the Typed PEA.
    // fromMaterializer runs the callback synchronously during runWith(), so both SubscribeCmds
    // are sent from the test thread before the next line executes.
    val stream1: Future[Seq[MessageFromPeer]] =
      PeerEventBusActor.messageSource(peerEventBusActor, classifier1).runWith(seqOnTermination)
    val stream2: Future[Seq[MessageFromPeer]] =
      PeerEventBusActor.messageSource(peerEventBusActor, classifier2).runWith(seqOnTermination)

    // Sync: subscribe syncProbe after streams (same test thread → same sender ordering).
    // Once syncProbe receives its message, PEA has processed all three subscriptions in order.
    val syncProbe: TestProbe = TestProbe()(system)
    peerEventBusActor ! SubscribeCmd(classifier2, syncProbe.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)
    syncProbe.expectMsg(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    syncProbe.expectMsg(msgFromPeer2)

    // Terminate streams by killing this test's PEA instance (watched via .watch(peerEventBus.toClassic)).
    peerEventBusActor.toClassic ! PoisonPill

    val res1: Seq[MessageFromPeer] = Await.result(stream1, 5.seconds)
    res1 shouldEqual Seq(msgFromPeer)

    val res2: Seq[MessageFromPeer] = Await.result(stream2, 5.seconds)
    res2 shouldEqual Seq(msgFromPeer, msgFromPeer2)
  }

  it should "only relay matching message codes" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    peerEventBusActor ! SubscribeCmd(classifier1, probe1.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    probe1.expectNoMessage()
  }

  it should "relay peers disconnecting to its subscribers" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("1"))), probe1.ref)
    peerEventBusActor ! SubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))), probe1.ref)
    peerEventBusActor ! SubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))), probe2.ref)

    val msgPeerDisconnected: PeerDisconnected = PeerDisconnected(PeerId("2"))
    peerEventBusActor ! PublishCmd(msgPeerDisconnected)

    probe1.expectMsg(msgPeerDisconnected)
    probe2.expectMsg(msgPeerDisconnected)

    peerEventBusActor ! UnsubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))), probe1.ref)

    peerEventBusActor ! PublishCmd(msgPeerDisconnected)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerDisconnected)
  }

  it should "relay peers handshaked to its subscribers" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(PeerHandshaked, probe1.ref)
    peerEventBusActor ! SubscribeCmd(PeerHandshaked, probe2.ref)

    val peerHandshaked =
      new Peer(
        PeerId("peer1"),
        new InetSocketAddress("127.0.0.1", 0),
        TestProbe().ref,
        false,
        nodeId = Some(ByteString())
      )
    val msgPeerHandshaked: PeerHandshakeSuccessful[PeerInfo] = PeerHandshakeSuccessful(peerHandshaked, initialPeerInfo)
    peerEventBusActor ! PublishCmd(msgPeerHandshaked)

    probe1.expectMsg(msgPeerHandshaked)
    probe2.expectMsg(msgPeerHandshaked)

    peerEventBusActor ! UnsubscribeCmd(PeerHandshaked, probe1.ref)

    peerEventBusActor ! PublishCmd(msgPeerHandshaked)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerHandshaked)
  }

  it should "relay a single notification when subscribed twice to the same message code" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()
  }

  it should "allow to handle subscriptions using AllPeers and WithId PeerSelector at the same time" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.AllPeers),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    // Receive a single notification
    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("2"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    // Receive based on AllPeers subscription
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor ! UnsubscribeCmd(MessageClassifier(Set(Ping.code), PeerSelector.AllPeers), probe1.ref)
    peerEventBusActor ! PublishCmd(msgFromPeer)

    // Still received after unsubscribing from AllPeers
    probe1.expectMsg(msgFromPeer)
  }

  it should "allow to subscribe to new messages" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
  }

  it should "not change subscriptions when subscribing to empty set" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
  }

  it should "allow to unsubscribe from messages" taggedAs (UnitTest, NetworkTest) in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref
    )

    val msgFromPeer1: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor ! UnsubscribeCmd(MessageClassifier(Set(Pong.code), PeerSelector.WithId(PeerId("1"))), probe1.ref)

    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectNoMessage()

    peerEventBusActor ! UnsubscribeAllCmd(probe1.ref)

    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectNoMessage()
  }

  trait TestSetup {
    val peerEventBusActor: typed.ActorRef[PeerEventBusActor.Command] =
      system.spawn(PeerEventBusActor.behavior(), s"pea-${java.util.UUID.randomUUID()}")

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )

  }

}
