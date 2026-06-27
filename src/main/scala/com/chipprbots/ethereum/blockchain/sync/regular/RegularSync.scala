package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
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
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.utils.Config.SyncConfig

object RegularSync {
  type Command = SyncProtocol.RegularSyncCommand

  private val FetcherStatusKey = "RegularSyncFetcherStatus"
  private val PrintStatusKey = "RegularSyncPrintStatus"

  /** Type alias so callers using `RegularSync.ProgressProtocol` keep working without import changes. */
  type ProgressProtocol = SyncProtocol.ProgressProtocol
  val ProgressProtocol: SyncProtocol.ProgressProtocol.type = SyncProtocol.ProgressProtocol

  // scalastyle:off parameter.number
  def apply(
      peersClient: TypedActorRef[PeersClient.Command],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
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
      configBuilder: BlockchainConfigBuilder,
      supervisor: TypedActorRef[SyncController.Command]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
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
              broadcaster,
              pendingTransactionsManager,
              blockTopic,
              ctx.self,
              peerEventBus,
              networkPeerManager,
              blockchain,
              blacklist,
              configBuilder
            ),
            "block-importer"
          )

        timers.startTimerWithFixedDelay(
          FetcherStatusKey,
          SyncProtocol.FetcherStatusTick,
          syncConfig.printStatusInterval
        )
        timers.startTimerWithFixedDelay(PrintStatusKey, SyncProtocol.PrintStatusTick, 60.seconds)

        running(
          ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0),
          fetcher,
          importer,
          supervisor,
          ctx
        )
      }
    }

  private def running(
      progressState: ProgressState,
      fetcher: TypedActorRef[BlockFetcher.FetchCommand],
      importer: TypedActorRef[BlockImporter.Command],
      supervisor: TypedActorRef[SyncController.Command],
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case SyncProtocol.Start =>
        ctx.log.info("Starting regular sync")
        importer ! BlockImporter.Start
        Behaviors.same

      case SyncProtocol.MinedBlock(block) =>
        ctx.log.info("Block mined [number = {}, hash = {}]", block.number, block.header.hashAsHexString)
        importer ! BlockImporter.MinedBlock(block)
        Behaviors.same

      case msg: SyncProtocol.GetStatus =>
        msg.replyTo ! progressState.toStatus
        Behaviors.same

      case ProgressProtocol.StartedFetching =>
        running(progressState.copy(startedFetching = true), fetcher, importer, supervisor, ctx)

      case ProgressProtocol.StartingFrom(blockNumber) =>
        val newState = progressState.copy(initialBlock = blockNumber, currentBlock = blockNumber)
        RegularSyncMetrics.setCurrentBlock(blockNumber)
        running(newState, fetcher, importer, supervisor, ctx)

      case ProgressProtocol.GotNewBlock(blockNumber) =>
        ctx.log.debug("Got information about new block [number = {}]", blockNumber)
        val newState = progressState.copy(bestKnownNetworkBlock = blockNumber)
        RegularSyncMetrics.setBestKnownNetworkBlock(blockNumber)
        running(newState, fetcher, importer, supervisor, ctx)

      case ProgressProtocol.ImportedBlock(blockNumber, internally) =>
        ctx.log.debug("Imported new block [number = {}, internally = {}]", blockNumber, internally)
        val newState = progressState.copy(currentBlock = blockNumber)
        RegularSyncMetrics.setCurrentBlock(blockNumber)
        RegularSyncMetrics.incrementBlocksImported()
        if internally then {
          fetcher ! InternalLastBlockImport(blockNumber)
        }
        running(newState, fetcher, importer, supervisor, ctx)

      case msg: SyncProtocol.RegularSyncStuck =>
        // Forward escape-valve signal to SyncController. BlockImporter detects this condition and emits the
        // message; we just relay it up so SyncController can re-trigger SNAP sync from a recent pivot.
        // 8k-F: SyncController is Behavior[Command]; its typed ref is injected as `supervisor` at spawn. Wrap
        // the raw SyncProtocol message in WrappedSyncProtocol so it is unwrapped by handleRegularSyncMsg
        // (the escape valve that re-runs SNAP sync from a recent pivot).
        ctx.log.warn(
          "Regular sync stuck on block {} (missing {}); forwarding to SyncController for SNAP re-sync",
          msg.blockNumber,
          msg.missingHash
        )
        supervisor ! SyncController.WrappedSyncProtocol(msg)
        Behaviors.same

      case SyncProtocol.FetcherStatusTick =>
        fetcher ! BlockFetcher.PrintStatus
        Behaviors.same

      case SyncProtocol.PrintStatusTick =>
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
        ctx.log.info(
          "RegularSync: current={} best={} lag={} rate={}/s eta={}",
          progressState.currentBlock,
          progressState.bestKnownNetworkBlock,
          lag,
          f"$rate%.1f",
          etaStr
        )
        val wormBar =
          if progressState.bestKnownNetworkBlock == 0 then WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Active)
          else if progressState.currentBlock >= progressState.bestKnownNetworkBlock then
            WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Complete)
          else
            val p = (progressState.currentBlock - progressState.initialBlock).toDouble /
              (progressState.bestKnownNetworkBlock - progressState.initialBlock).toDouble
            WormToBrainBar.renderKnown(p)
        ctx.log.info("{} — RegularSync", wormBar)
        running(
          progressState.copy(lastPrintBlock = progressState.currentBlock, lastPrintTimeMs = now),
          fetcher,
          importer,
          supervisor,
          ctx
        )

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

}
