package com.chipprbots.ethereum.blockchain.sync.fast

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*
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
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off file.size.limit
object FastSync {

  // scalastyle:off parameter.number method.length number.of.methods
  /** Typed core factory for FastSync. Returns a `Behavior[Any]` because the core receives a heterogeneous message
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
      peerEventBus: ActorRef,
      networkPeerManager: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      syncController: ActorRef
  ): Behavior[Any] =
    Behaviors.setup[Any] { ctx =>
      Behaviors.withTimers[Any] { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerDisconnected] =
          ctx.messageAdapter[PeerDisconnected](identity)
        // Immediate first poll + periodic rescans (matches PeerListSupportNg's 0-delay scheduleWithFixedDelay).
        networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, ctx.self.toClassic)
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
          peerDisconnectedAdapter
        ).idle()
      }
    }

  // scalastyle:off number.of.methods
  private class Impl(
      ctx: ActorContext[Any],
      timers: TimerScheduler[Any],
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
      peerEventBus: ActorRef,
      networkPeerManager: ActorRef,
      blacklist: Blacklist,
      val syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      syncController: ActorRef,
      peerDisconnectedAdapter: TypedActorRef[PeerDisconnected]
  ) extends ReceiptsValidator
      with SyncBlocksValidator {

    import configBuilder.*
    import syncConfig.*

    override protected val log: org.slf4j.Logger = ctx.log

    // Shared rate tracker: the PeerListHelper tunes it on each handshaked-peer refresh and the concurrent fetcher
    // queues read its RTT/capacity estimates.
    private val ethRateTracker: PeerRateTracker = new PeerRateTracker()

    private val prhResultAdapter: TypedActorRef[PeerRequestHandler.Result] =
      ctx.messageAdapter[PeerRequestHandler.Result](identity)

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
    private def handlePeerList(msg: Any): Boolean =
      msg match {
        case ScanPeersTick =>
          networkPeerManager.tell(NetworkPeerManagerActor.GetHandshakedPeers, ctx.self.toClassic)
          true
        case NetworkPeerManagerActor.HandshakedPeers(peers) =>
          peerHelper.handleHandshakedPeers(peers)
          true
        case PeerDisconnected(peerId) =>
          peerHelper.handlePeerDisconnected(peerId)
          true
        case _ => false
      }

    // ── Pre-syncing behaviors ──────────────────────────────────────────────────────────────────

    def idle(): Behavior[Any] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) then Behaviors.same
      else
        msg match {
          case SyncProtocol.Start     => start()
          case GetStatusCmd(replyTo)  => replyTo ! SyncProtocol.Status.NotSyncing; Behaviors.same
          case SyncProtocol.GetStatus => ctx.self ! GetStatusCmd(ctx.toClassic.sender()); Behaviors.same
          case _                      => Behaviors.same
        }
    }

    def start(): Behavior[Any] = {
      log.info("Trying to start block synchronization (fast mode)")
      fastSyncStateStorage.getSyncState() match {
        case Some(syncState) => startWithState(syncState)
        case None            => startFromScratch()
      }
    }

    def startWithState(syncState: SyncState): Behavior[Any] = {
      // Check if headers in RocksDB go beyond the persisted bestBlockHeaderNumber.
      // This happens when SyncState was persisted mid-download but the node restarted —
      // headers continued being written to RocksDB past the last SyncState snapshot.
      val existingBestHeader = blockchainReader.getBestBlockNumber
      val updatedState =
        if existingBestHeader > syncState.bestBlockHeaderNumber && existingBestHeader <= syncState.pivotBlock.number
        then {
          log.info(
            "Headers in database ({}) ahead of persisted sync state ({}). Advancing to skip redundant download.",
            existingBestHeader,
            syncState.bestBlockHeaderNumber
          )
          syncState.copy(
            bestBlockHeaderNumber = existingBestHeader,
            lastFullBlockNumber = existingBestHeader.max(syncState.lastFullBlockNumber)
          )
        } else syncState

      log.info("Starting fast sync with existing state and asking for new pivot block")
      initSyncSession(updatedState)
      askForPivotBlockUpdate(SyncRestart)
    }

    def startFromScratch(): Behavior[Any] = {
      log.info("Starting fast sync from scratch")
      val pivotBlockSelector = ctx
        .spawn(
          PivotBlockSelector(networkPeerManager, peerEventBus, syncConfig, ctx.self.toClassic, blacklist),
          "pivot-block-selector"
        )
        .toClassic
      pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
      waitingForPivotBlock()
    }

    def waitingForPivotBlock(): Behavior[Any] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) then Behaviors.same
      else
        msg match {
          case GetStatusCmd(replyTo) => replyTo ! SyncProtocol.Status.NotSyncing; Behaviors.same
          case RetryPivotBlockSelection =>
            log.info("Retrying pivot block selection")
            val pivotBlockSelector = ctx
              .spawn(
                PivotBlockSelector(networkPeerManager, peerEventBus, syncConfig, ctx.self.toClassic, blacklist),
                s"pivot-block-selector-retry-${java.util.UUID.randomUUID()}"
              )
              .toClassic
            pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
            Behaviors.same
          case PivotBlockSelector.SelectionFailed =>
            log.warn(
              "Pivot block selection failed after maximum attempts. Retrying in {}",
              startRetryInterval
            )
            timers.startSingleTimer(RetryPivotBlockSelection, startRetryInterval)
            Behaviors.same
          case SyncProtocol.GetStatus => ctx.self ! GetStatusCmd(ctx.toClassic.sender()); Behaviors.same
          case PivotBlockSelector.Result(pivotBlockHeader) =>
            if pivotBlockHeader.number < 1 then {
              log.info("Unable to start block synchronization in fast mode: pivot block is less than 1")
              // Don't give up — peers may not have been fork-validated yet at startup.
              // Retry pivot selection after a delay instead of marking fast sync done.
              log.info("Retrying pivot selection in {} (peers may still be connecting)", startRetryInterval)
              timers.startSingleTimer(RetryPivotBlockSelection, startRetryInterval)
              Behaviors.same
            } else {
              // Check if headers already exist in RocksDB from a previous sync run.
              // This avoids re-downloading millions of headers that survived a restart.
              val existingBestHeader = blockchainReader.getBestBlockNumber
              val bootstrappedHeaderNumber =
                if existingBestHeader > 0 && existingBestHeader <= pivotBlockHeader.number then {
                  log.info(
                    "Found existing headers in database up to block {}. Skipping redundant header download.",
                    existingBestHeader
                  )
                  existingBestHeader
                } else BigInt(0)

              val initialSyncState =
                SyncState(
                  pivotBlockHeader,
                  safeDownloadTarget = pivotBlockHeader.number + syncConfig.fastSyncBlockValidationX,
                  bestBlockHeaderNumber = bootstrappedHeaderNumber,
                  lastFullBlockNumber = bootstrappedHeaderNumber
                )
              initSyncSession(initialSyncState)
              val b = syncing()
              processSyncing()
              b
            }
          case _ => Behaviors.same
        }
    }

    private val actorCounter = new AtomicInteger
    private def countActor: Int = actorCounter.incrementAndGet

    // ── Syncing session state (was the inner SyncingHandler class) ─────────────────────────────────
    // There is at most one sync session per FastSync lifetime; the session state is initialised lazily by
    // initSyncSession() when a pivot is first established, and lives directly on Impl.

    // not part of syncstate as we do not want to persist is.
    private var stateSyncRestartRequested = false
    private var stateSyncStarted = false

    // Set by initSyncSession() the first time a pivot is established. Before that, the pre-syncing behaviors
    // (idle / waitingForPivotBlock) never read these fields.
    private var syncState: SyncState = null
    private var initialLastFullBlockNumber: BigInt = 0

    // Outstanding PRH requests. The Classic PeerRequestHandler children (spawned via ctx.toClassic.actorOf) reply to
    // context.parent (this core, adapted to Classic) with a raw ResponseReceived / RequestFailed, then stop. The core
    // matches those on its Behavior[Any] and identifies the originating peer from the message's `peer` field — there
    // is at most one in-flight bodies/receipts request per peer (the fetcher queue's inFlight(peerId) gate enforces
    // this), so peer.id is an unambiguous key for the requested-hashes maps. `assignedHandlers` tracks the live child
    // refs for the fullySynced/noBlockchainWorkRemaining gates and is pruned only on RequestTerminated (death-watch).
    private var assignedHandlers: Set[TypedActorRef[PeerRequestHandler.Command]] = Set.empty
    private var requestedBlockBodies: Map[PeerId, Seq[ByteString]] = Map.empty
    private var requestedReceipts: Map[PeerId, Seq[ByteString]] = Map.empty

    private var bodiesFetcherQueue = new BodiesFetcherQueue(ethRateTracker)
    private var receiptsFetcherQueue = new ReceiptsFetcherQueue(ethRateTracker)
    private var headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker)
    private val headerResponseBuffer = mutable.SortedMap.empty[BigInt, Seq[BlockHeader]]
    private var headerQueueHighWatermark: BigInt = 0

    // Children + timer keys established once per sync session by initSyncSession().
    private var syncStateStorageActor: ActorRef = null
    private var syncStateScheduler: TypedActorRef[Any] = null

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
    private def initSyncSession(initial: SyncState): Unit = {
      syncState = initial
      initialLastFullBlockNumber = initial.lastFullBlockNumber
      headerQueueHighWatermark = initial.bestBlockHeaderNumber
      lastLoggedFullBlock = initial.lastFullBlockNumber
      lastLoggedStateNodes = initial.downloadedNodesCount

      // Pekko Typed migration (Group S2): StateStorageActor is a Typed Behavior; FastSync's core is Typed too, so
      // spawn it directly and adapt the ref to Classic for the StateStorageActor.Init/Persist command sends.
      syncStateStorageActor = ctx
        .spawn(
          StateStorageActor(),
          s"$countActor-state-storage",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
        .toClassic
      syncStateStorageActor ! StateStorageActor.Init(fastSyncStateStorage)

      // SyncStateSchedulerActor (Group S4) is a Typed Behavior (Classic shell + Behavior[Any] core); spawn the shell
      // via the Typed factory. We send it StartSyncingTo / RestartRequested and it replies with foreign messages that
      // arrive on our Behavior[Any] core.
      syncStateScheduler = ctx
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
                ctx.self.toClassic
              )
            )
            .onFailure[Exception](org.apache.pekko.actor.typed.SupervisorStrategy.restart),
          s"$countActor-state-scheduler"
        )

      // Persist delay should be 0, as the presence of it marks that fast sync was started.
      timers.startTimerWithFixedDelay(PersistTimerKey, PersistSyncState, persistStateSnapshotInterval)
      timers.startTimerWithFixedDelay(PrintStatusTimerKey, PrintStatus, printStatusInterval)
      timers.startTimerWithFixedDelay(HeartBeatTimerKey, ProcessSyncing, syncRetryInterval * 2)

      // Initialize fetcher queues from persisted sync state
      bodiesFetcherQueue.enqueue(syncState.blockBodiesQueue)
      receiptsFetcherQueue.enqueue(syncState.receiptsQueue)
    }

    def handleStatus(msg: Any): Boolean = msg match {
      case SyncProtocol.GetStatus =>
        ctx.self ! GetStatusCmd(ctx.toClassic.sender())
        true
      case GetStatusCmd(replyTo) =>
        replyTo ! currentSyncingStatus
        true
      case SyncStateSchedulerActor.StateSyncStats(saved, missing) =>
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
        val newMax = math.max(
          math.max(syncState.maxTotalNodesCount, syncState.totalNodesCount),
          total
        )
        syncState = syncState.copy(
          downloadedNodesCount = saved,
          totalNodesCount = total,
          maxTotalNodesCount = newMax
        )
        true
      case _ => false
    }

    def syncing(): Behavior[Any] = Behaviors.receiveMessage { msg =>
      if handlePeerList(msg) || handleStatus(msg) then Behaviors.same
      else
        msg match {
          case PeerRequestHandler.RequestFailed(peer, reason) =>
            handleRequestFailure(peer, FastSyncRequestFailed(reason))
            Behaviors.same
          case RequestTerminated(handler) =>
            assignedHandlers -= handler
            Behaviors.same
          case r: ResponseReceived[?] =>
            handleResponses(r)
          case UpdatePivotBlock(reason) => updatePivotBlock(reason)
          case WaitingForNewTargetBlock =>
            log.debug("State sync stopped until receiving new pivot block")
            updatePivotBlock(ImportedLastBlock)
          case ProcessSyncing   => processSyncing()
          case PrintStatus      => printStatus(); Behaviors.same
          case PersistSyncState => persistSyncState(); Behaviors.same
          case StateSyncFinished =>
            syncState = syncState.copy(stateSyncFinished = true)
            processSyncing()
          case SyncStateSchedulerActor.NetworkIncompatible =>
            log.warn(
              "State scheduler reports no ETH63-67 peers available (ETH68-only network). " +
                "Fast sync cannot use GetNodeData. Requesting fallback to SNAP sync."
            )
            cleanup()
            syncController ! FallbackToSnapSync
            Behaviors.stopped
          case _ => Behaviors.same
        }
    }

    private def handleResponses(result: ResponseReceived[?]): Behavior[Any] = result match {
      case ResponseReceived(peer, blockHeadersMsg: ETHPackets.BlockHeaders, timeTaken) =>
        log.debug(
          "Received {} block headers from peer [{}] in {} ms",
          blockHeadersMsg.headers.size,
          peer.id,
          timeTaken
        )
        FastSyncMetrics.setBlockHeadersDownloadTime(timeTaken)
        handshakedPeers.get(peer.id) match {
          case Some(peerWithInfo) =>
            headersFetcherQueue.deliver(peerWithInfo, blockHeadersMsg, timeTaken) match {
              case DeliveryResult.Delivered(_) =>
                if blockHeadersMsg.headers.nonEmpty then {
                  headerResponseBuffer.put(blockHeadersMsg.headers.head.number, blockHeadersMsg.headers)
                  drainOrderedHeaders(peer)
                } else {
                  blacklist.add(peer.id, blacklistDuration, WrongBlockHeaders)
                  Behaviors.same
                }
              case DeliveryResult.Invalid(reason) =>
                log.warn("Header delivery rejected for peer [{}]: {}", peer.id, reason)
                blacklist.add(peer.id, blacklistDuration, WrongBlockHeaders)
                Behaviors.same
              case DeliveryResult.Duplicate =>
                log.debug("Duplicate/stale header response from peer [{}], ignoring", peer.id)
                Behaviors.same
            }
          case None =>
            log.debug("Received block headers from unknown peer [{}], ignoring", peer.id)
            Behaviors.same
        }

      case ResponseReceived(peer, blockBodiesMsg: ETHPackets.BlockBodies, timeTaken) =>
        handshakedPeers.get(peer.id) match {
          case Some(peerWithInfo) => bodiesFetcherQueue.deliver(peerWithInfo, blockBodiesMsg, timeTaken)
          case None =>
            ethRateTracker.update(
              peer.id.value,
              PeerRateTracker.MsgGetBlockBodies,
              timeTaken,
              blockBodiesMsg.bodies.size
            )
        }
        log.debug("Received {} block bodies from peer [{}] in {} ms", blockBodiesMsg.bodies.size, peer.id, timeTaken)
        FastSyncMetrics.setBlockBodiesDownloadTime(timeTaken)

        val requestedBodies = requestedBlockBodies.getOrElse(peer.id, Nil)
        requestedBlockBodies -= peer.id
        handleBlockBodies(peer, requestedBodies, blockBodiesMsg.bodies)
      case ResponseReceived(peer, receipts68: ETHPackets.Receipts68, timeTaken) =>
        handshakedPeers.get(peer.id) match {
          case Some(peerWithInfo) => receiptsFetcherQueue.deliver(peerWithInfo, receipts68, timeTaken)
          case None =>
            ethRateTracker.update(
              peer.id.value,
              PeerRateTracker.MsgGetReceipts,
              timeTaken,
              receipts68.receiptsForBlocks.items.size
            )
        }
        // Convert ETH66 RLPList format to Seq[Seq[Receipt]] expected by handleReceipts.
        // NOTE: Typed receipts (EIP-2718-style) can arrive on the wire as
        //   RLPValue(typeByte || rlp(payload))
        // so we must expand them before calling toTypedRLPEncodables/toReceipt.
        val receipts: Seq[Seq[Receipt]] = {
          import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
          import ETHPackets.TypedTransaction.*
          import com.chipprbots.ethereum.rlp.{RLPEncodeable, RLPException, RLPValue, rawDecode}

          def expandTypedReceipts(items: Seq[RLPEncodeable]): Seq[RLPEncodeable] =
            items.flatMap {
              case v: RLPValue =>
                val receiptBytes = v.bytes
                if receiptBytes.isEmpty then {
                  throw new RuntimeException("Cannot decode Receipt: empty RLPValue")
                }
                val first = receiptBytes(0)
                // Typed receipt in wire format: RLPValue(typeByte || rlp(payload))
                // Expand it to Seq(RLPValue(typeByte), RLPList(payload)) for toTypedRLPEncodables.
                if (first & 0xff) < 0x7f && receiptBytes.length > 1 then {
                  try
                    Seq(RLPValue(Array(first)), rawDecode(receiptBytes.tail))
                  catch {
                    case _: RuntimeException | _: RLPException => Seq(v)
                  }
                } else {
                  Seq(v)
                }
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
        }
        log.debug("Received {} receipts (ETH66) from peer [{}] in {} ms", receipts.size, peer.id, timeTaken)
        FastSyncMetrics.setBlockReceiptsDownloadTime(timeTaken)

        val requestedHashes = requestedReceipts.getOrElse(peer.id, Nil)
        requestedReceipts -= peer.id
        handleReceipts(peer, requestedHashes, receipts)

      case ResponseReceived(peer, other, _) =>
        log.debug(
          "Received unexpected response type {} from peer [{}], ignoring",
          other.getClass.getSimpleName,
          peer.id
        )
        Behaviors.same
    }

    def askForPivotBlockUpdate(updateReason: PivotBlockUpdateReason): Behavior[Any] = {
      syncState = syncState.copy(updatingPivotBlock = true)
      log.debug("Asking for new pivot block")
      val pivotBlockSelector =
        ctx
          .spawn(
            PivotBlockSelector(networkPeerManager, peerEventBus, syncConfig, ctx.self.toClassic, blacklist),
            s"$countActor-pivot-block-selector-update"
          )
          .toClassic
      pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock
      waitingForPivotBlockUpdate(updateReason)
    }

    private def newPivotIsGoodEnough(
        newPivot: BlockHeader,
        currentState: SyncState,
        updateReason: PivotBlockUpdateReason
    ): Boolean = {
      def stalePivotAfterRestart: Boolean =
        newPivot.number == currentState.pivotBlock.number && updateReason.isSyncRestart
      newPivot.number >= currentState.pivotBlock.number && !stalePivotAfterRestart
    }
    def waitingForPivotBlockUpdate(updateReason: PivotBlockUpdateReason): Behavior[Any] =
      Behaviors.receiveMessage { msg =>
        if handlePeerList(msg) || handleStatus(msg) then Behaviors.same
        else
          msg match {
            case PeerRequestHandler.RequestFailed(peer, reason) =>
              handleRequestFailure(peer, FastSyncRequestFailed(reason))
              Behaviors.same
            case RequestTerminated(handler) =>
              assignedHandlers -= handler
              Behaviors.same
            case PivotBlockSelector.SelectionFailed =>
              log.warn(
                "Pivot block selection failed after maximum attempts during update. Continuing with current pivot."
              )
              syncState = syncState.copy(updatingPivotBlock = false)
              // On SyncRestart, the state scheduler is in idle state waiting for StartSyncingTo.
              // Send it the existing pivot's state root so it doesn't deadlock.
              if updateReason.isSyncRestart then {
                log.info(
                  "SyncRestart: sending existing pivot state root to state scheduler (block {})",
                  syncState.pivotBlock.number
                )
                stateSyncStarted = true
                syncStateScheduler ! StartSyncingTo(syncState.pivotBlock.stateRoot, syncState.pivotBlock.number)
              }
              // Mirror the Classic `context.become(this.receive); processSyncing()`: transition out of the
              // pivot-update wait into `syncing()` (which handles ResponseReceived) and run processSyncing() for
              // its dispatch side effects. processSyncing() returns Behaviors.same here, so the explicit `syncing()`
              // is what takes effect — without it the actor would stay in waitingForPivotBlockUpdate and drop the
              // header/body/receipt responses.
              val b = syncing()
              processSyncing()
              b

            case PivotBlockSelector.Result(pivotBlockHeader)
                if newPivotIsGoodEnough(pivotBlockHeader, syncState, updateReason) =>
              log.debug("New pivot block with number {} received", pivotBlockHeader.number)
              updatePivotSyncState(updateReason, pivotBlockHeader)
              // See SelectionFailed above: become syncing() before running processSyncing()'s side effects so the
              // subsequently-arriving PeerRequestHandler responses are handled instead of dropped.
              val b = syncing()
              processSyncing()
              b

            case PivotBlockSelector.Result(pivotBlockHeader)
                if !newPivotIsGoodEnough(pivotBlockHeader, syncState, updateReason) =>
              log.debug("Received pivot block is older than old one, re-scheduling asking for new one")
              reScheduleAskForNewPivot(updateReason)
              Behaviors.same

            case PersistSyncState => persistSyncState(); Behaviors.same

            case UpdatePivotBlock(state) => updatePivotBlock(state)

            case _ => Behaviors.same
          }
      }

    private def reScheduleAskForNewPivot(updateReason: PivotBlockUpdateReason): Unit = {
      syncState = syncState.copy(pivotBlockUpdateFailures = syncState.pivotBlockUpdateFailures + 1)
      ctx.scheduleOnce(syncConfig.pivotBlockReScheduleInterval, ctx.self, UpdatePivotBlock(updateReason))
    }

    def currentSyncingStatus: SyncProtocol.Status =
      SyncProtocol.Status.Syncing(
        initialLastFullBlockNumber,
        Progress(syncState.lastFullBlockNumber, syncState.pivotBlock.number),
        Some(
          Progress(syncState.downloadedNodesCount, syncState.totalNodesCount.max(1))
        ) // There's always at least one state root to fetch
      )

    private def updatePivotBlock(updateReason: PivotBlockUpdateReason): Behavior[Any] =
      if syncState.pivotBlockUpdateFailures <= syncConfig.maximumTargetUpdateFailures then {
        if assignedHandlers.nonEmpty || syncState.blockChainWorkQueued then {
          log.debug("Still waiting for some responses, rescheduling pivot block update")
          ctx.scheduleOnce(1.second, ctx.self, UpdatePivotBlock(updateReason))
          processSyncing()
        } else {
          askForPivotBlockUpdate(updateReason)
        }
      } else {
        log.warn("Sync failure! Number of pivot block update failures reached maximum.")
        sys.exit(1)
      }

    private def updatePivotSyncState(updateReason: PivotBlockUpdateReason, pivotBlockHeader: BlockHeader): Unit =
      updateReason match {
        case ImportedLastBlock =>
          if pivotBlockHeader.number - syncState.pivotBlock.number <= syncConfig.maxTargetDifference then {
            log.debug("Current pivot block is fresh enough, starting state download.")
            // Empty root has means that there were no transactions in blockchain, and Mpt trie is empty
            // Asking for this root would result only with empty transactions
            if syncState.pivotBlock.stateRoot == ByteString(MerklePatriciaTrie.EmptyRootHash) then {
              syncState = syncState.copy(stateSyncFinished = true, updatingPivotBlock = false)
            } else {
              syncState = syncState.copy(updatingPivotBlock = false)
              stateSyncRestartRequested = false
              stateSyncStarted = true
              syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number)
            }
          } else {
            syncState = syncState.updatePivotBlock(
              pivotBlockHeader,
              syncConfig.fastSyncBlockValidationX,
              updateFailures = false
            )
            log.info(
              "Pivot block updated to {}, new safe target {}. Resuming state download with new root.",
              pivotBlockHeader.number,
              syncState.safeDownloadTarget
            )
            // Always restart state download with new pivot — even if the jump is large.
            // The state scheduler's bloom filter handles nodes already downloaded.
            stateSyncRestartRequested = false
            stateSyncStarted = true
            syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number)
          }

        case LastBlockValidationFailed =>
          log.debug(
            "Changing pivot block after failure, to {}, new safe target is {}",
            pivotBlockHeader.number,
            syncState.safeDownloadTarget
          )
          syncState =
            syncState.updatePivotBlock(pivotBlockHeader, syncConfig.fastSyncBlockValidationX, updateFailures = true)

        case SyncRestart =>
          // in case of node restart we are sure that new pivotBlockHeader > current pivotBlockHeader
          syncState = syncState.updatePivotBlock(
            pivotBlockHeader,
            syncConfig.fastSyncBlockValidationX,
            updateFailures = false
          )
          log.info(
            "SyncRestart: pivot block updated to {}, safe target {}, starting state download",
            pivotBlockHeader.number,
            syncState.safeDownloadTarget
          )
          // Start the state scheduler — without this, state download never begins after restart.
          if !syncState.stateSyncFinished && pivotBlockHeader.stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash)
          then {
            stateSyncRestartRequested = false
            stateSyncStarted = true
            syncStateScheduler ! StartSyncingTo(pivotBlockHeader.stateRoot, pivotBlockHeader.number)
          }
      }

    private def discardLastBlocks(startBlock: BigInt, blocksToDiscard: Int): Unit =
      (startBlock to ((startBlock - blocksToDiscard).max(1)) by -1).foreach { n =>
        blockchainReader.getBlockHeaderByNumber(n).foreach { headerToRemove =>
          blockchain.removeBlock(headerToRemove.hash)
        }
      }

    private def validateHeader(header: BlockHeader, peer: Peer): Either[HeaderProcessingResult, BlockHeader] = {
      val shouldValidate = header.number >= syncState.nextBlockToFullyValidate

      if shouldValidate then {
        validators.blockHeaderValidator.validate(header, blockchainReader.getBlockHeaderByHash) match {
          case Right(_) =>
            updateValidationState(header)
            Right(header)

          case Left(error) =>
            log.warn("Block header validation failed during fast sync at block {}: {}", header.number, error)
            Left(ValidationFailed(header, peer))
        }
      } else {
        Right(header)
      }
    }

    private def updateSyncState(header: BlockHeader, parentWeight: ChainWeight): Unit = {
      blockchainWriter
        .storeBlockHeader(header)
        .and(blockchainWriter.storeChainWeight(header.hash, parentWeight.increase(header)))
        .commit()

      if header.number > syncState.bestBlockHeaderNumber then {
        syncState = syncState.copy(bestBlockHeaderNumber = header.number)
      }

      syncState = syncState
        .enqueueBlockBodies(Seq(header.hash))
        .enqueueReceipts(Seq(header.hash))
      bodiesFetcherQueue.enqueue(Seq(header.hash))
      receiptsFetcherQueue.enqueue(Seq(header.hash))
    }

    private def updateValidationState(header: BlockHeader): Unit = {
      import syncConfig.{fastSyncBlockValidationK as K, fastSyncBlockValidationX as X}
      syncState = syncState.updateNextBlockToValidate(header, K, X)
    }

    private def handleRewind(
        header: BlockHeader,
        peer: Peer,
        N: Int,
        duration: FiniteDuration,
        continueSyncing: Boolean = true
    ): Behavior[Any] = {
      blacklist.add(peer.id, duration, BlockHeaderValidationFailed)
      if header.number <= syncState.safeDownloadTarget then {
        discardLastBlocks(header.number, N)
        syncState = syncState.updateDiscardedBlocks(header, N)
        if header.number >= syncState.pivotBlock.number then {
          updatePivotBlock(LastBlockValidationFailed)
        } else if continueSyncing then {
          processSyncing()
        } else Behaviors.same
      } else if continueSyncing then {
        processSyncing()
      } else Behaviors.same
    }

    // scalastyle:off method.length
    private def handleBlockHeaders(peer: Peer, headers: Seq[BlockHeader]): Behavior[Any] = {
      def processHeader(header: BlockHeader): Either[HeaderProcessingResult, (BlockHeader, ChainWeight)] =
        for {
          validatedHeader <- validateHeader(header, peer)
          parentWeight <- getParentChainWeight(header)
        } yield (validatedHeader, parentWeight)

      def getParentChainWeight(header: BlockHeader) =
        blockchainReader.getChainWeightByHash(header.parentHash).toRight(ParentChainWeightNotFound(header))

      @tailrec
      def processHeaders(headers: Seq[BlockHeader]): HeaderProcessingResult =
        if headers.nonEmpty then {
          val header = headers.head
          processHeader(header) match {
            case Left(result) => result
            case Right((header, weight)) =>
              updateSyncState(header, weight)
              if header.number == syncState.safeDownloadTarget then {
                ImportedPivotBlock
              } else {
                processHeaders(headers.tail)
              }
          }
        } else HeadersProcessingFinished

      if !checkHeadersChain(headers) then {
        blacklist.add(peer.id, blacklistDuration, ErrorInBlockHeaders)
        processSyncing()
      } else
        processHeaders(headers) match {
          case ParentChainWeightNotFound(header) =>
            // We could end in wrong fork and get blocked so we should rewind our state a little
            // we blacklist peer just in case we got malicious peer which would send us bad blocks, forcing us to roll
            // back to genesis
            log.warn(
              "Parent chain weight not found for block {} (parent: {}). Will retry sync with alternate peer.",
              header.idTag,
              header.parentHash
            )
            handleRewind(header, peer, syncConfig.fastSyncBlockValidationN, syncConfig.blacklistDuration)
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
        }
    }

    private def handleBlockBodies(
        peer: Peer,
        requestedHashes: Seq[ByteString],
        blockBodies: Seq[BlockBody]
    ): Behavior[Any] = {
      if blockBodies.isEmpty then {
        val knownHashes = requestedHashes.map(ByteStringUtils.hash2string)
        blacklist.add(peer.id, blacklistDuration, EmptyBlockBodies(knownHashes))
        syncState = syncState.enqueueBlockBodies(requestedHashes)
        bodiesFetcherQueue.enqueue(requestedHashes)
      } else {
        validateBlocks(requestedHashes, blockBodies) match {
          case BlockBodyValidationResult.Valid =>
            insertBlocks(requestedHashes, blockBodies)
          case BlockBodyValidationResult.Invalid =>
            blacklist.add(peer.id, blacklistDuration, BlockBodiesNotMatchingHeaders)
            syncState = syncState.enqueueBlockBodies(requestedHashes)
            bodiesFetcherQueue.enqueue(requestedHashes)
          case BlockBodyValidationResult.DbError =>
            redownloadBlockchain()
        }
      }

      processSyncing()
    }

    private def handleReceipts(
        peer: Peer,
        requestedHashes: Seq[ByteString],
        receipts: Seq[Seq[Receipt]]
    ): Behavior[Any] = {
      if receipts.isEmpty then {
        val knownHashes = requestedHashes.map(ByteStringUtils.hash2string)
        blacklist.add(peer.id, blacklistDuration, EmptyReceipts(knownHashes))
        syncState = syncState.enqueueReceipts(requestedHashes)
        receiptsFetcherQueue.enqueue(requestedHashes)
      } else {
        validateReceipts(requestedHashes, receipts) match {
          case ReceiptsValidationResult.Valid(blockHashesWithReceipts) =>
            if blockHashesWithReceipts.isEmpty then {
              log.warn(
                "Received receipts from peer [{}] but have no matching requested hashes (unsolicited or late response)",
                peer.id
              )
            } else {
              blockHashesWithReceipts
                .map { case (hash, receiptsForBlock) =>
                  blockchainWriter.storeReceipts(hash, receiptsForBlock)
                }
                .reduce(_.and(_))
                .commit()
            }

            val receivedHashes = blockHashesWithReceipts.map(_._1)
            updateBestBlockIfNeeded(receivedHashes)

            val remainingReceipts = requestedHashes.drop(receipts.size)
            if remainingReceipts.nonEmpty then {
              syncState = syncState.enqueueReceipts(remainingReceipts)
              receiptsFetcherQueue.enqueue(remainingReceipts)
            }

          case ReceiptsValidationResult.Invalid(error) =>
            val knownHashes = requestedHashes.map(h => Hex.toHexString(h.toArray[Byte]))
            blacklist.add(peer.id, blacklistDuration, InvalidReceipts(knownHashes, error))
            syncState = syncState.enqueueReceipts(requestedHashes)
            receiptsFetcherQueue.enqueue(requestedHashes)

          case ReceiptsValidationResult.DbError =>
            redownloadBlockchain()
        }
      }

      processSyncing()
    }

    private def handleRequestFailure(peer: Peer, reason: BlacklistReason): Unit = {
      // The handler ref is pruned from assignedHandlers by the RequestTerminated death-watch when the Classic PRH
      // child stops; here we only return the peer's in-flight work and apply the blacklist.
      val failedBodies = requestedBlockBodies.getOrElse(peer.id, Nil)
      val failedReceipts = requestedReceipts.getOrElse(peer.id, Nil)

      syncState = syncState
        .enqueueBlockBodies(failedBodies)
        .enqueueReceipts(failedReceipts)

      // Return items to fetcher queues: unreserve if the peer is still tracked in-flight,
      // otherwise enqueue directly (handles post-redownloadBlockchain orphaned handlers).
      if bodiesFetcherQueue.inFlight(peer.id).isDefined then bodiesFetcherQueue.unreserve(peer.id)
      else if failedBodies.nonEmpty then bodiesFetcherQueue.enqueue(failedBodies)

      if receiptsFetcherQueue.inFlight(peer.id).isDefined then receiptsFetcherQueue.unreserve(peer.id)
      else if failedReceipts.nonEmpty then receiptsFetcherQueue.enqueue(failedReceipts)

      headersFetcherQueue.unreserve(peer.id)
      requestedBlockBodies = requestedBlockBodies - peer.id
      requestedReceipts = requestedReceipts - peer.id

      // Peers that close the connection before answering (PEER_REQUEST_DISCONNECTED) get a longer
      // cooldown so they don't immediately rejoin and trigger another GetReceipts dispatch cycle.
      val effectiveDuration = reason match {
        case FastSyncRequestFailed("connection closed") => 10.minutes
        case _                                          => blacklistDuration
      }
      blacklistIfHandshaked(peer.id, effectiveDuration, reason)
    }

    /** Restarts download from a few blocks behind the current best block header, as an unexpected DB error happened
      */
    private def redownloadBlockchain(): Unit = {
      syncState = syncState.copy(
        blockBodiesQueue = Seq.empty,
        receiptsQueue = Seq.empty,
        // todo adjust the formula to minimize redownloaded block headers
        bestBlockHeaderNumber = (syncState.bestBlockHeaderNumber - 2 * blockHeadersPerRequest).max(0)
      )
      // Drop all in-flight + pending items; orphaned handler failures will enqueue to fresh queues.
      bodiesFetcherQueue = new BodiesFetcherQueue(ethRateTracker)
      receiptsFetcherQueue = new ReceiptsFetcherQueue(ethRateTracker)
      headersFetcherQueue = new HeadersFetcherQueue(ethRateTracker)
      headerResponseBuffer.clear()
      headerQueueHighWatermark = syncState.bestBlockHeaderNumber
      log.debug("Missing block header for known hash")
    }

