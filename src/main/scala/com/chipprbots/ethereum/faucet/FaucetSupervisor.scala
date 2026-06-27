package com.chipprbots.ethereum.faucet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.chipprbots.ethereum.faucet.FaucetHandler.WalletException
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.utils.Logger

object FaucetSupervisor:
  val name = "FaucetSupervisor"

class FaucetSupervisor(walletService: WalletService, config: FaucetConfig, shutdown: () => Unit)(using
    system: ActorSystem,
    runtime: IORuntime
) extends Logger:

  val minBackoff: FiniteDuration = config.supervisor.minBackoff
  val maxBackoff: FiniteDuration = config.supervisor.maxBackoff
  val randomFactor: Double = config.supervisor.randomFactor
  val autoReset: FiniteDuration = config.supervisor.autoReset

  val handler: ActorRef[FaucetHandler.Command] =
    system.spawn(
      Behaviors
        .supervise(
          Behaviors
            .supervise(FaucetHandler(walletService, config, shutdown))
            .onFailure[WalletException](SupervisorStrategy.stop)
        )
        .onFailure[Exception](
          SupervisorStrategy
            .restartWithBackoff(minBackoff, maxBackoff, randomFactor)
            .withResetBackoffAfter(autoReset)
        ),
      FaucetHandler.name
    )
