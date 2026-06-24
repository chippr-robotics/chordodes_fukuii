package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector.Result
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector.SelectPivotBlock
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

class PivotBlockSelectorSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("explicit-scheduler"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfter {

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  "FastSyncPivotBlockSelector" should "download pivot block from peers" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer2.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer3.id)
    )

    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    fastSync.expectMsg(Result(pivotBlockHeader))
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]
  }

  it should "ask for the block number 0 if [bestPeerBestBlockNumber < syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val highestNumber: Int = syncConfig.pivotBlockOffset - 1

    updateHandshakedPeers(
      HandshakedPeers(
        threeAcceptedPeers
          .updated(peer1, threeAcceptedPeers(peer1).copy(maxBlockNumber = highestNumber))
          .updated(peer2, threeAcceptedPeers(peer2).copy(maxBlockNumber = highestNumber / 2))
          .updated(peer3, threeAcceptedPeers(peer3).copy(maxBlockNumber = highestNumber / 5))
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), blockNumber = 0)
  }

  it should "skip peers whose maxBlockNumber is still 0 (probe reply not yet arrived)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // All three peers have forkAccepted=true but maxBlockNumber=0 — i.e., they
    // handshaked but their Bug 32 best-block probe hasn't been answered yet.
    // The selector must NOT pick a pivot from these peers; otherwise it asks for
    // block 0 (genesis) and loops forever. Mirrors the SNAP-side
    // `peer.maxBlockNumber > 0` filter.
    updateHandshakedPeers(
      HandshakedPeers(
        threeAcceptedPeers
          .updated(peer1, threeAcceptedPeers(peer1).copy(maxBlockNumber = 0))
          .updated(peer2, threeAcceptedPeers(peer2).copy(maxBlockNumber = 0))
          .updated(peer3, threeAcceptedPeers(peer3).copy(maxBlockNumber = 0))
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    // No subscriptions, no GetBlockHeaders, no fastSync ! Result — selector parks.
    peerMessageBus.expectNoMessage()
    networkPeerManager.expectNoMessage()
    fastSync.expectNoMessage()
  }

  it should "retry if there are no enough peers" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(singlePeer))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectNoMessage()

    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
  }

  it should "retry if there are no enough votes for one block" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer2.id)
    )

    // one peer return different header
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(differentBlockHeader)), peer3.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
  }

  it should "find out that there are no enough votes as soon as possible" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )

    // One peer return different header. Because pivotBlockSelector waits only for one peer more - consensus won't be reached
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(differentBlockHeader)), peer2.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
  }

  it should "handle case when one peer responded with wrong block header" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    override def minPeersToChoosePivotBlock: Int = 1

    updateHandshakedPeers(HandshakedPeers(singlePeer))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1), expectedPivotBlock)

    // peer responds with block header number
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(
        BlockHeaders(BigInt(0), Seq(pivotBlockHeader.copy(number = expectedPivotBlock + 1))),
        peer1.id
      )
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )
    testScheduler.timePasses(syncConfig.syncRetryInterval)

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated
    peerMessageBus.expectNoMessage()
  }

  it should "not ask additional peers if not needed" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer2.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer3.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(pivotBlockHeader))
  }

  it should "ask additional peers if needed" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(differentBlockHeader)), peer2.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(anotherDifferentBlockHeader)), peer3.id)
    )

    expectUnsubscribeCmdsWithNextSubscribe(
      unsubClassifiers = Seq(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
      ),
      nextSub = MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    expectGetBlockHeadersRequests(Seq(peer4), expectedPivotBlock)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer4.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(pivotBlockHeader))
  }

  it should "restart whole process after checking additional nodes" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(differentBlockHeader)), peer2.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(anotherDifferentBlockHeader)), peer3.id)
    )

    expectUnsubscribeCmdsWithNextSubscribe(
      unsubClassifiers = Seq(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
      ),
      nextSub = MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    expectGetBlockHeadersRequests(Seq(peer4), expectedPivotBlock)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(nextAnotherDifferentBlockHeader)), peer4.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()
  }

  it should "check only peers with the highest block at least equal to [bestPeerBestBlockNumber - syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    updateHandshakedPeers(
      HandshakedPeers(allPeers.updated(peer1, allPeers(peer1).copy(maxBlockNumber = expectedPivotBlock - 1)))
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )
    peerMessageBus.expectNoMessage() // Peer 1 will be skipped
  }

  it should "only use only peers from the correct network to choose pivot block" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup() {
    updateHandshakedPeers(HandshakedPeers(peersFromDifferentNetworks))

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      // Peer 2 is skipped
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer3, peer4), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer3.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer4.id)
    )

    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    fastSync.expectMsg(Result(pivotBlockHeader))
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]
  }

  it should "retry pivot block election with fallback to lower peer numbers" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {

    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(
      HandshakedPeers(
        allPeers
          .updated(peer1, allPeers(peer1).copy(maxBlockNumber = 2000))
          .updated(peer2, allPeers(peer2).copy(maxBlockNumber = 800))
          .updated(peer3, allPeers(peer3).copy(maxBlockNumber = 900))
          .updated(peer4, allPeers(peer4).copy(maxBlockNumber = 1400))
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer3, peer4), blockNumber = 900)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = 900))), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = 900))), peer3.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = 900))), peer4.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(baseBlockHeader.copy(number = 900)))
  }

  // ETH69 G1 — pivot TD consensus gate. The selector must exclude peers whose advertised chainWeight
  // is below 80% of our local best TD, defeating the low-difficulty-fork sybil attack on snap-sync pivot.

  it should "exclude a peer whose chainWeight is below 80% of our local best TD" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // ourBestTD = 100 => minPeerTD = 80. peer1/2/3 advertise TD = 100 (pass); peer4 advertises TD = 20 (fail).
    ourBestTD = BigInt(100)

    updateHandshakedPeers(
      HandshakedPeers(
        Map(
          peer1 -> peerInfoWithTD(peer1Status, td = 100),
          peer2 -> peerInfoWithTD(peer2Status, td = 100),
          peer3 -> peerInfoWithTD(peer3Status, td = 100),
          peer4 -> peerInfoWithTD(peer4Status, td = 20)
        )
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    // Only the three TD-passing peers are subscribed/asked; the low-TD peer4 is gated out.
    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()
  }

  it should "include peers whose chainWeight is at or above 80% of our local best TD" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // ourBestTD = 100 => minPeerTD = 80. All three peers advertise exactly 80 (boundary, inclusive) and pass.
    ourBestTD = BigInt(100)

    updateHandshakedPeers(
      HandshakedPeers(
        Map(
          peer1 -> peerInfoWithTD(peer1Status, td = 80),
          peer2 -> peerInfoWithTD(peer2Status, td = 80),
          peer3 -> peerInfoWithTD(peer3Status, td = 80)
        )
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
  }

  it should "elect the honest peer over K low-TD sybil peers" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // Sybil scenario: 3 low-TD sybils (TD = 1) + 1 honest peer (TD = 100). With minPeersToChoosePivotBlock = 1,
    // the honest peer alone clears the TD gate and wins the pivot election; the sybils are excluded entirely.
    override def minPeersToChoosePivotBlock = 1
    override def peersToChoosePivotBlockMargin = 0

    ourBestTD = BigInt(100) // minPeerTD = 80; sybils at TD = 1 are gated out, honest peer1 at TD = 100 passes.

    updateHandshakedPeers(
      HandshakedPeers(
        Map(
          peer1 -> peerInfoWithTD(peer1Status, td = 100),
          peer2 -> peerInfoWithTD(peer2Status, td = 1),
          peer3 -> peerInfoWithTD(peer3Status, td = 1),
          peer4 -> peerInfoWithTD(peer4Status, td = 1)
        )
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    // Only the honest peer is subscribed/asked — no sybil is contacted.
    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )
    expectGetBlockHeadersRequests(Seq(peer1), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // The honest peer's header is elected as pivot.
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivotBlockHeader)), peer1.id)
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )
    fastSync.expectMsg(Result(pivotBlockHeader))
  }

  it should "fall back to block-number ranking when no peer passes the TD gate (liveness)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // ourBestTD = 1000 => minPeerTD = 800, but every peer advertises TD = 20 (all below threshold).
    // The gate finds no qualifying peer and must fall back to block-number-only ranking rather than
    // blocking sync — all three peers are then asked.
    ourBestTD = BigInt(1000)

    updateHandshakedPeers(
      HandshakedPeers(
        Map(
          peer1 -> peerInfoWithTD(peer1Status, td = 20),
          peer2 -> peerInfoWithTD(peer2Status, td = 20),
          peer3 -> peerInfoWithTD(peer3Status, td = 20)
        )
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
  }

  class TestSetup extends TestSyncConfig {

    val blacklist: Blacklist = CacheBasedBlacklist.empty(100)

    private def isNewBlock(msg: Message): Boolean = msg match {
      case _: NewBlock => true
      case _           => false
    }

    def expectGetBlockHeadersRequests(peers: Seq[Peer], blockNumber: BigInt): Unit = {
      val expectedPeerIds = peers.map(_.id)
      val receivedMessages =
        (1 to expectedPeerIds.size).map(_ => networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage])

      expectedPeerIds.foreach { peerId =>
        val sendMsg = receivedMessages
          .find(_.peerId == peerId)
          .getOrElse(
            fail(s"Expected GetBlockHeaders request for peer $peerId, but received ${receivedMessages.map(_.peerId)}")
          )
        assertGetBlockHeaders(sendMsg.message.underlyingMsg, blockNumber)
      }

      val unexpectedPeers = receivedMessages.map(_.peerId).filterNot(expectedPeerIds.contains)
      withClue(s"Unexpected GetBlockHeaders requests for peers: $unexpectedPeers") {
        unexpectedPeers shouldBe empty
      }
    }

    private def assertGetBlockHeaders(msg: Message, expectedBlockNumber: BigInt): Unit = msg match {
      case GetBlockHeaders(_, Left(number), maxHeaders, skip, reverse) =>
        number shouldBe expectedBlockNumber
        maxHeaders shouldBe 1
        skip shouldBe 0
        reverse shouldBe false
      case other =>
        fail(s"Expected GetBlockHeaders for block $expectedBlockNumber but received $other")
    }

    // Assertion helpers: subscriber ref is an internal adapter ref — matched with wildcard.

    def expectSubscribeCmds(classifiers: SubscriptionClassifier*): Unit = {
      val msgs = peerMessageBus.receiveN(classifiers.size)
      val got = msgs.map {
        case SubscribeCmd(c, _) => c
        case other              => fail(s"Expected SubscribeCmd but got: $other")
      }
      got.toSet shouldEqual classifiers.toSet
    }

    def expectUnsubscribeCmds(classifiers: SubscriptionClassifier*): Unit = {
      val msgs = peerMessageBus.receiveN(classifiers.size)
      val got = msgs.map {
        case UnsubscribeCmd(c, _) => c
        case other                => fail(s"Expected UnsubscribeCmd but got: $other")
      }
      got.toSet shouldEqual classifiers.toSet
    }

    // Receives n UnsubscribeCmd + 1 UnsubscribeAllCmd in any order.
    def expectUnsubscribeCmdsWithAll(classifiers: SubscriptionClassifier*): Unit = {
      val msgs = peerMessageBus.receiveN(classifiers.size + 1)
      val unsubCmds = msgs.collect { case UnsubscribeCmd(c, _) => c }
      val unsubAllCmds = msgs.collect { case _: UnsubscribeAllCmd => () }
      unsubCmds.toSet shouldEqual classifiers.toSet
      unsubAllCmds.size shouldEqual 1
    }

    // Receives n UnsubscribeCmd + 1 SubscribeCmd in any order (ask-additional-peers pattern).
    def expectUnsubscribeCmdsWithNextSubscribe(
        unsubClassifiers: Seq[SubscriptionClassifier],
        nextSub: SubscriptionClassifier
    ): Unit = {
      val msgs = peerMessageBus.receiveN(unsubClassifiers.size + 1)
      val unsubCmds = msgs.collect { case UnsubscribeCmd(c, _) => c }
      val subCmds = msgs.collect { case SubscribeCmd(c, _) => c }
      unsubCmds.toSet shouldEqual unsubClassifiers.toSet
      subCmds.toSet shouldEqual Set(nextSub)
    }

    val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.ignoreMsg {
      case NetworkPeerManagerActor.SendMessage(msg, _) if isNewBlock(msg.underlyingMsg) => true
      case _: NetworkPeerManagerActor.GetHandshakedPeersCmd                             => true
    }

    val peerMessageBus: TestProbe = TestProbe()
    peerMessageBus.ignoreMsg {
      case SubscribeCmd(MessageClassifier(codes, PeerSelector.AllPeers), _)
          if codes == Set(Codes.NewBlockCode, Codes.NewBlockHashesCode) =>
        true
      case SubscribeCmd(PeerDisconnectedClassifier(_), _)   => true
      case UnsubscribeCmd(PeerDisconnectedClassifier(_), _) => true
    }

    def minPeersToChoosePivotBlock = 3
    def peersToChoosePivotBlockMargin = 1

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doFastSync = true,
      branchResolutionRequestSize = 30,
      checkForNewBlockInterval = 1.second,
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      minPeersToChoosePivotBlock = minPeersToChoosePivotBlock,
      peersToChoosePivotBlockMargin = peersToChoosePivotBlockMargin,
      peersScanInterval = 500.milliseconds,
      peerResponseTimeout = 2.seconds,
      redownloadMissingStateNodes = false,
      fastSyncBlockValidationX = 10,
      blacklistDuration = 1.second
    )

    val fastSync: TestProbe = TestProbe()

    def testScheduler: ExplicitlyTriggeredScheduler =
      classicSystem.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    // Local best total difficulty supplied to the pivot TD gate (ETH69 G1). Defaults to 0 so the gate is
    // inert for existing tests (minPeerTD = 0); TD-gate tests override this before spawning the selector.
    @volatile var ourBestTD: BigInt = BigInt(0)

    lazy val pivotBlockSelector: ActorRef = testKit
      .spawn(
        PivotBlockSelector(
          networkPeerManager.ref,
          peerMessageBus.ref,
          defaultSyncConfig,
          fastSync.ref,
          blacklist,
          () => ourBestTD
        ),
        s"pivot-block-selector-${java.util.UUID.randomUUID()}"
      )
      .toClassic

    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    val bestBlock = 400000
    // Ask for pivot block header (the best block from the best peer - offset)
    val expectedPivotBlock: Int = bestBlock - syncConfig.pivotBlockOffset

    val pivotBlockHeader: BlockHeader = baseBlockHeader.copy(number = expectedPivotBlock)
    val differentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different"))
    val anotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different2"))
    val nextAnotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different3"))

    val peer1TestProbe: TestProbe = TestProbe("peer1")(classicSystem)
    val peer2TestProbe: TestProbe = TestProbe("peer2")(classicSystem)
    val peer3TestProbe: TestProbe = TestProbe("peer3")(classicSystem)
    val peer4TestProbe: TestProbe = TestProbe("peer4")(classicSystem)

    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 0), peer1TestProbe.ref, false)
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.2", 0), peer2TestProbe.ref, false)
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.3", 0), peer3TestProbe.ref, false)
    val peer4: Peer = Peer(PeerId("peer4"), new InetSocketAddress("127.0.0.4", 0), peer4TestProbe.ref, false)

    val peer1Status: RemoteStatus =
      RemoteStatus(
        Capability.ETH68,
        1,
        ChainWeight.totalDifficultyOnly(20),
        ByteString("peer1_bestHash"),
        ByteString("unused")
      )
    val peer2Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer2_bestHash"))
    val peer3Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer3_bestHash"))
    val peer4Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer4_bestHash"))

    val allPeers: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      ),
      peer4 -> PeerInfo(
        peer4Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer4Status.bestHash
      )
    )

    val threeAcceptedPeers: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      )
    )

    val singlePeer: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      )
    )

    val peersFromDifferentNetworks: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = false,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      ),
      peer4 -> PeerInfo(
        peer4Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer4Status.bestHash
      )
    )

    def updateHandshakedPeers(handshakedPeers: HandshakedPeers): Unit =
      pivotBlockSelector ! PivotBlockSelector.WrappedHandshakedPeers(handshakedPeers)

    /** Build a forkAccepted PeerInfo at the standard bestBlock with the given advertised total difficulty. */
    def peerInfoWithTD(status: RemoteStatus, td: BigInt): PeerInfo =
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = ChainWeight.totalDifficultyOnly(td),
        maxBlockNumber = bestBlock,
        bestBlockHash = status.bestHash
      )
  }
}