    private def persistSyncState(): Unit =
      syncStateStorageActor ! StateStorageActor.Persist(
        syncState.copy(
          blockBodiesQueue = requestedBlockBodies.values.flatten.toSeq.distinct ++ syncState.blockBodiesQueue,
          receiptsQueue = requestedReceipts.values.flatten.toSeq.distinct ++ syncState.receiptsQueue
        )
      )

    private def printStatus(): Unit = {
      def formatPeerEntry(entry: PeerWithInfo): String = formatPeer(entry.peer)
      def formatPeer(peer: Peer): String =
        s"${com.chipprbots.ethereum.network.getHostName(peer.remoteAddress.getAddress)}:${peer.remoteAddress.getPort}"

      def pct(done: BigInt, total: BigInt): Int =
        if total <= 0 then 0
        else {
          val p = ((done.toDouble / total.toDouble) * 100.0).toInt
          p.max(0).min(100)
        }

      def formatRate(valuePerSec: Double): String =
        f"$valuePerSec%.2f/s"

      def wormToBrainBar(percent: Int, travelSlots: Int = 24): String = {
        val p = percent.max(0).min(100)
        val slots = travelSlots.max(4)
        val wormPos = ((p.toDouble / 100.0) * (slots - 1)).round.toInt.max(0).min(slots - 1)
        val sb = new StringBuilder(slots + 3)
        sb.append('[')
        var i = 0
        while i < slots do {
          if i < wormPos then sb.append('=')
          else if i == wormPos then sb.append("🪱")
          else sb.append('.')
          i += 1
        }
        sb.append("🧠")
        sb.append(']')
        sb.toString
      }

      val nowMs = System.currentTimeMillis()
      val dtSeconds = ((nowMs - lastProgressLogMs).toDouble / 1000.0).max(0.001)

      // Prefer the persisted fast-sync view of progress (lastFullBlockNumber), but also show current best.
      val bestBlockNow = blockchainReader.getBestBlockNumber
      val lastFull = syncState.lastFullBlockNumber.max(bestBlockNow)

      val deltaBlocks = (lastFull - lastLoggedFullBlock).toDouble
      val blocksPerSec = deltaBlocks / dtSeconds

      val savedNodes = syncState.downloadedNodesCount
      val totalNodes = syncState.totalNodesCount.max(1)
      val deltaNodes = (savedNodes - lastLoggedStateNodes).toDouble
      val nodesPerSec = deltaNodes / dtSeconds

      val blockTarget = syncState.pivotBlock.number.max(1)
      val blockPercent = pct(lastFull, blockTarget)
      val nodePercent = (((savedNodes.toDouble / totalNodes.toDouble) * 100.0).toInt).max(0).min(100)

      val blocksToBrain = wormToBrainBar(blockPercent)

      val phase =
        if !syncState.isBlockchainWorkFinished then {
          if syncState.receiptsQueue.nonEmpty then "Receipts"
          else if syncState.blockBodiesQueue.nonEmpty then "BlockBodies"
          else "BlockHeaders"
        } else if !syncState.stateSyncFinished then {
          "State"
        } else {
          "Finalizing"
        }

      val blacklistedIds = blacklist.keys
      log.info(
        s"""|🧠🪱 FastSync Progress: phase=$phase, blocks=$lastFull/$blockTarget (${blockPercent}%), state=$savedNodes/$totalNodes (${nodePercent}%),
        |to_brain=$blocksToBrain, rates=${formatRate(blocksPerSec)} blocks, ${formatRate(
             nodesPerSec
           )} nodes, queues=bodies=${syncState.blockBodiesQueue.size}, receipts=${syncState.receiptsQueue.size},
            |peers=waiting=${assignedHandlers.size}, connected=${handshakedPeers.size}, blacklisted=${blacklistedIds.size}, elapsed=${totalMinutesTaken()}m
            |""".stripMargin.replace("\n", " ")
      )

      lastProgressLogMs = nowMs
      lastLoggedFullBlock = lastFull
      lastLoggedStateNodes = savedNodes

      log.debug(
        s"""|Connection status: inFlightHandlers({})/
            |handshaked({})
            | blacklisted({})
            |""".stripMargin.replace("\n", " "),
        assignedHandlers.map(_.path.name).toSeq.sorted.mkString(", "),
        handshakedPeers.values.toList.map(e => formatPeerEntry(e)).sorted.mkString(", "),
        blacklistedIds.map(_.value).mkString(", ")
      )
    }

