package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.syntax.parallel.*

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.utils.BlockchainConfig

class PoWBlockCreator(
    val pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command],
    val getTransactionFromPoolTimeout: FiniteDuration,
    mining: PoWMining,
    ommersPool: typed.ActorRef[OmmersPool.Command],
    coinbaseProvider: CoinbaseProvider,
    system: ActorSystem
) extends TransactionPicker:
  override lazy val scheduler: Scheduler = system.toTyped.scheduler

  lazy val fullConsensusConfig = mining.config
  lazy val miningConfig = fullConsensusConfig.specific
  private lazy val blockGenerator: PoWBlockGenerator = mining.blockGenerator

  def getBlockForMining(
      parentBlock: Block,
      withTransactions: Boolean = true,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy] = None
  )(implicit blockchainConfig: BlockchainConfig): IO[PendingBlockAndState] =
    val transactions = if withTransactions then getTransactionsFromPool else IO.pure(PendingTransactionsResponse(Nil))
    (getOmmersFromPool(parentBlock.hash.value), transactions).parMapN { case (ommers, pendingTxs) =>
      blockGenerator.generateBlock(
        parentBlock,
        pendingTxs.pendingTransactions.map(_.stx.tx),
        coinbaseProvider.get(),
        ommers.headers,
        initialWorldStateBeforeExecution
      )
    }

  private def getOmmersFromPool(parentBlockHash: ByteString): IO[OmmersPool.Ommers] =
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    implicit val sc: Scheduler = scheduler
    IO.fromFuture(IO(ommersPool.ask[OmmersPool.Ommers](OmmersPool.GetOmmers(parentBlockHash, _))))
      .handleError { ex =>
        log.error("Failed to get ommers, mining block with empty ommers list", ex)
        OmmersPool.Ommers(Nil)
      }
