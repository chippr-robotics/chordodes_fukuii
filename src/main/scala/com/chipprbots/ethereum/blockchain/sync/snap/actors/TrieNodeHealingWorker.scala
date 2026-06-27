package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*

/** TrieNodeHealingWorker fetches trie nodes from a peer.
  *
  * Proxy worker — announces peer availability to the coordinator, which owns all healing logic, and forwards responses
  * back. Pekko Typed leaf actor (Group W1). `coordinator` is a typed ref (§8k-A). The idle watchdog uses a Typed
  * `TimerScheduler` rather than `system.scheduler.scheduleOnce`.
  */
object TrieNodeHealingWorker:

  import TrieNodeHealingCoordinator.*

  type Command = WorkerMessage

  /** @param coordinator
    *   Parent coordinator that manages all healing logic (Typed)
    * @param networkPeerManager
    *   Network manager (unused by this proxy; retained for call-site symmetry)
    * @param requestTracker
    *   Request tracker (unused by this proxy; retained for call-site symmetry)
    */
  def apply(
      coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      @annotation.unused networkPeerManager: org.apache.pekko.actor.ActorRef,
      @annotation.unused requestTracker: SNAPRequestTracker
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      idle(coordinator, timers, currentRequestId = None)
    }

  private def idle(
      coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      timers: TimerScheduler[Command],
      currentRequestId: Option[BigInt]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (_, msg) =>
      msg match
        case FetchTrieNodes(_, peer) =>
          // Request work from coordinator by notifying it of peer availability
          coordinator ! HealingPeerAvailable(peer)
          timers.startSingleTimer(HealingCheckIdle, 30.seconds)
          working(coordinator, timers, currentRequestId)
        case _ => Behaviors.same
    }

  private def working(
      coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      timers: TimerScheduler[Command],
      currentRequestId: Option[BigInt]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (context, msg) =>
      msg match
        case TrieNodesResponseMsg(response) =>
          // Forward response to coordinator for processing
          coordinator ! TrieNodesResponseMsg(response)
          idle(coordinator, timers, currentRequestId = None)

        case HealingCheckIdle =>
          // If still working after timeout, go back to idle
          if currentRequestId.isEmpty then
            context.log.debug("[HEALING-WORKER] idle check: no active request — worker idle, awaiting assignment")
            idle(coordinator, timers, currentRequestId = None)
          else Behaviors.same

        case HealingRequestTimeout(requestId) =>
          currentRequestId match
            case Some(reqId) if reqId == requestId =>
              context.log.warn(s"Healing request $requestId timed out")
              coordinator ! HealingTaskFailed(requestId, "Timeout")
              idle(coordinator, timers, currentRequestId = None)
            case _ => Behaviors.same

        case _: FetchTrieNodes => Behaviors.same // busy; ignore (matches Classic behaviour)
    }