    private def insertBlocks(requestedHashes: Seq[ByteString], blockBodies: Seq[BlockBody]): Unit = {
      val blockHashesWithBodies = requestedHashes.zip(blockBodies)
      if blockHashesWithBodies.isEmpty then
        log.warn(
          "Received block bodies but have no matching requested hashes (unsolicited or late response)"
        )
      else {
        blockHashesWithBodies
          .map { case (hash, body) =>
            blockchainWriter.storeBlockBody(hash, body)
          }
          .reduce(_.and(_))
          .commit()

        val receivedHashes = requestedHashes.take(blockBodies.size)
        updateBestBlockIfNeeded(receivedHashes)
        val remainingBlockBodies = requestedHashes.drop(blockBodies.size)
        if remainingBlockBodies.nonEmpty then {
          syncState = syncState.enqueueBlockBodies(remainingBlockBodies)
          bodiesFetcherQueue.enqueue(remainingBlockBodies)
        }
      } // else blockHashesWithBodies.nonEmpty
    }

    def hasBestBlockFreshEnoughToUpdatePivotBlock(info: PeerInfo, state: SyncState, syncConfig: SyncConfig): Boolean =
      (info.maxBlockNumber - syncConfig.pivotBlockOffset) - state.pivotBlock.number >= syncConfig.maxPivotBlockAge

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
      syncState.isBlockchainWorkFinished && assignedHandlers.isEmpty

