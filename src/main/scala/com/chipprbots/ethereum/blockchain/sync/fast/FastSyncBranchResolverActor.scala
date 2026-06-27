package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.ActorRef as ClassicActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders as ETH68BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETH68GetBlockHeaders
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Finds the first common block between our chain and the chain of the master peer (the peer with the highest block),
  * so fast sync can discard our diverged tip and re-download from the common ancestor.
  *
  * Pekko Typed migration (Group S2): the Classic `context.become` state machine becomes named `Behavior` factories
  * (`waitingForPeerWithHighestBlock` / `waitingForRecentBlockHeaders` / `waitingForBinarySearchBlock`). Peer-list
  * responsibilities move to the composed [[PeerListHelper]] (replacing the self-typed `PeerListSupportNg` trait); the
  * periodic `GetHandshakedPeers` poll is owned here via `Behaviors.withTimers`.
  *
  * The behavior type is `Behavior[Any]` (same idiom as `BytecodeRecoveryActor`): `PeerRequestHandler` stays Classic and
  * sends its `ResponseReceived` / `RequestFailed` replies to `context.parent`, which — when the handler is spawned via
  * `context.toClassic.actorOf` — is this Typed actor's mailbox. Those raw Classic case classes are matched directly.
  * `PeerDisconnected` and `HandshakedPeers` events arrive via `context.messageAdapter`s. `fastSync` stays Classic —
  * replies go out via Classic `tell` with `noSender`. The pure binary-search/discard logic stays in the
  * [[FastSyncBranchResolver]] trait, mixed into the helper [[BranchLogic]] instance.
  */
object FastSyncBranchResolverActor {

  import FastSyncBranchResolver.*

  /** Begin (or restart) branch resolution. Public command sent by the parent / test. */
  case object StartBranchResolver

  /** Periodic timer fire: poll `networkPeerManager` for handshaked peers. */
  private case object ScanPeers

  /** A watched PeerRequestHandler child terminated. */
  final private case class HandlerTerminated(ref: ClassicActorRef)

  // ----- Outgoing messages to the Classic `fastSync` parent -----

  sealed trait BranchResolverResponse
  final case class BranchResolvedSuccessful(highestCommonBlockNumber: BigInt, masterPeer: Peer)
      extends BranchResolverResponse

  import BranchResolutionFailed.*
  final case class BranchResolutionFailed(failure: BranchResolutionFailure)
  object BranchResolutionFailed {
    def noCommonBlock: BranchResolutionFailed = BranchResolutionFailed(NoCommonBlockFound)
    def blockHeaderNotFound(blockHeaderNum: BigInt): BranchResolutionFailed = BranchResolutionFailed(
      BlockHeaderNotFound(blockHeaderNum)
    )

    sealed trait BranchResolutionFailure
    case object NoCommonBlockFound extends BranchResolutionFailure
    final case class BlockHeaderNotFound(blockHeaderNum: BigInt) extends BranchResolutionFailure
  }

  // ----- Timer / log constants -----

  private val ScanKey: String = "ScanPeers"
  private val RestartTimerKey: String = "Restart"

  private val SwitchToBinarySearchLog: String =
    "Branch diverged earlier than {} blocks ago. Switching to binary search to determine first common block."

  private val ReceivedBlockHeaderLog: String =
    "Received requested block header [{}] from peer [{}] in {} ms"

  private val ReceivedWrongHeaders: String =
    "Received invalid response when requesting block header [{}]. Received: {}"

  private val peerTerminatedLog: String =
    "Peer request handler [{}] for peer [{}] terminated. Restarting branch resolver."

  /** Bundles the pure branch-resolution logic (`discardBlocksAfter`) provided by the trait. */
  private class BranchLogic(val blockchain: Blockchain, val blockchainReader: BlockchainReader)
      extends FastSyncBranchResolver

