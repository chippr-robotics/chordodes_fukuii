package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.SyncState
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage

/** Persists current state of fast sync to a storage. Can save only one state at a time. If during persisting new state
  * is received then it will be saved immediately after current state was persisted. If during persisting more than one
  * new state is received then only the last state will be kept in queue.
  *
  * Pekko Typed migration (Group S2): the Classic `context.become` state machine (receive -> idle -> busy) becomes named
  * `Behavior` factories; `sender()` on `GetStorage` becomes an explicit `replyTo`; the `pipeTo(self)` of the persist
  * `Future` becomes `context.pipeToSelf` wrapping the result in `PersistDone`. FastSync (still Classic) is the parent
  * and spawns this via the Classic->Typed adapter, holding the ref as Classic.
  */
object StateStorageActor:

  sealed trait Command

  /** After initialization send a valid Storage reference. */
  final case class Init(storage: FastSyncStateStorage) extends Command

  /** Begin saving the given state to the storage. */
  final case class Persist(state: SyncState) extends Command

  /** Reply with the currently persisted state. */
  final case class GetStorage(replyTo: ActorRef[Option[SyncState]]) extends Command

  /** Internal: the asynchronous persist completed (success or failure). */
  final private case class PersistDone(result: Try[FastSyncStateStorage]) extends Command

  def apply(): Behavior[Command] = withPreRestart(uninitialized())

  /** P19: wrap a behavior so a `PreRestart` signal is observed from every state before the supervisor restarts the
    * actor. StateStorageActor holds only a plain [[FastSyncStateStorage]] reference (no open file handles, no peer-bus
    * subscriptions, no timers) so cleanup is a warning log only — the restarted instance re-receives `Init` from
    * FastSync and rebinds the storage. The log makes an otherwise-silent restart visible in production.
    */
  private def withPreRestart(b: Behavior[Command]): Behavior[Command] =
    Behaviors.intercept(() =>
      new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
        override def aroundSignal(
            c: org.apache.pekko.actor.typed.TypedActorContext[Command],
            signal: org.apache.pekko.actor.typed.Signal,
            target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
        ): Behavior[Command] =
          if signal == PreRestart then
            c.asScala.log.warn(
              "{} received PreRestart — discarding in-flight persist; storage will be rebound on Init",
              c.asScala.self.path.name
            )
          target(c, signal)
    )(b)

  private def uninitialized(): Behavior[Command] =
    Behaviors.receiveMessage {
      case Init(storage) => idle(storage)
      case _             => Behaviors.same
    }

  private def idle(storage: FastSyncStateStorage): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        // begin saving of the state to the storage and become busy
        case Persist(state) => persistState(context, storage, state)
        case GetStorage(reply) =>
          reply ! storage.getSyncState()
          Behaviors.same
        case _ => Behaviors.same
    }

  private def busy(storage: FastSyncStateStorage, stateToPersist: Option[SyncState]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        // update state waiting to be persisted later. we only keep newest state
        case Persist(state) => busy(storage, Some(state))
        // exception was thrown during persisting of a state. push
        case PersistDone(Failure(e)) => throw e
        // state was saved in the storage. become idle
        case PersistDone(Success(s)) if stateToPersist.isEmpty => idle(s)
        // state was saved in the storage but new state is already waiting to be saved.
        case PersistDone(Success(s)) =>
          stateToPersist.fold[Behavior[Command]](idle(s))(persistState(context, s, _))
        case GetStorage(reply) =>
          reply ! storage.getSyncState()
          Behaviors.same
        case Init(_) => Behaviors.unhandled // Init only valid in initial behavior; unexpected here
    }

  private def persistState(
      context: ActorContext[Command],
      storage: FastSyncStateStorage,
      syncState: SyncState
  ): Behavior[Command] =
    given runtime: IORuntime = IORuntime.global

    // `context.log` is strictly confined to the actor thread in Pekko Typed; the IO below runs on the IO runtime, so we
    // capture the underlying (thread-safe) SLF4J logger here, on the actor thread, and use it inside the IO.
    val log = context.log

    val persistingQueues: IO[Try[FastSyncStateStorage]] = IO {
      lazy val result = Try(storage.putSyncState(syncState))
      if log.isDebugEnabled then
        val now = System.currentTimeMillis()
        result
        val end = System.currentTimeMillis()
        log.debug(s"Saving snapshot of a fast sync took ${end - now} ms")
        result
      else result
    }
    context.pipeToSelf(persistingQueues.unsafeToFuture()) {
      case Success(t) => PersistDone(t)
      case Failure(e) => PersistDone(Failure(e))
    }
    busy(storage, None)