    def notInTheMiddleOfUpdate: Boolean =
      !(syncState.updatingPivotBlock || stateSyncRestartRequested)

    def pivotBlockIsStale(): Boolean = {
      val peersWithInfo = peersToDownloadFrom.values.toList
      if peersWithInfo.isEmpty then {
        false
      } else {
        val peerWithBestBlockInNetwork = peersWithInfo.maxBy(_.peerInfo.maxBlockNumber)

        val bestPossibleTargetDifferenceInNetwork =
          (peerWithBestBlockInNetwork.peerInfo.maxBlockNumber - syncConfig.pivotBlockOffset) - syncState.pivotBlock.number

        val peersWithTooFreshPossiblePivotBlock =
          getPeersWithFreshEnoughPivot(NonEmptyList.fromListUnsafe(peersWithInfo), syncState, syncConfig)

        if peersWithTooFreshPossiblePivotBlock.isEmpty then {
          log.debug(
            s"There are no peers with too fresh possible pivot block. " +
              s"Current pivot block is {} blocks behind best possible target",
            bestPossibleTargetDifferenceInNetwork
          )
          false
        } else {
          val pivotBlockIsStale = peersWithTooFreshPossiblePivotBlock.size >= minPeersToChoosePivotBlock

          log.debug(
            "There are {} peers with possible new pivot block, " +
              "best known pivot in current peer list has number {}",
            peersWithTooFreshPossiblePivotBlock.size,
            peerWithBestBlockInNetwork.peerInfo.maxBlockNumber
          )

          pivotBlockIsStale
        }
      }
    }

