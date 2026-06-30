package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

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
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.BlockHash
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
    with BeforeAndAfter:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  "FastSyncPivotBlockSelector" should "download pivot block from peers" taggedAs (UnitTest, SyncTest) in new TestSetup:
    // ETH69 G5 — the elected pivot's backlink probe must find a canonical match; resolve the pivot at its height.
    canonicalByNumber = canonicalReturningPivot
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

    // ETH69 G5 — backlink probe + confirmation before the pivot is handed to FastSync.
    confirmBacklink(pivotBlockHeader, Seq(peer1, peer2, peer3), peer1)

    fastSyncResult.expectMessage(Result(pivotBlockHeader))
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

  it should "ask for the block number 0 if [bestPeerBestBlockNumber < syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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

  it should "skip peers whose maxBlockNumber is still 0 (probe reply not yet arrived)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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
    fastSyncResult.expectNoMessage()

  it should "retry if there are no enough peers" taggedAs (UnitTest, SyncTest) in new TestSetup:
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

  it should "retry if there are no enough votes for one block" taggedAs (UnitTest, SyncTest) in new TestSetup:
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

    fastSyncResult.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

  it should "find out that there are no enough votes as soon as possible" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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

    fastSyncResult.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

  it should "handle case when one peer responded with wrong block header" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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
        BlockHeaders(BigInt(0), Seq(pivotBlockHeader.copy(number = BlockNumber(expectedPivotBlock + 1)))),
        peer1.id
      )
    )

    expectUnsubscribeCmdsWithAll(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )
    testScheduler.timePasses(syncConfig.syncRetryInterval)

    fastSyncResult.expectNoMessage() // consensus not reached - process have to be repeated
    peerMessageBus.expectNoMessage()

  it should "not ask additional peers if not needed" taggedAs (UnitTest, SyncTest) in new TestSetup:
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    canonicalByNumber = canonicalReturningPivot
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

    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

    // ETH69 G5 — backlink probe + confirmation.
    confirmBacklink(pivotBlockHeader, Seq(peer1, peer2, peer3), peer1)
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    fastSyncResult.expectMessage(Result(pivotBlockHeader))

  it should "ask additional peers if needed" taggedAs (UnitTest, SyncTest) in new TestSetup:
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    canonicalByNumber = canonicalReturningPivot
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

    // peer4's vote completes the election (peer1 + peer4 backed the pivot); unsubscribe peer4's voting stream.
    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    // ETH69 G5 — the two peers that voted for the pivot (peer1, peer4) are probed for its backlink.
    confirmBacklink(pivotBlockHeader, Seq(peer1, peer4), peer1)
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    fastSyncResult.expectMessage(Result(pivotBlockHeader))

  it should "restart whole process after checking additional nodes" taggedAs (UnitTest, SyncTest) in new TestSetup:
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

    fastSyncResult.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    peerMessageBus.expectNoMessage()

  it should "check only peers with the highest block at least equal to [bestPeerBestBlockNumber - syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    updateHandshakedPeers(
      HandshakedPeers(allPeers.updated(peer1, allPeers(peer1).copy(maxBlockNumber = BigInt(expectedPivotBlock - 1))))
    )

    pivotBlockSelector ! SelectPivotBlock

    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )
    peerMessageBus.expectNoMessage() // Peer 1 will be skipped

  it should "only use only peers from the correct network to choose pivot block" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    canonicalByNumber = canonicalReturningPivot
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

    // ETH69 G5 — backlink probe across the three voting peers + confirmation.
    confirmBacklink(pivotBlockHeader, Seq(peer1, peer3, peer4), peer1)

    fastSyncResult.expectMessage(Result(pivotBlockHeader))
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

  it should "retry pivot block election with fallback to lower peer numbers" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:

    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    // ETH69 G5 — elected pivot is block 900; resolve it canonically for the backlink match.
    val pivot900: BlockHeader = baseBlockHeader.copy(number = BlockNumber(900))
    canonicalByNumber = n => if n == BigInt(900) then Some(pivot900) else None

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
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = BlockNumber(900)))), peer1.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = BlockNumber(900)))), peer3.id)
    )
    pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(baseBlockHeader.copy(number = BlockNumber(900)))), peer4.id)
    )

    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
    )

    // ETH69 G5 — backlink probe across the three voting peers + confirmation.
    confirmBacklink(pivot900, Seq(peer1, peer3, peer4), peer1)
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    fastSyncResult.expectMessage(Result(pivot900))

  // ETH69 G1 — pivot TD consensus gate. The selector must exclude peers whose advertised chainWeight
  // is below 80% of our local best TD, defeating the low-difficulty-fork sybil attack on snap-sync pivot.

  it should "exclude a peer whose chainWeight is below 80% of our local best TD" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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

  it should "include peers whose chainWeight is at or above 80% of our local best TD" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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

  it should "elect the honest peer over K low-TD sybil peers" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // Sybil scenario: 3 low-TD sybils (TD = 1) + 1 honest peer (TD = 100). With minPeersToChoosePivotBlock = 1,
    // the honest peer alone clears the TD gate and wins the pivot election; the sybils are excluded entirely.
    override def minPeersToChoosePivotBlock = 1
    override def peersToChoosePivotBlockMargin = 0

    ourBestTD = BigInt(100) // minPeerTD = 80; sybils at TD = 1 are gated out, honest peer1 at TD = 100 passes.
    canonicalByNumber = canonicalReturningPivot

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

    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))
    )

    // ETH69 G5 — the honest peer is probed for the pivot backlink and confirms it.
    confirmBacklink(pivotBlockHeader, Seq(peer1), peer1)
    fastSyncResult.expectMessage(Result(pivotBlockHeader))

  it should "fall back to block-number ranking when no peer passes the TD gate (liveness)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
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

  // ── ETH69 G5 — pivot parent-chain backlink validation ───────────────────────────────────────────────────

  /** Drive a three-peer election to a unanimous vote for `pivot` and return after the per-vote unsubscribes, leaving
    * the selector in the backlink-probe state. Shared setup for the G5 scenario tests below. The voted header must sit
    * at `expectedPivotBlock` so the election accepts it.
    */
  private def electUnanimousPivot(setup: TestSetup, pivot: BlockHeader): Unit =
    import setup.*
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))
    pivotBlockSelector ! SelectPivotBlock
    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )
    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    Seq(peer1, peer2, peer3).foreach { p =>
      pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
        MessageFromPeer(BlockHeaders(BigInt(0), Seq(pivot)), p.id)
      )
    }
    expectUnsubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

  it should "proceed when the pivot's canonical parent is within 5 hops" taggedAs (UnitTest, SyncTest) in
    new TestSetup:
      // Backlink chain [pivot, pivot-1, ..., pivot-4]; only pivot-4 matches our canonical chain.
      val chain: Seq[BlockHeader] = reverseChain(expectedPivotBlock, depth = 5)
      val pivot: BlockHeader = chain.head
      val anchor: BlockHeader = chain.last // pivot-4
      canonicalByNumber = n => if n == anchor.number.value then Some(anchor) else None

      electUnanimousPivot(this, pivot)
      expectBacklinkProbe(pivot, Seq(peer1, peer2, peer3))
      feedBacklink(chain, peer1)

      fastSyncResult.expectMessage(Result(pivot))
      peerMessageBus.expectMsgType[UnsubscribeAllCmd]

  it should "proceed when the canonical parent is at exactly hop N (BacklinkDepth)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val depth: Int = PivotBlockSelector.BacklinkDepth
    val chain: Seq[BlockHeader] = reverseChain(expectedPivotBlock, depth = depth)
    val pivot: BlockHeader = chain.head
    val anchor: BlockHeader = chain.last // pivot - (N-1), the deepest returned header
    canonicalByNumber = n => if n == anchor.number.value then Some(anchor) else None

    electUnanimousPivot(this, pivot)
    expectBacklinkProbe(pivot, Seq(peer1, peer2, peer3))
    feedBacklink(chain, peer1)

    fastSyncResult.expectMessage(Result(pivot))
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

  it should "reject the pivot and retry when no canonical parent is found within N hops" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // Full-depth chain, but NONE of the returned headers is canonical → backlink fails → retry.
    val chain: Seq[BlockHeader] = reverseChain(expectedPivotBlock, depth = PivotBlockSelector.BacklinkDepth)
    val pivot: BlockHeader = chain.head
    canonicalByNumber = _ => None

    electUnanimousPivot(this, pivot)
    expectBacklinkProbe(pivot, Seq(peer1, peer2, peer3))
    feedBacklink(chain, peer1)

    // No pivot handed to FastSync; the selector schedules a retry instead.
    fastSyncResult.expectNoMessage()
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    testScheduler.timePasses(syncConfig.startRetryInterval)

    // Retry re-runs the election from scratch (fresh subscribe round).
    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

  it should "reject the pivot immediately and blacklist the peer when a backlink header fails PoW" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val chain: Seq[BlockHeader] = reverseChain(expectedPivotBlock, depth = 5)
    val pivot: BlockHeader = chain.head
    val anchor: BlockHeader = chain.last
    // Even though a canonical match exists, forged PoW must reject the chain before the canonical check.
    canonicalByNumber = n => if n == anchor.number.value then Some(anchor) else None
    // The third returned header (pivot-2) has invalid PoW.
    val forged: BlockHeader = chain(2)
    validateHeaderPoWFn = h => h.hash != forged.hash

    electUnanimousPivot(this, pivot)
    expectBacklinkProbe(pivot, Seq(peer1, peer2, peer3))
    feedBacklink(chain, peer1)

    // The forged-PoW chain is rejected immediately: no pivot reaches FastSync.
    fastSyncResult.expectNoMessage()
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    // peer1 served a forged-PoW backlink and must be blacklisted (distinguishing malicious from honest-divergent).
    blacklist.isBlacklisted(peer1.id) shouldBe true

  it should "retry when the backlink probe times out with no voter response" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    canonicalByNumber = canonicalReturningPivot

    electUnanimousPivot(this, pivotBlockHeader)
    expectBacklinkProbe(pivotBlockHeader, Seq(peer1, peer2, peer3))

    // No backlink response arrives; the probe timeout fires (peerResponseTimeout).
    testScheduler.timePasses(syncConfig.peerResponseTimeout)

    fastSyncResult.expectNoMessage()
    peerMessageBus.expectMsgType[UnsubscribeAllCmd]

    testScheduler.timePasses(syncConfig.startRetryInterval)
    expectSubscribeCmds(
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id)),
      MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))
    )

  class TestSetup extends TestSyncConfig:

    val blacklist: Blacklist = CacheBasedBlacklist.empty(100)

    private def isNewBlock(msg: Message): Boolean = msg match
      case _: NewBlock => true
      case _           => false

    def expectGetBlockHeadersRequests(peers: Seq[Peer], blockNumber: BigInt): Unit =
      val expectedPeerIds = peers.map(_.id)
      val receivedMessages =
        (1 to expectedPeerIds.size).map(_ => networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd])

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

    private def assertGetBlockHeaders(msg: Message, expectedBlockNumber: BigInt): Unit = msg match
      case GetBlockHeaders(_, Left(number), maxHeaders, skip, reverse) =>
        number shouldBe expectedBlockNumber
        maxHeaders shouldBe 1
        skip shouldBe 0
        reverse shouldBe false
      case other =>
        fail(s"Expected GetBlockHeaders for block $expectedBlockNumber but received $other")

    // ── ETH69 G5 — backlink probe helpers ─────────────────────────────────────────────────────────────────

    /** After a pivot wins the vote, the selector re-subscribes to the winning voters and sends each a reverse
      * `GetBlockHeaders(Right(pivotHash), count=BacklinkDepth, reverse=true)`. Consume those subscriptions and
      * requests, asserting the reverse-by-hash shape, and return the peer ids that were probed.
      */
    def expectBacklinkProbe(pivot: BlockHeader, expectedPeers: Seq[Peer]): Unit =
      expectSubscribeCmds(
        expectedPeers.map(p => MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(p.id)))*
      )
      val sends =
        (1 to expectedPeers.size).map(_ => networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd])
      sends.foreach { s =>
        s.message.underlyingMsg match
          case GetBlockHeaders(_, Right(hash), maxHeaders, skip, reverse) =>
            hash shouldBe pivot.hash.value
            maxHeaders shouldBe PivotBlockSelector.BacklinkDepth
            skip shouldBe 0
            reverse shouldBe true
          case other => fail(s"Expected reverse GetBlockHeaders(Right(pivotHash)) but received $other")
      }
      val unexpected = sends.map(_.peerId).filterNot(expectedPeers.map(_.id).contains)
      withClue(s"Unexpected backlink probe peers: $unexpected")(unexpected shouldBe empty)

    /** Deliver a backlink header chain from one of the probed peers. */
    def feedBacklink(chain: Seq[BlockHeader], from: Peer): Unit =
      pivotBlockSelector ! PivotBlockSelector.WrappedMessageFromPeer(
        MessageFromPeer(BlockHeaders(BigInt(0), chain), from.id)
      )

    /** The common happy-path backlink: canonical lookup resolves the pivot at its own height, the probed peer returns
      * the single-header chain rooted at the pivot, and the selector confirms + emits Result. Call BEFORE spawning is
      * forced (sets `canonicalByNumber`), then drives the probe handshake after votes.
      */
    def confirmBacklink(pivot: BlockHeader, probedPeers: Seq[Peer], responder: Peer): Unit =
      expectBacklinkProbe(pivot, probedPeers)
      feedBacklink(Seq(pivot), responder)

    // Assertion helpers: subscriber ref is an internal adapter ref — matched with wildcard.

    def expectSubscribeCmds(classifiers: SubscriptionClassifier*): Unit =
      val msgs = peerMessageBus.receiveN(classifiers.size)
      val got = msgs.map {
        case SubscribeCmd(c, _) => c
        case other              => fail(s"Expected SubscribeCmd but got: $other")
      }
      got.toSet shouldEqual classifiers.toSet

    def expectUnsubscribeCmds(classifiers: SubscriptionClassifier*): Unit =
      val msgs = peerMessageBus.receiveN(classifiers.size)
      val got = msgs.map {
        case UnsubscribeCmd(c, _) => c
        case other                => fail(s"Expected UnsubscribeCmd but got: $other")
      }
      got.toSet shouldEqual classifiers.toSet

    // Receives n UnsubscribeCmd + 1 UnsubscribeAllCmd in any order.
    def expectUnsubscribeCmdsWithAll(classifiers: SubscriptionClassifier*): Unit =
      val msgs = peerMessageBus.receiveN(classifiers.size + 1)
      val unsubCmds = msgs.collect { case UnsubscribeCmd(c, _) => c }
      val unsubAllCmds = msgs.collect { case _: UnsubscribeAllCmd => () }
      unsubCmds.toSet shouldEqual classifiers.toSet
      unsubAllCmds.size shouldEqual 1

    // Receives n UnsubscribeCmd + 1 SubscribeCmd in any order (ask-additional-peers pattern).
    def expectUnsubscribeCmdsWithNextSubscribe(
        unsubClassifiers: Seq[SubscriptionClassifier],
        nextSub: SubscriptionClassifier
    ): Unit =
      val msgs = peerMessageBus.receiveN(unsubClassifiers.size + 1)
      val unsubCmds = msgs.collect { case UnsubscribeCmd(c, _) => c }
      val subCmds = msgs.collect { case SubscribeCmd(c, _) => c }
      unsubCmds.toSet shouldEqual unsubClassifiers.toSet
      subCmds.toSet shouldEqual Set(nextSub)

    val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.ignoreMsg {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) if isNewBlock(msg.underlyingMsg) => true
      case _: NetworkPeerManagerActor.GetHandshakedPeersCmd                                => true
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

    val fastSyncResult = testKit.createTestProbe[PivotBlockSelector.Result]()
    val fastSyncFailed = testKit.createTestProbe[PivotBlockSelector.SelectionFailed.type]()

    def testScheduler: ExplicitlyTriggeredScheduler =
      classicSystem.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    // Local best total difficulty supplied to the pivot TD gate (ETH69 G1). Defaults to 0 so the gate is
    // inert for existing tests (minPeerTD = 0); TD-gate tests override this before spawning the selector.
    @volatile var ourBestTD: BigInt = BigInt(0)

    // ETH69 G5 — pivot parent-chain backlink closures. Defaults make the backlink probe pass: every header
    // validates PoW, and the canonical lookup returns the elected pivot itself at its own height (so the
    // first probed header is an immediate canonical match). Backlink tests override these before spawning.
    @volatile var validateHeaderPoWFn: BlockHeader => Boolean = _ => true
    @volatile var canonicalByNumber: BigInt => Option[BlockHeader] = _ => None

    lazy val pivotBlockSelector = testKit
      .spawn(
        PivotBlockSelector(
          networkPeerManager.ref,
          peerMessageBus.ref,
          defaultSyncConfig,
          fastSyncResult.ref,
          fastSyncFailed.ref,
          blacklist,
          () => ourBestTD,
          n => canonicalByNumber(n),
          h => validateHeaderPoWFn(h)
        ),
        s"pivot-block-selector-${java.util.UUID.randomUUID()}"
      )

    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    val bestBlock = 400000
    // Ask for pivot block header (the best block from the best peer - offset)
    val expectedPivotBlock: Int = bestBlock - syncConfig.pivotBlockOffset

    val pivotBlockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(expectedPivotBlock))
    val differentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = BlockNumber(expectedPivotBlock), extraData = ByteString("different"))
    val anotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = BlockNumber(expectedPivotBlock), extraData = ByteString("different2"))
    val nextAnotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = BlockNumber(expectedPivotBlock), extraData = ByteString("different3"))

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

    /** ETH69 G5 — a canonical lookup that resolves the elected pivot at its own height (immediate backlink match). Used
      * by the happy-path electing tests so the backlink probe confirms on the pivot header itself.
      */
    def canonicalReturningPivot: BigInt => Option[BlockHeader] =
      n => if n == pivotBlockHeader.number.value then Some(pivotBlockHeader) else None

    /** ETH69 G5 — build a reverse-ordered, parentHash-linked header chain with its tip at `tipNum`, walking back
      * `depth` blocks (so the returned Seq is [tip, tip-1, ..., tip-depth+1]). Each header's parentHash points at the
      * next (older) header's hash, satisfying the selector's continuity check. extraData disambiguates the per-height
      * hashes so they differ from any canonical header unless explicitly matched. The tip header (Seq.head) is the
      * elected pivot to vote in `electUnanimousPivot`.
      */
    def reverseChain(tipNum: BigInt, depth: Int): Seq[BlockHeader] =
      // Oldest → newest, linking parentHash forward, then reverse to newest → oldest.
      val oldestNum = tipNum - depth + 1
      val ascending = (oldestNum to tipNum).foldLeft(Vector.empty[BlockHeader]) { (acc, n) =>
        val parentHash = acc.lastOption.map(_.hash).getOrElse(BlockHash(ByteString("genesis-parent")))
        acc :+ baseBlockHeader.copy(
          number = BlockNumber(n),
          parentHash = parentHash,
          extraData = ByteString(s"backlink-$n")
        )
      }
      ascending.reverse
