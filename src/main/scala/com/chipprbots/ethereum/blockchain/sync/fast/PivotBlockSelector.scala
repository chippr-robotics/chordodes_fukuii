package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
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
object PivotBlockSelector:

  // Besu: PivotBlockRetriever.SUSPICIOUS_NUMBER_OF_RETRIES = 5
  val SuspiciousRetryThreshold: Int = 5

  /** ETH69 G5 — pivot parent-chain backlink depth. After pivot election, we fetch this many headers reverse-walking
    * from the elected pivot (pivot + N-1 ancestors) and require at least one to match our local canonical chain at the
    * same height. A legitimate pivot sits within pivotBlockOffset of the honest head and therefore intersects our
    * canonical chain within a small window; a fork that diverged more than N blocks ago is not a valid SNAP anchor. 20
    * is deep enough to be certain, shallow enough to avoid delay.
    */
  val BacklinkDepth: Int = 20

  sealed trait Command
  case object SelectPivotBlock extends Command
  case object ElectionPivotBlockTimeout extends Command
  // ETH69 G5 — backlink probe (reverse header-chain fetch) did not complete in time.
  private case object BacklinkTimeout extends Command
  private case object ScanPeers extends Command
  final case class WrappedMessageFromPeer(msg: MessageFromPeer) extends Command
  final case class WrappedHandshakedPeers(hp: NetworkPeerManagerActor.HandshakedPeers) extends Command
  final case class WrappedPeerDisconnected(pd: PeerDisconnected) extends Command

  private case object ElectionTimeoutKey
  private case object RetryKey
  private case object ScanKey
  private case object BacklinkTimeoutKey

  def apply(
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      syncConfig: SyncConfig,
      replyTo: TypedActorRef[Result],
      selectionFailedTo: TypedActorRef[SelectionFailed.type],
      blacklist: Blacklist,
      ourBestTotalDifficulty: () => BigInt,
      // ETH69 G5 — pivot parent-chain backlink validation. `getCanonicalHeaderByNumber` reads our local
      // canonical chain (no peers); `validateHeaderPoW` checks a single header's PoW/difficulty in isolation
      // (no parent needed). Both are closures over FastSync's blockchainReader/validators so this actor stays
      // decoupled from consensus types and the implicit blockchainConfig resolves at the call site.
      getCanonicalHeaderByNumber: BigInt => Option[BlockHeader],
      validateHeaderPoW: BlockHeader => Boolean
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
        // P19: on PreRestart the supervisor is about to recreate this actor. `Behaviors.withTimers` cancels our
        // scheduled timers automatically, but the BlockHeaders subscription we registered with the peer event bus
        // (`blockHeadersAdapter`) is external state that would otherwise survive as a stale adapter ref — the bus
        // would keep routing BlockHeaders to a defunct adapter. Unsubscribe it here so the restarted instance starts
        // from a clean subscription set. The `peerDisconnectedAdapter` held by PeerListHelper is dropped with the
        // helper instance on restart, so only the BlockHeaders subscription needs explicit release.
        val behavior = new Impl(
          ctx,
          timers,
          networkPeerManager,
          peerEventBus,
          syncConfig,
          replyTo,
          selectionFailedTo,
          blacklist,
          peerListHelper,
          blockHeadersAdapter,
          handshakedPeersAdapter,
          ourBestTotalDifficulty,
          getCanonicalHeaderByNumber,
          validateHeaderPoW
        ).idle(initialState)
        Behaviors.intercept(() =>
          new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
            override def aroundSignal(
                c: org.apache.pekko.actor.typed.TypedActorContext[Command],
                signal: org.apache.pekko.actor.typed.Signal,
                target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
            ): Behavior[Command] =
              if signal == PreRestart then
                c.asScala.log.warn(
                  "{} received PreRestart — releasing peer-event-bus BlockHeaders subscription",
                  c.asScala.self.path.name
                )
                peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
              target(c, signal)
        )(behavior)
      }
    }

  case object SelectionFailed
  final case class Result(targetBlockHeader: BlockHeader)

  case class BlockHeaderWithVotes(header: BlockHeader, votes: Int = 1):
    def vote: BlockHeaderWithVotes = copy(votes = votes + 1)

  extension (headers: Map[ByteString, BlockHeaderWithVotes])
    def mostVotedHeader: Option[BlockHeaderWithVotes] =
      headers.toList.maximumByOption { case (_, headerWithVotes) => headerWithVotes.votes }.map(_._2)

  final case class ElectionDetails(
      participants: List[Peer],
      currentBestBlockNumber: BigInt,
      expectedPivotBlock: BigInt
  ):
    def hasEnoughVoters(minNumberOfVoters: Int): Boolean = participants.size >= minNumberOfVoters

  private case class PivotState(
      pivotBlockRetryCount: Int,
      totalSelectionAttempts: Int,
      pivotRetryState: RetryState,
      // ETH69 G5 — number of consecutive pivot elections whose parent-chain backlink probe failed. Each failure
      // deepens the next pivot by one pivotBlockOffset (liveness: a genuine chain split or lagging local chain
      // is escaped by anchoring further back rather than blocking indefinitely).
      backlinkFailureCount: Int = 0
  )

  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      syncConfig: SyncConfig,
      replyTo: TypedActorRef[Result],
      selectionFailedTo: TypedActorRef[SelectionFailed.type],
      blacklist: Blacklist,
      peerListHelper: PeerListHelper,
      blockHeadersAdapter: TypedActorRef[PeerEvent],
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers],
      ourBestTotalDifficulty: () => BigInt,
      getCanonicalHeaderByNumber: BigInt => Option[BlockHeader],
      validateHeaderPoW: BlockHeader => Boolean
  ):
    import syncConfig.*

    private val maxTotalSelectionAttempts = syncConfig.pivotBlockMaxTotalSelectionAttempts

    private def handleCommon(message: Command): Option[Behavior[Command]] = message match
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

    def idle(state: PivotState): Behavior[Command] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match
          case SelectPivotBlock =>
            if state.totalSelectionAttempts >= maxTotalSelectionAttempts then
              ctx.log.error(
                "Pivot block selection failed after {} total attempts. Stopping pivot block selector.",
                maxTotalSelectionAttempts
              )
              selectionFailedTo ! SelectionFailed
              peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
              Behaviors.stopped
            else
              startPivotBlockSelection(
                collectVoters(extraOffset = state.backlinkFailureCount * syncConfig.pivotBlockOffset),
                state.copy(totalSelectionAttempts = state.totalSelectionAttempts + 1)
              )
          case _ => Behaviors.same
      }
    }

    private def startPivotBlockSelection(election: ElectionDetails, state: PivotState): Behavior[Command] =
      val ElectionDetails(correctPeers, currentBestBlockNumber, expectedPivotBlock) = election

      if election.hasEnoughVoters(minPeersToChoosePivotBlock) then
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
          Map.empty,
          state
        )
      else
        ctx.log.debug(
          "Cannot pick pivot block. Need at least {} peers, but there are only {} which meet the criteria " +
            "({} all available at the moment). Best block number = {}",
          minPeersToChoosePivotBlock,
          correctPeers.size,
          peerListHelper.peersToDownloadFrom.size,
          currentBestBlockNumber
        )
        retryPivotBlockSelection(currentBestBlockNumber, state)

    private def retryPivotBlockSelection(pivotBlockNumber: BigInt, state: PivotState): Behavior[Command] =
      val newRetryCount = state.pivotBlockRetryCount + 1
      if newRetryCount <= maxPivotBlockFailuresCount && pivotBlockNumber > 0 then
        startPivotBlockSelection(
          collectVoters(Some(pivotBlockNumber)),
          state.copy(pivotBlockRetryCount = newRetryCount)
        )
      else
        ctx.log.debug(
          "Cannot pick pivot block. Current best block number [{}]. Scheduling retry with backoff (attempt {})",
          pivotBlockNumber,
          state.pivotRetryState.attempt + 1
        )
        scheduleRetry(state.copy(pivotBlockRetryCount = newRetryCount))

    def runningPivotBlockElection(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes],
        // ETH69 G5 — peerId of each peer that voted for the pivot-block height, keyed by the hash it voted for.
        // After election we resend the reverse backlink probe to the peers that backed the winning header.
        votesByPeer: Map[PeerId, ByteString],
        state: PivotState
    ): Behavior[Command] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match
          case WrappedMessageFromPeer(MessageFromPeer(blockHeaders: ETHPackets.BlockHeaders, peerId)) =>
            peerEventBus ! UnsubscribeCmd(
              MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peerId)),
              blockHeadersAdapter
            )
            val updatedPeersToAsk = peersToAsk - peerId
            blockHeaders.headers.find(_.number.value == pivotBlockNumber) match
              case Some(targetBlockHeader) =>
                val newValue =
                  headers
                    .get(targetBlockHeader.hash.value)
                    .map(_.vote)
                    .getOrElse(BlockHeaderWithVotes(targetBlockHeader))
                votingProcess(
                  updatedPeersToAsk,
                  waitingPeers,
                  pivotBlockNumber,
                  headers.updated(targetBlockHeader.hash.value, newValue),
                  votesByPeer.updated(peerId, targetBlockHeader.hash.value),
                  state
                )
              case None =>
                blacklist.add(peerId, blacklistDuration, InvalidPivotBlockElectionResponse)
                votingProcess(updatedPeersToAsk, waitingPeers, pivotBlockNumber, headers, votesByPeer, state)
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

    private def votingProcess(
        peersToAsk: Set[PeerId],
        waitingPeers: List[PeerId],
        pivotBlockNumber: BigInt,
        headers: Map[ByteString, BlockHeaderWithVotes],
        votesByPeer: Map[PeerId, ByteString],
        state: PivotState
    ): Behavior[Command] =
      val maybeBlockHeaderWithVotes = headers.mostVotedHeader
      if peersToAsk.isEmpty && maybeBlockHeaderWithVotes.exists(_.votes >= minPeersToChoosePivotBlock) then
        timers.cancel(ElectionTimeoutKey)
        // ETH69 G5 — the elected pivot has won the vote, but a sybil/low-difficulty-fork set can still produce
        // a majority for a fabricated header. Before handing it to FastSync as the SNAP anchor, probe its
        // parent chain back N blocks and require a link into our local canonical chain.
        maybeBlockHeaderWithVotes match
          case Some(hWv) =>
            // Probe the peers that backed the winning header (they should be able to serve its ancestors).
            val backlinkPeers = votesByPeer.collect { case (pid, hash) if hash == hWv.header.hash.value => pid }.toSet
            startBacklinkVerification(hWv.header, backlinkPeers, state)
          case None => Behaviors.stopped // unreachable: guarded by `exists` above
      else if !isPossibleToReachConsensus(peersToAsk.size, maybeBlockHeaderWithVotes.map(_.votes).getOrElse(0)) then
        timers.cancel(ElectionTimeoutKey)
        if waitingPeers.nonEmpty then
          val additionalPeer :: newWaitingPeers = waitingPeers: @unchecked
          obtainBlockHeaderFromPeer(additionalPeer, pivotBlockNumber)
          timers.startSingleTimer(ElectionTimeoutKey, ElectionPivotBlockTimeout, peerResponseTimeout)
          runningPivotBlockElection(
            peersToAsk + additionalPeer,
            newWaitingPeers,
            pivotBlockNumber,
            headers,
            votesByPeer,
            state
          )
        else
          peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
          ctx.log.warn(
            "Not enough votes for pivot block. Scheduling retry with backoff (attempt {})",
            state.pivotRetryState.attempt + 1
          )
          scheduleRetry(state)
      else runningPivotBlockElection(peersToAsk, waitingPeers, pivotBlockNumber, headers, votesByPeer, state)

    private def isPossibleToReachConsensus(peersLeft: Int, bestHeaderVotes: Int): Boolean =
      peersLeft + bestHeaderVotes >= minPeersToChoosePivotBlock

    // ── ETH69 G5 — pivot parent-chain backlink validation ─────────────────────────────────────────────────

    /** Issue a reverse `GetBlockHeaders(pivot.hash, count=BacklinkDepth, reverse=true)` to the peers that voted for the
      * elected pivot, then await the header chain in [[verifyingBacklink]]. With no peer to ask (all voters
      * disconnected after voting) we cannot probe — treat as a backlink failure and deepen-retry.
      */
    private def startBacklinkVerification(
        pivotBlockHeader: BlockHeader,
        backlinkPeers: Set[PeerId],
        state: PivotState
    ): Behavior[Command] =
      if backlinkPeers.isEmpty then
        ctx.log.warn(
          "ETH69_PIVOT_BACKLINK_FAIL: pivot block={} elected but no voting peer remains to probe its parent chain",
          pivotBlockHeader.number
        )
        peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
        scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))
      else
        backlinkPeers.foreach { peer =>
          peerEventBus ! SubscribeCmd(
            MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer)),
            blockHeadersAdapter
          )
          val msg: MessageSerializable = ETHPackets.GetBlockHeaders(
            ETHPackets.nextRequestId,
            Right(pivotBlockHeader.hash.value),
            maxHeaders = BacklinkDepth,
            skip = 0,
            reverse = true
          )
          networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(msg, peer)
        }
        timers.startSingleTimer(BacklinkTimeoutKey, BacklinkTimeout, peerResponseTimeout)
        ctx.log.debug(
          "ETH69 G5: probing backlink for pivot block={} hash={} across {} voter(s), depth={}",
          pivotBlockHeader.number,
          pivotBlockHeader.hashAsHexString,
          backlinkPeers.size,
          BacklinkDepth
        )
        verifyingBacklink(pivotBlockHeader, backlinkPeers, state)

    /** Awaits the reverse header chain. The first peer that returns a usable chain decides the outcome:
      *   - PoW invalid on any returned header → forged chain: blacklist the peer, deepen-retry.
      *   - parentHash discontinuity → not a real chain: deepen-retry (peer may be honest-but-buggy, no blacklist).
      *   - a returned ancestor matches our local canonical chain at its height → backlink confirmed, use the pivot.
      *   - no canonical match within BacklinkDepth → fork too deep to anchor SNAP: deepen-retry.
      */
    private def verifyingBacklink(
        pivotBlockHeader: BlockHeader,
        backlinkPeers: Set[PeerId],
        state: PivotState
    ): Behavior[Command] = Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match
          case WrappedMessageFromPeer(MessageFromPeer(blockHeaders: ETHPackets.BlockHeaders, peerId))
              if backlinkPeers.contains(peerId) =>
            timers.cancel(BacklinkTimeoutKey)
            peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
            checkBacklink(pivotBlockHeader, blockHeaders.headers, peerId, state)
          case BacklinkTimeout =>
            peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)
            ctx.log.warn(
              "ETH69_PIVOT_BACKLINK_FAIL: backlink probe for pivot block={} timed out (no response from {} voter(s))",
              pivotBlockHeader.number,
              backlinkPeers.size
            )
            scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))
          case _ => Behaviors.same
      }
    }

    /** Validate the returned reverse-ordered header chain and decide accept/reject. */
    private def checkBacklink(
        pivotBlockHeader: BlockHeader,
        returnedHeaders: Seq[BlockHeader],
        peerId: PeerId,
        state: PivotState
    ): Behavior[Command] =
      // Expect a reverse chain starting at the pivot itself.
      val chain = returnedHeaders
      val startsAtPivot = chain.headOption.exists(_.hash == pivotBlockHeader.hash)

      if chain.isEmpty || !startsAtPivot then
        ctx.log.warn(
          "ETH69_PIVOT_BACKLINK_FAIL: peer {} returned an empty/non-pivot-rooted backlink for block={}",
          peerId,
          pivotBlockHeader.number
        )
        scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))
      else
        // 1. PoW validity of every returned header (no parent needed — isolated nonce/difficulty check).
        val powInvalidHeader = chain.find(h => !validateHeaderPoW(h))
        // 2. parentHash continuity: each header's parentHash must equal the next (older) header's hash.
        val continuous = chain.sliding(2).forall {
          case Seq(child, parent) => child.parentHash == parent.hash
          case _                  => true
        }

        if powInvalidHeader.isDefined then
          // Forged PoW in the served chain — the peer fabricated a pivot ancestry. Treat as malicious.
          blacklist.add(peerId, blacklistDuration, InvalidPivotBlockElectionResponse)
          ctx.log.warn(
            "ETH69_PIVOT_BACKLINK_FAIL: peer {} served forged PoW at block={} in pivot {} backlink — blacklisting",
            peerId,
            powInvalidHeader.map(_.number).getOrElse(BigInt(-1)),
            pivotBlockHeader.number
          )
          scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))
        else if !continuous then
          ctx.log.warn(
            "ETH69_PIVOT_BACKLINK_FAIL: peer {} backlink for pivot {} has a broken parentHash chain",
            peerId,
            pivotBlockHeader.number
          )
          scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))
        else
          // 3. Canonical match: any returned header that equals our local canonical header at its height links
          //    the pivot to the honest chain we already trust.
          val canonicalMatch = chain.find { h =>
            getCanonicalHeaderByNumber(h.number.value).exists(_.hash == h.hash)
          }
          canonicalMatch match
            case Some(anchor) =>
              ctx.log.info(
                "ETH69 G5: pivot block={} backlink confirmed — connects to canonical block={} within {} hop(s)",
                pivotBlockHeader.number,
                anchor.number,
                pivotBlockHeader.number - anchor.number
              )
              sendResponseAndCleanup(pivotBlockHeader, state.pivotRetryState)
              Behaviors.stopped
            case None =>
              ctx.log.warn(
                "ETH69_PIVOT_BACKLINK_FAIL: pivot block={} did not reach our canonical chain within {} hops " +
                  "(deepening pivot offset and retrying)",
                pivotBlockHeader.number,
                BacklinkDepth
              )
              scheduleRetry(state.copy(backlinkFailureCount = state.backlinkFailureCount + 1))

    private def scheduleRetry(state: PivotState): Behavior[Command] =
      val delay = state.pivotRetryState.nextDelay
      val newPivotRetryState = state.pivotRetryState.recordAttempt
      if newPivotRetryState.attempt % SuspiciousRetryThreshold == 0 then
        ctx.log.warn(
          "{} pivot block selection retries have failed to obtain a valid pivot block",
          newPivotRetryState.attempt
        )
      ctx.log.debug("Scheduling pivot block selection retry in {}", delay)
      timers.startSingleTimer(RetryKey, SelectPivotBlock, delay)
      idle(state.copy(pivotBlockRetryCount = 0, pivotRetryState = newPivotRetryState))

    private def sendResponseAndCleanup(pivotBlockHeader: BlockHeader, pivotRetryState: RetryState): Unit =
      val resetState = pivotRetryState.reset
      val attempts = resetState.attempt
      ctx.log.info(
        "[PIVOT] Selected block={} hash={} after {} attempt(s)",
        pivotBlockHeader.number,
        pivotBlockHeader.hashAsHexString,
        attempts
      )
      replyTo ! Result(pivotBlockHeader)
      peerEventBus ! UnsubscribeAllCmd(blockHeadersAdapter)

    private def obtainBlockHeaderFromPeer(peer: PeerId, blockNumber: BigInt): Unit =
      peerEventBus ! SubscribeCmd(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer)),
        blockHeadersAdapter
      )
      val getBlockHeadersMsg: MessageSerializable = peerListHelper.handshakedPeers.get(peer) match
        case Some(peerWithInfo) if Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability) =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
        case _ =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Left(blockNumber), 1, 0, reverse = false)
      networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(getBlockHeadersMsg, peer)

    private def collectVoters(
        previousBestBlockNumber: Option[BigInt] = None,
        extraOffset: BigInt = 0
    ): ElectionDetails =
      // ETH69 G1 — TD consensus gate (P0). Before this gate, peers entered the snap-sync voter pool
      // by maxBlockNumber alone. An attacker on a long low-difficulty fork passes Tier3 TD estimation
      // at handshake (estimate looks legitimate) and wins pivot election by block-number ranking with
      // K sybil peers, anchoring SNAP sync to an attacker-chosen state root. Require each voter's
      // advertised chainWeight to be within 80% of our local best TD (±20% slack absorbs Tier3
      // estimation variance and genuine peer lag). Liveness fallback: if no peer clears the gate,
      // fall back to block-number-only ranking rather than blocking sync indefinitely.
      val ourBestTD = ourBestTotalDifficulty()
      val minPeerTD = if ourBestTD > 0 then ourBestTD * 8 / 10 else BigInt(0)

      val tdGatedPeers = peerListHelper.peersToDownloadFrom.collect {
        case (_, PeerWithInfo(peer, PeerInfo(_, chainWeight, true, maxBlockNumber, _)))
            if maxBlockNumber > 0 && chainWeight.totalDifficulty.value >= minPeerTD =>
          (peer, maxBlockNumber)
      }

      val peersUsedToChooseTarget =
        if tdGatedPeers.nonEmpty then tdGatedPeers
        else
          val blockNumberOnlyPeers = peerListHelper.peersToDownloadFrom.collect {
            case (_, PeerWithInfo(peer, PeerInfo(_, _, true, maxBlockNumber, _))) if maxBlockNumber > 0 =>
              (peer, maxBlockNumber)
          }
          if blockNumberOnlyPeers.nonEmpty then
            ctx.log.warn(
              "ETH69_PIVOT_TD_GATE_EMPTY: no peers passed TD gate (ourBestTD={}, minPeerTD={}); " +
                "falling back to block-number-only ranking for liveness",
              ourBestTD,
              minPeerTD
            )
          blockNumberOnlyPeers

      val peersSortedByBestNumber = peersUsedToChooseTarget.toList.sortBy { case (_, number) => -number }
      val bestPeerBestBlockNumber = peersSortedByBestNumber.headOption
        .map { case (_, bestPeerBestBlockNumber) => bestPeerBestBlockNumber }
        .getOrElse(BigInt(0))

      val currentBestBlockNumber: BigInt =
        previousBestBlockNumber
          .flatMap(previous => peersSortedByBestNumber.collectFirst { case (_, number) if number < previous => number })
          .getOrElse(bestPeerBestBlockNumber)

      // ETH69 G5 — `extraOffset` deepens the pivot after a backlink failure (liveness escape from a lagging
      // local chain or a genuine split). 0 on the normal path.
      val expectedPivotBlock = (currentBestBlockNumber - syncConfig.pivotBlockOffset - extraOffset).max(0)
      val correctPeers = peersSortedByBestNumber
        .takeWhile { case (_, number) => number >= expectedPivotBlock }
        .map { case (peer, _) => peer }

      ElectionDetails(correctPeers, currentBestBlockNumber, expectedPivotBlock)
