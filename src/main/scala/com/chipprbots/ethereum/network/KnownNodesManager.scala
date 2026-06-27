package com.chipprbots.ethereum.network

import java.net.URI

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.db.storage.KnownNodesStorage

object KnownNodesManager:

  /** Behavior factory for the Typed KnownNodesManager.
    *
    * Mutable Classic vars (knownNodes, toAdd, toRemove) are threaded through the recursive behavior as the [[State]]
    * accumulator. The PersistChanges tick is driven by a fixed-delay timer rather than the Classic external scheduler.
    */
  def apply(
      config: KnownNodesManagerConfig,
      knownNodesStorage: KnownNodesStorage
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(PersistChanges, config.persistInterval)
        running(ctx, config, knownNodesStorage, State(knownNodes = knownNodesStorage.getKnownNodes))
      }
    }

  final private case class State(
      knownNodes: Set[URI],
      toAdd: Set[URI] = Set.empty,
      toRemove: Set[URI] = Set.empty
  )

  private def running(
      ctx: ActorContext[Command],
      config: KnownNodesManagerConfig,
      knownNodesStorage: KnownNodesStorage,
      state: State
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case AddKnownNode(uri) =>
        if !state.knownNodes.contains(uri) then
          running(
            ctx,
            config,
            knownNodesStorage,
            state.copy(
              knownNodes = state.knownNodes + uri,
              toAdd = state.toAdd + uri,
              toRemove = state.toRemove - uri
            )
          )
        else Behaviors.same

      case RemoveKnownNode(uri) =>
        if state.knownNodes.contains(uri) then
          running(
            ctx,
            config,
            knownNodesStorage,
            state.copy(
              knownNodes = state.knownNodes - uri,
              toAdd = state.toAdd - uri,
              toRemove = state.toRemove + uri
            )
          )
        else Behaviors.same

      case GetKnownNodesReq(replyTo) =>
        replyTo ! KnownNodes(state.knownNodes)
        Behaviors.same

      case PersistChanges =>
        running(ctx, config, knownNodesStorage, persistChanges(ctx, config, knownNodesStorage, state))
    }

  private def persistChanges(
      ctx: ActorContext[Command],
      config: KnownNodesManagerConfig,
      knownNodesStorage: KnownNodesStorage,
      state: State
  ): State =
    ctx.log.debug(s"Persisting ${state.knownNodes.size} known nodes.")
    val pruned =
      if state.knownNodes.size > config.maxPersistedNodes then
        val toAbandon = state.knownNodes.take(state.knownNodes.size - config.maxPersistedNodes)
        state.copy(toRemove = state.toRemove ++ toAbandon, toAdd = state.toAdd -- toAbandon)
      else state
    if pruned.toAdd.nonEmpty || pruned.toRemove.nonEmpty then
      knownNodesStorage.updateKnownNodes(toAdd = pruned.toAdd, toRemove = pruned.toRemove).commit()
      pruned.copy(toAdd = Set.empty, toRemove = Set.empty)
    else pruned

  sealed trait Command

  final case class AddKnownNode(uri: URI) extends Command
  final case class RemoveKnownNode(uri: URI) extends Command

  /** Typed ask request for the current known-node set. */
  final case class GetKnownNodesReq(replyTo: ActorRef[KnownNodes]) extends Command

  /** Internal persist tick. Package-private so tests can inject it directly instead of advancing a scheduler. */
  private[network] case object PersistChanges extends Command

  /** Response message — not part of the Command ADT. */
  final case class KnownNodes(nodes: Set[URI])

  case class KnownNodesManagerConfig(persistInterval: FiniteDuration, maxPersistedNodes: Int)

  object KnownNodesManagerConfig:
    def apply(etcClientConfig: com.typesafe.config.Config): KnownNodesManagerConfig =
      val knownNodesManagerConfig = etcClientConfig.getConfig("network.known-nodes")
      KnownNodesManagerConfig(
        persistInterval = knownNodesManagerConfig.getDuration("persist-interval").toMillis.millis,
        maxPersistedNodes = knownNodesManagerConfig.getInt("max-persisted-nodes")
      )
