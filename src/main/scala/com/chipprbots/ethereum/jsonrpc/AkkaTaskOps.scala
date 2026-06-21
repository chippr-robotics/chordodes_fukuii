package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.reflect.ClassTag

object AkkaTaskOps {
  extension (to: ActorRef) {
    def askFor[A](
        message: Any
    )(implicit timeout: Timeout, classTag: ClassTag[A], sender: ActorRef = ActorRef.noSender): IO[A] =
      // let the akka ask future manage its timeout instead of adding a second timeout layer
      IO.fromFuture(IO((to ? message).mapTo[A]))
  }

  // Typed ask: converts the temp typed replyTo to a Classic ActorRef so existing Cmd variants
  // (replyTo: ActorRef) stay unchanged. The lambda receives the Classic ref directly.
  extension [C](to: typed.ActorRef[C]) {
    def askFor[A](
        makeCmd: ActorRef => C
    )(implicit timeout: Timeout, scheduler: typed.Scheduler): IO[A] =
      IO.fromFuture(IO(to.ask[A](typedRef => makeCmd(typedRef.toClassic))))
  }
}