    def processSyncing(): Behavior[Any] = {
      // Accumulator mirrors the Classic "last context.become wins" semantics: the stale-state branch may transition
      // to waitingForPivotBlockUpdate, then the final block may override it (or keep it). The method returns the
      // last-decided behavior.
      var nextBehavior: Behavior[Any] = Behaviors.same
      FastSyncMetrics.measure(syncState)
      log.debug(
        "Start of processSyncing: {}",
        Map(
          "fullySynced" -> fullySynced,
          "blockchainDataToDownload" -> blockchainDataToDownload,
          "noBlockchainWorkRemaining" -> noBlockchainWorkRemaining,
          "stateSyncFinished" -> syncState.stateSyncFinished,
          "notInTheMiddleOfUpdate" -> notInTheMiddleOfUpdate
        )
      )
      // Start state download in parallel with block download — don't wait for blocks to finish.
      // State only depends on the pivot block's state root, which we know from the start.
      if !stateSyncStarted && !syncState.stateSyncFinished && notInTheMiddleOfUpdate &&
        syncState.pivotBlock.stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash)
      then {
        log.info(
          "Starting state download in parallel with block download for pivot block {}",
          syncState.pivotBlock.number
        )
        stateSyncStarted = true
        stateSyncRestartRequested = false
        syncStateScheduler ! StartSyncingTo(syncState.pivotBlock.stateRoot, syncState.pivotBlock.number)
      }

