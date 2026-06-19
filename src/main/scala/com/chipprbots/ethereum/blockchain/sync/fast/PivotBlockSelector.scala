package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.ActorRef as ClassicActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import cats.implicits.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.InvalidPivotBlockElectionResponse
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.PivotBlockElectionTimeout
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.RetryState
import com.chipprbots.ethereum.blockchain.sync.RetryStrategy
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Selects the pivot block for fast sync by running a voting protocol across handshaked peers.
  *
  * Pekko Typed migration (Group S4): the Classic `context.become` state machine becomes named `Behavior` factories
  * (`idle` / `runningPivotBlockElection`). Peer-list responsibilities move to [[PeerListHelper]] (replacing the
  * self-typed `PeerListSupportNg` trait). The three `scheduler.scheduleOnce` calls become `Behaviors.withTimers`.
  *
  * Direct `PeerEventBusActor` subscriptions for `BlockHeaders` use `ctx.self.toClassic` as the subscriber identity;
  * PeerListHelper's `PeerDisconnected` subscription uses a separate `messageAdapter` ref and is not affected by the
  * actor-level `Unsubscribe()` call.
  *
  * The behavior type is `Behavior[Any]`: `fastSync` stays Classic and sends `SelectPivotBlock` as a plain message.
  */
object PivotBlockSelector {

  // Besu: PivotBlockRetriever.SUSPICIOUS_NUMBER_OF_RETRIES = 5
  val SuspiciousRetryThreshold: Int = 5

  private case object ScanPeers

  private val ElectionTimeoutKey: String = "ElectionTimeout"
  private val RetryKey: String = "Retry"
  private val ScanKey: String = "ScanPeers"

