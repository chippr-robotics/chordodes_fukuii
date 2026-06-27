package com.chipprbots.ethereum.faucet

import java.security.SecureRandom

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.keyPairToByteStrings
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetHandler.Command
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.ParserError
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcClientError
import com.chipprbots.ethereum.keystore.KeyStore.DecryptionFailed
import com.chipprbots.ethereum.keystore.Wallet

class FaucetHandlerSpec
    extends ScalaTestWithActorTestKit
    with AnyFreeSpecLike
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience:

  "Faucet Handler" - {
    "without wallet unlocked" - {

      "should not respond in case wallet unlock fails" in new TestSetup:
        withUnavailableFaucet {
          handler ! Command.Initialize
          responseProbe.expectNoMessage()
        }

      "shouldn't send funds if the Faucet isn't initialized" in new TestSetup:
        handler ! Command.Status(responseProbe.ref)
        responseProbe.expectMessage(FaucetHandlerResponse.StatusResponse(FaucetStatus.FaucetUnavailable))

        handler ! Command.SendFunds(paymentAddress, responseProbe.ref)
        responseProbe.expectMessage(FaucetHandlerResponse.FaucetIsUnavailable)
    }

    "with wallet unlocked" - {

      "should not respond when Initialization is received in available state" in new TestSetup:
        withInitializedFaucet {
          handler ! Command.Initialize
          responseProbe.expectNoMessage()
        }

      "should respond that it is available when ask the status if it was initialized successfully" in new TestSetup:
        withInitializedFaucet {
          handler ! Command.Status(responseProbe.ref)
          responseProbe.expectMessage(FaucetHandlerResponse.StatusResponse(FaucetStatus.WalletAvailable))
        }

      "should be able to paid if it was initialized successfully" in new TestSetup:
        withInitializedFaucet {
          val retTxId = ByteString(Hex.decode("112233"))
          walletService.sendFunds.expects(wallet, paymentAddress).returning(IO.pure(Right(retTxId)))

          handler ! Command.SendFunds(paymentAddress, responseProbe.ref)
          responseProbe.expectMessage(FaucetHandlerResponse.TransactionSent(retTxId))
        }

      "should failed the payment if don't can parse the payload" in new TestSetup:
        withInitializedFaucet {
          val errorMessage = RpcClientError("parser error")
          walletService.sendFunds
            .expects(wallet, paymentAddress)
            .returning(IO.pure(Left(errorMessage)))

          handler ! Command.SendFunds(paymentAddress, responseProbe.ref)
          responseProbe.expectMessage(FaucetHandlerResponse.WalletRpcClientError(errorMessage.msg))
        }

      "should failed the payment if throw rpc client error" in new TestSetup:
        withInitializedFaucet {
          val errorMessage = ParserError("error parser")
          walletService.sendFunds
            .expects(wallet, paymentAddress)
            .returning(IO.pure(Left(errorMessage)))

          handler ! Command.SendFunds(paymentAddress, responseProbe.ref)
          responseProbe.expectMessage(FaucetHandlerResponse.WalletRpcClientError(errorMessage.msg))
        }
    }
  }

  given runtime: IORuntime = IORuntime.global

  trait TestSetup extends FaucetConfigBuilder:
    val walletService: WalletService = mock[WalletService]
    val paymentAddress: Address = Address("0x99")

    val handler: ActorRef[FaucetHandler.Command] =
      testKit.spawn(FaucetHandler.testBehavior(walletService, faucetConfig, () => ()))

    val responseProbe: TestProbe[FaucetHandlerResponse] = testKit.createTestProbe[FaucetHandlerResponse]()

    val walletKeyPair: AsymmetricCipherKeyPair = generateKeyPair(new SecureRandom)
    val (prvKey, pubKey) = keyPairToByteStrings(walletKeyPair)
    val wallet: Wallet = Wallet(Address(crypto.kec256(pubKey)), prvKey)

    def withUnavailableFaucet(behaviour: => Unit): Unit =
      (() => walletService.getWallet).expects().returning(IO.pure(Left(DecryptionFailed)))

      handler ! Command.Status(responseProbe.ref)
      responseProbe.expectMessage(FaucetHandlerResponse.StatusResponse(FaucetStatus.FaucetUnavailable))

      behaviour

    def withInitializedFaucet(behaviour: => Unit): Unit =
      (() => walletService.getWallet).expects().returning(IO.pure(Right(wallet)))

      handler ! Command.Initialize
      handler ! Command.Status(responseProbe.ref)
      responseProbe.expectMessage(FaucetHandlerResponse.StatusResponse(FaucetStatus.WalletAvailable))

      behaviour