      // Refresh pivot for state download when it becomes stale — whether blocks are done or not.
      // The SNAP serve window is ~128 blocks (~28 min on ETC). If state download takes longer,
      // peers stop serving the old root. Refreshing the pivot gives state a fresh root to work with.
      // Reset the restart flag once the pivot update completes (updatingPivotBlock returns to false).
      if stateSyncRestartRequested && !syncState.updatingPivotBlock then {
        stateSyncRestartRequested = false
      }
      if stateSyncStarted && !syncState.stateSyncFinished && !stateSyncRestartRequested &&
        !syncState.updatingPivotBlock
      then {
        // Detect stale state root via two signals:
        // 1. pivotBlockIsStale() — peers are ahead of our pivot
        // 2. All peers blacklisted — evidence the root expired (peers return empty for this root)
        val allPeersBlacklisted = peersToDownloadFrom.isEmpty && handshakedPeers.nonEmpty
        if pivotBlockIsStale() || allPeersBlacklisted then {
          log.info(
            "State root stale (allBlacklisted={}), requesting pivot update for state refresh",
            allPeersBlacklisted
          )
          syncStateScheduler ! RestartRequested
          stateSyncRestartRequested = true
          nextBehavior = askForPivotBlockUpdate(ImportedLastBlock)
        }
      }

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
      if noBlockchainWorkRemaining && !syncState.stateSyncFinished && stateSyncStarted then {
        val downloaded = FastSyncMetrics.getDownloadedNodes
        val dynTotal = FastSyncMetrics.getTotalNodes
        val effectiveTotal = math.max(dynTotal, syncState.maxTotalNodesCount)
        val pct = if effectiveTotal > 0 then (downloaded.toDouble / effectiveTotal * 100).toInt else 0
        if pct >= 95 && effectiveTotal > 1000 then {
          log.info(
            "State download at {}% ({}/{}, peak total {}) with blocks complete. " +
              "Remaining nodes are at the chain tip and change every block. " +
              "Completing fast sync — regular sync will fetch missing nodes on-demand.",
            pct,
            downloaded,
            effectiveTotal,
            syncState.maxTotalNodesCount
          )
          syncState = syncState.copy(stateSyncFinished = true)
        }
      }

