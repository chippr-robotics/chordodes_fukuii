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
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
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
  * Direct `PeerEventBusActor` subscriptions for `BlockHeaders` use `blockHeadersAdapter` (TypedActorRef[PeerEvent]);
  * PeerListHelper's `PeerDisconnected` subscription uses a separate `messageAdapter` ref. Selection state
  * (`pivotBlockRetryCount`, `totalSelectionAttempts`, `pivotRetryState`) is threaded as immutable [[PivotState]]
  * through behavior factory closures — no mutable `Impl` fields.
  */
object PivotBlockSelector {

  // Besu: PivotBlockRetriever.SUSPICIOUS_NUMBER_OF_RETRIES = 5
  val SuspiciousRetryThreshold: Int = 5

  sealed trait Command
  case object SelectPivotBlock extends Command
  case object ElectionPivotBlockTimeout extends Command
  private case object ScanPeers extends Command
  final case class WrappedMessageFromPeer(msg: MessageFromPeer) extends Command
  final case class WrappedHandshakedPeers(hp: NetworkPeerManagerActor.HandshakedPeers) extends Command
  final case class WrappedPeerDisconnected(pd: PeerDisconnected) extends Command

  private case object ElectionTimeoutKey
  private case object RetryKey
  private case object ScanKey

  def apply(
      networkPeerManager: ClassicActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      syncConfig: SyncConfig,
      fastSync: ClassicActorRef,
      blacklist: Blacklist
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers[Command] { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case pd: PeerDisconnected => WrappedPeerDisconnected(pd)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }
        val blockHeadersAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case mfp: MessageFromPeer => WrappedMessageFromPeer(mfp)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }
        val handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers] =
          ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](WrappedHandshakedPeers(_))
        val peerListHelper = new PeerListHelper(
          peerEventBus,
          blacklist,
          peerDisconnectedAdapter,
          ctx.log
        )
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)
        val initialState = PivotState(
          pivotBlockRetryCount = 0,
          totalSelectionAttempts = 0,
          pivotRetryState = RetryState(
            strategy =
              RetryStrategy(initialDelay = syncConfig.startRetryInterval, maxDelay = 60.seconds, jitterFactor = 0.0)
          )
        )
        new Impl(
          ctx,
          timers,
          networkPeerManager,
          peerEventBus,
          syncConfig,
          fastSync,
          blacklist,
          peerListHelper,
          blockHeadersAdapter,
          handshakedPeersAdapter
        ).idle(initialState)
      }
    }

  case object SelectionFailed
  final case class Result(targetBlockHeader: BlockHeader)

  case class BlockHeaderWithVotes(header: BlockHeader, votes: Int = 1) {
    def vote: BlockHeaderWithVotes = copy(votes = votes + 1)
  }

  extension (headers: Map[ByteString, BlockHeaderWithVotes]) {
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

  private case class PivotState(
      pivotBlockRetryCount: Int,
      totalSelectionAttempts: Int,
      pivotRetryState: RetryState
  )

  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      networkPeerManager: ClassicActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      syncConfig: SyncConfig,
      fastSync: ClassicActorRef,
      blacklist: Blacklist,
      peerListHelper: PeerListHelper,
      blockHeadersAdapter: TypedActorRef[PeerEvent],
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]
  ) {
    import syncConfig.*

    private val maxTotalSelectionAttempts = syncConfig.pivotBlockMaxTotalSelectionAttempts

    private def handleCommon(message: Command): Option[Behavior[Command]] = message match {
      case ScanPeers =>
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        Some(Behaviors.same)
      case WrappedHandshakedPeers(NetworkPeerManagerActor.HandshakedPeers(peers)) =>
        peerListHelper.handleHandshakedPeers(peers)
        Some(Behaviors.same)
      case WrappedPeerDisconnected(pd) =>
        peerListHelper.handlePeerDisconnected(pd.peerId)
        Some(Behaviors.same)
      case _ => None
    }

    def idle(state: PivotState): Behavior[Command] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match {
          case SelectPivotBlock =>
            if state.totalSelectionAttempts >= maxTotalSelectionAttempts then {
              ctx.log.error(
                "Pivot block selection failed after {} total attempts. Stopping pivot block selector.",
                maxTotalSelectionAttempts
              )
              fastSync ! SelectionFailed
              peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
              Behaviors.stopped
            } else {
              startPivotBlockSelection(
                collectVoters(),
                state.copy(totalSelectionAttempts = state.totalSelectionAttempts + 1)
              )
            }
          case _ => Behaviors.same
        }
      }
    }

    private def startPivotBlockSelection(election: ElectionDetails, state: PivotState): Behavior[Command] = {
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
        runningPivotBlockElection(
          peersToAsk.map(_.id).toSet,
          waitingPeers.map(_.id),
          expectedPivotBlock,
          Map.empty,
          state
        )
      } else {
        ctx.log.debug(
          "Cannot pick pivot block. Need at least {} peers, but there are only {} which meet the criteria " +
            "({} all available at the moment). Best block number = {}",
          minPeersToChoosePivotBlock,
          correctPeers.size,
          peerListHelper.peersToDownloadFrom.size,
          currentBestBlockNumber
        )
        retryPivotBlockSelection(currentBestBlockNumber, state)
      }
    }

    private def retryPivotBlockSelection(pivotBlockNumber: BigInt, state: PivotState): Behavior[Command] = {
      val newRetryCount = state.pivotBlockRetryCount + 1
      if newRetryCount <= maxPivotBlockFailuresCount && pivotBlockNumber > 0 then {
        startPivotBlockSelection(
          collectVoters(Some(pivotBlockNumber)),
          state.copy(pivotBlockRetryCount = newRetryCount)
        )
      } else {
        ctx.log.debug(
          "Cannot pick pivot block. Current best block number [{}]. Scheduling retry with backoff (attempt {})",
          pivotBlockNumber,
          state.pivotRetryState.attempt + 1
        )
        scheduleRetry(state.copy(pivotBlockRetryCount = newRetryCount))
      }
    }

    def runningPivotBlockElection(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes],
        state: PivotState
    ): Behavior[Command] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match {
          case WrappedMessageFromPeer(MessageFromPeer(blockHeaders: ETHPackets.BlockHeaders, peerId)) =>
            peerEventBus ! UnsubscribeCmd(
              MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peerId)),
              blockHeadersAdapter
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
                  headers.updated(targetBlockHeader.hash, newValue),
                  state
                )
              case None =>
                blacklist.add(peerId, blacklistDuration, InvalidPivotBlockElectionResponse)
                votingProcess(updatedPeersToAsk, waitingPeers, pivotBlockNumber, headers, state)
            }
          case ElectionPivotBlockTimeout =>
            peersToAsk.foreach(peerId => blacklist.add(peerId, blacklistDuration, PivotBlockElectionTimeout))
            peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
            ctx.log.warn(
              "Pivot block header receive timeout. Scheduling retry with backoff (attempt {})",
              state.pivotRetryState.attempt + 1
            )
            scheduleRetry(state)
          case _ => Behaviors.same
        }
      }
    }

    private def votingProcess(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes],
        state: PivotState
    ): Behavior[Command] = {
      val maybeBlockHeaderWithVotes = headers.mostVotedHeader
      if peersToAsk.isEmpty && maybeBlockHeaderWithVotes.exists(_.votes >= minPeersToChoosePivotBlock) then {
        timers.cancel(ElectionTimeoutKey)
        maybeBlockHeaderWithVotes.foreach(hWv => sendResponseAndCleanup(hWv.header, state.pivotRetryState))
        Behaviors.stopped
      } else if !isPossibleToReachConsensus(peersToAsk.size, maybeBlockHeaderWithVotes.map(_.votes).getOrElse(0)) then {
        timers.cancel(ElectionTimeoutKey)
        if waitingPeers.nonEmpty then {
          val additionalPeer :: newWaitingPeers = waitingPeers: @unchecked
          obtainBlockHeaderFromPeer(additionalPeer, pivotBlockNumber)
          timers.startSingleTimer(ElectionTimeoutKey, ElectionPivotBlockTimeout, peerResponseTimeout)
          runningPivotBlockElection(peersToAsk + additionalPeer, newWaitingPeers, pivotBlockNumber, headers, state)
        } else {
          peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
          ctx.log.warn(
            "Not enough votes for pivot block. Scheduling retry with backoff (attempt {})",
            state.pivotRetryState.attempt + 1
          )
          scheduleRetry(state)
        }
      } else {
        runningPivotBlockElection(peersToAsk, waitingPeers, pivotBlockNumber, headers, state)
      }
    }

    private def isPossibleToReachConsensus(peersLeft: Int, bestHeaderVotes: Int): Boolean =
      peersLeft + bestHeaderVotes >= minPeersToChoosePivotBlock

    private def scheduleRetry(state: PivotState): Behavior[Command] = {
      val delay = state.pivotRetryState.nextDelay
      val newPivotRetryState = state.pivotRetryState.recordAttempt
      if newPivotRetryState.attempt % SuspiciousRetryThreshold == 0 then {
        ctx.log.warn(
          "{} pivot block selection retries have failed to obtain a valid pivot block",
          newPivotRetryState.attempt
        )
      }
      ctx.log.debug("Scheduling pivot block selection retry in {}", delay)
      timers.startSingleTimer(RetryKey, SelectPivotBlock, delay)
      idle(state.copy(pivotBlockRetryCount = 0, pivotRetryState = newPivotRetryState))
    }

    private def sendResponseAndCleanup(pivotBlockHeader: BlockHeader, pivotRetryState: RetryState): Unit = {
      val resetState = pivotRetryState.reset
      val attempts = resetState.attempt
      ctx.log.info(
        "[PIVOT] Selected block={} hash={} after {} attempt(s)",
        pivotBlockHeader.number,
        pivotBlockHeader.hashAsHexString,
        attempts
      )
      fastSync ! Result(pivotBlockHeader)
      peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
    }

    private def obtainBlockHeaderFromPeer(peer: PeerId, blockNumber: BigInt): Unit = {
      peerEventBus ! SubscribeCmd(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer)),
        blockHeadersAdapter
      )
      val getBlockHeadersMsg: MessageSerializable = peerListHelper.handshakedPeers.get(peer) match {
        case Some(peerWithInfo) if Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability) =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
        case _ =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
      }
      // In production the response arrives via the peerEventBus subscription above. Pass blockHeadersAdapter
      // explicitly so a directly-replying peer manager (notably the test AutoPilot) routes its MessageFromPeer
      // response back here rather than to dead letters.
      networkPeerManager.tell(
        NetworkPeerManagerActor.SendMessage(getBlockHeadersMsg, peer),
        blockHeadersAdapter.toClassic
      )
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
