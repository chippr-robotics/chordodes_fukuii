package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.Terminated
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.BootstrapComplete
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.PivotBootstrapFailed
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrap
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrapByHash
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

class SyncController(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    appStateStorage: AppStateStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    evmCodeStorage: EvmCodeStorage,
    stateStorage: StateStorage,
    nodeStorage: NodeStorage,
    flatSlotStorage: FlatSlotStorage,
    fastSyncStateStorage: FastSyncStateStorage,
    consensus: ConsensusAdapter,
    validators: Validators,
    peerEventBus: ActorRef,
    pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[
      com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
    ],
    ommersPool: org.apache.pekko.actor.typed.ActorRef[com.chipprbots.ethereum.ommers.OmmersPool.Command],
    networkPeerManager: ActorRef,
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    configBuilder: BlockchainConfigBuilder,
    messConfig: Option[MESSConfig] = None,
    forkChoiceManagerOpt: Option[ForkChoiceManager] = None,
    externalSchedulerOpt: Option[Scheduler] = None
) extends Actor
    with ActorLogging {

  private case object RestartFastSyncNow
  private case object PollRecoveryPeers
  // Self-ping: the recovery recent-root header bootstrap for this generation took too long → decline the roll.
  private case class RecentRootTimeout(generation: Int)
  // spec 004 (Decoupled Heal Serve-Root) T012: self-ping for the HEALING serve-root header bootstrap. Distinct
  // from RecentRootTimeout so the healing serve-root request never contends with storage recovery's requester.
  private case class HealingServeRootTimeout(generation: Int)

  // Generation counters for actor names to prevent Pekko name collisions
  // (context.stop is async — new actors can race with still-stopping ones).
  private var bootstrapGeneration: Long = 0
  private var syncGeneration: Long = 0

  // Recovery recent-root roll (Task #5/#6): post-SNAP storage recovery asks for a recent canonical root
  // when the saved pivot has aged out of peers' serve window. We fetch a recent header via
  // PivotHeaderBootstrap (inline in `runningRecovery` — no transition into the deadlock-prone bootstrap
  // state) and reply with StorageRecoveryActor.RecentRoot. Only one request is serviced at a time.
  private var recentRootRequester: Option[ActorRef] = None
  private var recentRootBootstrap: Option[(ActorRef, ActorRef)] = None // (peersClient, headerBootstrap)
  private var recentRootGeneration: Int = 0

  // spec 004 (Decoupled Heal Serve-Root) T012: a SEPARATE requester slot + bootstrap + generation for the HEALING
  // serve-root request, so it never contends with `recentRootRequester` (storage recovery's single slot). During
  // healing, SNAPSyncController (the child) asks for a newest-servable root via RequestHealingServeRoot; we fetch
  // a recent header with a dedicated PivotHeaderBootstrap (inline in `runningSnapSync` — no transition into the
  // deadlock-prone bootstrap state) and reply HealingServeRoot to the child. Only one is serviced at a time.
  private var healingServeRootRequester: Option[ActorRef] = None
  private var healingServeRootBootstrap: Option[(ActorRef, ActorRef)] = None // (peersClient, headerBootstrap)
  private var healingServeRootGeneration: Int = 0
  // Roll the download root this many blocks back from the network head — comfortably inside core-geth's
  // ~128-block snapshot serve window so peers can serve the recent root, yet recent enough that ~all
  // cold contracts' storage is unchanged since the original pivot (and thus content-identical).
  private val RecentRootMarginBlocks: BigInt = BigInt(64)

  // SNAP<->Fast sync bounce cycle counter, persisted across restarts.
  private var snapFastCycleCount: Int = appStateStorage.getSnapFastCycleCount()

  // Latest CL-driven head hint received from ForkChoiceManager. Buffered so that when SNAP
  // sync starts (which may happen after the CL has already pushed several FCUs), the freshest
  // head is available as the pivot target. Only populated on post-merge chains where TTD is
  // configured AND a ForkChoiceManager was supplied — ETC mainnet leaves this `None` forever
  // and the existing TD-based pivot path is unaffected. Closes #1207.
  private var latestBeaconHead: Option[ForkChoiceManager.BeaconHead] = None

  // Whether SNAP should consume CL-driven pivot selection. Captured once at construction
  // because both `syncConfig` and the chain config are stable for the actor's lifetime.
  private val isPostMergeChain: Boolean = configBuilder.blockchainConfig.terminalTotalDifficulty.isDefined
  private val clPivotEnabled: Boolean = isPostMergeChain && forkChoiceManagerOpt.isDefined

  // TD calibration stats — updated by CalibrateChainWeightFromPeer handler.
  // calibrationSucceeded and networkBestTD are read by the TD_CALIBRATION_STATS periodic log
  // (future enhancement) — suppress unused warnings.
  private var tdCalibrationAttempt: Int = 0 // number of tier-3 (local chain) attempts
  @annotation.unused
  private var calibrationSucceeded: Boolean = false
  private var lastCalibrationSource: String = "NONE"
  @annotation.unused
  private var networkBestTD: BigInt = BigInt(0) // last peerTD pushed by NPA

  override def preStart(): Unit = {
    super.preStart()
    if clPivotEnabled then {
      forkChoiceManagerOpt.foreach { fcm =>
        fcm.setListener(self)
        log.info(
          "Registered SyncController as ForkChoiceManager listener (post-merge chain TTD={}); " +
            "SNAP pivot will be CL-driven once first forkchoiceUpdated arrives.",
          configBuilder.blockchainConfig.terminalTotalDifficulty.get
        )
      }
    }
  }

  override def postStop(): Unit = {
    forkChoiceManagerOpt.foreach(_.clearListener())
    super.postStop()
  }

  private def stopSyncChildren(): Unit = {
    // Stop all sync-related child actors. Names may have generation suffixes
    // (e.g. "fast-sync-3") because PoisonPill is async and a new actor can
    // race with a still-stopping one.
    val prefixes = Seq(
      "fast-sync",
      "regular-sync",
      "peers-client",
      "snap-sync"
    )
    context.children
      .filter { child =>
        val n = child.path.name
        prefixes.exists(p => n == p || n.startsWith(s"$p-"))
      }
      .foreach(_ ! PoisonPill)

    // Stop any generation-numbered bootstrap children
    context.children
      .filter { child =>
        val n = child.path.name
        n.startsWith("peers-client-bootstrap") || n.startsWith("pivot-header-bootstrap")
      }
      .foreach(_ ! PoisonPill)

    // Ensure snap-sync routing is not left pointing at a dead actor.
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
      context.system.deadLetters
    )
  }

  private def handleResetFastSync(): Unit = {
    log.warning("ResetFastSync requested: clearing persisted fast-sync markers")
    appStateStorage.clearFastSyncDone().commit()
    fastSyncStateStorage.purge()
    sender() ! SyncProtocol.ResetFastSyncResponse(reset = true)
  }

  private def handleRestartFastSync(): Unit = {
    val nowMillis = System.currentTimeMillis()
    val cooldownUntil = appStateStorage.getFastSyncCooldownUntilMillis()

    if cooldownUntil > nowMillis then {
      val delay = (cooldownUntil - nowMillis).millis
      log.warning(
        "RestartFastSync requested but circuit-breaker is open (cool-off {} remaining); scheduling restart",
        delay
      )
      scheduler.scheduleOnce(delay, self, RestartFastSyncNow)
      sender() ! SyncProtocol.RestartFastSyncResponse(started = false, cooldownUntilMillis = cooldownUntil)
    } else {
      self ! RestartFastSyncNow
      sender() ! SyncProtocol.RestartFastSyncResponse(started = true, cooldownUntilMillis = nowMillis)
    }
  }

  private def doRestartFastSyncNow(): Unit = {
    val nowMillis = System.currentTimeMillis()
    val cooldownUntil = nowMillis + syncConfig.fastSyncRestartCooloff.toMillis

    log.warning(
      "Restarting fast sync now (cool-off {}); stopping current sync actors and clearing fast-sync markers",
      syncConfig.fastSyncRestartCooloff
    )

    // spec 004 MUST-FIX: this is reachable from runningSnapSync (RestartFastSyncNow). Clear the healing serve-root
    // latch before we tear down sync children and become(runningFastSync), so no stale requester/bootstrap lingers.
    abortHealingServeRootRequest("restart fast sync — leaving snap sync")
    stopSyncChildren()
    appStateStorage.clearFastSyncDone().and(appStateStorage.putFastSyncCooldownUntilMillis(cooldownUntil)).commit()
    fastSyncStateStorage.purge()

    startFastSync()
  }

  def scheduler: Scheduler = externalSchedulerOpt.getOrElse(context.system.scheduler)

  /** Load SNAP sync configuration with fallback to defaults */
  private def loadSnapSyncConfig(): SNAPSyncConfig =
    try
      SNAPSyncConfig.fromConfig(Config.config.getConfig("sync"))
    catch {
      case e: Exception =>
        log.warning(s"Failed to load SNAP sync config, using defaults: ${e.getMessage}")
        SNAPSyncConfig()
    }

  override def receive: Receive = idle

  def idle: Receive = {
    case SyncProtocol.Start =>
      start()
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case bh: ForkChoiceManager.BeaconHead =>
      // Buffer for the eventual SNAP startup; idle predates startSnapSync().
      handleBeaconHead(bh, snapSyncOpt = None)
  }

  def runningFastSync(fastSync: ActorRef): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case FastSync.Done =>
      fastSync ! PoisonPill

      // Open circuit-breaker for a cool-off period before allowing another fast-sync restart.
      val cooldownUntil = System.currentTimeMillis() + syncConfig.fastSyncRestartCooloff.toMillis
      appStateStorage.putFastSyncCooldownUntilMillis(cooldownUntil).commit()

      resetSnapFastCycleCount()
      startRegularSync()

    case FastSync.FallbackToSnapSync =>
      fastSync ! PoisonPill
      log.warning("Fast sync detected ETH68-only network (no GetNodeData support), falling back to SNAP sync")
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if !checkSnapFastEscapeHatch() then {
        startSnapSync()
      }

    case other => fastSync.forward(other)
  }

  def runningSnapSync(snapSync: ActorRef): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case StartRegularSyncBootstrap(targetBlock) =>
      log.info(s"SNAP sync requested bootstrap to pivot ${targetBlock}")

      // Prefer a header-only bootstrap: SNAP only needs the pivot header (stateRoot).
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val peersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val headerBootstrap =
        context.actorOf(
          PivotHeaderBootstrap
            .props(peersClient, blockchainWriter, targetBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"pivot-header-bootstrap-$gen"
        )

      // spec 004 MUST-FIX: clear any in-flight healing serve-root request as part of the transition so no healing
      // bootstrap survives into runningPivotHeaderBootstrap (where its Completed/Failed/Timeout would dead-letter).
      abortHealingServeRootRequest("entering pivot header bootstrap")
      context.become(runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock, snapSync))

    case StartRegularSyncBootstrapByHash(headHash) =>
      // CL-driven bootstrap path (#1207): fetch the head header by hash from peers.
      // The block number isn't known until the header arrives.
      log.info(
        "SNAP requested by-hash pivot header bootstrap for {}",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(headHash)
      )
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val peersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val headerBootstrap =
        context.actorOf(
          PivotHeaderBootstrap
            .propsByHash(peersClient, blockchainWriter, headHash, syncConfig, scheduler, preferSnapPeers = false),
          s"pivot-header-bootstrap-$gen"
        )
      // We pass `targetBlock = 0` as a placeholder — the bootstrap reply carries the
      // resolved `header.number`. The runningPivotHeaderBootstrap state's matching on
      // `block == targetBlock` is bypassed in by-hash mode by using a wildcard handler;
      // the resolved Completed.targetBlock is preserved when handed to SNAP via
      // BootstrapComplete.
      // spec 004 MUST-FIX: abort any in-flight healing serve-root request BEFORE entering the by-hash bootstrap.
      // With targetBlock == 0 the bootstrap's `Completed` guard accepts ANY block, so a stray healing `Completed`
      // could otherwise be mis-consumed as the pivot header. Clearing here also prevents the dead-letter wedge.
      abortHealingServeRootRequest("entering by-hash pivot header bootstrap")
      context.become(
        runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock = BigInt(0), snapSync)
      )

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
      log.info(
        s"SNAP state finalised at pivot=$pivot. Starting regular sync; chain backfill continues in background."
      )
      // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync.
      abortHealingServeRootRequest("SNAP finalised — leaving snap sync")
      resetSnapFastCycleCount()
      // SNAPSyncController already owns the live ChainDownloader child via its
      // `completedWithBackfill` state — don't spawn a duplicate standalone resumer (#1169).
      val regularSync = startRegularSync(resumeBackfill = false)
      context.watch(snapSync)
      context.become(runningRegularSyncWithBackfill(regularSync, snapSync))

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      // Defensive fallback: with the post-#1162 handshake, SnapSyncFinalized always precedes Done,
      // so this branch should not normally be reached. If it is (e.g., unexpected message ordering),
      // treat as a legacy "SNAP done" signal.
      snapSync ! PoisonPill
      log.info("SNAP sync completed (legacy Done path), transitioning to regular sync")
      // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync.
      abortHealingServeRootRequest("SNAP done (legacy) — leaving snap sync")
      resetSnapFastCycleCount()
      startRegularSync()

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.FallbackToFastSync =>
      snapSync ! PoisonPill
      log.warning("SNAP sync failed repeatedly, falling back to fast sync")
      // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync.
      abortHealingServeRootRequest("SNAP fallback to fast sync — leaving snap sync")
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if !checkSnapFastEscapeHatch() then {
        startFastSync()
      }

    case SyncProtocol.HealingImpossible =>
      snapSync ! PoisonPill
      log.warning(
        "SNAP finalization aborted (state root mismatch). Clearing sync state and restarting SNAP with a fresh pivot."
      )
      // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync. The new SNAP
      // actor started below gets a fresh latch, so the stale requester here must not linger.
      abortHealingServeRootRequest("SNAP healing impossible — restarting snap sync")
      appStateStorage.clearSnapSyncDone().commit()
      appStateStorage.clearFastSyncDone().commit()
      startSnapSync()

    case SyncProtocol.Status.Progress(_, _) =>
      log.debug("SNAP sync in progress")

    case bh: ForkChoiceManager.BeaconHead =>
      handleBeaconHead(bh, snapSyncOpt = Some(snapSync))

    // spec 004 (Decoupled Heal Serve-Root) T012: the healing coordinator (via SNAPSyncController) asks for a
    // newest-servable root to fetch missing nodes against, while its completeness walk stays pinned to the walk
    // root. Fetch a recent header via a DEDICATED bootstrap slot (never the storage-recovery `recentRootRequester`)
    // and reply HealingServeRoot to the child. Run inline — no transition into the deadlock-prone bootstrap state.
    case SNAPSyncController.RequestHealingServeRoot =>
      if healingServeRootRequester.isEmpty && healingServeRootBootstrap.isEmpty then {
        healingServeRootRequester = Some(sender())
        log.info("[HEAL-SERVE-ROOT] Healing requested a newest-servable root. Polling peers for the network head.")
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers
      } else {
        log.debug("[HEAL-SERVE-ROOT] Healing serve-root request already in flight; ignoring duplicate.")
      }

    // Peer snapshot used both to feed snap peers and (if waiting) to start the healing serve-root bootstrap.
    case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers)
        if healingServeRootRequester.isDefined && healingServeRootBootstrap.isEmpty =>
      maybeStartHealingServeRootBootstrap(peers)

    case PivotHeaderBootstrap.Completed(block, header) if healingServeRootRequester.isDefined =>
      val rootHex = header.stateRoot.take(4).toArray.map("%02x".format(_)).mkString
      log.info(s"[HEAL-SERVE-ROOT] Fetched header for block $block (root $rootHex). Replying to healing.")
      stopHealingServeRootBootstrap()
      healingServeRootRequester.foreach(
        _ ! SNAPSyncController.HealingServeRoot(block, Some(header.stateRoot))
      )
      healingServeRootRequester = None

    case PivotHeaderBootstrap.Failed(reason) if healingServeRootRequester.isDefined =>
      log.warning(s"[HEAL-SERVE-ROOT] Serve-root bootstrap failed ($reason). Replying None (serve root kept).")
      stopHealingServeRootBootstrap()
      healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
      healingServeRootRequester = None

    // spec 004 MUST-FIX: the guard (gen == healingServeRootGeneration && requester.isDefined) makes a late timeout a
    // no-op after abortHealingServeRootRequest — abort clears the requester, and the next request bumps the generation.
    case HealingServeRootTimeout(gen) if gen == healingServeRootGeneration && healingServeRootRequester.isDefined =>
      log.warning("[HEAL-SERVE-ROOT] Serve-root bootstrap timed out. Replying None (serve root kept).")
      stopHealingServeRootBootstrap()
      healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
      healingServeRootRequester = None

    case msg =>
      snapSync.forward(msg)
  }

  /** spec 004 T012: start a one-shot header bootstrap for a newest-servable block (margin back from the network head)
    * on the dedicated healing serve-root slot, and arm a timeout. On `Completed` we reply
    * [[snap.SNAPSyncController.HealingServeRoot]] to the waiting child; if no peer height is known yet, reply None so
    * the child keeps its current serve root (U2). Mirrors `maybeStartRecentRootBootstrap` but on a separate slot.
    */
  private def maybeStartHealingServeRootBootstrap(
      peers: Map[com.chipprbots.ethereum.network.Peer, com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo]
  ): Unit = {
    val snapHeights = peers.values.filter(_.remoteStatus.supportsSnap).map(_.maxBlockNumber)
    SyncController.recentRootTarget(snapHeights, RecentRootMarginBlocks) match {
      case Some(recentBlock) =>
        healingServeRootGeneration += 1
        val gen = healingServeRootGeneration
        val peersClient = context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"healing-serve-root-peers-$gen"
        )
        val bootstrap = context.actorOf(
          PivotHeaderBootstrap
            .props(peersClient, blockchainWriter, recentBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"healing-serve-root-bootstrap-$gen"
        )
        healingServeRootBootstrap = Some((peersClient, bootstrap))
        log.info(s"[HEAL-SERVE-ROOT] Fetching header for newest-servable block $recentBlock.")
        scheduler.scheduleOnce(20.seconds, self, HealingServeRootTimeout(gen))(context.dispatcher, self)
      case None =>
        log.info("[HEAL-SERVE-ROOT] No usable peer height yet; replying None (serve root kept).")
        healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
        healingServeRootRequester = None
    }
  }

  private def stopHealingServeRootBootstrap(): Unit = {
    healingServeRootBootstrap.foreach { case (peersClient, bootstrap) =>
      bootstrap ! PoisonPill
      peersClient ! PoisonPill
    }
    healingServeRootBootstrap = None
  }

  /** spec 004 (Decoupled Heal Serve-Root) MUST-FIX: abort any in-flight healing serve-root request before the parent
    * leaves `runningSnapSync` for `runningPivotHeaderBootstrap`. The healing handlers (`HandshakedPeers`,
    * `PivotHeaderBootstrap.Completed|Failed`, `HealingServeRootTimeout`) live ONLY in `runningSnapSync`; if a
    * concurrent pivot refresh transitions into `runningPivotHeaderBootstrap` with a healing bootstrap still in flight,
    * those messages would hit that state's catch-all, be forwarded to the child, and dead-letter — leaving
    * `healingServeRootRequester = Some(...)` here and the child's `healingServeRootRequestInFlight = true` forever,
    * silently freezing every future serve-root refresh (the pre-spec-004 deadlock). It also eliminates the ETH by-hash
    * hazard where a `targetBlock == 0` pivot bootstrap could mis-consume the healing `Completed` as its pivot header.
    * We reply `HealingServeRoot(0, None)`: the child clears its latch (SNAPSyncController.scala ~637) and keeps its
    * current serve root (U2), retrying on a later healing tick. Safe no-op when nothing is in flight.
    */
  private def abortHealingServeRootRequest(reason: String): Unit =
    if healingServeRootRequester.isDefined || healingServeRootBootstrap.isDefined then {
      log.info(s"[HEAL-SERVE-ROOT] Aborting in-flight serve-root request ($reason) — replying None (serve root kept).")
      stopHealingServeRootBootstrap()
      // A HealingServeRootTimeout self-message scheduled by maybeStartHealingServeRootBootstrap may still be in
      // flight, but it is generation-guarded (gen == healingServeRootGeneration && healingServeRootRequester.isDefined):
      // clearing the requester below — and the generation bump on the next request — makes any late timeout a no-op.
      healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
      healingServeRootRequester = None
    }

  def runningRegularSync(regularSync: ActorRef): Receive = { case other =>
    other match {
      case Terminated(actor) if actor == regularSync =>
        log.error("RegularSync actor terminated unexpectedly — restarting regular sync.")
        startRegularSync(resumeBackfill = false)
      case SyncProtocol.ResetFastSync =>
        handleResetFastSync()
      case SyncProtocol.RestartFastSync =>
        handleRestartFastSync()
      case RestartFastSyncNow =>
        doRestartFastSyncNow()
      case SyncProtocol.RegularSyncStuck(blockNumber, missingHash) =>
        // Regular sync can't make progress: state-node recovery has exhausted on the same hash
        // 3+ times. Local parent state is too far behind canonical tip for any peer's snap-serve
        // window, so trie-node fetches keep returning empty. Only viable recovery is to re-run
        // SNAP from a recent pivot. Kill regular sync, clear both SnapSyncDone AND FastSyncDone
        // (so a subsequent restart re-evaluates start() and enters SNAP rather than getting
        // stuck in `do-fast-sync is true but fast sync already completed` → regular-sync), then
        // start SNAP directly.
        log.error(
          "Regular sync stuck on block {} (missing {}). Re-triggering SNAP sync from a recent pivot.",
          blockNumber,
          missingHash
        )
        regularSync ! PoisonPill
        appStateStorage.clearSnapSyncDone().commit()
        appStateStorage.clearFastSyncDone().commit()
        startSnapSync(minPivotBlock = Some(blockNumber))
      case SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock) =>
        // Three-tier calibration cascade:
        //   Tier 1 (peerTD > 0, peerMaxBlock > 0): exact interpolation from NewBlock TD+blockNum
        //   Tier 2 (peerTD > 0, peerMaxBlock = 0): ETH68 STATUS only — peerTD direct (<0.05% over)
        //   Tier 3 (peerTD = 0): pure ETH69 sentinel — compute from local chain DB via parentHash traversal
        if peerTD > BigInt(0) then {
          // Tier 1 or 2: ETH68 peer TD available
          blockchainReader.getBestBlock.foreach { bestBlock =>
            val genesisWeight = blockchainReader
              .getChainWeightByHash(blockchainReader.genesisHeader.hash)
              .map(_.totalDifficulty)
              .getOrElse(blockchainReader.genesisHeader.difficulty)
            val calibratedTD =
              if peerMaxBlock > BigInt(0) then peerTD * bestBlock.header.number / peerMaxBlock
              else peerTD
            if calibratedTD > genesisWeight * BigInt(1000) then {
              val storedTD = blockchainReader
                .getChainWeightByHash(bestBlock.header.hash)
                .map(_.totalDifficulty)
                .getOrElse(BigInt(0))
              blockchainWriter
                .storeChainWeight(
                  bestBlock.header.hash,
                  com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(calibratedTD)
                )
                .commit()
              networkBestTD = peerTD
              calibrationSucceeded = true
              lastCalibrationSource = if peerMaxBlock > BigInt(0) then "NEWBLOCK_EXACT" else "ETH68_STATUS"
              log.info(
                "CHAIN_WEIGHT_CALIBRATED_ON_RESUME: bestBlock={} storedTD={} calibratedTD={} source={}",
                bestBlock.header.number,
                storedTD,
                calibratedTD,
                lastCalibrationSource
              )
              val ratio = if storedTD > BigInt(0) then (calibratedTD / storedTD).toString else "∞"
              log.info(
                s"TD_CALIBRATION_SUMMARY: block=${bestBlock.header.number} before=$storedTD after=$calibratedTD ratio=$ratio source=$lastCalibrationSource attempt=$tdCalibrationAttempt"
              )
            }
          }
        } else {
          // Tier 3: pure ETH69 sentinel (0, 0) — no ETH68 peer TD seen at T+30s (or retry).
          // Walk backward via parentHash to find a ChainDownloader anchor with plausible TD,
          // accumulate forward. Retry every 30min until success or ETH68 peers appear.
          tdCalibrationAttempt += 1
          val succeeded = calibrateTDFromLocalChain()
          if succeeded then {
            calibrationSucceeded = true
            lastCalibrationSource = "LOCAL_CHAIN"
          } else {
            scheduleTDCalibrationRetry()
          }
        }

      case msg =>
        regularSync.forward(msg)
    }
  }

  /** Receive used between `SnapSyncFinalized` and `Done` from the lingering SNAPSyncController.
    *
    * Regular sync is the primary owner of peer slots; SNAP backfill runs at low priority in the background. This
    * Receive lets `SNAPSyncController.Done` arrive (so we can poison-pill the SNAP actor) and intercepts restart paths
    * so the lingering backfill actor is cleaned up before a new sync mode takes over. Everything else is delegated to
    * `runningRegularSync(regularSync)`.
    */
  def runningRegularSyncWithBackfill(regularSync: ActorRef, snapSync: ActorRef): Receive = {
    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      log.info("SNAP background backfill complete; shutting down SNAPSyncController.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      context.become(runningRegularSync(regularSync))

    case Terminated(actor) if actor == snapSync =>
      log.warning("SNAPSyncController died while regular sync was running; chain backfill aborted.")
      context.become(runningRegularSync(regularSync))

    case Terminated(actor) if actor == regularSync =>
      log.error("RegularSync actor terminated unexpectedly during backfill — restarting regular sync.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      startRegularSync(resumeBackfill = false)

    case msg if isRestartTrigger(msg) =>
      log.info("Restart triggered while SNAP backfill was running; poison-pilling SNAP backfill actor first.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      context.become(runningRegularSync(regularSync))
      self ! msg // Re-deliver so the new state handles it.

    case msg =>
      runningRegularSync(regularSync).apply(msg)
  }

  /** Restart-style messages that mean the current sync strategy is being abandoned. Used by
    * `runningRegularSyncWithBackfill` to detect when it must terminate the lingering backfill actor before delegating.
    */
  private def isRestartTrigger(msg: Any): Boolean = msg match {
    case SyncProtocol.ResetFastSync       => true
    case SyncProtocol.RestartFastSync     => true
    case RestartFastSyncNow               => true
    case _: SyncProtocol.RegularSyncStuck => true
    case _                                => false
  }

  def runningRegularSyncBootstrap(
      regularSync: ActorRef,
      targetBlock: BigInt,
      originalSnapSyncRef: ActorRef
  ): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case RegularSync.ProgressProtocol.ImportedBlock(blockNumber, _) =>
      log.debug(s"Bootstrap progress: block $blockNumber / $targetBlock")

      if blockNumber >= targetBlock then {
        log.info(s"Bootstrap target ${targetBlock} reached - transitioning to SNAP sync")

        // Stop regular sync
        regularSync ! PoisonPill

        // Notify SNAP sync controller that bootstrap is complete, including the pivot header if available.
        blockchainReader.getBlockHeaderByNumber(targetBlock) match {
          case Some(header) =>
            originalSnapSyncRef ! BootstrapComplete(Some(header))
          case None =>
            log.warning(
              s"Bootstrap reached target $targetBlock but pivot header not found locally; notifying SNAP without header"
            )
            originalSnapSyncRef ! BootstrapComplete()
        }

        // Switch back to runningSnapSync state
        context.become(runningSnapSync(originalSnapSyncRef))
      }

    case SyncProtocol.GetStatus =>
      // Forward status requests to regular sync
      regularSync.forward(SyncProtocol.GetStatus)

    case other =>
      regularSync.forward(other)
  }

  def runningPivotHeaderBootstrap(
      peersClient: ActorRef,
      headerBootstrap: ActorRef,
      targetBlock: BigInt,
      originalSnapSyncRef: ActorRef
  ): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()

    case PivotHeaderBootstrap.Completed(block, header) if block == targetBlock || targetBlock == 0 =>
      // `targetBlock == 0` is the sentinel for by-hash bootstrap (#1207): the actual
      // block number is unknown at request time and resolved from the returned header.
      log.info(
        s"Pivot header bootstrap complete for block ${header.number} (requested $targetBlock) - notifying SNAP sync"
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! BootstrapComplete(Some(header))
      context.become(runningSnapSync(originalSnapSyncRef))

    case PivotHeaderBootstrap.Failed(reason) =>
      log.warning(s"Pivot header bootstrap failed (reason: $reason). Notifying SNAP sync controller.")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PivotBootstrapFailed(reason)
      context.become(runningSnapSync(originalSnapSyncRef))

    case SyncProtocol.GetStatus =>
      // Expose progress as a generic syncing state.
      sender() ! SyncProtocol.Status.Syncing(
        startingBlockNumber = appStateStorage.getSyncStartingBlock(),
        blocksProgress = SyncProtocol.Status.Progress(appStateStorage.getBestBlockNumber(), targetBlock),
        stateNodesProgress = None
      )

    case StartRegularSyncBootstrap(newTargetBlock) =>
      // A new bootstrap request arrived while one is already in progress.
      // Stop stale bootstrap actors and start fresh ones.
      log.info(
        s"New pivot header bootstrap requested for block $newTargetBlock (was $targetBlock). Restarting bootstrap."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val newPeersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val newHeaderBootstrap =
        context.actorOf(
          PivotHeaderBootstrap
            .props(newPeersClient, blockchainWriter, newTargetBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"pivot-header-bootstrap-$gen"
        )
      context.become(
        runningPivotHeaderBootstrap(newPeersClient, newHeaderBootstrap, newTargetBlock, originalSnapSyncRef)
      )

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.FallbackToFastSync =>
      log.warning("Received FallbackToFastSync during pivot header bootstrap. Stopping bootstrap and falling back.")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PoisonPill
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if !checkSnapFastEscapeHatch() then {
        startFastSync()
      }

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
      log.info(
        s"Received SnapSyncFinalized(pivot=$pivot) during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      // SNAP finalised mid-bootstrap is an exceptional path; tear down the SNAP actor cleanly
      // (no chain-backfill watch, since the bootstrap state is already racy).
      originalSnapSyncRef ! PoisonPill
      resetSnapFastCycleCount()
      startRegularSync()

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      log.info(
        "Received Done from SNAP sync during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PoisonPill
      resetSnapFastCycleCount()
      startRegularSync()

    case bh: ForkChoiceManager.BeaconHead =>
      handleBeaconHead(bh, snapSyncOpt = Some(originalSnapSyncRef))

    // spec 004 T012: a healing serve-root request that lands during the brief pivot-header bootstrap window
    // (a concurrent pivot refresh) is declined immediately so the child's in-flight latch clears and it can
    // retry on a later healing tick. We never start a second bootstrap here (the pivot bootstrap is already
    // using the slot). U2: declining keeps the child's current serve root.
    case SNAPSyncController.RequestHealingServeRoot =>
      log.debug("[HEAL-SERVE-ROOT] Request arrived during pivot header bootstrap — declining (serve root kept).")
      sender() ! SNAPSyncController.HealingServeRoot(0, None)

    case msg =>
      // Forward coordinator and protocol messages to SNAP sync during the brief bootstrap.
      // This keeps coordinators functional while we fetch the pivot header (~1-5 seconds).
      originalSnapSyncRef.forward(msg)
  }

  /** Buffer the latest CL-driven head and, when SNAP is currently running, forward it as a `CLPivotHint` so the pivot
    * can be re-anchored on the freshest CL head. Called from the receive handler in every state where a `BeaconHead`
    * can arrive. No-op on chains without TTD or where `forkChoiceManagerOpt` is `None`.
    */
  private def handleBeaconHead(
      bh: ForkChoiceManager.BeaconHead,
      snapSyncOpt: Option[ActorRef]
  ): Unit =
    if clPivotEnabled then {
      val isNew = !latestBeaconHead.exists(_.headHash == bh.headHash)
      latestBeaconHead = Some(bh)
      if isNew then
        log.info(
          "Received CL-driven beacon head {} (knownHeader={})",
          com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
          bh.knownHeader.map(_.number).getOrElse("unknown")
        )
      snapSyncOpt.foreach { snapSync =>
        snapSync ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.CLPivotHint(
          bh.headHash,
          bh.knownHeader
        )
      }
    }

  /** Check if the SNAP<->Fast bounce cycle count has exceeded the configured threshold. If so, mark both sync modes as
    * done and escape to regular sync.
    * @return
    *   true if the escape hatch fired (caller should NOT start another sync), false otherwise
    */
  private def checkSnapFastEscapeHatch(): Boolean = {
    val threshold = syncConfig.maxSnapFastCycleTransitions
    if threshold > 0 && snapFastCycleCount >= threshold then {
      log.warning(
        "SNAP<->Fast sync bounce cycle count ({}) reached threshold ({}). " +
          "Escaping to regular sync — missing state will be fetched on-demand via GetTrieNodes.",
        snapFastCycleCount,
        threshold
      )
      // Mark both sync modes as done so they won't restart
      appStateStorage.snapSyncDone().commit()
      appStateStorage.fastSyncDone().commit()
      // Purge persisted fast sync state to prevent stale state on next restart
      fastSyncStateStorage.purge()
      // Reset cycle count
      resetSnapFastCycleCount()
      startRegularSync()
      true
    } else false
  }

  private def resetSnapFastCycleCount(): Unit = {
    snapFastCycleCount = 0
    appStateStorage.clearSnapFastCycleCount().commit()
  }

  def start(): Unit = {
    import syncConfig.{doFastSync, doSnapSync}

    val nowMillis = System.currentTimeMillis()

    // One-shot operator override. Setting -Dfukuii.reset-fast-sync-done=true on the JVM
    // command line clears the FastSyncDone flag at startup. Used when a node was wedged
    // by the pre-fix premature-completion bug — operator can clear the flag once, the
    // node resumes fast sync to finish state download, then on next normal restart the
    // flag is back to its real value (set by FastSync.finish()). Cheap, surgical recovery
    // that doesn't touch chain data.
    if System.getProperty("fukuii.reset-fast-sync-done", "false").equalsIgnoreCase("true") then {
      log.warning(
        "System property fukuii.reset-fast-sync-done=true — clearing FastSyncDone flag on this startup"
      )
      appStateStorage.clearFastSyncDone().commit()
    }

    // Dangling-best-block recovery. If the persisted best-block hash points to a block that
    // isn't actually in storage, the previous sync was interrupted before the canonical tip
    // could be written (e.g. mid-SNAP container restart while account download was incomplete).
    // Without this, start() lands in `do-fast-sync is true but fast sync already completed` →
    // regular sync, which loops on `Best block ... not found in storage` indefinitely.
    //
    // Recovery: clear ONLY the *SyncDone flags. SNAPSyncController persists its own progress
    // (snapSyncProgress / snapSyncStateRoot / snapSyncFinalizedRoot) and will resume the
    // partial download from where it left off — DO NOT reset bestBlockNumber, that would throw
    // away potentially many hours of completed account/storage/bytecode work and force a
    // genesis re-sync. Trie nodes are content-addressed, so leftover state from the prior run
    // is automatically reused as SNAP fills in the gaps.
    val persistedBest = appStateStorage.getBestBlockNumber()
    if persistedBest > 0 && blockchainReader.getBlockHeaderByNumber(persistedBest).isEmpty then {
      log.warning(
        "Persisted best block {} not found in storage — clearing sync-done flags so SNAP can resume from persisted progress",
        persistedBest
      )
      appStateStorage.clearSnapSyncDone().commit()
      appStateStorage.clearFastSyncDone().commit()
    }

    // Incomplete-fast-sync recovery. The fast sync "95% complete" check uses a dynamic
    // total = downloaded + currently-queued-missing. After a JVM restart the scheduler
    // re-walks the trie from the pivot root and queues only the newly-discovered missing
    // frontier; the dynamic total drops to ≈downloaded and the percentage falsely reads
    // 99%+, so fast sync declares itself done with a partial trie. The persisted SyncState
    // now tracks `maxTotalNodesCount` (the high-water mark across the run); if downloaded
    // is far short of that peak and FastSyncDone is set, the prior run finished
    // prematurely. Clear the flag so this start() routes back to fast sync and finishes
    // the missing nodes. Without this auto-recovery, the only fix is a manual re-pivot or
    // wipe — neither of which the node can do "in the wild".
    if appStateStorage.isFastSyncDone() then {
      fastSyncStateStorage.getSyncState().foreach { ss =>
        val saved = ss.downloadedNodesCount
        val peak = ss.maxTotalNodesCount
        if peak > 1000L && saved.toDouble / peak.toDouble < 0.90 then {
          val pct = (saved.toDouble / peak.toDouble * 100).toInt
          log.warning(
            "FastSyncDone is set but persisted SyncState shows trie incomplete: " +
              "downloaded={} / peak total={} ({}%). Clearing FastSyncDone to resume fast sync " +
              "and finish state download.",
            saved,
            peak,
            pct
          )
          appStateStorage.clearFastSyncDone().commit()
        }
      }
    }

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()

    // Load bootstrap checkpoints if enabled and DB is fresh (best block = 0).
    // The highest checkpoint becomes the bootstrap pivot — SNAPSyncController uses it
    // for peer filtering and pivot selection, bypassing the peer discovery delay.
    if syncConfig.useBootstrapCheckpoints && appStateStorage.getBestBlockNumber() == 0 then {
      val checkpoints = syncConfig.bootstrapCheckpoints
      if checkpoints.nonEmpty then {
        val (highestBlock, highestHash) = checkpoints.maxBy(_._1)
        val existingPivot = appStateStorage.getBootstrapPivotBlock()
        if existingPivot == 0 || highestBlock > existingPivot then {
          import org.apache.pekko.util.ByteString
          val hashBytes = ByteString(com.chipprbots.ethereum.utils.Hex.decode(highestHash.stripPrefix("0x")))
          appStateStorage.putBootstrapPivotBlock(highestBlock, hashBytes).commit()
          log.info(
            s"Loaded bootstrap checkpoint: block $highestBlock (${highestHash.take(10)}...) " +
              s"from ${checkpoints.size} configured checkpoints"
          )
        } else {
          log.info(s"Bootstrap checkpoint already loaded (block $existingPivot), skipping")
        }
      }
    }

    // Checkpoint sync: bootstrap a fresh datadir by importing a `.checkpoint` archive instead
    // of running SNAP. Only fires when DB is fresh (best-block == 0 and SNAP not already done).
    // Resolution order:
    //   1. `checkpoint-sync-file` if set — use the local path directly.
    //   2. else `checkpoint-sync-url` if set — download to `${datadir}/checkpoint.bin`
    //      (resumable via HTTP Range) and import.
    // On success the importer marks SNAP/bytecode/storage as done; the match below routes to
    // RegularSync. On failure we log and fall through to the normal SNAP/Fast/Regular path.
    if appStateStorage.getBestBlockNumber() == 0 && !appStateStorage.isSnapSyncDone() then {
      val fileOpt: Option[java.nio.file.Path] = syncConfig.checkpointSyncFile.orElse {
        syncConfig.checkpointSyncUrl.flatMap { url =>
          val datadir = java.nio.file.Paths.get(System.getProperty("fukuii.datadir", "."))
          val target = datadir.resolve("checkpoint.bin")
          log.info("[CHECKPOINT DOWNLOAD] {} -> {}", url, target)
          val downloader = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointDownloader()
          downloader.download(url, target) match {
            case Right(_) => Some(target)
            case Left(err) =>
              log.error("[CHECKPOINT DOWNLOAD] failed: {} — falling through to SNAP/Fast/Regular", err)
              None
          }
        }
      }
      fileOpt.foreach { path =>
        val chainIdBig = configBuilder.blockchainConfig.chainId
        log.info("[CHECKPOINT IMPORT] starting from {} (chainId={})", path, chainIdBig)
        val importer = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter(
          blockchainWriter,
          stateStorage,
          evmCodeStorage,
          appStateStorage
        )
        importer.importFromFile(path, Some(chainIdBig.toLong)) match {
          case Right(result) =>
            log.info(
              "[CHECKPOINT IMPORT] success: block={} nodes={} bytecodes={} elapsed={}s",
              result.blockNumber,
              result.nodesImported,
              result.bytecodesImported,
              result.elapsedMs / 1000
            )
          case Left(err) =>
            log.error("[CHECKPOINT IMPORT] failed: {} — falling through to SNAP/Fast/Regular", err)
        }
      }
    } else if syncConfig.checkpointSyncFile.isDefined || syncConfig.checkpointSyncUrl.isDefined then {
      log.info(
        "Checkpoint sync configured but DB already initialized (bestBlock={}, snapDone={}); skipping import",
        appStateStorage.getBestBlockNumber(),
        appStateStorage.isSnapSyncDone()
      )
    }

    // If fast sync is desired but the circuit-breaker is open, start regular sync for now and
    // schedule an in-process restart of fast sync once the cool-off expires.
    if doFastSync && appStateStorage.isFastSyncCoolingOff(nowMillis) then {
      val until = appStateStorage.getFastSyncCooldownUntilMillis()
      val delay = (until - nowMillis).millis
      log.warning(
        "Fast sync requested but in cool-off until {} ({} remaining); starting regular sync and scheduling fast-sync restart",
        until,
        delay
      )
      startRegularSync()
      scheduler.scheduleOnce(delay, self, RestartFastSyncNow)
    } else {

      // Recovery flag: -Dfukuii.snap.clearDoneOnStart=true clears SnapSyncDone to re-enter healing.
      // Use when healing completed prematurely (BUG-006: root mismatch) to resume without a full re-sync.
      if doSnapSync && System.getProperty("fukuii.snap.clearDoneOnStart", "false").toBoolean then {
        if appStateStorage.isSnapSyncDone() then {
          log.warning("fukuii.snap.clearDoneOnStart=true: clearing SnapSyncDone to re-enter SNAP healing")
          appStateStorage.clearSnapSyncDone().commit()
        }
      }

      (appStateStorage.isSnapSyncDone(), appStateStorage.isFastSyncDone(), doSnapSync, doFastSync) match {
        case (false, _, true, _) =>
          // SNAP sync requested - just start it
          // It will fall back to fast sync if needed
          startSnapSync()
        case (true, _, true, _) =>
          log.warning("do-snap-sync is true but SNAP sync already completed")
          // Diagnostic: log stored SNAP sync state root vs pivot block state root
          val snapStateRoot = appStateStorage.getSnapSyncStateRoot()
          val bestBlockNum = appStateStorage.getBestBlockNumber()
          val bestBlockHeader = blockchainReader.getBlockHeaderByNumber(bestBlockNum)
          val pivotStateRoot = bestBlockHeader.map(_.stateRoot)
          log.info(
            "SNAP state root diagnostic: stored snapStateRoot={}, pivotBlockStateRoot={}, bestBlock={}, match={}",
            snapStateRoot.map(r => r.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
            pivotStateRoot.map(r => r.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
            bestBlockNum,
            snapStateRoot == pivotStateRoot
          )
          // After SNAP sync with deferred merkleization + pivot refreshes, the finalized trie root
          // may differ from the pivot block header's stateRoot. The trie nodes are stored under
          // the finalized root's hash, but the pivot header references the original (now orphaned) root.
          // Fix: substitute the finalized root into the pivot block header.
          bestBlockHeader.foreach { header =>
            val mptStorage = stateStorage.getReadOnlyStorage
            val pivotRootExists =
              try { mptStorage.get(header.stateRoot.toArray); true }
              catch { case _: Exception => false }
            log.info(
              "State root availability check: pivotRoot({})={}",
              header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString,
              if pivotRootExists then "EXISTS" else "MISSING"
            )
            if !pivotRootExists then {
              val finalizedRoot = appStateStorage.getSnapSyncFinalizedRoot()
              finalizedRoot match {
                case Some(fRoot) =>
                  val fRootExists =
                    try { mptStorage.get(fRoot.toArray); true }
                    catch { case _: Exception => false }
                  log.info(
                    "Finalized trie root {} availability: {}",
                    fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    if fRootExists then "EXISTS" else "MISSING"
                  )
                  if fRootExists then {
                    log.warning(
                      "Substituting finalized trie root {} into pivot block header (replacing missing root {})",
                      fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                      header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                    )
                    val updatedHeader = header.copy(stateRoot = fRoot)
                    blockchainWriter.storeBlockHeader(updatedHeader).commit()
                  }
                case None =>
                  log.error(
                    "Pivot state root {} MISSING and no finalized root stored! " +
                      "Database is in an unrecoverable state — clear data and re-sync.",
                    header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                  )
              }
            } else {
              // Symmetric case (Run-26): pivot root EXISTS in MPT but differs from snapStateRoot.
              // The downloaded account trie is stored under snapStateRoot; update the pivot header
              // to match so the startup diagnostic passes and regular sync reads the correct trie.
              snapStateRoot.foreach { snapRoot =>
                if snapRoot != header.stateRoot then {
                  val snapRootExists =
                    try { mptStorage.get(snapRoot.toArray); true }
                    catch { case _: Exception => false }
                  log.info(
                    "snapStateRoot({}) availability: {}",
                    snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    if snapRootExists then "EXISTS" else "MISSING"
                  )
                  if snapRootExists then {
                    log.warning(
                      "snapStateRoot({}) differs from pivotHeader.stateRoot({}) — " +
                        "updating pivot block header to use downloaded state root.",
                      snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                      header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                    )
                    val updatedHeader = header.copy(stateRoot = snapRoot)
                    blockchainWriter.storeBlockHeader(updatedHeader).commit()
                  }
                }
              }
            }
          }
          val needBytecode = !appStateStorage.isBytecodeRecoveryDone()
          val needStorage = !appStateStorage.isStorageRecoveryDone()
          if needBytecode || needStorage then {
            startRecovery(needBytecode, needStorage)
          } else {
            startRegularSync()
          }
        case (_, false, false, true) =>
          startFastSync()
        case (_, true, false, true) =>
          log.warning("do-fast-sync is true but fast sync already completed")
          startRegularSync()
        case (_, true, false, false) =>
          startRegularSync()
        case (_, false, false, false) =>
          if fastSyncStateStorage.getSyncState().isDefined then {
            log.warning("do-fast-sync is false but fast sync hasn't completed")
            startFastSync()
          } else startRegularSync()
      }
    } // else !isFastSyncCoolingOff
  }

  def startFastSync(): Unit = {
    syncGeneration += 1
    val fastSync = context.actorOf(
      FastSync
        .props(
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
          scheduler,
          configBuilder
        )
        .withDispatcher("sync-dispatcher"),
      s"fast-sync-$syncGeneration"
    )
    fastSync ! SyncProtocol.Start
    context.become(runningFastSync(fastSync))
  }

  def startSnapSync(minPivotBlock: Option[BigInt] = None): Unit = {
    log.info("Starting SNAP sync mode")
    syncGeneration += 1

    val snapSyncConfig = loadSnapSyncConfig()

    val snapSync = context
      .spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          stateStorage,
          evmCodeStorage,
          flatSlotStorage,
          networkPeerManager,
          peerEventBus,
          syncConfig,
          snapSyncConfig,
          scheduler,
          blacklist,
          syncController = self
        ),
        s"snap-sync-$syncGeneration",
        DispatcherSelector.fromConfig("sync-dispatcher")
      )
      .toClassic

    // Register SNAPSyncController with NetworkPeerManagerActor for message routing
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(snapSync)

    // If a CL-driven head arrived before SNAP started (post-merge chains), prime the new
    // SNAP actor with it so pivot selection skips the TD-based path entirely.
    if clPivotEnabled then {
      latestBeaconHead.foreach { bh =>
        log.info(
          "Priming SNAP sync with buffered CL beacon head {} (knownHeader={})",
          com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
          bh.knownHeader.map(_.number).getOrElse("unknown")
        )
        snapSync ! SNAPSyncController.CLPivotHint(bh.headHash, bh.knownHeader)
      }
    }

    minPivotBlock.foreach { minBlock =>
      log.info("Sending MinPivotBlock({}) to new SNAP sync actor", minBlock)
      snapSync ! SNAPSyncController.MinPivotBlock(minBlock)
    }
    snapSync ! SNAPSyncController.Start
    context.become(runningSnapSync(snapSync))
  }

  def startRegularSync(resumeBackfill: Boolean = true): ActorRef = {
    syncGeneration += 1

    // Operator escape hatch: seed exact chain-weight values before RegularSync starts.
    // Used when a node finished SNAP sync with a proxy TD (e.g. no ETH68 peers at finalize
    // time) and needs correcting without a full re-sync.
    // Format: -Dfukuii.seed-chain-weights=HASH1:TD1,HASH2:TD2 (hash hex, TD decimal)
    // Get canonical values from a trusted local client:
    //   curl -s localhost:8545 -d '{"method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    //     | jq -r '.result | "\(.hash):\(.totalDifficulty | ltrimstr("0x") | tonumber)"'
    Option(System.getProperty("fukuii.seed-chain-weights")).foreach { seeds =>
      seeds.split(",").foreach { seed =>
        seed.trim.split(":") match {
          case Array(hashHex, tdStr) =>
            val hash = ByteString(com.chipprbots.ethereum.utils.Hex.decode(hashHex.stripPrefix("0x")))
            val td = BigInt(tdStr.trim)
            blockchainWriter
              .storeChainWeight(hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(td))
              .commit()
            log.warning("seed-chain-weights: wrote TD={} for hash={}...", td, hashHex.take(16))
          case _ =>
            log.warning("seed-chain-weights: invalid entry '{}' (expected HASH:TD)", seed.trim)
        }
      }
    }

    // Register self as calibration target so NetworkPeerManagerActor can push the correct
    // cumulative TD when it detects a TD-PROXY-GAP at peer handshake (stale genesis-proxy TD
    // stored by SNAP finalization when no ETH68 peers were available at that time).
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor
      .RegisterChainWeightCalibrationTarget(self)

    // Unconditional timed calibration: fire CalibrateChainWeightNow 30s after RegularSync starts.
    // NPA forwards bestNetworkTip (best ETH68 peer TD seen since startup) to this actor.
    // Handles multi-restart TD drift that falls below the TD-PROXY-GAP 10,000× threshold
    // (e.g. Restart #7 ratio=7,411×). For pure ETH69 networks, NPA sends a (0,0) sentinel
    // and tier-3 local chain computation fires instead.
    context.system.scheduler.scheduleOnce(30.seconds) {
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNow
    }(context.dispatcher)

    val peersClient =
      context.actorOf(
        PeersClient
          .props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler)
          .withDispatcher("sync-dispatcher"),
        s"peers-client-$syncGeneration"
      )
    val regularSync = context.actorOf(
      RegularSync
        .props(
          peersClient,
          networkPeerManager,
          peerEventBus,
          consensus,
          blockchainReader,
          blockchainWriter,
          stateStorage,
          evmCodeStorage,
          { val br = new BranchResolution(blockchainReader); br.messConfig = messConfig; br },
          validators.blockValidator,
          blacklist,
          syncConfig,
          ommersPool,
          pendingTransactionsManager,
          configBuilder
        )
        .withDispatcher("sync-dispatcher"),
      s"regular-sync-$syncGeneration"
    )
    regularSync ! SyncProtocol.Start
    context.watch(regularSync)
    context.become(runningRegularSync(regularSync))
    // After SNAP completes, chain backfill (#1162) writes headers / bodies / receipts in the
    // background. If the node was killed mid-backfill, persisted cursors (#1169) tell us how
    // far it got — spawn a standalone ChainDownloader to finish the job alongside regular sync.
    // Suppressed when called from the SnapSyncFinalized path: SNAPSyncController already owns
    // the live backfill actor in that flow.
    if resumeBackfill then maybeStartBackfillResume(regularSync)
    regularSync
  }

  /** Spawn a standalone `ChainDownloader` to resume background chain backfill from persisted cursors. No-op when SNAP
    * has not completed, when no `BackfillTarget` was persisted, or when all cursors have already reached the target.
    * Issues #1162 (background backfill) + #1169 (resume across restarts).
    */
  private def maybeStartBackfillResume(regularSync: ActorRef): Unit =
    if appStateStorage.needsBackfillResume() then {
      val target = appStateStorage.getBackfillTarget()
      val headerCursor = appStateStorage.getBackfillBestHeader()
      val bodyCursor = appStateStorage.getBackfillBestBody()
      val receiptCursor = appStateStorage.getBackfillBestReceipt()
      log.info(
        "Resuming background chain backfill: target={}, header={}, body={}, receipt={}",
        target,
        headerCursor,
        bodyCursor,
        receiptCursor
      )
      val snapSyncConfig = loadSnapSyncConfig()
      syncGeneration += 1
      import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
      // ChainDownloader is Pekko Typed (Group S6). Spawn it via the Classic→Typed adapter and convert the
      // resulting Typed ref back to Classic so the existing `! ChainDownloader.X` sends below keep compiling.
      val resumer = context
        .spawn(
          ChainDownloader(
            blockchainReader = blockchainReader,
            blockchainWriter = blockchainWriter,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager,
            peerEventBus = peerEventBus,
            syncConfig = syncConfig,
            replyTo = self,
            maxConcurrentRequests = snapSyncConfig.chainBackfillConcurrentRequests,
            requestTimeout = snapSyncConfig.chainDownloadTimeout
          ),
          s"backfill-resumer-$syncGeneration",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
        .toClassic
      context.watch(resumer)
      resumer ! ChainDownloader.Start(target)
      context.become(runningRegularSyncWithStandaloneBackfill(regularSync, resumer))
    }

  /** Receive while regular sync runs alongside a standalone backfill resumer (#1169). Mirrors
    * `runningRegularSyncWithBackfill` but for the post-restart case where we own the backfill actor directly instead of
    * routing through a lingering `SNAPSyncController`.
    */
  def runningRegularSyncWithStandaloneBackfill(regularSync: ActorRef, resumer: ActorRef): Receive = {
    case com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Done =>
      log.info("Standalone chain backfill resume complete.")
      context.unwatch(resumer)
      resumer ! PoisonPill
      context.become(runningRegularSync(regularSync))

    case progress: com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Progress =>
      log.debug(
        "Standalone backfill progress: headers={} bodies={} receipts={} target={}",
        progress.headersDownloaded,
        progress.bodiesDownloaded,
        progress.receiptsDownloaded,
        progress.targetBlock
      )

    case Terminated(actor) if actor == resumer =>
      log.warning("Standalone backfill resumer died; chain backfill aborted (cursors persist for next restart).")
      context.become(runningRegularSync(regularSync))

    case msg if isRestartTrigger(msg) =>
      log.info("Restart triggered while standalone backfill was running; poison-pilling backfill resumer first.")
      context.unwatch(resumer)
      resumer ! PoisonPill
      context.become(runningRegularSync(regularSync))
      self ! msg // Re-deliver so the new state handles it.

    case msg =>
      runningRegularSync(regularSync).apply(msg)
  }

  def startRecovery(needBytecode: Boolean, needStorage: Boolean): Unit = {
    syncGeneration += 1
    val stateRootOpt = appStateStorage.getSnapSyncStateRoot()
    val pivotBlockOpt = appStateStorage.getSnapSyncPivotBlock()

    (stateRootOpt, pivotBlockOpt) match {
      case (Some(stateRoot), Some(pivotBlock)) =>
        log.info(
          s"[SNAP-RECOVERY] Phase starting — bytecodeNeeded=$needBytecode storageNeeded=$needStorage " +
            s"generation=$syncGeneration — polling for snap-capable peers every 5s"
        )

        val snapSyncConfig = loadSnapSyncConfig()

        if snapSyncConfig.parallelRecoveryScan then {
          // Combined path: ONE parallel, resumable single-pass scan finds both gap sets; downloads start
          // once it reports (in `runningCombinedScan`).
          log.info("Recovery: combined parallel scan enabled — one pass finds bytecode + storage gaps.")
          // Phase gauges are need-aware: a phase already done in a prior run shows Complete (not idle) while the
          // combined scan re-verifies it in the same pass.
          RecoveryMetrics.setBytecodePhase(
            if needBytecode then RecoveryMetrics.PhaseScanning else RecoveryMetrics.PhaseComplete
          )
          RecoveryMetrics.setStoragePhase(
            if needStorage then RecoveryMetrics.PhaseScanning else RecoveryMetrics.PhaseComplete
          )
          context.spawn(
            CombinedRecoveryScanActor(
              stateRoot,
              stateStorage,
              evmCodeStorage,
              appStateStorage,
              self,
              pivotBlock,
              snapSyncConfig
            ),
            s"combined-recovery-scan-$syncGeneration",
            DispatcherSelector.fromConfig("sync-dispatcher")
          )
          context.become(runningCombinedScan(needBytecode, needStorage, stateRoot, pivotBlock, snapSyncConfig))
        } else {
          // Legacy path: each phase scans the full trie independently, then downloads.
          val bytecodeActor =
            if needBytecode then
              Some(
                context
                  .spawn(
                    BytecodeRecoveryActor(
                      stateRoot,
                      stateStorage,
                      evmCodeStorage,
                      appStateStorage,
                      networkPeerManager,
                      self,
                      pivotBlock,
                      snapSyncConfig
                    ),
                    s"bytecode-recovery-$syncGeneration",
                    DispatcherSelector.fromConfig("sync-dispatcher")
                  )
                  .toClassic
              )
            else None
          val storageActor =
            if needStorage then
              Some(
                context
                  .spawn(
                    StorageRecoveryActor(
                      stateRoot,
                      stateStorage,
                      appStateStorage,
                      flatSlotStorage,
                      networkPeerManager,
                      self,
                      pivotBlock,
                      snapSyncConfig
                    ),
                    s"storage-recovery-$syncGeneration",
                    DispatcherSelector.fromConfig("sync-dispatcher")
                  )
                  .toClassic
              )
            else None
          beginRecoveryDownloads(
            bytecodeActor,
            storageActor,
            bytecodeComplete = !needBytecode,
            storageComplete = !needStorage
          )
        }

      case _ =>
        log.warning("Cannot run recovery: missing stateRoot or pivotBlock. Marking done and proceeding.")
        if needBytecode then appStateStorage.bytecodeRecoveryDone().commit()
        if needStorage then appStateStorage.storageRecoveryDone().commit()
        startRegularSync()
    }
  }

  /** Wait for the combined scan to report both gap sets, then spawn download-only recovery actors for whichever phases
    * still have gaps. Phases the scan found already complete are marked done immediately.
    */
  def runningCombinedScan(
      needBytecode: Boolean,
      needStorage: Boolean,
      stateRoot: ByteString,
      pivotBlock: BigInt,
      snapSyncConfig: com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
  ): Receive = {
    case CombinedRecoveryScanActor.CombinedScanComplete(byteGaps, storGaps) =>
      val effByte = if needBytecode then byteGaps else Nil
      val effStor = if needStorage then storGaps else Nil
      log.info(s"Combined recovery scan reported ${effByte.size} bytecode gaps, ${effStor.size} storage gaps.")
      // Phases the combined scan found complete (no gaps) are done right now.
      if needBytecode && effByte.isEmpty then appStateStorage.bytecodeRecoveryDone().commit()
      if needStorage && effStor.isEmpty then appStateStorage.storageRecoveryDone().commit()

      val bytecodeActor =
        if needBytecode && effByte.nonEmpty then
          Some(
            context
              .spawn(
                BytecodeRecoveryActor.applyPreloaded(
                  stateRoot,
                  stateStorage,
                  evmCodeStorage,
                  appStateStorage,
                  networkPeerManager,
                  self,
                  pivotBlock,
                  snapSyncConfig,
                  effByte
                ),
                s"bytecode-recovery-dl-$syncGeneration",
                DispatcherSelector.fromConfig("sync-dispatcher")
              )
              .toClassic
          )
        else None
      val storageActor =
        if needStorage && effStor.nonEmpty then
          Some(
            context
              .spawn(
                StorageRecoveryActor.applyPreloaded(
                  stateRoot,
                  stateStorage,
                  appStateStorage,
                  flatSlotStorage,
                  networkPeerManager,
                  self,
                  pivotBlock,
                  snapSyncConfig,
                  effStor
                ),
                s"storage-recovery-dl-$syncGeneration",
                DispatcherSelector.fromConfig("sync-dispatcher")
              )
              .toClassic
          )
        else None
      // A phase with no download actor is finished (no gaps / already done) — show Complete, not idle. Phases that
      // will download have their phase set to Downloading by the recovery actor.
      if bytecodeActor.isEmpty then RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
      if storageActor.isEmpty then RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
      beginRecoveryDownloads(
        bytecodeActor,
        storageActor,
        bytecodeComplete = bytecodeActor.isEmpty,
        storageComplete = storageActor.isEmpty
      )

    case bh: ForkChoiceManager.BeaconHead =>
      handleBeaconHead(bh, snapSyncOpt = None)

    case other =>
      log.debug("Ignoring message during combined recovery scan: {}", other.getClass.getSimpleName)
  }

  /** Wire up download-only recovery: register for SNAP routing, start the peer poller, and enter `runningRecovery`. If
    * there is nothing to download (no gaps), clear the resumable checkpoint and go straight to regular sync.
    */
  private def beginRecoveryDownloads(
      bytecodeActor: Option[ActorRef],
      storageActor: Option[ActorRef],
      bytecodeComplete: Boolean,
      storageComplete: Boolean
  ): Unit =
    if bytecodeActor.isEmpty && storageActor.isEmpty then {
      log.info("Recovery: no gaps to download. Transitioning to regular sync.")
      appStateStorage.clearRecoveryProgress().commit()
      startRegularSync()
    } else {
      bytecodeActor.foreach(context.watch)
      storageActor.foreach(context.watch)
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(self)
      val peerPoller = context.system.scheduler.scheduleWithFixedDelay(2.seconds, 5.seconds, self, PollRecoveryPeers)
      context.become(runningRecovery(bytecodeActor, storageActor, bytecodeComplete, storageComplete, peerPoller))
    }

  /** Centralised recovery teardown: stop the peer poller, deregister SNAP routing, clear the resumable checkpoint, and
    * start regular sync. Called from every "all recovery complete" path.
    */
  private def completeRecovery(peerPoller: org.apache.pekko.actor.Cancellable): Unit = {
    peerPoller.cancel()
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
      context.system.deadLetters
    )
    appStateStorage.clearRecoveryProgress().commit()
    log.info("All recovery complete. Transitioning to regular sync.")
    startRegularSync()
  }

  def runningRecovery(
      bytecodeActor: Option[ActorRef],
      storageActor: Option[ActorRef],
      bytecodeComplete: Boolean,
      storageComplete: Boolean,
      peerPoller: org.apache.pekko.actor.Cancellable = org.apache.pekko.actor.Cancellable.alreadyCancelled
  ): Receive = {
    case BytecodeRecoveryActor.RecoveryComplete =>
      log.info(s"[SNAP-RECOVERY] bytecode recovery complete (storage done: $storageComplete)")
      if storageComplete then {
        completeRecovery(peerPoller)
      } else {
        context.become(
          runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete, peerPoller)
        )
      }

    case StorageRecoveryActor.RecoveryComplete =>
      log.info(s"[SNAP-RECOVERY] storage recovery complete (bytecode done: $bytecodeComplete)")
      if bytecodeComplete then {
        completeRecovery(peerPoller)
      } else {
        context.become(
          runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true, peerPoller)
        )
      }

    case PollRecoveryPeers =>
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers

    case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
      val snapPeers = peers.filter { case (_, peerInfo) => peerInfo.remoteStatus.supportsSnap && peerInfo.forkAccepted }
      if snapPeers.nonEmpty then {
        snapPeers.foreach { case (peer, _) =>
          bytecodeActor.foreach(_ ! snap.actors.Messages.ByteCodePeerAvailable(peer))
          storageActor.foreach(_ ! snap.actors.Messages.StoragePeerAvailable(peer))
        }
      }
      // If storage recovery is waiting for a recent root and no header fetch is in flight, start one
      // now using the freshest peer height in this snapshot.
      if recentRootRequester.isDefined && recentRootBootstrap.isEmpty then {
        maybeStartRecentRootBootstrap(peers)
      }

    // Storage recovery: the saved pivot root has aged out of every peer's serve window. Fetch a recent
    // canonical root so the download can roll onto something peers can still serve, instead of wedging.
    case StorageRecoveryActor.RequestRecentRoot =>
      if recentRootRequester.isEmpty && recentRootBootstrap.isEmpty then {
        recentRootRequester = Some(sender())
        log.info("Recovery requested a recent root to roll off the aged pivot. Polling peers for the network head.")
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers
      } else {
        log.debug("Recovery recent-root request already in flight; ignoring duplicate.")
      }

    case PivotHeaderBootstrap.Completed(block, header) if recentRootRequester.isDefined =>
      val rootHex = header.stateRoot.take(4).toArray.map("%02x".format(_)).mkString
      log.info(s"Recovery recent-root: fetched header for block $block (root $rootHex). Replying.")
      stopRecentRootBootstrap()
      recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(block, Some(header.stateRoot)))
      recentRootRequester = None

    case PivotHeaderBootstrap.Failed(reason) if recentRootRequester.isDefined =>
      log.warning(s"Recovery recent-root bootstrap failed ($reason). Declining the roll; abandon path will run.")
      stopRecentRootBootstrap()
      recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
      recentRootRequester = None

    case RecentRootTimeout(gen) if gen == recentRootGeneration && recentRootRequester.isDefined =>
      log.warning("Recovery recent-root bootstrap timed out. Declining the roll; abandon path will run.")
      stopRecentRootBootstrap()
      recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
      recentRootRequester = None

    case Terminated(actor) if bytecodeActor.contains(actor) =>
      log.error("BytecodeRecoveryActor terminated unexpectedly. Treating as complete to unblock sync.")
      if storageComplete then {
        completeRecovery(peerPoller)
      } else {
        context.become(
          runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete, peerPoller)
        )
      }

    case Terminated(actor) if storageActor.contains(actor) =>
      log.error("StorageRecoveryActor terminated unexpectedly. Treating as complete to unblock sync.")
      if bytecodeComplete then {
        completeRecovery(peerPoller)
      } else {
        context.become(
          runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true, peerPoller)
        )
      }

    case msg =>
      // Forward SNAP protocol responses to both active recovery actors
      bytecodeActor.foreach(_.forward(msg))
      storageActor.foreach(_.forward(msg))
  }

  /** Start a one-shot header bootstrap for a recent block (margin back from the network head) and arm a timeout. On
    * `Completed` we reply [[StorageRecoveryActor.RecentRoot]] to the waiting recovery actor; if no peer height is known
    * yet, decline immediately so the actor's abandon path still runs.
    */
  private def maybeStartRecentRootBootstrap(
      peers: Map[com.chipprbots.ethereum.network.Peer, com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo]
  ): Unit = {
    val snapHeights = peers.values.filter(_.remoteStatus.supportsSnap).map(_.maxBlockNumber)
    SyncController.recentRootTarget(snapHeights, RecentRootMarginBlocks) match {
      case Some(recentBlock) =>
        recentRootGeneration += 1
        val gen = recentRootGeneration
        val peersClient = context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"recovery-recent-root-peers-$gen"
        )
        val bootstrap = context.actorOf(
          PivotHeaderBootstrap
            .props(peersClient, blockchainWriter, recentBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"recovery-recent-root-bootstrap-$gen"
        )
        recentRootBootstrap = Some((peersClient, bootstrap))
        log.info(s"Recovery recent-root: fetching header for recent block $recentBlock.")
        scheduler.scheduleOnce(20.seconds, self, RecentRootTimeout(gen))(context.dispatcher, self)
      case None =>
        log.info("Recovery recent-root: no usable peer height yet; declining the roll.")
        recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
        recentRootRequester = None
    }
  }

  private def stopRecentRootBootstrap(): Unit = {
    recentRootBootstrap.foreach { case (peersClient, bootstrap) =>
      bootstrap ! PoisonPill
      peersClient ! PoisonPill
    }
    recentRootBootstrap = None
  }

  /** Walk backward from bestBlock via parentHash until a header with a plausible stored TD is found (ChainDownloader
    * anchor), then accumulate forward to produce the correct cumulative TD for bestBlock. Writes the result to DB if
    * plausible.
    *
    * MUST NOT use getBlockHeaderByNumber: SNAP-synced nodes lack the number→hash canonical index for pre-pivot blocks.
    * Returns None silently for most such blocks, causing silent TD undercount. Uses parentHash traversal exclusively
    * (same approach as ChainWeightRepair).
    *
    * Returns true if calibration wrote a value; false if deferred (caller schedules retry).
    */
  private def calibrateTDFromLocalChain(): Boolean = {
    val MaxWalkBlocks = 10000
    // Conservative ETC lower bound: ~10^13 difficulty per block.
    // Rejects BLOCK_NUMBER_PROXY (blockNum ≈ 24.7M << correct TD ≈ 24.64×10^21) and
    // RegularSync wrong TDs built on a proxy base.
    val MinTDPerBlock = BigInt("10000000000000")

    blockchainReader.getBestBlockHeader match {
      case None =>
        log.warning("TIMED_CALIBRATION_LOCAL: no best block header — skipping (attempt={})", tdCalibrationAttempt)
        false

      case Some(bestHeader) =>
        log.info(
          "TIMED_CALIBRATION_LOCAL: tier3 triggered (attempt={}), bestBlock={}, walking up to {} blocks",
          tdCalibrationAttempt,
          bestHeader.number,
          MaxWalkBlocks
        )

        // Phase 1: walk backward via parentHash collecting headers above the anchor.
        // headersAboveAnchor is DESCENDING (bestBlock first); reversed in Phase 2.
        val headersAboveAnchor = scala.collection.mutable.ArrayBuffer.empty[com.chipprbots.ethereum.domain.BlockHeader]
        var cur = bestHeader
        var anchorTD = BigInt(0)
        var anchorFound = false
        var abort = false

        while !anchorFound && !abort do
          blockchainReader.getChainWeightByHash(cur.hash) match {
            case Some(cw) if cw.totalDifficulty > cur.number * MinTDPerBlock =>
              anchorTD = cw.totalDifficulty
              anchorFound = true
              log.debug(
                "TIMED_CALIBRATION_LOCAL: anchor found at block={} anchorTD={} (walked {} headers)",
                cur.number,
                anchorTD,
                headersAboveAnchor.size
              )
            case _ =>
              if headersAboveAnchor.size >= MaxWalkBlocks then {
                abort = true
              } else {
                headersAboveAnchor += cur
                blockchainReader.getBlockHeaderByHash(cur.parentHash) match {
                  case Some(parent) => cur = parent
                  case None =>
                    log.warning(
                      "TIMED_CALIBRATION_LOCAL: parentHash chain broken at block={} hash={} — aborting (attempt={})",
                      cur.number,
                      cur.hash,
                      tdCalibrationAttempt
                    )
                    abort = true
                }
              }
          }

        if abort then {
          log.warning(
            "TIMED_CALIBRATION_LOCAL: no plausible anchor within {} blocks of bestBlock={} — deferring (attempt={})",
            MaxWalkBlocks,
            bestHeader.number,
            tdCalibrationAttempt
          )
          false
        } else {
          // Phase 2: accumulate forward from anchorTD over headers collected above anchor.
          // All headers guaranteed present (collected via parentHash traversal — no silent skips).
          var td = anchorTD
          headersAboveAnchor.reverseIterator.foreach(h => td += h.difficulty)

          val genesisWeight = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty)
            .getOrElse(blockchainReader.genesisHeader.difficulty)

          if td > genesisWeight * BigInt(1000) then {
            val storedTD = blockchainReader
              .getChainWeightByHash(bestHeader.hash)
              .map(_.totalDifficulty)
              .getOrElse(BigInt(0))
            blockchainWriter
              .storeChainWeight(bestHeader.hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(td))
              .commit()
            log.info(
              "CHAIN_WEIGHT_CALIBRATED_LOCAL: anchor={} gap={} calibratedTD={} source=LOCAL_CHAIN_ACCUMULATION attempt={}",
              cur.number,
              headersAboveAnchor.size,
              td,
              tdCalibrationAttempt
            )
            val tdRatio = if storedTD > BigInt(0) then (td / storedTD).toString else "∞"
            log.info(
              s"TD_CALIBRATION_SUMMARY: block=${bestHeader.number} before=$storedTD after=$td ratio=$tdRatio source=LOCAL_CHAIN attempt=$tdCalibrationAttempt"
            )
            true
          } else {
            log.warning(
              "TIMED_CALIBRATION_LOCAL: computed td={} below plausibility threshold (genesisWeight={}) — aborting write (attempt={})",
              td,
              genesisWeight,
              tdCalibrationAttempt
            )
            false
          }
        }
    }
  }

  /** Schedule a retry of CalibrateChainWeightNow in 30 minutes. Called when tier-3 calibration defers (ChainDownloader
    * gap > 10K blocks). The retry loop continues until calibration succeeds or ETH68 peers appear.
    */
  private def scheduleTDCalibrationRetry(): Unit = {
    context.system.scheduler.scheduleOnce(30.minutes) {
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNow
    }(context.dispatcher)
    val bestBlockNum = blockchainReader.getBestBlockHeader.map(_.number).getOrElse(BigInt(0))
    log.info(
      "TIMED_CALIBRATION_LOCAL: retry #{} scheduled in 30min (ChainDownloader advancing, current bestBlock={})",
      tdCalibrationAttempt + 1,
      bestBlockNum
    )
  }

  def startRegularSyncForBootstrap(): ActorRef = {
    log.info("Starting regular sync for SNAP sync bootstrap")

    val peersClient =
      context.actorOf(
        PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
        "peers-client-bootstrap"
      )
    val regularSync = context.actorOf(
      RegularSync.props(
        peersClient,
        networkPeerManager,
        peerEventBus,
        consensus,
        blockchainReader,
        blockchainWriter,
        stateStorage,
        evmCodeStorage,
        { val br = new BranchResolution(blockchainReader); br.messConfig = messConfig; br },
        validators.blockValidator,
        blacklist,
        syncConfig,
        ommersPool,
        pendingTransactionsManager,
        configBuilder
      ),
      "regular-sync-bootstrap"
    )
    regularSync ! SyncProtocol.Start
    regularSync
  }

}

