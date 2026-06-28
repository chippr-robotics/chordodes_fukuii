package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.implicits.*

import scala.annotation.unused
import scala.collection.immutable.NumericRange
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.FukuiiService.ResetFastSyncRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.ResetFastSyncResponse
import com.chipprbots.ethereum.jsonrpc.FukuiiService.RestartFastSyncRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.RestartFastSyncResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object FukuiiService:
  case class GetAccountTransactionsRequest(address: Address, blocksRange: NumericRange[BigInt])
  case class GetAccountTransactionsResponse(transactions: List[ExtendedTransactionData])

  case class ResetFastSyncRequest()
  case class ResetFastSyncResponse(reset: Boolean)

  case class RestartFastSyncRequest()
  case class RestartFastSyncResponse(started: Boolean, cooldownUntilMillis: Long)
class FukuiiService(
    transactionHistoryService: TransactionHistoryService,
    jsonRpcConfig: JsonRpcConfig,
    syncController: TypedActorRef[SyncController.Command],
    scheduler: Scheduler
):

  import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
  given timeout: Timeout = Timeout(10.seconds)
  private given typedScheduler: Scheduler = scheduler

  given blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def getAccountTransactions(
      request: GetAccountTransactionsRequest
  ): ServiceResponse[GetAccountTransactionsResponse] =
    if request.blocksRange.length > jsonRpcConfig.accountTransactionsMaxBlocks then
      IO.pure(
        Left(
          JsonRpcError.InvalidParams(
            s"""Maximum number of blocks to search is ${jsonRpcConfig.accountTransactionsMaxBlocks}, requested: ${request.blocksRange.length}.
               |See: 'fukuii.network.rpc.account-transactions-max-blocks' config.""".stripMargin
          )
        )
      )
    else
      transactionHistoryService
        .getAccountTransactions(request.address, request.blocksRange)
        .map(GetAccountTransactionsResponse(_).asRight)

  def resetFastSync(@unused request: ResetFastSyncRequest): ServiceResponse[ResetFastSyncResponse] =
    syncController
      .askForTyped[SyncProtocol.ResetFastSyncResponse](replyTo =>
        SyncController.WrappedSyncProtocol(SyncProtocol.ResetFastSync(replyTo))
      )
      .map(resp => Right(ResetFastSyncResponse(resp.reset)))

  def restartFastSync(@unused request: RestartFastSyncRequest): ServiceResponse[RestartFastSyncResponse] =
    syncController
      .askForTyped[SyncProtocol.RestartFastSyncResponse](replyTo =>
        SyncController.WrappedSyncProtocol(SyncProtocol.RestartFastSync(replyTo))
      )
      .map(resp => Right(RestartFastSyncResponse(resp.started, resp.cooldownUntilMillis)))
