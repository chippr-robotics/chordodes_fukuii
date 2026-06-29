package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.annotation.unused

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.FilterManager as FM
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterChanges
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterLogs
import com.chipprbots.ethereum.jsonrpc.FilterManager.LogFilterLogs
import com.chipprbots.ethereum.utils.*

object EthFilterService:
  case class NewFilterRequest(filter: Filter)
  case class Filter(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]],
      blockHash: Option[org.apache.pekko.util.ByteString] = None
  )

  case class NewBlockFilterRequest()
  case class NewPendingTransactionFilterRequest()

  case class NewFilterResponse(filterId: BigInt)

  case class UninstallFilterRequest(filterId: BigInt)
  case class UninstallFilterResponse(success: Boolean)

  case class GetFilterChangesRequest(filterId: BigInt)
  case class GetFilterChangesResponse(filterChanges: FilterChanges)

  case class GetFilterLogsRequest(filterId: BigInt)
  case class GetFilterLogsResponse(filterLogs: FilterLogs)

  case class GetLogsRequest(filter: Filter)
  case class GetLogsResponse(filterLogs: LogFilterLogs)

class EthFilterService(
    filterManager: ActorRef[FM.Command],
    filterConfig: FilterConfig,
    blockchainReader: com.chipprbots.ethereum.domain.BlockchainReader
)(implicit system: ActorSystem):
  import EthFilterService.*
  given timeout: Timeout = Timeout(filterConfig.filterManagerQueryTimeout)
  given scheduler: Scheduler = system.toTyped.scheduler

  def newFilter(req: NewFilterRequest): ServiceResponse[NewFilterResponse] =
    import req.filter.*

    IO.fromFuture(
      IO(
        filterManager.ask[FM.NewFilterResponse](replyTo =>
          FM.NewLogFilter(fromBlock, toBlock, address, topics, replyTo)
        )
      )
    ).map { resp =>
      Right(NewFilterResponse(resp.id))
    }

  def newBlockFilter(@unused req: NewBlockFilterRequest): ServiceResponse[NewFilterResponse] =
    IO.fromFuture(
      IO(filterManager.ask[FM.NewFilterResponse](replyTo => FM.NewBlockFilter(replyTo)))
    ).map { resp =>
      Right(NewFilterResponse(resp.id))
    }

  def newPendingTransactionFilter(@unused req: NewPendingTransactionFilterRequest): ServiceResponse[NewFilterResponse] =
    IO.fromFuture(
      IO(filterManager.ask[FM.NewFilterResponse](replyTo => FM.NewPendingTransactionFilter(replyTo)))
    ).map { resp =>
      Right(NewFilterResponse(resp.id))
    }

  def uninstallFilter(req: UninstallFilterRequest): ServiceResponse[UninstallFilterResponse] =
    IO.fromFuture(
      IO(filterManager.ask[FM.UninstallFilterResponse.type](replyTo => FM.UninstallFilter(req.filterId, replyTo)))
    ).map(_ => Right(UninstallFilterResponse(success = true)))

  def getFilterChanges(req: GetFilterChangesRequest): ServiceResponse[GetFilterChangesResponse] =
    IO.fromFuture(
      IO(filterManager.ask[FM.FilterChanges](replyTo => FM.GetFilterChanges(req.filterId, replyTo)))
    ).map { filterChanges =>
      Right(GetFilterChangesResponse(filterChanges))
    }

  def getFilterLogs(req: GetFilterLogsRequest): ServiceResponse[GetFilterLogsResponse] =
    IO.fromFuture(
      IO(filterManager.ask[FM.FilterLogs](replyTo => FM.GetFilterLogs(req.filterId, replyTo)))
    ).map { filterLogs =>
      Right(GetFilterLogsResponse(filterLogs))
    }

  def getLogs(req: GetLogsRequest): ServiceResponse[GetLogsResponse] =
    import req.filter.*

    // Validate: blockHash cannot be combined with fromBlock/toBlock
    if blockHash.isDefined && (fromBlock.isDefined || toBlock.isDefined) then
      IO.pure(Left(JsonRpcError.InvalidParams("cannot specify both blockHash and fromBlock/toBlock")))
    else
      // Resolve block numbers for range validation
      val bestBlockNum = blockchainReader.getBestBlockNumber
      val fromNum = fromBlock.collect { case BlockParam.WithNumber(n) => n }.getOrElse(BigInt(0))
      val toNum = toBlock.collect { case BlockParam.WithNumber(n) => n }.getOrElse(bestBlockNum)

      // Validate: block range must not exceed current head
      if fromNum > bestBlockNum || toNum > bestBlockNum then
        IO.pure(Left(JsonRpcError.InvalidParams("block range extends beyond current head block")))
      else if fromNum > toNum then IO.pure(Left(JsonRpcError.InvalidParams("invalid block range params")))
      else
        // If blockHash specified, resolve to block number and use as from=to.
        // Returns None when the hash resolves to no block (emit empty logs).
        val resolvedPair: Option[(Option[BlockParam], Option[BlockParam])] =
          if blockHash.isDefined then
            val blockNum =
              blockHash.flatMap(h => blockchainReader.getBlockByHash(BlockHash(h)).map(_.header.number.value))
            blockNum match
              case Some(n) =>
                val bp = Some(BlockParam.WithNumber(n))
                Some((bp, bp))
              case None => None
          else Some((fromBlock, toBlock))

        resolvedPair match
          case None =>
            IO.pure(Right(GetLogsResponse(FM.LogFilterLogs(Nil))))
          case Some((resolvedFrom, resolvedTo)) =>
            IO.fromFuture(
              IO(
                filterManager.ask[FM.LogFilterLogs](replyTo =>
                  FM.GetLogs(resolvedFrom, resolvedTo, address, topics, replyTo)
                )
              )
            ).map { filterLogs =>
              Right(GetLogsResponse(filterLogs))
            }
