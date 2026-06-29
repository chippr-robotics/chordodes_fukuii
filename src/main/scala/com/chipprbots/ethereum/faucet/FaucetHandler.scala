package com.chipprbots.ethereum.faucet

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetStatus.WalletAvailable
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.Wallet

object FaucetHandler:

  sealed trait Command
  object Command:
    case class Status(replyTo: ActorRef[FaucetHandlerResponse]) extends Command
    private[faucet] case object Initialize extends Command
    case class SendFunds(address: Address, replyTo: ActorRef[FaucetHandlerResponse]) extends Command

  sealed trait FaucetHandlerResponse
  object FaucetHandlerResponse:
    case class StatusResponse(status: FaucetStatus) extends FaucetHandlerResponse
    case object FaucetIsUnavailable extends FaucetHandlerResponse
    case class WalletRpcClientError(error: String) extends FaucetHandlerResponse
    case class TransactionSent(txHash: ByteString) extends FaucetHandlerResponse

  class WalletException(keyStoreError: KeyStoreError) extends RuntimeException(keyStoreError.toString)

  val name: String = "FaucetHandler"

  def apply(walletService: WalletService, config: FaucetConfig, shutdown: () => Unit)(using
      runtime: IORuntime
  ): Behavior[Command] =
    // P19: FaucetSupervisor restarts this handler with backoff on any Exception (a WalletException stops it instead).
    // On PreRestart we drop the unlocked wallet held in the `available` state; the restarted instance re-sends
    // Initialize and re-derives the wallet from the keystore, so cleanup is the warning log only (no open handles).
    Behaviors.intercept(() =>
      new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
        override def aroundSignal(
            c: org.apache.pekko.actor.typed.TypedActorContext[Command],
            signal: org.apache.pekko.actor.typed.Signal,
            target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
        ): Behavior[Command] =
          if signal == PreRestart then
            c.asScala.log.warn(
              "{} received PreRestart — dropping unlocked wallet; will re-initialize from keystore",
              c.asScala.self.path.name
            )
          target(c, signal)
    )(
      Behaviors.setup { ctx =>
        ctx.self ! Command.Initialize
        unavailable(walletService, config, shutdown)
      }
    )

  private[faucet] def testBehavior(walletService: WalletService, config: FaucetConfig, shutdown: () => Unit)(using
      runtime: IORuntime
  ): Behavior[Command] =
    unavailable(walletService, config, shutdown)

  private def unavailable(walletService: WalletService, config: FaucetConfig, shutdown: () => Unit)(using
      runtime: IORuntime
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Command.Status(replyTo) =>
          replyTo ! FaucetHandlerResponse.StatusResponse(FaucetStatus.FaucetUnavailable)
          Behaviors.same

        case Command.Initialize =>
          ctx.log.info("Initialization called (faucet unavailable)")
          walletService.getWallet.unsafeRunSync() match
            case Left(error) =>
              ctx.log.debug(s"Couldn't initialize wallet - error: $error")
              shutdown()
              throw new WalletException(error)
            case Right(wallet) =>
              ctx.log.info("Faucet initialization succeeded")
              available(walletService, wallet, config)

        case Command.SendFunds(addressTo, replyTo) =>
          ctx.log.info(
            s"SendFunds called, to: $addressTo, value: ${config.txValue}, gas price: ${config.txGasPrice}," +
              s" gas limit: ${config.txGasLimit} (faucet unavailable)"
          )
          replyTo ! FaucetHandlerResponse.FaucetIsUnavailable
          Behaviors.same
    }

  private def available(walletService: WalletService, wallet: Wallet, config: FaucetConfig)(using
      runtime: IORuntime
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Command.Status(replyTo) =>
          replyTo ! FaucetHandlerResponse.StatusResponse(WalletAvailable)
          Behaviors.same

        case Command.Initialize =>
          ctx.log.debug("Initialization called (faucet available)")
          Behaviors.same

        case Command.SendFunds(addressTo, replyTo) =>
          ctx.log.info(
            s"SendFunds called, to: $addressTo, value: ${config.txValue}, gas price: ${config.txGasPrice}," +
              s" gas limit: ${config.txGasLimit} (faucet available)"
          )
          walletService
            .sendFunds(wallet, addressTo)
            .map {
              case Right(txHash) => replyTo ! FaucetHandlerResponse.TransactionSent(txHash)
              case Left(error)   => replyTo ! FaucetHandlerResponse.WalletRpcClientError(error.msg)
            }
            .unsafeRunAndForget()
          Behaviors.same
    }
