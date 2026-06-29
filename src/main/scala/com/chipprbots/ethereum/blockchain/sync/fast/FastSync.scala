package com.chipprbots.ethereum.blockchain.sync.fast

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.implicits.*

import scala.annotation.tailrec
import scala.collection.mutable

import scala.concurrent.duration.*
import scala.util.Random

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.*
import com.chipprbots.ethereum.blockchain.sync.Blacklist.*
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.*
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRateTracker
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.fast.ReceiptsValidator.ReceiptsValidationResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncBlocksValidator.BlockBodyValidationResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.RestartRequested
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StartSyncingTo
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncFinished
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.WaitingForNewTargetBlock
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off file.size.limit
object FastSync:

  // scalastyle:off parameter.number method.length number.of.methods
  /** Typed core factory for FastSync. Returns a `Behavior[Command]` because the core receives a heterogeneous message
    * stream (external `SyncProtocol`, coordinator messages, internal ticks, and `PeerRequestHandler.Result` via an
    * id-keyed adapter). `syncController` replaces the Classic `context.parent` reply target.
    */
  def behavior(
      fastSyncStateStorage: FastSyncStateStorage,
      appStateStorage: AppStateStorage,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      nodeStorage: NodeStorage,
      validators: Validators,
      peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      syncController: TypedActorRef[SyncControllerMsg]
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers[Command] { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case pd: PeerDisconnected => WrappedPeerDisconnected(pd)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }
        val handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers] =
          ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](WrappedHandshakedPeers(_))
        // Immediate first poll + periodic rescans (matches PeerListSupportNg's 0-delay scheduleWithFixedDelay).
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanPeersTick, syncConfig.peersScanInterval)
        new Impl(
          ctx,
          timers,
          fastSyncStateStorage,
          appStateStorage,
          blockNumberMappingStorage,
          blockchain,
          blockchainReader,
          blockchainWriter,
          evmCodeStorage,
          stateStorage,
          nodeStorage,
          validators,
          peerEventBus,
          networkPeerManager,
          blacklist,
          syncConfig,
          configBuilder,
          syncController,
          peerDisconnectedAdapter,
          handshakedPeersAdapter
        ).idle()
      }
    }

  // scalastyle:off number.of.methods
  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      fastSyncStateStorage: FastSyncStateStorage,
      appStateStorage: AppStateStorage,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      blockchain: Blockchain,
      val blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      nodeStorage: NodeStorage,
      val validators: Validators,
      peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blacklist: Blacklist,
      val syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      syncController: TypedActorRef[SyncControllerMsg],
      peerDisconnectedAdapter: TypedActorRef[PeerEvent],
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]
  ) extends ReceiptsValidator
      with SyncBlocksValidator:

    import configBuilder.*
    import syncConfig.*

    override protected val log: org.slf4j.Logger = ctx.log

    // Shared rate tracker: the PeerListHelper tunes it on each handshaked-peer refresh and the concurrent fetcher
    // queues read its RTT/capacity estimates.
    private val ethRateTracker: PeerRateTracker = new PeerRateTracker()

    private val prhResultAdapter: TypedActorRef[PeerRequestHandler.Result] =
      ctx.messageAdapter[PeerRequestHandler.Result](WrappedPrhResult(_))

    // Typed adapters for inbound foreign messages. Each `messageAdapter` call registers a type→wrapper mapping in
    // the shared underlying adapter ref. `pivotResultAdapter` and `pivotFailedAdapter` are passed directly to
    // PivotBlockSelector as its typed reply targets. `schedulerResponseAdapter` and `stateSyncStatsAdapter` are
    // passed directly to SyncStateSchedulerActor as its reply targets (post-migration).
    private val pivotResultAdapter: TypedActorRef[PivotBlockSelector.Result] =
      ctx.messageAdapter[PivotBlockSelector.Result](WrappedPivotResult(_))
    private val pivotFailedAdapter: TypedActorRef[PivotBlockSelector.SelectionFailed.type] =
      ctx.messageAdapter[PivotBlockSelector.SelectionFailed.type](_ => PivotSelectionFailed)
    private val schedulerResponseAdapter: TypedActorRef[SyncStateSchedulerActor.SyncStateSchedulerActorResponse] =
      ctx.messageAdapter[SyncStateSchedulerActor.SyncStateSchedulerActorResponse](WrappedSchedulerResponse(_))
    private val stateSyncStatsAdapter: TypedActorRef[SyncStateSchedulerActor.StateSyncStats] =
      ctx.messageAdapter[SyncStateSchedulerActor.StateSyncStats](WrappedStateSyncStats(_))

    private val peerHelper =
      new PeerListHelper(peerEventBus, blacklist, peerDisconnectedAdapter, log, Some(ethRateTracker))

    private def handshakedPeers = peerHelper.handshakedPeers
    private def peersToDownloadFrom = peerHelper.peersToDownloadFrom
    private def globalMedianRttMs = peerHelper.globalMedianRttMs
    private def blacklistIfHandshaked(peerId: PeerId, duration: FiniteDuration, reason: BlacklistReason): Unit =
      peerHelper.blacklistIfHandshaked(peerId, duration, reason)

    /** Handle the two PeerListHelper-routed messages plus the periodic poll tick. Returned by every behavior's
      * `handlePeerListMessages` prefix.
      */
    private def handlePeerList(msg: Command): Boolean =
      msg match
        case ScanPeersTick =>
          networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
          true
        case WrappedHandshakedPeers(hp) =>
          peerHelper.handleHandshakedPeers(hp.peers)
          true
        case WrappedPeerDisconnected(PeerDisconnected(peerId)) =>
          peerHelper.handlePeerDisconnected(peerId)
          true
        case _ => false

    // ── Pre-syncing behaviors ──────────────────────────────────────────────────────────────────

    def idle(): Behavior[Command] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) then Behaviors.same
      else
        msg match
          case WrappedSyncProtocol(SyncProtocol.Start) => start()
          case GetStatusCmd(replyTo)                   => replyTo ! SyncProtocol.Status.NotSyncing; Behaviors.same
          case WrappedSyncProtocol(msg: SyncProtocol.GetStatus) =>
            ctx.self ! GetStatusCmd(msg.replyTo); Behaviors.same
          case _ => Behaviors.same
    }

    def start(): Behavior[Command] =
      log.info("Trying to start block synchronization (fast mode)")
      fastSyncStateStorage.getSyncState() match
        case Some(syncState) => startWithState(syncState)
        case None            => startFromScratch()

    /** Local best total difficulty, used as the consensus floor for the pivot-block TD gate (ETH69 G1). Returns 0 when
      * the best block / its chain weight is not yet available (e.g. early SNAP sync when only the pivot header is
      * persisted) — a 0 floor makes the TD gate inert and lets block-number ranking apply.
      */
    private def ourBestTotalDifficulty(): BigInt =
      blockchainReader.getBestBlock
        .flatMap(best => blockchainReader.getChainWeightByHash(best.header.hash))
        .map(_.totalDifficulty.value)
        .getOrElse(BigInt(0))

    // ETH69 G5 — closures for the pivot-selector's parent-chain backlink probe. `getCanonicalHeaderByNumber`
    // reads our local canonical chain; `validateHeaderPoW` runs the isolated PoW/difficulty header check
    // (validateHeaderOnly needs no parent). Defined here so the implicit blockchainConfig (from configBuilder)
    // resolves at this call site rather than leaking consensus types into PivotBlockSelector.
    private def getCanonicalHeaderByNumber(number: BigInt): Option[BlockHeader] =
      blockchainReader.getBlockHeaderByNumber(number)

    private def validateHeaderPoW(header: BlockHeader): Boolean =
      validators.blockHeaderValidator.validateHeaderOnly(header).isRight

    def startWithState(syncState: SyncState): Behavior[Command] =
      // Check if headers in RocksDB go beyond the persisted bestBlockHeaderNumber.
      // This happens when SyncState was persisted mid-download but the node restarted —
      // headers continued being written to RocksDB past the last SyncState snapshot.
      val existingBestHeader = blockchainReader.getBestBlockNumber
      val updatedState =
        if existingBestHeader > syncState.bestBlockHeaderNumber && existingBestHeader <= syncState.pivotBlock.number.value
        then
          log.info(
            "Headers in database ({}) ahead of persisted sync state ({}). Advancing to skip redundant download.",
            existingBestHeader,
            syncState.bestBlockHeaderNumber
          )
          syncState.copy(
            bestBlockHeaderNumber = existingBestHeader,
            lastFullBlockNumber = existingBestHeader.max(syncState.lastFullBlockNumber)
          )
        else syncState

      log.info("Starting fast sync with existing state and asking for new pivot block")
      initSyncSession(updatedState)
      askForPivotBlockUpdate(SyncRestart)

    def startFromScratch(): Behavior[Command] =
      log.info("Starting fast sync from scratch")
      val pivotBlockSelector = ctx
        .spawn(
          Behaviors
            .supervise(
              PivotBlockSelector(
                networkPeerManager,
                peerEventBus,
                syncConfig,
                pivotResultAdapter,
                pivotFailedAdapter,
                blacklist,
                () => ourBestTotalDifficulty(),
                getCanonicalHeaderByNumber,
                validateHeaderPoW
              )
            )
            // PR #1378: pivot selection must keep retrying until a pivot is found — backoff, no cap.
            .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, 0.1)),
          "pivot-block-selector"
        )
      pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
      waitingForPivotBlock()

    def waitingForPivotBlock(): Behavior[Command] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) then Behaviors.same
      else
        msg match
          case GetStatusCmd(replyTo) => replyTo ! SyncProtocol.Status.NotSyncing; Behaviors.same
          case RetryPivotBlockSelection =>
            log.info("Retrying pivot block selection")
            val pivotBlockSelector = ctx
              .spawn(
                Behaviors
                  .supervise(
                    PivotBlockSelector(
                      networkPeerManager,
                      peerEventBus,
                      syncConfig,
                      pivotResultAdapter,
                      pivotFailedAdapter,
                      blacklist,
                      () => ourBestTotalDifficulty(),
                      getCanonicalHeaderByNumber,
                      validateHeaderPoW
                    )
                  )
                  // PR #1378: pivot selection must keep retrying until a pivot is found — backoff, no cap.
                  .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, 0.1)),
                s"pivot-block-selector-retry-${java.util.UUID.randomUUID()}"
              )
            pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
            Behaviors.same
          case PivotSelectionFailed =>
            log.warn(
              "Pivot block selection failed after maximum attempts. Retrying in {}",
              startRetryInterval
            )
            timers.startSingleTimer(RetryPivotBlockSelection, startRetryInterval)
            Behaviors.same
          case WrappedSyncProtocol(msg: SyncProtocol.GetStatus) =>
            ctx.self ! GetStatusCmd(msg.replyTo); Behaviors.same
          case WrappedPivotResult(PivotBlockSelector.Result(pivotBlockHeader)) =>
            if pivotBlockHeader.number < BlockNumber(1L) then
              log.info("Unable to start block synchronization in fast mode: pivot block is less than 1")
              // Don't give up — peers may not have been fork-validated yet at startup.
              // Retry pivot selection after a delay instead of marking fast sync done.
              log.info("Retrying pivot selection in {} (peers may still be connecting)", startRetryInterval)
              timers.startSingleTimer(RetryPivotBlockSelection, startRetryInterval)
              Behaviors.same
            else
              // Check if headers already exist in RocksDB from a previous sync run.
              // This avoids re-downloading millions of headers that survived a restart.
              val existingBestHeader = blockchainReader.getBestBlockNumber
              val bootstrappedHeaderNumber =
                if existingBestHeader > 0 && existingBestHeader <= pivotBlockHeader.number.value then
                  log.info(
                    "Found existing headers in database up to block {}. Skipping redundant header download.",
                    existingBestHeader
                  )
                  existingBestHeader
                else BigInt(0)

              val initialSyncState =
                SyncState(
                  pivotBlockHeader,
                  safeDownloadTarget = pivotBlockHeader.number.value + syncConfig.fastSyncBlockValidationX,
                  bestBlockHeaderNumber = bootstrappedHeaderNumber,
                  lastFullBlockNumber = bootstrappedHeaderNumber
                )
              initSyncSession(initialSyncState)
              val b = syncing()
              processSyncing()
              b
          case _ => Behaviors.same
    }

    private val actorCounter = new AtomicInteger
    private def countActor: Int = actorCounter.incrementAndGet

    // ── Syncing session state (was the inner SyncingHandler class) ─────────────────────────────────
    // There is at most one sync session per FastSync lifetime. All 13 post-init fields are encapsulated
    // in SyncSession and held behind Option to eliminate null-initialization and guard against stale
    // messages arriving before initSyncSession() establishes the session. (C1 + W8 fix)

    private val headerResponseBuffer = mutable.SortedMap.empty[BigInt, Seq[BlockHeader]]

    /** All mutable state that is valid only after initSyncSession() has run. Holding it behind Option[SyncSession]
      * means any handler that receives a message before the session is established returns Behaviors.same cleanly
      * instead of throwing a NullPointerException.
      */
    private var session: Option[SyncSession] = None

    /** Mutate the current session in-place; no-op if the session has not been initialised. */
    private def updateSession(f: SyncSession => SyncSession): Unit =
      session = session.map(f)

    private val PersistTimerKey = "persist-sync-state"
    private val PrintStatusTimerKey = "print-status"
    private val HeartBeatTimerKey = "heart-beat"

    private val startTime: Long = System.currentTimeMillis()
    private def totalMinutesTaken(): Long = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - startTime)

    // Progress logging state (for rates)
    private var lastProgressLogMs: Long = startTime
    private var lastLoggedFullBlock: BigInt = 0
    private var lastLoggedStateNodes: Long = 0

    /** Initialise the per-session state on first pivot establishment: state, fetcher queues, child actors, and the
      * persist/print/heartbeat timers. Replaces the work the Classic `SyncingHandler` constructor did.
      */
    private def initSyncSession(initial: SyncState): Unit =
      val storageActor: TypedActorRef[StateStorageActor.Command] = ctx
        .spawn(
          Behaviors
            .supervise(StateStorageActor())
            // PR #1378: state storage must stay alive for sync to complete — backoff, no cap.
            .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)),
          s"$countActor-state-storage",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
      storageActor ! StateStorageActor.Init(fastSyncStateStorage)

      // SyncStateSchedulerActor (Group S4, narrowed S4) is a Typed Behavior (Behavior[Command]); spawn via ctx.spawn.
      // We send it StartSyncingTo / RestartRequested and it replies with messages matched in our Behavior[Command] states.
      // §7c-E3: restartWithBackoff bounds the re-request storm on restart. On restart, all in-flight peer assignments
      // are lost and re-requested, but PeerResponseTimeout per-request and the fresh DownloaderState (starts from zero)
      // limit the burst. Backoff: min 5s, max 60s, 0.3 jitter.
      // PR #1378: SyncStateSchedulerActor is a sync backbone actor — no `.withMaxRestarts` cap. A cap would silently
      // stop the state-sync scheduler forever (FastSync stalls and never completes), the exact failure validated on
      // Mordor. The backoff alone bounds the restart rate.
      val scheduler = ctx
        .spawn(
          Behaviors
            .supervise(
              SyncStateSchedulerActor.behavior(
                SyncStateScheduler(
                  blockchainReader,
                  evmCodeStorage,
                  stateStorage,
                  nodeStorage,
                  syncConfig.stateSyncBloomFilterSize
                ),
                syncConfig,
                networkPeerManager,
                peerEventBus,
                blacklist,
                schedulerResponseAdapter,
                stateSyncStatsAdapter
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(5.seconds, 60.seconds, 0.3)
            ),
          s"$countActor-state-scheduler"
        )

      val bodiesQ = new BodiesFetcherQueue(ethRateTracker)
      val receiptsQ = new ReceiptsFetcherQueue(ethRateTracker)
      bodiesQ.enqueue(initial.blockBodiesQueue)
      receiptsQ.enqueue(initial.receiptsQueue)

      session = Some(
        SyncSession(
          syncState = initial,
          initialLastFullBlockNumber = initial.lastFullBlockNumber,
          assignedHandlers = Set.empty,
          requestedBlockBodies = Map.empty,
          requestedReceipts = Map.empty,
          bodiesFetcherQueue = bodiesQ,
          receiptsFetcherQueue = receiptsQ,
          headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker),
          headerQueueHighWatermark = initial.bestBlockHeaderNumber,
          syncStateStorageActor = storageActor,
          syncStateScheduler = scheduler,
          stateSyncRestartRequested = false,
          stateSyncStarted = false
        )
      )

      lastProgressLogMs = startTime
      lastLoggedFullBlock = initial.lastFullBlockNumber
      lastLoggedStateNodes = initial.downloadedNodesCount

      // Persist delay should be 0, as the presence of it marks that fast sync was started.
      timers.startTimerWithFixedDelay(PersistTimerKey, PersistSyncState, persistStateSnapshotInterval)
      timers.startTimerWithFixedDelay(PrintStatusTimerKey, PrintStatus, printStatusInterval)
      timers.startTimerWithFixedDelay(HeartBeatTimerKey, ProcessSyncing, syncRetryInterval * 2)

    def handleStatus(msg: Command): Boolean = msg match
      case WrappedSyncProtocol(msg: SyncProtocol.GetStatus) =>
        ctx.self ! GetStatusCmd(msg.replyTo)
        true
      case GetStatusCmd(replyTo) =>
        replyTo ! currentSyncingStatus
        true
      case WrappedStateSyncStats(SyncStateSchedulerActor.StateSyncStats(saved, missing)) =>
        val total = saved + missing
        // Track high-water mark so resume after a JVM restart doesn't lose the discovered
        // scope. Used by the "95% complete" guard in processSyncing — see comment on
        // SyncState.maxTotalNodesCount.
        //
        // Bootstrap consideration: legacy persisted SyncState from before maxTotalNodesCount
        // existed deserializes with maxTotalNodesCount=0. The OLD totalNodesCount field is
        // still present and reflects the pre-bounce peak, so seed the high-water mark from
        // it on the first tick after resume — otherwise newMax would be initialized from
        // the post-restart freshly-walked scope (small) and the very bug this fix targets
        // would still bite on the first restart after a legacy upgrade.
        updateSession { s =>
          val newMax = math.max(
            math.max(s.syncState.maxTotalNodesCount, s.syncState.totalNodesCount),
            total
          )
          s.copy(syncState =
            s.syncState.copy(
              downloadedNodesCount = saved,
              totalNodesCount = total,
              maxTotalNodesCount = newMax
            )
          )
        }
        true
      case _ => false

    def syncing(): Behavior[Command] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) || handleStatus(msg) then Behaviors.same
      else
        session match
          case None => Behaviors.same
          case Some(_) =>
            msg match
              case WrappedPrhResult(PeerRequestHandler.RequestFailed(_, peer, reason)) =>
                handleRequestFailure(peer, FastSyncRequestFailed(reason))
                Behaviors.same
              case RequestTerminated(handler) =>
                updateSession(s => s.copy(assignedHandlers = s.assignedHandlers - handler))
                Behaviors.same
              case WrappedPrhResult(r: ResponseReceived[?]) =>
                handleResponses(r)
              case UpdatePivotBlock(reason) => updatePivotBlock(reason)
              case WrappedSchedulerResponse(WaitingForNewTargetBlock) =>
                log.debug("State sync stopped until receiving new pivot block")
                updatePivotBlock(ImportedLastBlock)
              case ProcessSyncing   => processSyncing()
              case PrintStatus      => printStatus(); Behaviors.same
              case PersistSyncState => persistSyncState(); Behaviors.same
              case WrappedSchedulerResponse(StateSyncFinished) =>
                updateSession(s => s.copy(syncState = s.syncState.copy(stateSyncFinished = true)))
                processSyncing()
              case WrappedSchedulerResponse(SyncStateSchedulerActor.NetworkIncompatible) =>
                log.warn(
                  "State scheduler reports no ETH63-67 peers available (ETH68-only network). " +
                    "Fast sync cannot use GetNodeData. Requesting fallback to SNAP sync."
                )
                cleanup()
                syncController ! FallbackToSnapSync
                Behaviors.stopped
              case FatalError(reason) =>
                log.error("FastSync fatal error: {}. Stopping actor.", reason)
                org.apache.pekko.actor
                  .CoordinatedShutdown(ctx.system)
                  .run(
                    org.apache.pekko.actor.CoordinatedShutdown.UnknownReason
                  )
                Behaviors.stopped
              case _ => Behaviors.same
    }

    private def handleResponses(result: ResponseReceived[?]): Behavior[Command] = result match
      case ResponseReceived(_, peer, blockHeadersMsg: ETHPackets.BlockHeaders, timeTaken) =>
        log.debug(
          "Received {} block headers from peer [{}] in {} ms",
          blockHeadersMsg.headers.size,
          peer.id,
          timeTaken
        )
        FastSyncMetrics.setBlockHeadersDownloadTime(timeTaken)
        session match
          case None => Behaviors.same
          case Some(s) =>
            handshakedPeers.get(peer.id) match
              case Some(peerWithInfo) =>
                s.headersFetcherQueue.deliver(peerWithInfo, blockHeadersMsg, timeTaken) match
                  case DeliveryResult.Delivered(_) =>
                    if blockHeadersMsg.headers.nonEmpty then
                      headerResponseBuffer.put(blockHeadersMsg.headers.head.number.value, blockHeadersMsg.headers)
                      drainOrderedHeaders(peer)
                    else
                      blacklist.add(peer.id, blacklistDuration, WrongBlockHeaders)
                      Behaviors.same
                  case DeliveryResult.Invalid(reason) =>
                    log.warn("Header delivery rejected for peer [{}]: {}", peer.id, reason)
                    blacklist.add(peer.id, blacklistDuration, WrongBlockHeaders)
                    Behaviors.same
                  case DeliveryResult.Duplicate =>
                    log.debug("Duplicate/stale header response from peer [{}], ignoring", peer.id)
                    Behaviors.same
              case None =>
                log.debug("Received block headers from unknown peer [{}], ignoring", peer.id)
                Behaviors.same

      case ResponseReceived(_, peer, blockBodiesMsg: ETHPackets.BlockBodies, timeTaken) =>
        session match
          case None => Behaviors.same
          case Some(s) =>
            handshakedPeers.get(peer.id) match
              case Some(peerWithInfo) => s.bodiesFetcherQueue.deliver(peerWithInfo, blockBodiesMsg, timeTaken)
              case None =>
                ethRateTracker.update(
                  peer.id.value,
                  PeerRateTracker.MsgGetBlockBodies,
                  timeTaken,
                  blockBodiesMsg.bodies.size
                )
            log.debug(
              "Received {} block bodies from peer [{}] in {} ms",
              blockBodiesMsg.bodies.size,
              peer.id,
              timeTaken
            )
            FastSyncMetrics.setBlockBodiesDownloadTime(timeTaken)

            val requestedBodies = s.requestedBlockBodies.getOrElse(peer.id, Nil)
            updateSession(s => s.copy(requestedBlockBodies = s.requestedBlockBodies - peer.id))
            handleBlockBodies(peer, requestedBodies, blockBodiesMsg.bodies)
      case ResponseReceived(_, peer, receipts68: ETHPackets.Receipts68, timeTaken) =>
        session match
          case None => Behaviors.same
          case Some(s) =>
            handshakedPeers.get(peer.id) match
              case Some(peerWithInfo) => s.receiptsFetcherQueue.deliver(peerWithInfo, receipts68, timeTaken)
              case None =>
                ethRateTracker.update(
                  peer.id.value,
                  PeerRateTracker.MsgGetReceipts,
                  timeTaken,
                  receipts68.receiptsForBlocks.items.size
                )
            // Convert ETH66 RLPList format to Seq[Seq[Receipt]] expected by handleReceipts.
            // NOTE: Typed receipts (EIP-2718-style) can arrive on the wire as
            //   RLPValue(typeByte || rlp(payload))
            // so we must expand them before calling toTypedRLPEncodables/toReceipt.
            val receipts: Seq[Seq[Receipt]] =
              import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
              import ETHPackets.TypedTransaction.*
              import com.chipprbots.ethereum.rlp.{RLPEncodeable, RLPValue, rawDecode}

              def expandTypedReceipts(items: Seq[RLPEncodeable]): Seq[RLPEncodeable] =
                items.flatMap {
                  case v: RLPValue =>
                    val receiptBytes = v.bytes
                    if receiptBytes.isEmpty then Seq(v)
                    else
                      val first = receiptBytes(0)
                      // Typed receipt in wire format: RLPValue(typeByte || rlp(payload))
                      // Expand it to Seq(RLPValue(typeByte), RLPList(payload)) for toTypedRLPEncodables.
                      // rawDecode may fail on malformed wire data; fall back to passthrough on any failure.
                      if (first & 0xff) < 0x7f && receiptBytes.length > 1 then
                        scala.util
                          .Try(rawDecode(receiptBytes.tail))
                          .fold(_ => Seq(v), decoded => Seq(RLPValue(Array(first)), decoded))
                      else Seq(v)
                  case other => Seq(other)
                }

              receipts68.receiptsForBlocks.items.flatMap {
                case r: RLPList =>
                  Some(expandTypedReceipts(r.items).toTypedRLPEncodables.map(_.toReceipt))
                case other =>
                  log.warn(
                    "Unexpected RLP item type in Receipts68 from peer [{}]: {}",
                    peer.id,
                    other.getClass.getSimpleName
                  )
                  None
              }
            log.debug("Received {} receipts (ETH66) from peer [{}] in {} ms", receipts.size, peer.id, timeTaken)
            FastSyncMetrics.setBlockReceiptsDownloadTime(timeTaken)

            val requestedHashes = s.requestedReceipts.getOrElse(peer.id, Nil)
            updateSession(s => s.copy(requestedReceipts = s.requestedReceipts - peer.id))
            handleReceipts(peer, requestedHashes, receipts)

      case ResponseReceived(_, peer, other, _) =>
        log.debug(
          "Received unexpected response type {} from peer [{}], ignoring",
          other.getClass.getSimpleName,
          peer.id
        )
        Behaviors.same

    def askForPivotBlockUpdate(updateReason: PivotBlockUpdateReason): Behavior[Command] =
      updateSession(s => s.copy(syncState = s.syncState.copy(updatingPivotBlock = true)))
      log.debug("Asking for new pivot block")
      val pivotBlockSelector =
        ctx
          .spawn(
            Behaviors
              .supervise(
                PivotBlockSelector(
                  networkPeerManager,
                  peerEventBus,
                  syncConfig,
                  pivotResultAdapter,
                  pivotFailedAdapter,
                  blacklist,
                  () => ourBestTotalDifficulty(),
                  getCanonicalHeaderByNumber,
                  validateHeaderPoW
                )
              )
              // PR #1378: pivot selection must keep retrying until a pivot is found — backoff, no cap.
              .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, 0.1)),
            s"$countActor-pivot-block-selector-update"
          )
      pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
      waitingForPivotBlockUpdate(updateReason)

    private def newPivotIsGoodEnough(
        newPivot: BlockHeader,
        currentState: SyncState,
        updateReason: PivotBlockUpdateReason
    ): Boolean =
      def stalePivotAfterRestart: Boolean =
        newPivot.number == currentState.pivotBlock.number && updateReason.isSyncRestart
      newPivot.number >= currentState.pivotBlock.number && !stalePivotAfterRestart

    /** Suspends header dispatch while `FastSyncBranchResolverActor` binary-searches for the highest common ancestor.
      * The resolver discards diverged blocks itself; on `BranchResolvedSuccessful` we reset in-memory tracking to
      * match, then resume. On `BranchResolutionFailed` we fall back to the N-block blind rewind.
      */
    def waitingForBranchResolution(): Behavior[Command] =
      Behaviors.receiveMessage { msg =>
        if handlePeerList(msg) || handleStatus(msg) then Behaviors.same
        else
          session match
            case None => Behaviors.same
            case Some(s) =>
              msg match
                case WrappedBranchResolverResponse(
                      FastSyncBranchResolverActor.BranchResolvedSuccessful(highestCommonBlock, _)
                    ) =>
                  log.info(
                    "Branch resolution completed: highest common block is {}. Resetting cursor and resuming fast sync.",
                    highestCommonBlock
                  )
                  // The resolver already removed diverged blocks from the DB; realign in-memory state.
                  headerResponseBuffer.clear()
                  updateSession(s =>
                    s.copy(
                      syncState = s.syncState.copy(
                        bestBlockHeaderNumber = highestCommonBlock,
                        blockBodiesQueue = Seq.empty,
                        receiptsQueue = Seq.empty,
                        nextBlockToFullyValidate = (highestCommonBlock + 1).max(1)
                      ),
                      bodiesFetcherQueue = new BodiesFetcherQueue(ethRateTracker),
                      receiptsFetcherQueue = new ReceiptsFetcherQueue(ethRateTracker),
                      headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker),
                      headerQueueHighWatermark = highestCommonBlock
                    )
                  )
                  val b = syncing()
                  processSyncing()
                  b

                case WrappedBranchResolverResponse(FastSyncBranchResolverActor.BranchResolutionFailed(failure)) =>
                  log.warn(
                    "Branch resolution failed ({}). Falling back to {}-block rewind from {}.",
                    failure,
                    syncConfig.fastSyncBlockValidationN,
                    s.syncState.bestBlockHeaderNumber
                  )
                  val rewindTarget =
                    (s.syncState.bestBlockHeaderNumber - syncConfig.fastSyncBlockValidationN - 1).max(0)
                  headerResponseBuffer.clear()
                  updateSession(s =>
                    s.copy(
                      syncState = s.syncState.copy(
                        blockBodiesQueue = Seq.empty,
                        receiptsQueue = Seq.empty,
                        bestBlockHeaderNumber = rewindTarget,
                        nextBlockToFullyValidate = (rewindTarget + 1).max(1)
                      ),
                      bodiesFetcherQueue = new BodiesFetcherQueue(ethRateTracker),
                      receiptsFetcherQueue = new ReceiptsFetcherQueue(ethRateTracker),
                      headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker),
                      headerQueueHighWatermark = rewindTarget
                    )
                  )
                  val b = syncing()
                  processSyncing()
                  b

                case WrappedPrhResult(PeerRequestHandler.RequestFailed(_, peer, reason)) =>
                  handleRequestFailure(peer, FastSyncRequestFailed(reason))
                  Behaviors.same
                case RequestTerminated(handler) =>
                  updateSession(s => s.copy(assignedHandlers = s.assignedHandlers - handler))
                  Behaviors.same
                case _ => Behaviors.same
      }

    def waitingForPivotBlockUpdate(updateReason: PivotBlockUpdateReason): Behavior[Command] =
      Behaviors.receiveMessage { msg =>
        if handlePeerList(msg) || handleStatus(msg) then Behaviors.same
        else
          session match
            case None => Behaviors.same
            case Some(s) =>
              msg match
                case WrappedPrhResult(PeerRequestHandler.RequestFailed(_, peer, reason)) =>
                  handleRequestFailure(peer, FastSyncRequestFailed(reason))
                  Behaviors.same
                case RequestTerminated(handler) =>
                  updateSession(s => s.copy(assignedHandlers = s.assignedHandlers - handler))
                  Behaviors.same
                case PivotSelectionFailed =>
                  log.warn(
                    "Pivot block selection failed after maximum attempts during update. Continuing with current pivot."
                  )
                  updateSession(s => s.copy(syncState = s.syncState.copy(updatingPivotBlock = false)))
                  // On SyncRestart, the state scheduler is in idle state waiting for StartSyncingTo.
                  // Send it the existing pivot's state root so it doesn't deadlock.
                  if updateReason.isSyncRestart then
                    // re-read session after updateSession above
                    session.foreach { s2 =>
                      log.info(
                        "SyncRestart: sending existing pivot state root to state scheduler (block {})",
                        s2.syncState.pivotBlock.number
                      )
                      updateSession(s => s.copy(stateSyncStarted = true))
                      s2.syncStateScheduler ! StartSyncingTo(
                        s2.syncState.pivotBlock.stateRoot,
                        s2.syncState.pivotBlock.number.value
                      )
                    }
                  // Mirror the Classic `context.become(this.receive); processSyncing()`: transition out of the
                  // pivot-update wait into `syncing()` (which handles ResponseReceived) and run processSyncing() for
                  // its dispatch side effects. processSyncing() returns Behaviors.same here, so the explicit `syncing()`
                  // is what takes effect — without it the actor would stay in waitingForPivotBlockUpdate and drop the
                  // header/body/receipt responses.
                  val b = syncing()
                  processSyncing()
                  b

                case WrappedPivotResult(PivotBlockSelector.Result(pivotBlockHeader))
                    if newPivotIsGoodEnough(pivotBlockHeader, s.syncState, updateReason) =>
                  log.debug("New pivot block with number {} received", pivotBlockHeader.number)
                  updatePivotSyncState(updateReason, pivotBlockHeader)
                  // See SelectionFailed above: become syncing() before running processSyncing()'s side effects so the
                  // subsequently-arriving PeerRequestHandler responses are handled instead of dropped.
                  val b = syncing()
                  processSyncing()
                  b

                case WrappedPivotResult(PivotBlockSelector.Result(pivotBlockHeader))
                    if !newPivotIsGoodEnough(pivotBlockHeader, s.syncState, updateReason) =>
                  log.debug("Received pivot block is older than old one, re-scheduling asking for new one")
                  reScheduleAskForNewPivot(updateReason)
                  Behaviors.same

                case PersistSyncState => persistSyncState(); Behaviors.same

                case UpdatePivotBlock(state) => updatePivotBlock(state)

                case FatalError(reason) =>
                  log.error("FastSync fatal error: {}. Stopping actor.", reason)
                  org.apache.pekko.actor
                    .CoordinatedShutdown(ctx.system)
                    .run(
                      org.apache.pekko.actor.CoordinatedShutdown.UnknownReason
                    )
                  Behaviors.stopped

                case _ => Behaviors.same
      }

    private def reScheduleAskForNewPivot(updateReason: PivotBlockUpdateReason): Unit =
      updateSession(s =>
        s.copy(syncState = s.syncState.copy(pivotBlockUpdateFailures = s.syncState.pivotBlockUpdateFailures + 1))
      )
      ctx.scheduleOnce(syncConfig.pivotBlockReScheduleInterval, ctx.self, UpdatePivotBlock(updateReason))

    def currentSyncingStatus: SyncProtocol.Status =
      session match
        case None =>
          SyncProtocol.Status.NotSyncing
        case Some(s) =>
          SyncProtocol.Status.Syncing(
            s.initialLastFullBlockNumber,
            Progress(s.syncState.lastFullBlockNumber, s.syncState.pivotBlock.number.value),
            Some(
              Progress(s.syncState.downloadedNodesCount, s.syncState.totalNodesCount.max(1))
            ) // There's always at least one state root to fetch
          )

    private def updatePivotBlock(updateReason: PivotBlockUpdateReason): Behavior[Command] =
      session match
        case None => Behaviors.same
        case Some(s) =>
          if s.syncState.pivotBlockUpdateFailures <= syncConfig.maximumTargetUpdateFailures then
            if s.assignedHandlers.nonEmpty || s.syncState.blockChainWorkQueued then
              log.debug("Still waiting for some responses, rescheduling pivot block update")
              ctx.scheduleOnce(1.second, ctx.self, UpdatePivotBlock(updateReason))
              processSyncing()
            else askForPivotBlockUpdate(updateReason)
          else
            log.warn("Sync failure! Number of pivot block update failures reached maximum.")
            ctx.self ! FatalError("max pivot update attempts exceeded")
            Behaviors.same

    private def updatePivotSyncState(updateReason: PivotBlockUpdateReason, pivotBlockHeader: BlockHeader): Unit =
      session.foreach { s =>
        updateReason match
          case ImportedLastBlock =>
            if (pivotBlockHeader.number - s.syncState.pivotBlock.number).value <= syncConfig.maxTargetDifference then
              log.debug("Current pivot block is fresh enough, starting state download.")
              // Empty root has means that there were no transactions in blockchain, and Mpt trie is empty
              // Asking for this root would result only with empty transactions
              if s.syncState.pivotBlock.stateRoot.value == ByteString(MerklePatriciaTrie.EmptyRootHash) then
                updateSession(s =>
                  s.copy(syncState = s.syncState.copy(stateSyncFinished = true, updatingPivotBlock = false))
                )
              else
                updateSession(s =>
                  s.copy(
                    syncState = s.syncState.copy(updatingPivotBlock = false),
                    stateSyncRestartRequested = false,
                    stateSyncStarted = true
                  )
                )
                session.foreach(
                  _.syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number.value)
                )
            else
              updateSession(s =>
                s.copy(
                  syncState = s.syncState.updatePivotBlock(
                    pivotBlockHeader,
                    syncConfig.fastSyncBlockValidationX,
                    updateFailures = false
                  ),
                  stateSyncRestartRequested = false,
                  stateSyncStarted = true
                )
              )
              session.foreach { s2 =>
                log.info(
                  "Pivot block updated to {}, new safe target {}. Resuming state download with new root.",
                  pivotBlockHeader.number,
                  s2.syncState.safeDownloadTarget
                )
                // Always restart state download with new pivot — even if the jump is large.
                // The state scheduler's bloom filter handles nodes already downloaded.
                s2.syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number.value)
              }

          case LastBlockValidationFailed =>
            log.debug(
              "Changing pivot block after failure, to {}, new safe target is {}",
              pivotBlockHeader.number,
              s.syncState.safeDownloadTarget
            )
            updateSession(s =>
              s.copy(syncState =
                s.syncState
                  .updatePivotBlock(pivotBlockHeader, syncConfig.fastSyncBlockValidationX, updateFailures = true)
              )
            )

          case SyncRestart =>
            // in case of node restart we are sure that new pivotBlockHeader > current pivotBlockHeader
            updateSession(s =>
              s.copy(syncState =
                s.syncState.updatePivotBlock(
                  pivotBlockHeader,
                  syncConfig.fastSyncBlockValidationX,
                  updateFailures = false
                )
              )
            )
            session.foreach { s2 =>
              log.info(
                "SyncRestart: pivot block updated to {}, safe target {}, starting state download",
                pivotBlockHeader.number,
                s2.syncState.safeDownloadTarget
              )
              // Start the state scheduler — without this, state download never begins after restart.
              if !s2.syncState.stateSyncFinished && pivotBlockHeader.stateRoot.value != ByteString(
                  MerklePatriciaTrie.EmptyRootHash
                )
              then
                updateSession(s => s.copy(stateSyncRestartRequested = false, stateSyncStarted = true))
                s2.syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number.value)
            }
      }

    private def discardLastBlocks(startBlock: BigInt, blocksToDiscard: Int): Unit =
      (startBlock to ((startBlock - blocksToDiscard).max(1)) by -1).foreach { n =>
        blockchainReader.getBlockHeaderByNumber(n).foreach { headerToRemove =>
          blockchain.removeBlock(headerToRemove.hash)
        }
      }

    private def validateHeader(header: BlockHeader, peer: Peer): Either[HeaderProcessingResult, BlockHeader] =
      val shouldValidate = session.exists(s => header.number.value >= s.syncState.nextBlockToFullyValidate)

      if shouldValidate then
        validators.blockHeaderValidator.validate(
          header,
          h => blockchainReader.getBlockHeaderByHash(BlockHash(h))
        ) match
          case Right(_) =>
            updateValidationState(header)
            Right(header)

          case Left(error) =>
            log.warn("Block header validation failed during fast sync at block {}: {}", header.number, error)
            Left(ValidationFailed(header, peer))
      else Right(header)

    private def updateSyncState(header: BlockHeader, parentWeight: ChainWeight): Unit =
      blockchainWriter
        .storeBlockHeader(header)
        .and(blockchainWriter.storeChainWeight(header.hash, parentWeight.increase(header)))
        .commit()

      updateSession { s =>
        val s2 =
          if header.number.value > s.syncState.bestBlockHeaderNumber then
            s.copy(syncState = s.syncState.copy(bestBlockHeaderNumber = header.number.value))
          else s
        s2.copy(syncState =
          s2.syncState
            .enqueueBlockBodies(Seq(header.hash.value))
            .enqueueReceipts(Seq(header.hash.value))
        )
      }
      session.foreach { s =>
        s.bodiesFetcherQueue.enqueue(Seq(header.hash.value))
        s.receiptsFetcherQueue.enqueue(Seq(header.hash.value))
      }

    private def updateValidationState(header: BlockHeader): Unit =
      import syncConfig.{fastSyncBlockValidationK as K, fastSyncBlockValidationX as X}
      updateSession(s => s.copy(syncState = s.syncState.updateNextBlockToValidate(header, K, X)))

    private def handleRewind(
        header: BlockHeader,
        peer: Peer,
        N: Int,
        duration: FiniteDuration,
        continueSyncing: Boolean = true
    ): Behavior[Command] =
      blacklist.add(peer.id, duration, BlockHeaderValidationFailed)
      session match
        case None => Behaviors.same
        case Some(s) =>
          if header.number.value <= s.syncState.safeDownloadTarget then
            discardLastBlocks(header.number.value, N)
            updateSession(s => s.copy(syncState = s.syncState.updateDiscardedBlocks(header, N)))
            if header.number >= s.syncState.pivotBlock.number then updatePivotBlock(LastBlockValidationFailed)
            else if continueSyncing then processSyncing()
            else Behaviors.same
          else if continueSyncing then processSyncing()
          else Behaviors.same

    // scalastyle:off method.length
    private def handleBlockHeaders(peer: Peer, headers: Seq[BlockHeader]): Behavior[Command] =
      def processHeader(header: BlockHeader): Either[HeaderProcessingResult, (BlockHeader, ChainWeight)] =
        for
          validatedHeader <- validateHeader(header, peer)
          parentWeight <- getParentChainWeight(header)
        yield (validatedHeader, parentWeight)

      def getParentChainWeight(header: BlockHeader) =
        blockchainReader.getChainWeightByHash(header.parentHash).toRight(ParentChainWeightNotFound(header))

      @tailrec
      def processHeaders(headers: Seq[BlockHeader]): HeaderProcessingResult =
        if headers.nonEmpty then
          val header = headers.head
          processHeader(header) match
            case Left(result) => result
            case Right((header, weight)) =>
              updateSyncState(header, weight)
              if session.exists(s => header.number.value == s.syncState.safeDownloadTarget) then ImportedPivotBlock
              else processHeaders(headers.tail)
        else HeadersProcessingFinished

      if !checkHeadersChain(headers) then
        blacklist.add(peer.id, blacklistDuration, ErrorInBlockHeaders)
        processSyncing()
      else
        processHeaders(headers) match
          case ParentChainWeightNotFound(header) =>
            log.warn(
              "Parent chain weight not found for block {} (parent: {}). " +
                "Spawning branch resolver to locate common ancestor.",
              header.idTag,
              header.parentHash
            )
            blacklist.add(peer.id, syncConfig.blacklistDuration, BlockHeaderValidationFailed)
            val resolver = ctx.spawn(
              Behaviors
                .supervise(
                  FastSyncBranchResolverActor(
                    replyTo = ctx.messageAdapter[FastSyncBranchResolverActor.BranchResolverResponse](
                      WrappedBranchResolverResponse(_)
                    ),
                    peerEventBus = peerEventBus,
                    networkPeerManager = networkPeerManager,
                    blockchain = blockchain,
                    blockchainReader = blockchainReader,
                    blacklist = blacklist,
                    syncConfig = syncConfig
                  )
                )
                // PR #1378: branch resolution is task-scoped (stops itself on completion); backoff
                // avoids a tight crash loop. No cap — re-spawn cadence is bounded by the parent.
                .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)),
              s"fast-sync-branch-resolver-${java.util.UUID.randomUUID()}"
            )
            resolver ! FastSyncBranchResolverActor.StartBranchResolver
            waitingForBranchResolution()
          case HeadersProcessingFinished =>
            processSyncing()
          case ImportedPivotBlock =>
            updatePivotBlock(ImportedLastBlock)
          case ValidationFailed(header, peerToBlackList) =>
            log.warn("validation of header {} failed", header.idTag)
            // pow validation failure indicate that either peer is malicious or it is on wrong fork
            handleRewind(
              header,
              peerToBlackList,
              syncConfig.fastSyncBlockValidationN,
              syncConfig.criticalBlacklistDuration
            )

    private def handleBlockBodies(
        peer: Peer,
        requestedHashes: Seq[ByteString],
        blockBodies: Seq[BlockBody]
    ): Behavior[Command] =
      if blockBodies.isEmpty then
        val knownHashes = requestedHashes.map(ByteStringUtils.hash2string)
        blacklist.add(peer.id, blacklistDuration, EmptyBlockBodies(knownHashes))
        updateSession(s => s.copy(syncState = s.syncState.enqueueBlockBodies(requestedHashes)))
        session.foreach(_.bodiesFetcherQueue.enqueue(requestedHashes))
      else
        validateBlocks(requestedHashes, blockBodies) match
          case BlockBodyValidationResult.Valid =>
            insertBlocks(requestedHashes, blockBodies)
          case BlockBodyValidationResult.Invalid =>
            blacklist.add(peer.id, blacklistDuration, BlockBodiesNotMatchingHeaders)
            updateSession(s => s.copy(syncState = s.syncState.enqueueBlockBodies(requestedHashes)))
            session.foreach(_.bodiesFetcherQueue.enqueue(requestedHashes))
          case BlockBodyValidationResult.DbError =>
            redownloadBlockchain()

      processSyncing()

    private def handleReceipts(
        peer: Peer,
        requestedHashes: Seq[ByteString],
        receipts: Seq[Seq[Receipt]]
    ): Behavior[Command] =
      if receipts.isEmpty then
        val knownHashes = requestedHashes.map(ByteStringUtils.hash2string)
        blacklist.add(peer.id, blacklistDuration, EmptyReceipts(knownHashes))
        updateSession(s => s.copy(syncState = s.syncState.enqueueReceipts(requestedHashes)))
        session.foreach(_.receiptsFetcherQueue.enqueue(requestedHashes))
      else
        validateReceipts(requestedHashes, receipts) match
          case ReceiptsValidationResult.Valid(blockHashesWithReceipts) =>
            if blockHashesWithReceipts.isEmpty then
              log.warn(
                "Received receipts from peer [{}] but have no matching requested hashes (unsolicited or late response)",
                peer.id
              )
            else
              blockHashesWithReceipts
                .map { case (hash, receiptsForBlock) =>
                  blockchainWriter.storeReceipts(BlockHash(hash), receiptsForBlock)
                }
                .reduce(_.and(_))
                .commit()

            val receivedHashes = blockHashesWithReceipts.map(_._1)
            updateBestBlockIfNeeded(receivedHashes)

            val remainingReceipts = requestedHashes.drop(receipts.size)
            if remainingReceipts.nonEmpty then
              updateSession(s => s.copy(syncState = s.syncState.enqueueReceipts(remainingReceipts)))
              session.foreach(_.receiptsFetcherQueue.enqueue(remainingReceipts))

          case ReceiptsValidationResult.Invalid(error) =>
            val knownHashes = requestedHashes.map(h => Hex.toHexString(h.toArray[Byte]))
            blacklist.add(peer.id, blacklistDuration, InvalidReceipts(knownHashes, error))
            updateSession(s => s.copy(syncState = s.syncState.enqueueReceipts(requestedHashes)))
            session.foreach(_.receiptsFetcherQueue.enqueue(requestedHashes))

          case ReceiptsValidationResult.DbError =>
            redownloadBlockchain()

      processSyncing()

    private def handleRequestFailure(peer: Peer, reason: BlacklistReason): Unit =
      // The handler ref is pruned from assignedHandlers by the RequestTerminated death-watch when the Classic PRH
      // child stops; here we only return the peer's in-flight work and apply the blacklist.
      val failedBodies = session.map(_.requestedBlockBodies.getOrElse(peer.id, Nil)).getOrElse(Nil)
      val failedReceipts = session.map(_.requestedReceipts.getOrElse(peer.id, Nil)).getOrElse(Nil)

      updateSession(s =>
        s.copy(syncState =
          s.syncState
            .enqueueBlockBodies(failedBodies)
            .enqueueReceipts(failedReceipts)
        )
      )

      // Return items to fetcher queues: unreserve if the peer is still tracked in-flight,
      // otherwise enqueue directly (handles post-redownloadBlockchain orphaned handlers).
      session.foreach { s =>
        if s.bodiesFetcherQueue.inFlight(peer.id).isDefined then s.bodiesFetcherQueue.unreserve(peer.id)
        else if failedBodies.nonEmpty then s.bodiesFetcherQueue.enqueue(failedBodies)

        if s.receiptsFetcherQueue.inFlight(peer.id).isDefined then s.receiptsFetcherQueue.unreserve(peer.id)
        else if failedReceipts.nonEmpty then s.receiptsFetcherQueue.enqueue(failedReceipts)

        s.headersFetcherQueue.unreserve(peer.id)
      }
      updateSession(s =>
        s.copy(
          requestedBlockBodies = s.requestedBlockBodies - peer.id,
          requestedReceipts = s.requestedReceipts - peer.id
        )
      )

      // Peers that close the connection before answering (PEER_REQUEST_DISCONNECTED) get a longer
      // cooldown so they don't immediately rejoin and trigger another GetReceipts dispatch cycle.
      val effectiveDuration = reason match
        case FastSyncRequestFailed("connection closed") => 10.minutes
        case _                                          => blacklistDuration
      blacklistIfHandshaked(peer.id, effectiveDuration, reason)

    /** Restarts download from a few blocks behind the current best block header, as an unexpected DB error happened
      */
    private def redownloadBlockchain(): Unit =
      updateSession { s =>
        val newBestHeader = (s.syncState.bestBlockHeaderNumber - 2 * blockHeadersPerRequest).max(0)
        s.copy(
          syncState = s.syncState.copy(
            blockBodiesQueue = Seq.empty,
            receiptsQueue = Seq.empty,
            // todo adjust the formula to minimize redownloaded block headers
            bestBlockHeaderNumber = newBestHeader
          ),
          // Drop all in-flight + pending items; orphaned handler failures will enqueue to fresh queues.
          bodiesFetcherQueue = new BodiesFetcherQueue(ethRateTracker),
          receiptsFetcherQueue = new ReceiptsFetcherQueue(ethRateTracker),
          headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker),
          headerQueueHighWatermark = newBestHeader
        )
      }
      headerResponseBuffer.clear()
      log.debug("Missing block header for known hash")

    private def persistSyncState(): Unit =
      session.foreach { s =>
        s.syncStateStorageActor ! StateStorageActor.Persist(
          s.syncState.copy(
            blockBodiesQueue = s.requestedBlockBodies.values.flatten.toSeq.distinct ++ s.syncState.blockBodiesQueue,
            receiptsQueue = s.requestedReceipts.values.flatten.toSeq.distinct ++ s.syncState.receiptsQueue
          )
        )
      }

    private def printStatus(): Unit =
      def formatPeerEntry(entry: PeerWithInfo): String = formatPeer(entry.peer)
      def formatPeer(peer: Peer): String =
        s"${com.chipprbots.ethereum.network.getHostName(peer.remoteAddress.getAddress)}:${peer.remoteAddress.getPort}"

      def pct(done: BigInt, total: BigInt): Int =
        if total <= 0 then 0
        else
          val p = ((done.toDouble / total.toDouble) * 100.0).toInt
          p.max(0).min(100)

      def formatRate(valuePerSec: Double): String =
        f"$valuePerSec%.2f/s"

      val nowMs = System.currentTimeMillis()
      val dtSeconds = ((nowMs - lastProgressLogMs).toDouble / 1000.0).max(0.001)

      session.foreach { s =>
        // Prefer the persisted fast-sync view of progress (lastFullBlockNumber), but also show current best.
        val bestBlockNow = blockchainReader.getBestBlockNumber
        val lastFull = s.syncState.lastFullBlockNumber.max(bestBlockNow)

        val deltaBlocks = (lastFull - lastLoggedFullBlock).toDouble
        val blocksPerSec = deltaBlocks / dtSeconds

        val savedNodes = s.syncState.downloadedNodesCount
        val totalNodes = s.syncState.totalNodesCount.max(1)
        val deltaNodes = (savedNodes - lastLoggedStateNodes).toDouble
        val nodesPerSec = deltaNodes / dtSeconds

        val blockTarget = s.syncState.pivotBlock.number.value.max(1)
        val blockPercent = pct(lastFull, blockTarget)
        val nodePercent = (((savedNodes.toDouble / totalNodes.toDouble) * 100.0).toInt).max(0).min(100)

        val overallProgress = blockPercent.toDouble / 100.0

        val phase =
          if !s.syncState.isBlockchainWorkFinished then
            if s.syncState.receiptsQueue.nonEmpty then "Receipts"
            else if s.syncState.blockBodiesQueue.nonEmpty then "BlockBodies"
            else "BlockHeaders"
          else if !s.syncState.stateSyncFinished then "State"
          else "Finalizing"

        val blacklistedIds = blacklist.keys
        log.info(
          s"""|FastSync Progress: phase=$phase, blocks=$lastFull/$blockTarget (${blockPercent}%), state=$savedNodes/$totalNodes (${nodePercent}%),
          |rates=${formatRate(blocksPerSec)} blocks, ${formatRate(
               nodesPerSec
             )} nodes, queues=bodies=${s.syncState.blockBodiesQueue.size}, receipts=${s.syncState.receiptsQueue.size},
              |peers=waiting=${s.assignedHandlers.size}, connected=${handshakedPeers.size}, blacklisted=${blacklistedIds.size}, elapsed=${totalMinutesTaken()}m
              |""".stripMargin.replace("\n", " ")
        )
        log.info(s"${WormToBrainBar.renderKnown(overallProgress)} — FastSync")

        lastProgressLogMs = nowMs
        lastLoggedFullBlock = lastFull
        lastLoggedStateNodes = savedNodes

        log.debug(
          s"""|Connection status: inFlightHandlers({})/
              |handshaked({})
              | blacklisted({})
              |""".stripMargin.replace("\n", " "),
          s.assignedHandlers.map(_.path.name).toSeq.sorted.mkString(", "),
          handshakedPeers.values.toList.map(e => formatPeerEntry(e)).sorted.mkString(", "),
          blacklistedIds.map(_.value).mkString(", ")
        )
      }

    private def insertBlocks(requestedHashes: Seq[ByteString], blockBodies: Seq[BlockBody]): Unit =
      val blockHashesWithBodies = requestedHashes.zip(blockBodies)
      if blockHashesWithBodies.isEmpty then
        log.warn(
          "Received block bodies but have no matching requested hashes (unsolicited or late response)"
        )
      else
        blockHashesWithBodies
          .map { case (hash, body) =>
            blockchainWriter.storeBlockBody(BlockHash(hash), body)
          }
          .reduce(_.and(_))
          .commit()

        val receivedHashes = requestedHashes.take(blockBodies.size)
        updateBestBlockIfNeeded(receivedHashes)
        val remainingBlockBodies = requestedHashes.drop(blockBodies.size)
        if remainingBlockBodies.nonEmpty then
          updateSession(s => s.copy(syncState = s.syncState.enqueueBlockBodies(remainingBlockBodies)))
          session.foreach(_.bodiesFetcherQueue.enqueue(remainingBlockBodies))
      // else blockHashesWithBodies.nonEmpty

    def hasBestBlockFreshEnoughToUpdatePivotBlock(info: PeerInfo, state: SyncState, syncConfig: SyncConfig): Boolean =
      (info.maxBlockNumber - syncConfig.pivotBlockOffset) - state.pivotBlock.number.value >= syncConfig.maxPivotBlockAge

    private def getPeersWithFreshEnoughPivot(
        peers: NonEmptyList[PeerWithInfo],
        state: SyncState,
        syncConfig: SyncConfig
    ): List[(Peer, BigInt)] =
      peers.collect {
        case PeerWithInfo(peer, info) if hasBestBlockFreshEnoughToUpdatePivotBlock(info, state, syncConfig) =>
          (peer, info.maxBlockNumber)
      }

    def noBlockchainWorkRemaining: Boolean =
      session.exists(s => s.syncState.isBlockchainWorkFinished && s.assignedHandlers.isEmpty)

    def notInTheMiddleOfUpdate: Boolean =
      session.forall(s => !(s.syncState.updatingPivotBlock || s.stateSyncRestartRequested))

    def pivotBlockIsStale(): Boolean =
      val peersWithInfo = peersToDownloadFrom.values.toList
      if peersWithInfo.isEmpty then false
      else
        val peerWithBestBlockInNetwork = peersWithInfo.maxBy(_.peerInfo.maxBlockNumber)
        val pivotNumber = session.map(_.syncState.pivotBlock.number.value).getOrElse(BigInt(0))

        val bestPossibleTargetDifferenceInNetwork =
          (peerWithBestBlockInNetwork.peerInfo.maxBlockNumber - syncConfig.pivotBlockOffset) - pivotNumber

        val peersWithTooFreshPossiblePivotBlock =
          session
            .map(s => getPeersWithFreshEnoughPivot(NonEmptyList.fromListUnsafe(peersWithInfo), s.syncState, syncConfig))
            .getOrElse(Nil)

        if peersWithTooFreshPossiblePivotBlock.isEmpty then
          log.debug(
            s"There are no peers with too fresh possible pivot block. " +
              s"Current pivot block is {} blocks behind best possible target",
            bestPossibleTargetDifferenceInNetwork
          )
          false
        else
          val pivotBlockIsStale = peersWithTooFreshPossiblePivotBlock.size >= minPeersToChoosePivotBlock

          log.debug(
            "There are {} peers with possible new pivot block, " +
              "best known pivot in current peer list has number {}",
            peersWithTooFreshPossiblePivotBlock.size,
            peerWithBestBlockInNetwork.peerInfo.maxBlockNumber
          )

          pivotBlockIsStale

    def processSyncing(): Behavior[Command] =
      session match
        case None => Behaviors.same
        case Some(s) =>
          FastSyncMetrics.measure(s.syncState)
          log.debug(
            "Start of processSyncing: {}",
            Map(
              "fullySynced" -> fullySynced,
              "blockchainDataToDownload" -> blockchainDataToDownload,
              "noBlockchainWorkRemaining" -> noBlockchainWorkRemaining,
              "stateSyncFinished" -> s.syncState.stateSyncFinished,
              "notInTheMiddleOfUpdate" -> notInTheMiddleOfUpdate
            )
          )
          // Start state download in parallel with block download — don't wait for blocks to finish.
          // State only depends on the pivot block's state root, which we know from the start.
          if !s.stateSyncStarted && !s.syncState.stateSyncFinished && notInTheMiddleOfUpdate &&
            s.syncState.pivotBlock.stateRoot.value != ByteString(MerklePatriciaTrie.EmptyRootHash)
          then
            log.info(
              "Starting state download in parallel with block download for pivot block {}",
              s.syncState.pivotBlock.number
            )
            updateSession(s => s.copy(stateSyncStarted = true, stateSyncRestartRequested = false))
            session.foreach(
              _.syncStateScheduler ! StartSyncingTo(
                s.syncState.pivotBlock.stateRoot,
                s.syncState.pivotBlock.number.value
              )
            )

          // Refresh pivot for state download when it becomes stale — whether blocks are done or not.
          // The SNAP serve window is ~128 blocks (~28 min on ETC). If state download takes longer,
          // peers stop serving the old root. Refreshing the pivot gives state a fresh root to work with.
          // Reset the restart flag once the pivot update completes (updatingPivotBlock returns to false).
          if s.stateSyncRestartRequested && !s.syncState.updatingPivotBlock then
            updateSession(_.copy(stateSyncRestartRequested = false))
          // Re-read after potential update. Returns Some(pivotUpdateBehavior) if stale, None otherwise.
          // The stale-state branch may transition to waitingForPivotBlockUpdate; the final block may
          // override it (or keep it). The method returns the last-decided behavior.
          val nextBehavior: Behavior[Command] = session
            .flatMap { s2 =>
              if s2.stateSyncStarted && !s2.syncState.stateSyncFinished && !s2.stateSyncRestartRequested &&
                !s2.syncState.updatingPivotBlock
              then
                // Detect stale state root via two signals:
                // 1. pivotBlockIsStale() — peers are ahead of our pivot
                // 2. All peers blacklisted — evidence the root expired (peers return empty for this root)
                val allPeersBlacklisted = peersToDownloadFrom.isEmpty && handshakedPeers.nonEmpty
                if pivotBlockIsStale() || allPeersBlacklisted then
                  log.info(
                    "State root stale (allBlacklisted={}), requesting pivot update for state refresh",
                    allPeersBlacklisted
                  )
                  s2.syncStateScheduler ! RestartRequested
                  updateSession(_.copy(stateSyncRestartRequested = true))
                  Some(askForPivotBlockUpdate(ImportedLastBlock))
                else None
              else None
            }
            .getOrElse(Behaviors.same)

          // When blocks are done and state download is mostly complete but can't converge
          // (remaining nodes change every block), declare state done and let regular sync
          // fetch missing nodes on-demand via resolvingMissingNode.
          //
          // Compare downloaded against the persisted high-water mark of total — NOT against
          // the dynamic `total = saved + currently-queued-missing`. After a JVM restart the
          // scheduler walks the trie from the pivot root and queues only the newly-discovered
          // missing frontier; the dynamic total drops to ≈saved and the percentage looks like
          // 99% even when the trie is genuinely 56% incomplete. Using maxTotalNodesCount keeps
          // the comparison honest across restarts so a node that bounced mid-state-download
          // resumes correctly instead of declaring itself done with a partial trie.
          session.foreach { s3 =>
            if noBlockchainWorkRemaining && !s3.syncState.stateSyncFinished && s3.stateSyncStarted then
              val downloaded = FastSyncMetrics.getDownloadedNodes
              val dynTotal = FastSyncMetrics.getTotalNodes
              val effectiveTotal = math.max(dynTotal, s3.syncState.maxTotalNodesCount)
              val pct = if effectiveTotal > 0 then (downloaded.toDouble / effectiveTotal * 100).toInt else 0
              if pct >= 95 && effectiveTotal > 1000 then
                log.info(
                  "State download at {}% ({}/{}, peak total {}) with blocks complete. " +
                    "Remaining nodes are at the chain tip and change every block. " +
                    "Completing fast sync — regular sync will fetch missing nodes on-demand.",
                  pct,
                  downloaded,
                  effectiveTotal,
                  s3.syncState.maxTotalNodesCount
                )
                updateSession(s => s.copy(syncState = s.syncState.copy(stateSyncFinished = true)))
          }

          if fullySynced then finish()
          else if blockchainDataToDownload then processDownloads()
          else if noBlockchainWorkRemaining && notInTheMiddleOfUpdate then
            session.foreach { s4 =>
              if !s4.syncState.stateSyncFinished then
                if pivotBlockIsStale() then
                  log.debug("Restarting state sync to new pivot block")
                  s4.syncStateScheduler ! RestartRequested
                  updateSession(_.copy(stateSyncRestartRequested = true))
            }
            nextBehavior
          else
            log.debug(
              "No more items to request, waiting for {} responses",
              session.map(_.assignedHandlers.size).getOrElse(0)
            )
            nextBehavior

    def finish(): Behavior[Command] =
      val totalTime = totalMinutesTaken()
      FastSyncMetrics.setFastSyncTotalTimeGauge(totalTime.toDouble)
      log.info("Total time taken for FastSync was {} minutes", totalTime)
      log.info("Block synchronization in fast mode finished, switching to regular mode")

      // We have downloaded to target + fastSyncBlockValidationX, se we must discard those last blocks
      session.foreach(s => discardLastBlocks(s.syncState.safeDownloadTarget, syncConfig.fastSyncBlockValidationX - 1))
      cleanup()
      appStateStorage.fastSyncDone().commit()
      ctx.scheduleOnce(syncSwitchDelay, syncController, Done)
      idle()

    def cleanup(): Unit =
      timers.cancel(HeartBeatTimerKey)
      timers.cancel(PersistTimerKey)
      timers.cancel(PrintStatusTimerKey)
      // StateStorageActor is now Typed; stop the classic-adapted ref directly so the child terminates.
      session.foreach(s => ctx.stop(s.syncStateStorageActor))
      fastSyncStateStorage.purge()

    def processDownloads(): Behavior[Command] =
      session.foreach { s =>
        // Self-heal: if syncState has items but fetcher queues are empty (e.g., post-restart), re-sync them
        if s.syncState.blockBodiesQueue.nonEmpty && s.bodiesFetcherQueue.pending == 0 && s.bodiesFetcherQueue.inFlightCount == 0
        then s.bodiesFetcherQueue.enqueue(s.syncState.blockBodiesQueue)
        if s.syncState.receiptsQueue.nonEmpty && s.receiptsFetcherQueue.pending == 0 && s.receiptsFetcherQueue.inFlightCount == 0
        then s.receiptsFetcherQueue.enqueue(s.syncState.receiptsQueue)

        val hasWork = s.bodiesFetcherQueue.pending > 0 || s.receiptsFetcherQueue.pending > 0 ||
          s.headersFetcherQueue.pending > 0 || s.headersFetcherQueue.inFlightCount > 0 ||
          s.syncState.bestBlockHeaderNumber < s.syncState.safeDownloadTarget

        if handshakedPeers.isEmpty then
          if s.assignedHandlers.nonEmpty then
            log.debug("There are no available peers, waiting for [{}] responses.", s.assignedHandlers.size)
          else ctx.scheduleOnce(syncRetryInterval, ctx.self, ProcessSyncing)
        else if hasWork then dispatchWork()
        else if s.assignedHandlers.nonEmpty then
          log.debug("No pending work; waiting for [{}] in-flight responses.", s.assignedHandlers.size)
        else ctx.scheduleOnce(syncRetryInterval, ctx.self, ProcessSyncing)
      }
      Behaviors.same

    /** Spawn a Typed PeerRequestHandler child, register a death-watch that delivers RequestTerminated, and track the
      * ref in `assignedHandlers`.
      */
    private def spawnHandler(behaviorName: String, beh: Behavior[PeerRequestHandler.Command]): Unit =
      val handler = ctx.spawn(beh, behaviorName)
      ctx.watchWith(handler, RequestTerminated(handler))
      updateSession(s => s.copy(assignedHandlers = s.assignedHandlers + handler))

    private def dispatchWork(): Unit =
      session.foreach { s =>
        val allPeers = handshakedPeers.values
        val targetRtt = globalMedianRttMs

        // Bodies: dispatch to all idle-for-bodies peers simultaneously (mirrors go-ethereum fetchBodies goroutine)
        val bodyAssignments = ConcurrentFetch.dispatchTo(s.bodiesFetcherQueue, allPeers, targetRtt, "bodies", log)
        bodyAssignments.foreach { case (peerWithInfo, req) =>
          spawnHandler(
            s"$countActor-peer-request-handler-block-bodies",
            PeerRequestHandler.behavior[ETHPackets.GetBlockBodies, ETHPackets.BlockBodies](
              peerWithInfo.peer,
              peerResponseTimeout,
              networkPeerManager,
              peerEventBus,
              requestMsg = req,
              responseMsgCode = Codes.BlockBodiesCode,
              replyTo = prhResultAdapter,
              requestId = 0
            )
          )
          updateSession(s =>
            s.copy(
              requestedBlockBodies = s.requestedBlockBodies + (peerWithInfo.peer.id -> req.hashes),
              syncState = s.syncState.copy(blockBodiesQueue = s.syncState.blockBodiesQueue.diff(req.hashes))
            )
          )
        }

        // Receipts: dispatch to all idle-for-receipts peers simultaneously (mirrors go-ethereum fetchReceipts goroutine)
        val receiptAssignments =
          ConcurrentFetch.dispatchTo(s.receiptsFetcherQueue, allPeers, targetRtt, "receipts", log)
        receiptAssignments.foreach { case (peerWithInfo, req) =>
          spawnHandler(
            s"$countActor-peer-request-handler-receipts",
            PeerRequestHandler.behavior[ETHPackets.GetReceipts, ETHPackets.Receipts68](
              peerWithInfo.peer,
              peerResponseTimeout,
              networkPeerManager,
              peerEventBus,
              requestMsg = req,
              responseMsgCode = Codes.ReceiptsCode,
              replyTo = prhResultAdapter,
              requestId = 0
            )
          )
          updateSession(s =>
            s.copy(
              requestedReceipts = s.requestedReceipts + (peerWithInfo.peer.id -> req.blockHashes),
              syncState = s.syncState.copy(receiptsQueue = s.syncState.receiptsQueue.diff(req.blockHashes))
            )
          )
        }

        // Headers: concurrent dispatch to all eligible peers (mirrors go-ethereum skeleton.assignTasks)
        enqueueHeadersIfNeeded()
        // re-read session after enqueueHeadersIfNeeded may update headerQueueHighWatermark
        session.foreach { s2 =>
          val eligibleHeaderPeers =
            allPeers.filter(_.peerInfo.maxBlockNumber >= s2.syncState.pivotBlock.number.value).toSeq
          val headerAssignments =
            ConcurrentFetch.dispatchTo(s2.headersFetcherQueue, eligibleHeaderPeers, targetRtt, "headers", log)
          headerAssignments.foreach { case (peerWithInfo, req) =>
            spawnHandler(
              s"$countActor-fast-headers-${req.requestId}",
              PeerRequestHandler.behavior[ETHPackets.GetBlockHeaders, ETHPackets.BlockHeaders](
                peerWithInfo.peer,
                peerResponseTimeout,
                networkPeerManager,
                peerEventBus,
                requestMsg = req,
                responseMsgCode = Codes.BlockHeadersCode,
                replyTo = prhResultAdapter,
                requestId = 0
              )
            )
          }
        }
      }

    private def enqueueHeadersIfNeeded(): Unit =
      session.foreach { s =>
        val fillTarget =
          (s.headerQueueHighWatermark + FastSync.HeaderQueueLookahead).min(s.syncState.safeDownloadTarget)
        if fillTarget > s.headerQueueHighWatermark then
          val nums = (s.headerQueueHighWatermark + 1 to fillTarget).toSeq
          s.headersFetcherQueue.enqueue(nums)
          updateSession(_.copy(headerQueueHighWatermark = fillTarget))
          log.debug("Enqueued {} header block numbers [{}-{}]", nums.size, nums.head, nums.last)
      }

    private def drainOrderedHeaders(peer: Peer): Behavior[Command] =
      session match
        case None    => Behaviors.same
        case Some(s) =>
          // Prune anything superseded by a redownloadBlockchain reset
          while headerResponseBuffer.nonEmpty && headerResponseBuffer.firstKey <= s.syncState.bestBlockHeaderNumber do
            headerResponseBuffer.remove(headerResponseBuffer.firstKey)

          // Drain the contiguous run from bestBlockHeaderNumber + 1. Each handleBlockHeaders call may decide a behavior
          // transition; the last decision wins (mirrors the Classic "last context.become wins" semantics).
          @tailrec def drain(last: Behavior[Command]): Behavior[Command] =
            headerResponseBuffer.headOption match
              case Some((blockNum, headers))
                  if session.exists(s => blockNum == s.syncState.bestBlockHeaderNumber + 1) =>
                headerResponseBuffer.remove(blockNum)
                drain(handleBlockHeaders(peer, headers))
              case _ => last
          enqueueHeadersIfNeeded()
          drain(Behaviors.same)

    private def blockchainDataToDownload: Boolean =
      session.exists(s =>
        s.syncState.blockChainWorkQueued || s.syncState.bestBlockHeaderNumber < s.syncState.safeDownloadTarget
      )

    private def fullySynced: Boolean =
      session.exists(s =>
        s.syncState.isBlockchainWorkFinished && s.assignedHandlers.isEmpty && s.syncState.stateSyncFinished
      )

    private def updateBestBlockIfNeeded(receivedHashes: Seq[ByteString]): Unit =
      val fullBlocks = receivedHashes.flatMap { rawHash =>
        val hash = BlockHash(rawHash)
        for
          header <- blockchainReader.getBlockHeaderByHash(hash)
          _ <- blockchainReader.getBlockBodyByHash(hash)
          _ <- blockchainReader.getReceiptsByHash(hash)
        yield header
      }

      if fullBlocks.nonEmpty then
        val bestReceivedBlock = fullBlocks.maxBy(_.number)
        val lastStoredBestBlockNumber = blockchainReader.getBestBlockNumber
        if lastStoredBestBlockNumber < bestReceivedBlock.number.value then
          // Set best block info with BOTH hash and number (putBestBlockNumber only
          // sets the number, leaving getBestBlockInfo().hash stale/empty).
          appStateStorage
            .putBestBlockInfo(BlockInfo(bestReceivedBlock.hash.value, bestReceivedBlock.number.value))
            .and(blockNumberMappingStorage.put(bestReceivedBlock.number.value, bestReceivedBlock.hash.value))
            .commit()
        updateSession(s =>
          s.copy(syncState =
            s.syncState.copy(lastFullBlockNumber = bestReceivedBlock.number.value.max(lastStoredBestBlockNumber))
          )
        )

  /** Number of block numbers to keep ahead of bestBlockHeaderNumber in the header fetch queue. Ensures peers always
    * have work to do without enqueuing the entire chain at once. Mirrors go-ethereum skeleton scratchHeaders (131,072)
    * in spirit; tuned for actor-model dispatch.
    */
  val HeaderQueueLookahead: Int = 4096

  /** Sealed protocol for the FastSync Typed core (SNAP2 narrowing). The core receives FastSync-internal commands
    * directly and a set of foreign message streams via `messageAdapter` wrappers (so that PivotBlockSelector,
    * SyncStateSchedulerActor, FastSyncBranchResolverActor, PeerRequestHandler, NetworkPeerManagerActor, and the
    * external SyncProtocol can all reach a single `Behavior[Command]`). Each `Wrapped*` case corresponds to one foreign
    * reply target that must be supplied a typed adapter at the spawn/subscription site (Phase 2 wiring).
    */
  /** All 13 mutable fields that are only valid after the sync session has been established by initSyncSession(). Held
    * behind Option[SyncSession] on Impl so that stale messages arriving before initSyncSession() can be safely dropped
    * (returning Behaviors.same) instead of throwing NullPointerException. (C1 + W8)
    */
  private[fast] case class SyncSession(
      syncState: SyncState,
      initialLastFullBlockNumber: BigInt,
      assignedHandlers: Set[TypedActorRef[PeerRequestHandler.Command]],
      requestedBlockBodies: Map[PeerId, Seq[ByteString]],
      requestedReceipts: Map[PeerId, Seq[ByteString]],
      bodiesFetcherQueue: BodiesFetcherQueue,
      receiptsFetcherQueue: ReceiptsFetcherQueue,
      headersFetcherQueue: HeadersFetcherQueue,
      headerQueueHighWatermark: BigInt,
      syncStateStorageActor: TypedActorRef[StateStorageActor.Command],
      syncStateScheduler: TypedActorRef[SyncStateSchedulerActor.Command],
      stateSyncRestartRequested: Boolean,
      stateSyncStarted: Boolean
  )

  sealed trait Command

  // ── FastSync-internal commands ──────────────────────────────────────────────────────────────
  private case class UpdatePivotBlock(reason: PivotBlockUpdateReason) extends Command
  private case object ProcessSyncing extends Command
  private case object PersistSyncState extends Command
  private case object PrintStatus extends Command

  /** Signals a fatal, non-recoverable error. The handler triggers CoordinatedShutdown and stops the actor cleanly
    * instead of calling sys.exit(1) which would bypass the Pekko supervisor chain and log flushes. (C3)
    */
  private case class FatalError(reason: String) extends Command

  /** Carries the Typed reply-to for a `SyncProtocol.GetStatus` ask. */
  final private[fast] case class GetStatusCmd(replyTo: TypedActorRef[SyncProtocol.Status]) extends Command

  /** Core-internal: a watched Classic `PeerRequestHandler` child stopped (it replies to `context.parent` then stops
    * itself). Delivered via `ctx.watchWith`, this is the single place that removes the handler from the active set —
    * the response/failure handlers only do data processing (keyed by `peer.id`).
    */
  final private[fast] case class RequestTerminated(handler: TypedActorRef[PeerRequestHandler.Command]) extends Command

  /** Core-internal: periodic poll tick that re-requests the handshaked peer list from `networkPeerManager` (replaces
    * the `scheduleWithFixedDelay` that `PeerListSupportNg` ran in the Classic actor).
    */
  private case object ScanPeersTick extends Command

  /** Core-internal: retry pivot block selection after a delay (was a private case object inside the Classic actor). */
  private case object RetryPivotBlockSelection extends Command

  // ── Foreign message wrappers (Phase 2 wires a messageAdapter to each) ───────────────────────────
  /** External `SyncProtocol.Start` / `SyncProtocol.GetStatus` from the SyncController. Visible to the parent `sync`
    * package so the Classic-shell SyncController can wrap before its raw `.tell` to the Typed FastSync child.
    */
  final private[sync] case class WrappedSyncProtocol(msg: SyncProtocol.SyncProtocolMsg) extends Command

  /** `NetworkPeerManagerActor.HandshakedPeers` poll reply (routed to PeerListHelper). */
  final private[fast] case class WrappedHandshakedPeers(peers: NetworkPeerManagerActor.HandshakedPeers) extends Command

  /** `PeerDisconnected` event-bus notification (routed to PeerListHelper). */
  final private[fast] case class WrappedPeerDisconnected(pd: PeerDisconnected) extends Command

  /** `PivotBlockSelector.Result` — a freshly selected pivot block header. */
  final private[fast] case class WrappedPivotResult(result: PivotBlockSelector.Result) extends Command

  /** `PivotBlockSelector.SelectionFailed` — pivot election exhausted its attempts. */
  case object PivotSelectionFailed extends Command

  /** `PeerRequestHandler.Result` — `ResponseReceived` / `RequestFailed` from a Classic PRH child. Visible to the parent
    * `sync` package so tests can simulate a PRH result reaching the Typed FastSync (e.g. the typed-receipt wire-format
    * regression in FastSyncSpec).
    */
  final private[sync] case class WrappedPrhResult(result: PeerRequestHandler.Result) extends Command

  /** Foreign replies from `SyncStateSchedulerActor` (StateSyncFinished / WaitingForNewTargetBlock / NetworkIncompatible
    * — all extend SyncStateSchedulerActorResponse). `RestartRequested` is not sent to FastSync, and `StateSyncStats`
    * does NOT extend SyncStateSchedulerActorResponse — it has its own wrapper below.
    */
  final private[fast] case class WrappedSchedulerResponse(
      response: SyncStateSchedulerActor.SyncStateSchedulerActorResponse
  ) extends Command

  /** `SyncStateSchedulerActor.StateSyncStats` progress tick. Kept separate from `WrappedSchedulerResponse` because
    * `StateSyncStats` does not extend `SyncStateSchedulerActorResponse` (and we do not reseal it in its source file).
    */
  final private[fast] case class WrappedStateSyncStats(stats: SyncStateSchedulerActor.StateSyncStats) extends Command

  /** `FastSyncBranchResolverActor.BranchResolverResponse` — branch resolution success/failure. */
  final private[fast] case class WrappedBranchResolverResponse(
      response: FastSyncBranchResolverActor.BranchResolverResponse
  ) extends Command

  /** Sync state that should be persisted.
    */
  final case class SyncState(
      pivotBlock: BlockHeader,
      lastFullBlockNumber: BigInt = 0,
      safeDownloadTarget: BigInt = 0,
      blockBodiesQueue: Seq[ByteString] = Nil,
      receiptsQueue: Seq[ByteString] = Nil,
      downloadedNodesCount: Long = 0,
      totalNodesCount: Long = 0,
      bestBlockHeaderNumber: BigInt = 0,
      nextBlockToFullyValidate: BigInt = 1,
      pivotBlockUpdateFailures: Int = 0,
      updatingPivotBlock: Boolean = false,
      stateSyncFinished: Boolean = false,
      // High-water mark of totalNodesCount seen during this fast sync run, persisted across
      // JVM restarts. The dynamic `totalNodesCount` field above is `saved + currently-queued
      // missing` and resets after a restart (the scheduler walks the trie from the pivot
      // root and only queues newly-discovered missing nodes). Without a high-water mark, the
      // "95% complete" check at the end of state download wrongly trips on resume — saved
      // is the same but total drops to ≈saved, looking like 99%+, and fast sync declares
      // itself done with a partial trie. The high-water mark preserves the true scope so
      // that completion only fires when we've genuinely downloaded ≥95% of the discovered
      // total. Added at the END of the case class for boopickle backward compat with
      // pre-existing persisted state — deserializes to default 0 from older payloads.
      maxTotalNodesCount: Long = 0
  ):

    def enqueueBlockBodies(blockBodies: Seq[ByteString]): SyncState =
      copy(blockBodiesQueue = blockBodiesQueue ++ blockBodies)

    def enqueueReceipts(receipts: Seq[ByteString]): SyncState =
      copy(receiptsQueue = receiptsQueue ++ receipts)

    def blockChainWorkQueued: Boolean =
      blockBodiesQueue.nonEmpty || receiptsQueue.nonEmpty

    def updateNextBlockToValidate(header: BlockHeader, K: Int, X: Int): SyncState = copy(
      nextBlockToFullyValidate =
        if bestBlockHeaderNumber >= pivotBlock.number.value - X then header.number.value + 1
        else (header.number.value + K / 2 + Random.nextInt(K)).min(pivotBlock.number.value - X)
    )

    def updateDiscardedBlocks(header: BlockHeader, N: Int): SyncState = copy(
      blockBodiesQueue = Seq.empty,
      receiptsQueue = Seq.empty,
      bestBlockHeaderNumber = (header.number.value - N - 1).max(0),
      nextBlockToFullyValidate = (header.number.value - N).max(1)
    )

    def updatePivotBlock(newPivot: BlockHeader, numberOfSafeBlocks: BigInt, updateFailures: Boolean): SyncState =
      copy(
        pivotBlock = newPivot,
        safeDownloadTarget = newPivot.number.value + numberOfSafeBlocks,
        pivotBlockUpdateFailures = if updateFailures then pivotBlockUpdateFailures + 1 else pivotBlockUpdateFailures,
        updatingPivotBlock = false
      )

    def isBlockchainWorkFinished: Boolean =
      bestBlockHeaderNumber >= safeDownloadTarget && !blockChainWorkQueued

  sealed trait HashType:
    def v: ByteString

  case class StateMptNodeHash(v: ByteString) extends HashType
  case class ContractStorageMptNodeHash(v: ByteString) extends HashType
  case class EvmCodeHash(v: ByteString) extends HashType
  case class StorageRootHash(v: ByteString) extends HashType

  /** Sealed umbrella for the two messages sent from FastSync to SyncController. Enables a narrow
    * `TypedActorRef[SyncControllerMsg]` adapter instead of `TypedActorRef[Any]`.
    */
  sealed trait SyncControllerMsg
  case object Done extends SyncControllerMsg
  case object FallbackToSnapSync extends SyncControllerMsg

  sealed abstract class HeaderProcessingResult
  case object HeadersProcessingFinished extends HeaderProcessingResult
  case class ParentChainWeightNotFound(header: BlockHeader) extends HeaderProcessingResult
  case class ValidationFailed(header: BlockHeader, peer: Peer) extends HeaderProcessingResult
  case object ImportedPivotBlock extends HeaderProcessingResult

  sealed abstract class PivotBlockUpdateReason:
    def isSyncRestart: Boolean = this match
      case ImportedLastBlock         => false
      case LastBlockValidationFailed => false
      case SyncRestart               => true
  case object ImportedLastBlock extends PivotBlockUpdateReason
  case object LastBlockValidationFailed extends PivotBlockUpdateReason
  case object SyncRestart extends PivotBlockUpdateReason
