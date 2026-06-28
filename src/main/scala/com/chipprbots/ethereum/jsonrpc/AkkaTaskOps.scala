package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import cats.effect.IO

object AkkaTaskOps:
  extension [C](to: typed.ActorRef[C])
    def askForTyped[A](
        makeCmd: typed.ActorRef[A] => C
    )(implicit timeout: Timeout, scheduler: typed.Scheduler): IO[A] =
      IO.fromFuture(IO(to.ask[A](makeCmd)))
