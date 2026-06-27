package com.chipprbots.ethereum.network

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/** STOP-AND-ALERT death-watch for a critical actor that must NOT be restarted (restart corrupts state or silently drops
  * subscribers / in-flight session state). See `.claude/agent-protocols/alert-wrapper-protocol.md`.
  *
  * The critical child is spawned normally (callers hold its real, unwrapped ref — no message-forwarding hop, no
  * hot-path cost). A standalone alerter is spawned alongside it and `watchWith`-es the child. When the child
  * terminates, the alerter logs a `CRITICAL` error (the keyword ops alerting greps for) and stops. There is
  * deliberately no `SupervisorStrategy` restart — the node is left in a loudly-flagged degraded state requiring a
  * controlled restart, rather than a silent recovery that hides the failure.
  */
object CriticalActorAlerter:

  /** Internal lifecycle signal: the watched critical actor terminated. */
  private case object CriticalActorFailed

  /** Watch `critical` and emit a CRITICAL alert on its termination.
    *
    * @param critical
    *   the already-spawned STOP-AND-ALERT actor to death-watch (any `ActorRef`, not necessarily a child)
    * @param name
    *   stable name of the watched actor, used in the alert log line
    */
  def apply(critical: TypedActorRef[?], name: String): Behavior[Nothing] =
    Behaviors
      .setup[CriticalActorFailed.type] { ctx =>
        ctx.watchWith(critical, CriticalActorFailed)
        Behaviors.receiveMessage { case CriticalActorFailed =>
          ctx.log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", name)
          Behaviors.stopped
        }
      }
      .narrow