      if fullySynced then {
        finish()
      } else {
        if blockchainDataToDownload then {
          processDownloads()
        } else if noBlockchainWorkRemaining && !syncState.stateSyncFinished && notInTheMiddleOfUpdate then {
          if pivotBlockIsStale() then {
            log.debug("Restarting state sync to new pivot block")
            syncStateScheduler ! RestartRequested
            stateSyncRestartRequested = true
          }
          nextBehavior
        } else {
          log.debug("No more items to request, waiting for {} responses", assignedHandlers.size)
          nextBehavior
        }
      }
    }

    def finish(): Behavior[Any] = {
      val totalTime = totalMinutesTaken()
      FastSyncMetrics.setFastSyncTotalTimeGauge(totalTime.toDouble)
      log.info("Total time taken for FastSync was {} minutes", totalTime)
      log.info("Block synchronization in fast mode finished, switching to regular mode")

      // We have downloaded to target + fastSyncBlockValidationX, se we must discard those last blocks
      discardLastBlocks(syncState.safeDownloadTarget, syncConfig.fastSyncBlockValidationX - 1)
      cleanup()
      appStateStorage.fastSyncDone().commit()
      ctx.scheduleOnce(syncSwitchDelay, syncController, Done)
      idle()
    }

    def cleanup(): Unit = {
      timers.cancel(HeartBeatTimerKey)
      timers.cancel(PersistTimerKey)
      timers.cancel(PrintStatusTimerKey)
      // StateStorageActor is now Typed; stop the classic-adapted ref directly so the child terminates.
      if syncStateStorageActor != null then ctx.stop(syncStateStorageActor.toTyped[Nothing])
      fastSyncStateStorage.purge()
    }

    def processDownloads(): Behavior[Any] = {
      // Self-heal: if syncState has items but fetcher queues are empty (e.g., post-restart), re-sync them
      if syncState.blockBodiesQueue.nonEmpty && bodiesFetcherQueue.pending == 0 && bodiesFetcherQueue.inFlightCount == 0
      then bodiesFetcherQueue.enqueue(syncState.blockBodiesQueue)
      if syncState.receiptsQueue.nonEmpty && receiptsFetcherQueue.pending == 0 && receiptsFetcherQueue.inFlightCount == 0
      then receiptsFetcherQueue.enqueue(syncState.receiptsQueue)

      val hasWork = bodiesFetcherQueue.pending > 0 || receiptsFetcherQueue.pending > 0 ||
        headersFetcherQueue.pending > 0 || headersFetcherQueue.inFlightCount > 0 ||
        syncState.bestBlockHeaderNumber < syncState.safeDownloadTarget

      if handshakedPeers.isEmpty then {
        if assignedHandlers.nonEmpty then
          log.debug("There are no available peers, waiting for [{}] responses.", assignedHandlers.size)
        else ctx.scheduleOnce(syncRetryInterval, ctx.self, ProcessSyncing)
      } else if hasWork then {
        dispatchWork()
      } else if assignedHandlers.nonEmpty then {
        log.debug("No pending work; waiting for [{}] in-flight responses.", assignedHandlers.size)
      } else {
        ctx.scheduleOnce(syncRetryInterval, ctx.self, ProcessSyncing)
      }
      Behaviors.same
    }

    /** Spawn a Typed PeerRequestHandler child, register a death-watch that delivers RequestTerminated, and track the
      * ref in `assignedHandlers`.
      */
    private def spawnHandler(behaviorName: String, beh: Behavior[PeerRequestHandler.Command]): Unit = {
      val handler = ctx.spawn(beh, behaviorName)
      ctx.watchWith(handler, RequestTerminated(handler))
      assignedHandlers += handler
    }

    private def dispatchWork(): Unit = {
      val allPeers = handshakedPeers.values
      val targetRtt = globalMedianRttMs

      // Bodies: dispatch to all idle-for-bodies peers simultaneously (mirrors go-ethereum fetchBodies goroutine)
      val bodyAssignments = ConcurrentFetch.dispatchTo(bodiesFetcherQueue, allPeers, targetRtt, "bodies", log)
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
            replyTo = prhResultAdapter
          )
        )
        requestedBlockBodies += peerWithInfo.peer.id -> req.hashes
        syncState = syncState.copy(blockBodiesQueue = syncState.blockBodiesQueue.diff(req.hashes))
      }

      // Receipts: dispatch to all idle-for-receipts peers simultaneously (mirrors go-ethereum fetchReceipts goroutine)
      val receiptAssignments = ConcurrentFetch.dispatchTo(receiptsFetcherQueue, allPeers, targetRtt, "receipts", log)
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
            replyTo = prhResultAdapter
          )
        )
        requestedReceipts += peerWithInfo.peer.id -> req.blockHashes
        syncState = syncState.copy(receiptsQueue = syncState.receiptsQueue.diff(req.blockHashes))
      }

      // Headers: concurrent dispatch to all eligible peers (mirrors go-ethereum skeleton.assignTasks)
      enqueueHeadersIfNeeded()
      val eligibleHeaderPeers = allPeers.filter(_.peerInfo.maxBlockNumber >= syncState.pivotBlock.number).toSeq
      val headerAssignments =
        ConcurrentFetch.dispatchTo(headersFetcherQueue, eligibleHeaderPeers, targetRtt, "headers", log)
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
            replyTo = prhResultAdapter
          )
        )
      }
    }

    private def enqueueHeadersIfNeeded(): Unit = {
      val fillTarget = (headerQueueHighWatermark + FastSync.HeaderQueueLookahead).min(syncState.safeDownloadTarget)
      if fillTarget > headerQueueHighWatermark then {
        val nums = (headerQueueHighWatermark + 1 to fillTarget).toSeq
        headersFetcherQueue.enqueue(nums)
        headerQueueHighWatermark = fillTarget
        log.debug("Enqueued {} header block numbers [{}-{}]", nums.size, nums.head, nums.last)
      }
    }

    private def drainOrderedHeaders(peer: Peer): Behavior[Any] = {
      // Prune anything superseded by a redownloadBlockchain reset
      while headerResponseBuffer.nonEmpty && headerResponseBuffer.firstKey <= syncState.bestBlockHeaderNumber do
        headerResponseBuffer.remove(headerResponseBuffer.firstKey)

      // Drain the contiguous run from bestBlockHeaderNumber + 1. Each handleBlockHeaders call may decide a behavior
      // transition; the last decision wins (mirrors the Classic "last context.become wins" semantics).
      var nextBehavior: Behavior[Any] = Behaviors.same
      var continue = true
      while continue do
        headerResponseBuffer.headOption match {
          case Some((blockNum, headers)) if blockNum == syncState.bestBlockHeaderNumber + 1 =>
            headerResponseBuffer.remove(blockNum)
            nextBehavior = handleBlockHeaders(peer, headers)
          case _ =>
            continue = false
        }
      enqueueHeadersIfNeeded()
      nextBehavior
    }

    private def blockchainDataToDownload: Boolean =
      syncState.blockChainWorkQueued || syncState.bestBlockHeaderNumber < syncState.safeDownloadTarget

    private def fullySynced: Boolean =
      syncState.isBlockchainWorkFinished && assignedHandlers.isEmpty && syncState.stateSyncFinished

    private def updateBestBlockIfNeeded(receivedHashes: Seq[ByteString]): Unit = {
      val fullBlocks = receivedHashes.flatMap { hash =>
        for {
          header <- blockchainReader.getBlockHeaderByHash(hash)
          _ <- blockchainReader.getBlockBodyByHash(hash)
          _ <- blockchainReader.getReceiptsByHash(hash)
        } yield header
      }

      if fullBlocks.nonEmpty then {
        val bestReceivedBlock = fullBlocks.maxBy(_.number)
        val lastStoredBestBlockNumber = blockchainReader.getBestBlockNumber
        if lastStoredBestBlockNumber < bestReceivedBlock.number then {
          // Set best block info with BOTH hash and number (putBestBlockNumber only
          // sets the number, leaving getBestBlockInfo().hash stale/empty).
          appStateStorage
            .putBestBlockInfo(BlockInfo(bestReceivedBlock.hash, bestReceivedBlock.number))
            .and(blockNumberMappingStorage.put(bestReceivedBlock.number, bestReceivedBlock.hash))
            .commit()
        }
        syncState = syncState.copy(lastFullBlockNumber = bestReceivedBlock.number.max(lastStoredBestBlockNumber))
      }
    }
  }

  /** Number of block numbers to keep ahead of bestBlockHeaderNumber in the header fetch queue. Ensures peers always
    * have work to do without enqueuing the entire chain at once. Mirrors go-ethereum skeleton scratchHeaders (131,072)
    * in spirit; tuned for actor-model dispatch.
    */
  val HeaderQueueLookahead: Int = 4096

  private case class UpdatePivotBlock(reason: PivotBlockUpdateReason)
  private case object ProcessSyncing
  private case object PersistSyncState
  private case object PrintStatus

  /** Carries the Classic reply-to for an ask-based `SyncProtocol.GetStatus`. */
  final private[fast] case class GetStatusCmd(replyTo: ActorRef)

  /** Core-internal: a watched Classic `PeerRequestHandler` child stopped (it replies to `context.parent` then stops
    * itself). Delivered via `ctx.watchWith`, this is the single place that removes the handler from the active set —
    * the response/failure handlers only do data processing (keyed by `peer.id`).
    */
  final private[fast] case class RequestTerminated(handler: TypedActorRef[PeerRequestHandler.Command])

  /** Core-internal: periodic poll tick that re-requests the handshaked peer list from `networkPeerManager` (replaces
    * the `scheduleWithFixedDelay` that `PeerListSupportNg` ran in the Classic actor).
    */
  private case object ScanPeersTick

  /** Core-internal: retry pivot block selection after a delay (was a private case object inside the Classic actor). */
  private case object RetryPivotBlockSelection

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
  ) {

    def enqueueBlockBodies(blockBodies: Seq[ByteString]): SyncState =
      copy(blockBodiesQueue = blockBodiesQueue ++ blockBodies)

    def enqueueReceipts(receipts: Seq[ByteString]): SyncState =
      copy(receiptsQueue = receiptsQueue ++ receipts)

    def blockChainWorkQueued: Boolean =
      blockBodiesQueue.nonEmpty || receiptsQueue.nonEmpty

    def updateNextBlockToValidate(header: BlockHeader, K: Int, X: Int): SyncState = copy(
      nextBlockToFullyValidate =
        if bestBlockHeaderNumber >= pivotBlock.number - X then header.number + 1
        else (header.number + K / 2 + Random.nextInt(K)).min(pivotBlock.number - X)
    )

    def updateDiscardedBlocks(header: BlockHeader, N: Int): SyncState = copy(
      blockBodiesQueue = Seq.empty,
      receiptsQueue = Seq.empty,
      bestBlockHeaderNumber = (header.number - N - 1).max(0),
      nextBlockToFullyValidate = (header.number - N).max(1)
    )

    def updatePivotBlock(newPivot: BlockHeader, numberOfSafeBlocks: BigInt, updateFailures: Boolean): SyncState =
      copy(
        pivotBlock = newPivot,
        safeDownloadTarget = newPivot.number + numberOfSafeBlocks,
        pivotBlockUpdateFailures = if updateFailures then pivotBlockUpdateFailures + 1 else pivotBlockUpdateFailures,
        updatingPivotBlock = false
      )

    def isBlockchainWorkFinished: Boolean =
      bestBlockHeaderNumber >= safeDownloadTarget && !blockChainWorkQueued
  }

  sealed trait HashType {
    def v: ByteString
  }

  case class StateMptNodeHash(v: ByteString) extends HashType
  case class ContractStorageMptNodeHash(v: ByteString) extends HashType
  case class EvmCodeHash(v: ByteString) extends HashType
  case class StorageRootHash(v: ByteString) extends HashType

  case object Done
  case object FallbackToSnapSync

  sealed abstract class HeaderProcessingResult
  case object HeadersProcessingFinished extends HeaderProcessingResult
  case class ParentChainWeightNotFound(header: BlockHeader) extends HeaderProcessingResult
  case class ValidationFailed(header: BlockHeader, peer: Peer) extends HeaderProcessingResult
  case object ImportedPivotBlock extends HeaderProcessingResult

  sealed abstract class PivotBlockUpdateReason {
    def isSyncRestart: Boolean = this match {
      case ImportedLastBlock         => false
      case LastBlockValidationFailed => false
      case SyncRestart               => true
    }
  }
  case object ImportedLastBlock extends PivotBlockUpdateReason
  case object LastBlockValidationFailed extends PivotBlockUpdateReason
  case object SyncRestart extends PivotBlockUpdateReason
}
