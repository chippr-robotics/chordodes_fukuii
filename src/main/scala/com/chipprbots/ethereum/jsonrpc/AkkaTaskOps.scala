package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.reflect.ClassTag

object AkkaTaskOps {
  extension (to: ActorRef) {
    def askFor[A](
        message: Any // Any: Classic Pekko ask — ActorRef.? is untyped
    )(implicit timeout: Timeout, classTag: ClassTag[A], sender: ActorRef = ActorRef.noSender): IO[A] =
      // let the akka ask future manage its timeout instead of adding a second timeout layer
      IO.fromFuture(IO((to ? message).mapTo[A]))

    // Classic ask where the reply target is an explicit field of the message (replyTo: ActorRef).
    // Uses the "extended" ask so the ask temp actor is passed as the Cmd's replyTo instead of the
    // implicit sender(). Mirrors the typed `askFor(makeCmd)` for Cmd variants that carry replyTo.
    def askForVia[A](
        makeCmd: ActorRef => Any // Any: Classic extended ask — untyped by design
    )(implicit timeout: Timeout, classTag: ClassTag[A]): IO[A] =
      IO.fromFuture(IO(org.apache.pekko.pattern.extended.ask(to, makeCmd).mapTo[A]))
  }

  extension [C](to: typed.ActorRef[C]) {
    def askForTyped[A](
        makeCmd: typed.ActorRef[A] => C
    )(implicit timeout: Timeout, scheduler: typed.Scheduler): IO[A] =
      IO.fromFuture(IO(to.ask[A](makeCmd)))
  }
}
