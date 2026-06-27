package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.annotation.unused

import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.faucet.FaucetHandler
import com.chipprbots.ethereum.faucet.FaucetHandler.Command
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.ServiceResponse
import com.chipprbots.ethereum.utils.Logger

class FaucetRpcService(config: FaucetConfig, handler: ActorRef[FaucetHandler.Command])(implicit
    system: ActorSystem
) extends Logger:

  given actorTimeout: Timeout = Timeout(config.actorCommunicationMargin + config.rpcClient.timeout)
  given scheduler: Scheduler = system.toTyped.scheduler

  def sendFunds(sendFundsRequest: SendFundsRequest): ServiceResponse[SendFundsResponse] =
    IO.fromFuture(
      IO(handler.ask[FaucetHandlerResponse](replyTo => Command.SendFunds(sendFundsRequest.address, replyTo)))
    ).map(handleSendFundsResponse.orElse(handleResponseErrors))
      .recover(handleThrowable)

  def status(@unused statusRequest: StatusRequest): ServiceResponse[StatusResponse] =
    IO.fromFuture(IO(handler.ask[FaucetHandlerResponse](replyTo => Command.Status(replyTo))))
      .map(handleStatusResponse.orElse(handleResponseErrors))
      .recover(handleThrowable)

  private def handleSendFundsResponse
      : PartialFunction[FaucetHandlerResponse, Either[JsonRpcError, SendFundsResponse]] = {
    case FaucetHandlerResponse.TransactionSent(txHash) =>
      Right(SendFundsResponse(txHash))
  }

  private def handleStatusResponse: PartialFunction[FaucetHandlerResponse, Either[JsonRpcError, StatusResponse]] = {
    case FaucetHandlerResponse.StatusResponse(status) =>
      Right(StatusResponse(status))
  }

  private def handleResponseErrors[T]: PartialFunction[FaucetHandlerResponse, Either[JsonRpcError, T]] = {
    case FaucetHandlerResponse.FaucetIsUnavailable =>
      Left(JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds"))
    case FaucetHandlerResponse.WalletRpcClientError(error) =>
      Left(JsonRpcError.LogicError(s"Faucet error: $error"))
    case other =>
      log.debug(s"process failure: $other")
      Left(JsonRpcError.InternalError)
  }

  private def handleThrowable[T]: PartialFunction[Throwable, Either[JsonRpcError, T]] = { case other =>
    log.debug(s"process failure: $other")
    Left(JsonRpcError.InternalError)
  }
