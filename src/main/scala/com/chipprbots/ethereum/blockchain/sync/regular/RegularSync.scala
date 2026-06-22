package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.event.Logging
import org.apache.pekko.event.LoggingAdapter

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InternalLastBlockImport
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.Config.SyncConfig

object RegularSync {
  // non-sealed: SyncProtocol cases (Start, GetStatus, MinedBlock, RegularSyncStuck) sent
  // by Classic SyncController also extend this via SyncProtocol.RegularSyncCommand (P7)
  type Command = SyncProtocol.RegularSyncCommand

  private[regular] case object FetcherStatusTick extends SyncProtocol.RegularSyncCommand
  private[regular] case object PrintStatusTick extends SyncProtocol.RegularSyncCommand
  private val FetcherStatusKey = "RegularSyncFetcherStatus"
  private val PrintStatusKey = "RegularSyncPrintStatus"

  // scalastyle:off parameter.number
  def apply(
      peersClient: TypedActorRef[PeersClient.Command],
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      consensus: ConsensusAdapter,
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      branchResolution: BranchResolution,
      blockValidator: BlockValidator,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      ommersPool: TypedActorRef[OmmersPool.Command],
      pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command],
      blockTopic: TypedActorRef[
        org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
      ],
      configBuilder: BlockchainConfigBuilder
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val log: LoggingAdapter = Logging(ctx.system.classicSystem, classOf[RegularSyncImpl])

        val fetcher: TypedActorRef[BlockFetcher.FetchCommand] =
          ctx.spawn(
            BlockFetcher(peersClient, peerEventBus, ctx.self.narrow[ProgressProtocol], syncConfig, blockValidator),
            "block-fetcher"
          )