object SyncController {

  /** Pick the block to roll the recovery storage download onto: `margin` blocks back from the highest known
    * SNAP-capable peer head (so the target is inside peers' snapshot serve window), clamped to >= 1. Returns None when
    * no peer height is known yet, so the caller declines the roll and lets the abandon path run. Pure for testability.
    */
  private[sync] def recentRootTarget(snapPeerHeights: Iterable[BigInt], margin: BigInt): Option[BigInt] =
    snapPeerHeights.filter(_ > 0).maxOption.map(best => (best - margin).max(1))

  // scalastyle:off parameter.number
  def props(
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      nodeStorage: NodeStorage,
      flatSlotStorage: FlatSlotStorage,
      syncStateStorage: FastSyncStateStorage,
      consensus: ConsensusAdapter,
      validators: Validators,
      peerEventBus: ActorRef,
      pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[
        com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
      ],
      ommersPool: org.apache.pekko.actor.typed.ActorRef[com.chipprbots.ethereum.ommers.OmmersPool.Command],
      networkPeerManager: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      messConfig: Option[MESSConfig] = None,
      forkChoiceManagerOpt: Option[ForkChoiceManager] = None
  ): Props =
    Props(
      new SyncController(
        blockchain,
        blockchainReader,
        blockchainWriter,
        appStateStorage,
        blockNumberMappingStorage,
        evmCodeStorage,
        stateStorage,
        nodeStorage,
        flatSlotStorage,
        syncStateStorage,
        consensus,
        validators,
        peerEventBus,
        pendingTransactionsManager,
        ommersPool,
        networkPeerManager,
        blacklist,
        syncConfig,
        configBuilder,
        messConfig,
        forkChoiceManagerOpt
      )
    )
}
