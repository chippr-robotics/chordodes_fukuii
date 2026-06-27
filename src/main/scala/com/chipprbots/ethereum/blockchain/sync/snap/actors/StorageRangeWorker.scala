package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*

/** StorageRangeWorker fetches storage ranges from a peer.
  *
  * Proxy worker — announces peer availability to the coordinator, which owns all storage sync logic, and forwards
  * responses back. Pekko Typed leaf actor (Group W1). `coordinator` remains a Classic ref during co-existence; sends to
  * it use the classic `tell` via the typed→classic adapter. The idle watchdog uses a Typed `TimerScheduler` rather than
  * `system.scheduler.scheduleOnce`.
  */
object StorageRangeWorker {

  import Messages.*

  type Command = StorageRangeWorkerMessage

  /** @param coordinator
    *   Parent coordinator that manages all storage sync logic (Classic)
    * @param networkPeerManager
    *   Network manager (unused by this proxy; retained for call-site symmetry)
    * @param requestTracker
    *   Request tracker (unused by this proxy; retained for call-site symmetry)
    */
  def apply(
      coordinator: ActorRef,
      @annotation.unused networkPeerManager: ActorRef,
      @annotation.unused requestTracker: SNAPRequestTracker
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      idle(coordinator, timers, currentRequestId = None)
    }

  private def idle(
      coordinator: ActorRef,
      timers: TimerScheduler[Command],
      currentRequestId: Option[BigInt]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (_, msg) =>
      msg match {
        case FetchStorageRanges(_, peer) =>
          // Request work from coordinator by notifying it of peer availability
          coordinator.tell(StoragePeerAvailable(peer), org.apache.pekko.actor.ActorRef.noSender)
          timers.startSingleTimer(StorageCheckIdle, 30.seconds)
          working(coordinator, timers, currentRequestId)
        case _ => Behaviors.same
      }
    }

  private def working(
      coordinator: ActorRef,
      timers: TimerScheduler[Command],
      currentRequestId: Option[BigInt]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (context, msg) =>
      msg match {
        case StorageRangesResponseMsg(response) =>
          // Forward response to coordinator for processing
          coordinator.tell(StorageRangesResponseMsg(response), org.apache.pekko.actor.ActorRef.noSender)
          idle(coordinator, timers, currentRequestId = None)

        case StorageCheckIdle =>
          // If still working after timeout, go back to idle
          if currentRequestId.isEmpty then {
            context.log.debug("[STORAGE-WORKER] idle check: no active request — worker idle, awaiting assignment")
            idle(coordinator, timers, currentRequestId = None)
          } else Behaviors.same

        case StorageRequestTimeout(requestId) =>
          currentRequestId match {
            case Some(reqId) if reqId == requestId =>
              context.log.warn(s"Storage request $requestId timed out")
              coordinator.tell(StorageTaskFailed(requestId, "Timeout"), org.apache.pekko.actor.ActorRef.noSender)
              idle(coordinator, timers, currentRequestId = None)
            case _ => Behaviors.same
          }

        case _: FetchStorageRanges => Behaviors.same // busy; ignore (matches Classic behaviour)
      }
    }
}