  def apply(
      networkPeerManager: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      syncConfig: SyncConfig,
      fastSync: ClassicActorRef,
      blacklist: Blacklist
  ): Behavior[Any] =
    Behaviors.setup[Any] { ctx =>
      Behaviors.withTimers[Any] { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerDisconnected] =
          ctx.messageAdapter[PeerDisconnected](identity)
        val peerListHelper = new PeerListHelper(
          peerEventBus,
          blacklist,
          peerDisconnectedAdapter,
          ctx.log
        )
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, ctx.self.toClassic)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)
        new Impl(ctx, timers, networkPeerManager, peerEventBus, syncConfig, fastSync, blacklist, peerListHelper).idle()
      }
    }

  case object SelectPivotBlock
  final case class Result(targetBlockHeader: BlockHeader)
  case object SelectionFailed
  case object ElectionPivotBlockTimeout

  case class BlockHeaderWithVotes(header: BlockHeader, votes: Int = 1) {
    def vote: BlockHeaderWithVotes = copy(votes = votes + 1)
  }

  implicit class SortableHeadersMap(headers: Map[ByteString, BlockHeaderWithVotes]) {
    def mostVotedHeader: Option[BlockHeaderWithVotes] =
      headers.toList.maximumByOption { case (_, headerWithVotes) => headerWithVotes.votes }.map(_._2)
  }

  final case class ElectionDetails(
      participants: List[Peer],
      currentBestBlockNumber: BigInt,
      expectedPivotBlock: BigInt
  ) {
    def hasEnoughVoters(minNumberOfVoters: Int): Boolean = participants.size >= minNumberOfVoters
  }

  private class Impl(
      ctx: ActorContext[Any],
      timers: TimerScheduler[Any],
      networkPeerManager: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      syncConfig: SyncConfig,
      fastSync: ClassicActorRef,
      blacklist: Blacklist,
      peerListHelper: PeerListHelper
  ) {
    import syncConfig.*

    private var pivotBlockRetryCount = 0
    private var totalSelectionAttempts = 0
    private val maxTotalSelectionAttempts = syncConfig.pivotBlockMaxTotalSelectionAttempts

    private var pivotRetryState: RetryState = RetryState(
      strategy = RetryStrategy(initialDelay = syncConfig.startRetryInterval, maxDelay = 60.seconds, jitterFactor = 0.0)
    )

    private def handleCommon(message: Any): Option[Behavior[Any]] = message match {
      case ScanPeers =>
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, ctx.self.toClassic)
        Some(Behaviors.same)
      case NetworkPeerManagerActor.HandshakedPeers(peers) =>
        peerListHelper.handleHandshakedPeers(peers)
        Some(Behaviors.same)
      case pd: PeerDisconnected =>
        peerListHelper.handlePeerDisconnected(pd.peerId)
        Some(Behaviors.same)
      case _ => None
    }

    def idle(): Behavior[Any] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match {
          case SelectPivotBlock =>
            if totalSelectionAttempts >= maxTotalSelectionAttempts then {
              ctx.log.error(
                "Pivot block selection failed after {} total attempts. Stopping pivot block selector.",
                maxTotalSelectionAttempts
              )
              fastSync ! SelectionFailed
              peerEventBus.tell(Unsubscribe(), ctx.self.toClassic)
              Behaviors.stopped
            } else {
              totalSelectionAttempts += 1
              startPivotBlockSelection(collectVoters())
            }
          case _ => Behaviors.same
        }
      }
    }

    private def startPivotBlockSelection(election: ElectionDetails): Behavior[Any] = {
      val ElectionDetails(correctPeers, currentBestBlockNumber, expectedPivotBlock) = election

      if election.hasEnoughVoters(minPeersToChoosePivotBlock) then {
        val (peersToAsk, waitingPeers) =
          correctPeers.splitAt(minPeersToChoosePivotBlock + peersToChoosePivotBlockMargin)

        ctx.log.debug(
          "Trying to choose fast sync pivot block using {} peers ({} ones with high enough block). Ask {} peers for block nr {}",
          peerListHelper.peersToDownloadFrom.size,
          correctPeers.size,
          peersToAsk.size,
          expectedPivotBlock
        )

        peersToAsk.foreach(peer => obtainBlockHeaderFromPeer(peer.id, expectedPivotBlock))
        timers.startSingleTimer(ElectionTimeoutKey, ElectionPivotBlockTimeout, peerResponseTimeout)
        runningPivotBlockElection(peersToAsk.map(_.id).toSet, waitingPeers.map(_.id), expectedPivotBlock, Map.empty)
      } else {
        ctx.log.debug(
          "Cannot pick pivot block. Need at least {} peers, but there are only {} which meet the criteria " +
            "({} all available at the moment). Best block number = {}",
          minPeersToChoosePivotBlock,
          correctPeers.size,
          peerListHelper.peersToDownloadFrom.size,
          currentBestBlockNumber
        )
        retryPivotBlockSelection(currentBestBlockNumber)
      }
    }

    private def retryPivotBlockSelection(pivotBlockNumber: BigInt): Behavior[Any] = {
      pivotBlockRetryCount += 1
      if pivotBlockRetryCount <= maxPivotBlockFailuresCount && pivotBlockNumber > 0 then {
        startPivotBlockSelection(collectVoters(Some(pivotBlockNumber)))
      } else {
        ctx.log.debug(
          "Cannot pick pivot block. Current best block number [{}]. Scheduling retry with backoff (attempt {})",
          pivotBlockNumber,
          pivotRetryState.attempt + 1
        )
        scheduleRetry()
      }
    }

    def runningPivotBlockElection(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes]
    ): Behavior[Any] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match {
          case MessageFromPeer(blockHeaders: ETHPackets.BlockHeaders, peerId) =>
            peerEventBus.tell(
              Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peerId))),
              ctx.self.toClassic
            )
            val updatedPeersToAsk = peersToAsk - peerId
            blockHeaders.headers.find(_.number == pivotBlockNumber) match {
              case Some(targetBlockHeader) =>
                val newValue =
                  headers.get(targetBlockHeader.hash).map(_.vote).getOrElse(BlockHeaderWithVotes(targetBlockHeader))
                votingProcess(
                  updatedPeersToAsk,
                  waitingPeers,
                  pivotBlockNumber,
                  headers.updated(targetBlockHeader.hash, newValue)
                )
              case None =>
                blacklist.add(peerId, blacklistDuration, InvalidPivotBlockElectionResponse)
                votingProcess(updatedPeersToAsk, waitingPeers, pivotBlockNumber, headers)
            }
          case ElectionPivotBlockTimeout =>
            peersToAsk.foreach(peerId => blacklist.add(peerId, blacklistDuration, PivotBlockElectionTimeout))
            peerEventBus.tell(Unsubscribe(), ctx.self.toClassic)
            ctx.log.warn(
              "Pivot block header receive timeout. Scheduling retry with backoff (attempt {})",
              pivotRetryState.attempt + 1
            )
            scheduleRetry()
          case _ => Behaviors.same
        }
      }
    }

    private def votingProcess(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes]
    ): Behavior[Any] = {
      val maybeBlockHeaderWithVotes = headers.mostVotedHeader
      if peersToAsk.isEmpty && maybeBlockHeaderWithVotes.exists(_.votes >= minPeersToChoosePivotBlock) then {
        timers.cancel(ElectionTimeoutKey)
        maybeBlockHeaderWithVotes.foreach(hWv => sendResponseAndCleanup(hWv.header))
        Behaviors.stopped
      } else if !isPossibleToReachConsensus(peersToAsk.size, maybeBlockHeaderWithVotes.map(_.votes).getOrElse(0)) then {
        timers.cancel(ElectionTimeoutKey)
        if waitingPeers.nonEmpty then {
          val additionalPeer :: newWaitingPeers = waitingPeers: @unchecked
          obtainBlockHeaderFromPeer(additionalPeer, pivotBlockNumber)
          timers.startSingleTimer(ElectionTimeoutKey, ElectionPivotBlockTimeout, peerResponseTimeout)
          runningPivotBlockElection(peersToAsk + additionalPeer, newWaitingPeers, pivotBlockNumber, headers)
        } else {
          peerEventBus.tell(Unsubscribe(), ctx.self.toClassic)
          ctx.log.warn(
            "Not enough votes for pivot block. Scheduling retry with backoff (attempt {})",
            pivotRetryState.attempt + 1
          )
          scheduleRetry()
        }
      } else {
        runningPivotBlockElection(peersToAsk, waitingPeers, pivotBlockNumber, headers)
      }
    }

    private def isPossibleToReachConsensus(peersLeft: Int, bestHeaderVotes: Int): Boolean =
      peersLeft + bestHeaderVotes >= minPeersToChoosePivotBlock

    private def scheduleRetry(): Behavior[Any] = {
      pivotBlockRetryCount = 0
      val delay = pivotRetryState.nextDelay
      pivotRetryState = pivotRetryState.recordAttempt
      if pivotRetryState.attempt % SuspiciousRetryThreshold == 0 then {
        ctx.log.warn(
          "{} pivot block selection retries have failed to obtain a valid pivot block",
          pivotRetryState.attempt
        )
      }
      ctx.log.debug("Scheduling pivot block selection retry in {}", delay)
      timers.startSingleTimer(RetryKey, SelectPivotBlock, delay)
      idle()
    }

    private def sendResponseAndCleanup(pivotBlockHeader: BlockHeader): Unit = {
      pivotRetryState = pivotRetryState.reset
      val attempts = pivotRetryState.attempt
      ctx.log.info(
        "[PIVOT] Selected block={} hash={} after {} attempt(s)",
        pivotBlockHeader.number,
        pivotBlockHeader.hashAsHexString,
        attempts
      )
      fastSync ! Result(pivotBlockHeader)
      peerEventBus.tell(Unsubscribe(), ctx.self.toClassic)
    }

    private def obtainBlockHeaderFromPeer(peer: PeerId, blockNumber: BigInt): Unit = {
      peerEventBus.tell(
        Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer))),
        ctx.self.toClassic
      )
      val getBlockHeadersMsg: MessageSerializable = peerListHelper.handshakedPeers.get(peer) match {
        case Some(peerWithInfo) if Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability) =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
        case _ =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
      }
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(getBlockHeadersMsg, peer)
    }

    private def collectVoters(previousBestBlockNumber: Option[BigInt] = None): ElectionDetails = {
      val peersUsedToChooseTarget = peerListHelper.peersToDownloadFrom.collect {
        case (_, PeerWithInfo(peer, PeerInfo(_, _, true, maxBlockNumber, _))) if maxBlockNumber > 0 =>
          (peer, maxBlockNumber)
      }

      val peersSortedByBestNumber = peersUsedToChooseTarget.toList.sortBy { case (_, number) => -number }
      val bestPeerBestBlockNumber = peersSortedByBestNumber.headOption
        .map { case (_, bestPeerBestBlockNumber) => bestPeerBestBlockNumber }
        .getOrElse(BigInt(0))

      val currentBestBlockNumber: BigInt =
        previousBestBlockNumber
          .flatMap(previous => peersSortedByBestNumber.collectFirst { case (_, number) if number < previous => number })
          .getOrElse(bestPeerBestBlockNumber)

      val expectedPivotBlock = (currentBestBlockNumber - syncConfig.pivotBlockOffset).max(0)
      val correctPeers = peersSortedByBestNumber
        .takeWhile { case (_, number) => number >= expectedPivotBlock }
        .map { case (peer, _) => peer }

      ElectionDetails(correctPeers, currentBestBlockNumber, expectedPivotBlock)
    }
  }
}