        val broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg] =
          ctx.spawn(
            BlockBroadcasterActor.apply(
              new BlockBroadcast(
                networkPeerManager,
                isPoWChain = configBuilder.blockchainConfig.terminalTotalDifficulty.isEmpty
              ),
              peerEventBus,
              networkPeerManager,
              blacklist,
              syncConfig
            ),
            "block-broadcaster"
          )

        val importer: TypedActorRef[BlockImporter.Command] =
          ctx.spawn(
            BlockImporter.apply(
              fetcher,
              consensus,
              blockchainReader,
              blockchainWriter,
              stateStorage,
              evmCodeStorage,
              branchResolution,
              syncConfig,
              ommersPool,
              broadcaster.toClassic,
              pendingTransactionsManager,
              blockTopic,
              ctx.self.toClassic,
              peerEventBus,
              networkPeerManager,
              blockchain,
              blacklist,
              configBuilder
            ),
            "block-importer"
          )

        timers.startTimerWithFixedDelay(FetcherStatusKey, FetcherStatusTick, syncConfig.printStatusInterval)
        timers.startTimerWithFixedDelay(PrintStatusKey, PrintStatusTick, 60.seconds)

        running(
          ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0),
          fetcher,
          importer,
          log,
          ctx
        )
      }
    }

  // scalastyle:off parameter.number
  def props(
      peersClient: TypedActorRef[PeersClient.Command],
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      consensus: ConsensusAdapter,
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      branchResolution: BranchResolution,
      blockValidator: BlockValidator,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      ommersPool: TypedActorRef[OmmersPool.Command],
      pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command],
      blockTopic: TypedActorRef[
        org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
      ],
      configBuilder: BlockchainConfigBuilder
  ): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
      apply(
        peersClient,
        networkPeerManager,
        peerEventBus,
        consensus,
        blockchain,
        blockchainReader,
        blockchainWriter,
        stateStorage,
        evmCodeStorage,
        branchResolution,
        blockValidator,
        blacklist,
        syncConfig,
        ommersPool,
        pendingTransactionsManager,
        blockTopic,
        configBuilder
      )
    )

  private def running(
      progressState: ProgressState,
      fetcher: TypedActorRef[BlockFetcher.FetchCommand],
      importer: TypedActorRef[BlockImporter.Command],
      log: LoggingAdapter,
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case SyncProtocol.Start =>
        log.info("Starting regular sync")
        importer ! BlockImporter.Start
        Behaviors.same

      case SyncProtocol.MinedBlock(block) =>
        log.info("Block mined [number = {}, hash = {}]", block.number, block.header.hashAsHexString)
        importer ! BlockImporter.MinedBlock(block)
        Behaviors.same

      case SyncProtocol.GetStatus =>
        ctx.toClassic.sender() ! progressState.toStatus
        Behaviors.same

      case ProgressProtocol.StartedFetching =>
        running(progressState.copy(startedFetching = true), fetcher, importer, log, ctx)

      case ProgressProtocol.StartingFrom(blockNumber) =>
        val newState = progressState.copy(initialBlock = blockNumber, currentBlock = blockNumber)
        RegularSyncMetrics.setCurrentBlock(blockNumber)
        running(newState, fetcher, importer, log, ctx)

      case ProgressProtocol.GotNewBlock(blockNumber) =>
        log.debug(s"Got information about new block [number = $blockNumber]")
        val newState = progressState.copy(bestKnownNetworkBlock = blockNumber)
        RegularSyncMetrics.setBestKnownNetworkBlock(blockNumber)
        running(newState, fetcher, importer, log, ctx)

      case ProgressProtocol.ImportedBlock(blockNumber, internally) =>
        log.debug(s"Imported new block [number = $blockNumber, internally = $internally]")
        val newState = progressState.copy(currentBlock = blockNumber)
        RegularSyncMetrics.setCurrentBlock(blockNumber)
        RegularSyncMetrics.incrementBlocksImported()
        if internally then {
          fetcher ! InternalLastBlockImport(blockNumber)
        }
        running(newState, fetcher, importer, log, ctx)

      case msg: SyncProtocol.RegularSyncStuck =>
        // Forward escape-valve signal to SyncController (our parent). BlockImporter detects this
        // condition and emits the message; we just relay it up so SyncController can re-trigger
        // SNAP sync from a recent pivot.
        // ROOT-c: SyncController is now Behavior[Command]; its real ref (= ctx.toClassic.parent here, since
        // RegularSync is spawned as a direct child) only accepts SyncController.Command. Wrap the raw SyncProtocol
        // message so it survives the Typed boundary and is unwrapped by handleRegularSyncMsg (a bare send would
        // ClassCastException → dead-letter, silently disabling the SNAP re-sync escape valve).
        log.warning(
          "Regular sync stuck on block {} (missing {}); forwarding to SyncController for SNAP re-sync",
          msg.blockNumber,
          msg.missingHash
        )
        ctx.toClassic.parent ! SyncController.WrappedSyncProtocol(msg)
        Behaviors.same

      case FetcherStatusTick =>
        fetcher ! BlockFetcher.PrintStatus
        Behaviors.same

      case PrintStatusTick =>
        val lag = progressState.bestKnownNetworkBlock - progressState.currentBlock
        val now = System.currentTimeMillis()
        val dtSecs =
          if progressState.lastPrintTimeMs > 0 then (now - progressState.lastPrintTimeMs) / 1000.0 else 0.0
        val deltaBlocks = progressState.currentBlock - progressState.lastPrintBlock
        val rate =
          if dtSecs > 0 && progressState.lastPrintTimeMs > 0 then deltaBlocks.toDouble / dtSecs else 0.0
        val etaStr =
          if rate > 0.1 && lag > 0 then f"${lag.toDouble / rate / 3600}%.1fh"
          else if lag == 0 then "at head"
          else "unknown"
        log.info(
          s"RegularSync: current=${progressState.currentBlock} best=${progressState.bestKnownNetworkBlock} " +
            s"lag=$lag rate=${f"$rate%.1f"}/s eta=$etaStr"
        )
        running(
          progressState.copy(lastPrintBlock = progressState.currentBlock, lastPrintTimeMs = now),
          fetcher,
          importer,
          log,
          ctx
        )

      case _ => Behaviors.same
    }

  case class ProgressState(
      startedFetching: Boolean,
      initialBlock: BigInt,
      currentBlock: BigInt,
      bestKnownNetworkBlock: BigInt,
      lastPrintBlock: BigInt = BigInt(0),
      lastPrintTimeMs: Long = 0L
  ) {
    def toStatus: SyncProtocol.Status =
      if startedFetching && bestKnownNetworkBlock != 0 && currentBlock < bestKnownNetworkBlock then {
        Status.Syncing(initialBlock, Progress(currentBlock, bestKnownNetworkBlock), None)
      } else if startedFetching && bestKnownNetworkBlock != 0 && currentBlock >= bestKnownNetworkBlock then {
        Status.SyncDone
      } else {
        Status.NotSyncing
      }
  }

  sealed trait ProgressProtocol extends SyncProtocol.RegularSyncCommand
  object ProgressProtocol {
    case object StartedFetching extends ProgressProtocol
    case class StartingFrom(blockNumber: BigInt) extends ProgressProtocol
    case class GotNewBlock(blockNumber: BigInt) extends ProgressProtocol
    case class ImportedBlock(blockNumber: BigInt, internally: Boolean) extends ProgressProtocol
  }
}

// Logger name anchor — never instantiated
final private class RegularSyncImpl