  // scalastyle:off parameter.number
  def apply(
      fastSync: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      networkPeerManager: ClassicActorRef,
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      appStateStorage: AppStateStorage
  ): Behavior[Any] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerDisconnected] =
          context.messageAdapter[PeerDisconnected](identity)

        val peerListHelper = new PeerListHelper(
          networkPeerManager,
          peerEventBus,
          blacklist,
          syncConfig,
          peerDisconnectedAdapter,
          context.log
        )

        val branchLogic = new BranchLogic(blockchain, blockchainReader)

        val resolver = new Resolver(
          context,
          timers,
          fastSync,
          peerEventBus,
          networkPeerManager,
          blockchainReader,
          syncConfig,
          peerListHelper,
          branchLogic,
          new RecentBlocksSearch(blockchainReader),
          syncConfig.blockHeadersPerRequest
        )

        // Immediate poll, then periodic poll for handshaked peers (replaces PeerListSupportNg's scheduleWithFixedDelay).
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, context.self.toClassic)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)

        resolver.waitingForPeerWithHighestBlock()
      }
    }

  /** Holds the wiring shared across the behavior states. Each `waiting...` method returns the next `Behavior`. */
  private class Resolver(
      context: ActorContext[Any],
      timers: TimerScheduler[Any],
      fastSync: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      networkPeerManager: ClassicActorRef,
      blockchainReader: BlockchainReader,
      syncConfig: SyncConfig,
      peerListHelper: PeerListHelper,
      branchLogic: BranchLogic,
      recentBlocksSearch: RecentBlocksSearch,
      recentHeadersSize: Int
  ) {
    import BinarySearchSupport.*

    private def log = context.log

    /** Shared peer-list / scan handling for every state. Returns `Some(next)` if the message was handled. */
    private def handleCommon(message: Any): Option[Behavior[Any]] = message match {
      case ScanPeers =>
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, context.self.toClassic)
        Some(Behaviors.same)
      case NetworkPeerManagerActor.HandshakedPeers(peers) =>
        peerListHelper.handleHandshakedPeers(peers)
        Some(Behaviors.same)
      case PeerDisconnected(peerId) =>
        peerListHelper.handlePeerDisconnected(peerId)
        Some(Behaviors.same)
      case _ => None
    }

    def waitingForPeerWithHighestBlock(): Behavior[Any] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match {
            case StartBranchResolver =>
              peerListHelper.getPeerWithHighestBlock match {
                case Some(peerWithInfo @ PeerWithInfo(peer, _)) =>
                  log.debug(
                    "Starting branch resolution now with peer {} and block number {}",
                    peerWithInfo,
                    blockchainReader.getBestBlockNumber
                  )
                  requestRecentBlockHeaders(peer, blockchainReader.getBestBlockNumber)
                case None =>
                  log.info("Waiting for peers, rescheduling StartBranchResolver")
                  timers.startSingleTimer(RestartTimerKey, StartBranchResolver, 1.second)
                  Behaviors.same
              }
            case _ => Behaviors.same
          }
        }
      }

    private def waitingForRecentBlockHeaders(
        masterPeer: Peer,
        bestBlockNumber: BigInt,
        requestHandler: ClassicActorRef
    ): Behavior[Any] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match {
            case ResponseReceived(peer, ETH68BlockHeaders(_, headers), timeTaken) if peer == masterPeer =>
              if headers.size == recentHeadersSize then {
                log.debug("Received {} block headers from peer {} in {} ms", headers.size, masterPeer.id, timeTaken)
                handleRecentBlockHeadersResponse(headers, masterPeer, bestBlockNumber)
              } else {
                handleInvalidResponse(peer, requestHandler)
              }
            case RequestFailed(peer, reason) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
          }
        }
      }

    private def waitingForBinarySearchBlock(
        searchState: SearchState,
        blockHeaderNumberToSearch: BigInt,
        requestHandler: ClassicActorRef
    ): Behavior[Any] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match {
            case ResponseReceived(peer, ETH68BlockHeaders(_, headers), durationMs) if peer == searchState.masterPeer =>
              context.unwatch(requestHandler.toTyped[Nothing])
              headers.toList match {
                case childHeader :: Nil if childHeader.number == blockHeaderNumberToSearch =>
                  log.debug(ReceivedBlockHeaderLog, blockHeaderNumberToSearch, peer.id, durationMs)
                  handleBinarySearchBlockHeaderResponse(searchState, childHeader)
                case _ =>
                  log.warn(ReceivedWrongHeaders, blockHeaderNumberToSearch, headers.map(_.number))
                  handleInvalidResponse(peer, requestHandler)
              }
            case RequestFailed(peer, reason) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(searchState.masterPeer, ref)
            case HandlerTerminated(_) => Behaviors.same // ignore
            case _                    => Behaviors.same
          }
        }
      }

    private def requestRecentBlockHeaders(masterPeer: Peer, bestBlockNumber: BigInt): Behavior[Any] = {
      val requestHandler = sendGetBlockHeadersRequest(
        masterPeer,
        fromBlock = childOf((bestBlockNumber - recentHeadersSize).max(0)),
        amount = recentHeadersSize
      )
      waitingForRecentBlockHeaders(masterPeer, bestBlockNumber, requestHandler)
    }

    /** Searches recent blocks for a valid parent/child relationship. If none found, switch to binary search. */
    private def handleRecentBlockHeadersResponse(
        blockHeaders: Seq[BlockHeader],
        masterPeer: Peer,
        bestBlockNumber: BigInt
    ): Behavior[Any] =
      recentBlocksSearch.getHighestCommonBlock(blockHeaders, bestBlockNumber) match {
        case Some(highestCommonBlockNumber) =>
          finalizeBranchResolver(highestCommonBlockNumber, masterPeer)
        case None =>
          log.info(SwitchToBinarySearchLog, recentHeadersSize)
          requestBlockHeaderForBinarySearch(
            SearchState(minBlockNumber = 1, maxBlockNumber = bestBlockNumber, masterPeer)
          )
      }

    private def requestBlockHeaderForBinarySearch(searchState: SearchState): Behavior[Any] = {
      val headerNumberToRequest = blockHeaderNumberToRequest(searchState.minBlockNumber, searchState.maxBlockNumber)
      val handler = sendGetBlockHeadersRequest(searchState.masterPeer, headerNumberToRequest, 1)
      waitingForBinarySearchBlock(searchState, headerNumberToRequest, handler)
    }

    private def handleBinarySearchBlockHeaderResponse(
        searchState: SearchState,
        childHeader: BlockHeader
    ): Behavior[Any] = {
      import BinarySearchSupport.*
      blockchainReader.getBlockHeaderByNumber(parentOf(childHeader.number)) match {
        case Some(parentHeader) =>
          validateBlockHeaders(parentHeader, childHeader, searchState) match {
            case NoCommonBlock => stopWithFailure(BranchResolutionFailed.noCommonBlock)
            case BinarySearchCompleted(highestCommonBlockNumber) =>
              finalizeBranchResolver(highestCommonBlockNumber, searchState.masterPeer)
            case ContinueBinarySearch(newSearchState) =>
              log.debug(s"Continuing binary search with new search state: $newSearchState")
              requestBlockHeaderForBinarySearch(newSearchState)
          }
        case None => stopWithFailure(BranchResolutionFailed.blockHeaderNotFound(childHeader.number))
      }
    }

    private def finalizeBranchResolver(firstCommonBlockNumber: BigInt, masterPeer: Peer): Behavior[Any] = {
      branchLogic.discardBlocksAfter(firstCommonBlockNumber)
      log.info(s"Branch resolution completed with first common block number [$firstCommonBlockNumber]")
      fastSync.tell(
        BranchResolvedSuccessful(highestCommonBlockNumber = firstCommonBlockNumber, masterPeer = masterPeer),
        org.apache.pekko.actor.ActorRef.noSender
      )
      Behaviors.stopped
    }

    /** On fatal errors (and to prevent trying forever) signal fast-sync and let it decide whether to retry. */
    private def stopWithFailure(response: BranchResolutionFailed): Behavior[Any] = {
      fastSync.tell(response, org.apache.pekko.actor.ActorRef.noSender)
      Behaviors.stopped
    }

    private def sendGetBlockHeadersRequest(peer: Peer, fromBlock: BigInt, amount: BigInt): ClassicActorRef = {
      // ETH68+ always uses request-id; capability check kept for parity with the Classic implementation.
      val _ = peerListHelper.handshakedPeers
        .get(peer.id)
        .exists(peerWithInfo => Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability))

      // `props` needs an implicit Scheduler (the Classic system scheduler) plus the implicit
      // GetBlockHeaders -> MessageSerializable conversion (from ETHPackets.GetBlockHeaders.GetBlockHeadersEnc).
      implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.classicSystem.scheduler

      // Spawned as a child of this Typed actor so PeerRequestHandler's `initiator = context.parent` resolves to us;
      // it then sends ResponseReceived / RequestFailed straight to our mailbox (matched as raw Classic case classes).
      val handler = context.toClassic.actorOf(
        PeerRequestHandler.props[ETH68GetBlockHeaders, ETH68BlockHeaders](
          peer,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg =
            ETH68GetBlockHeaders(ETHPackets.nextRequestId, Left(fromBlock), amount, skip = 0, reverse = false),
          responseMsgCode = Codes.BlockHeadersCode
        )
      )
      context.watchWith(handler.toTyped[Nothing], HandlerTerminated(handler))
      handler
    }

    private def handleInvalidResponse(peer: Peer, peerRef: ClassicActorRef): Behavior[Any] = {
      log.warn(s"Received invalid response from peer [${peer.id}]. Restarting branch resolver.")
      context.unwatch(peerRef.toTyped[Nothing])
      peerListHelper.blacklistIfHandshaked(
        peer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.WrongBlockHeaders
      )
      restart()
    }

    private def handleRequestFailure(peer: Peer, peerRef: ClassicActorRef, reason: String): Behavior[Any] = {
      log.warn(s"Request to peer [${peer.id}] failed: [$reason]. Restarting branch resolver.")
      context.unwatch(peerRef.toTyped[Nothing])
      peerListHelper.blacklistIfHandshaked(
        peer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.FastSyncRequestFailed(reason)
      )
      restart()
    }

    private def handlePeerTermination(peer: Peer, peerHandlerRef: ClassicActorRef): Behavior[Any] = {
      log.warn(peerTerminatedLog, peerHandlerRef.path.name, peer.id)
      restart()
    }

    private def restart(): Behavior[Any] = {
      context.self ! StartBranchResolver
      waitingForPeerWithHighestBlock()
    }
  }

}
