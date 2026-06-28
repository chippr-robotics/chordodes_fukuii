package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.actor.ActorSystem as ClassicSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.faucet.FaucetHandler
import com.chipprbots.ethereum.faucet.FaucetHandler.Command
import com.chipprbots.ethereum.faucet.FaucetHandler.Command.SendFunds
import com.chipprbots.ethereum.faucet.FaucetHandler.Command.Status
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.FaucetIsUnavailable
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.StatusResponse
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.TransactionSent
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.WalletRpcClientError
import com.chipprbots.ethereum.faucet.FaucetStatus.WalletAvailable
import com.chipprbots.ethereum.faucet.RpcClientConfig
import com.chipprbots.ethereum.faucet.SupervisorConfig
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.testing.Tags.*

class FaucetRpcServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with TypeCheckedTripleEquals:

  implicit val runtime: IORuntime = IORuntime.global

  "FaucetRpcService" should "answer txHash correctly when the wallet is available and the requested send funds be successfully" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)
    val txHash: ByteString = ByteString(Hex.decode("112233"))

    val future: Future[Either[JsonRpcError, SendFundsResponse]] = faucetRpcService.sendFunds(request).unsafeToFuture()
    val cmd: SendFunds = handlerProbe.expectMessageType[Command.SendFunds]
    cmd.replyTo ! TransactionSent(txHash)

    future.futureValue match
      case Left(error)     => fail(s"failure with error: $error")
      case Right(response) => response.txId shouldBe txHash

  it should "answer WalletRpcClientError when the wallet is available and the requested send funds be failure" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)
    val clientError = "Parser error"

    val future: Future[Either[JsonRpcError, SendFundsResponse]] = faucetRpcService.sendFunds(request).unsafeToFuture()
    val cmd: SendFunds = handlerProbe.expectMessageType[Command.SendFunds]
    cmd.replyTo ! WalletRpcClientError(clientError)

    future.futureValue match
      case Right(_)    => fail()
      case Left(error) => error shouldBe JsonRpcError.LogicError(s"Faucet error: $clientError")

  it should "answer FaucetIsUnavailable when tried to send funds and the wallet is unavailable" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)

    val future: Future[Either[JsonRpcError, SendFundsResponse]] = faucetRpcService.sendFunds(request).unsafeToFuture()
    val cmd: SendFunds = handlerProbe.expectMessageType[Command.SendFunds]
    cmd.replyTo ! FaucetIsUnavailable

    future.futureValue match
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds")

  it should "answer FaucetIsUnavailable when tried to get status and the wallet is unavailable" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val future: Future[Either[JsonRpcError, FaucetDomain.StatusResponse]] =
      faucetRpcService.status(StatusRequest()).unsafeToFuture()
    val cmd: Status = handlerProbe.expectMessageType[Command.Status]
    cmd.replyTo ! FaucetIsUnavailable

    future.futureValue match
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds")

  it should "answer WalletAvailable when tried to get status and the wallet is available" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val future: Future[Either[JsonRpcError, FaucetDomain.StatusResponse]] =
      faucetRpcService.status(StatusRequest()).unsafeToFuture()
    val cmd: Status = handlerProbe.expectMessageType[Command.Status]
    cmd.replyTo ! StatusResponse(WalletAvailable)

    future.futureValue match
      case Left(error)     => fail(s"failure with error: $error")
      case Right(response) => response shouldBe FaucetDomain.StatusResponse(WalletAvailable)

  it should "answer internal error when tried to send funds but the Faucet Handler is disable" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)

    faucetRpcServiceWithoutFaucetHandler.sendFunds(request).unsafeToFuture().futureValue match
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.InternalError

  it should "answer internal error when tried to get status but the Faucet Handler is disable" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    faucetRpcServiceWithoutFaucetHandler.status(StatusRequest()).unsafeToFuture().futureValue match
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.InternalError

  class TestSetup:
    implicit val classicSystem: ClassicSystem = FaucetRpcServiceSpec.this.system.toClassic

    val config: FaucetConfig = FaucetConfig(
      walletAddress = Address("0x99"),
      walletPassword = "",
      txGasPrice = 10,
      txGasLimit = 20,
      txValue = 1,
      rpcClient = RpcClientConfig(address = "", timeout = 10.seconds),
      keyStoreDir = "",
      handlerTimeout = 10.seconds,
      actorCommunicationMargin = 10.seconds,
      supervisor = mock[SupervisorConfig],
      shutdownTimeout = 15.seconds
    )

    val handlerProbe: TestProbe[Command] = testKit.createTestProbe[FaucetHandler.Command]()
    val faucetRpcService = new FaucetRpcService(config, handlerProbe.ref)

    val shortTimeoutConfig: FaucetConfig =
      config.copy(actorCommunicationMargin = 50.millis, rpcClient = config.rpcClient.copy(timeout = 50.millis))
    val silentProbe: TestProbe[Command] = testKit.createTestProbe[FaucetHandler.Command]()
    val faucetRpcServiceWithoutFaucetHandler: FaucetRpcService =
      new FaucetRpcService(shortTimeoutConfig, silentProbe.ref)
