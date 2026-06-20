package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.*
import org.apache.pekko.actor.typed.{ActorRef as TypedActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets

class PeerRequestHandler[RequestMsg <: Message, ResponseMsg <: Message: ClassTag](
    peer: Peer,
    responseTimeout: FiniteDuration,
    networkPeerManager: ActorRef,
    peerEventBus: TypedActorRef[PeerEventBusCommand],
    requestMsg: RequestMsg,
    responseMsgCode: Int
)(implicit scheduler: Scheduler, toSerializable: RequestMsg => MessageSerializable)
    extends Actor
    with ActorLogging {

  import PeerRequestHandler.*

  private val initiator: ActorRef = context.parent

  private val timeout: Cancellable = scheduler.scheduleOnce(responseTimeout, self, Timeout)

  private val startTime: Long = System.currentTimeMillis()

  private val expectedRequestId: Option[BigInt] = requestMsg match {
    case hasId: ETHPackets.HasRequestId => Some(hasId.requestId)
    case _                              => None
  }

  private def subscribeMessageClassifier = MessageClassifier(Set(responseMsgCode), PeerSelector.WithId(peer.id))

  private def timeTakenSoFar(): Long = System.currentTimeMillis() - startTime

  override def preStart(): Unit = {
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(toSerializable(requestMsg), peer.id)
    peerEventBus ! SubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)), context.self)
    peerEventBus ! SubscribeCmd(subscribeMessageClassifier, context.self)
  }

  override def receive: Receive = {
    case MessageFromPeer(responseMsg: ResponseMsg, _) =>
      (expectedRequestId, responseMsg) match {
        case (Some(expected), hasId: ETHPackets.HasRequestId) if hasId.requestId != expected =>
          log.debug(
            "PEER_REQUEST_STALE: peer={}, expected requestId={}, got={} — ignoring",
            peer.id,
            expected,
            hasId.requestId
          )
        case _ =>
          handleResponseMsg(responseMsg)
      }
    case Timeout                                       => handleTimeout()
    case PeerDisconnected(peerId) if peerId == peer.id => handleTerminated()
  }

  def handleResponseMsg(responseMsg: ResponseMsg): Unit = {
    val elapsed = timeTakenSoFar()
    cleanupAndStop()
    initiator ! ResponseReceived(peer, responseMsg, timeTaken = elapsed)
  }

  def handleTimeout(): Unit = {
    val elapsed = timeTakenSoFar()
    log.warning(
      "PEER_REQUEST_TIMEOUT: peer={}, reqType={}, elapsed={}ms (timeout={}ms)",
      peer.id,
      requestMsg.getClass.getSimpleName,
      elapsed,
      responseTimeout.toMillis
    )
    cleanupAndStop()
    initiator ! RequestFailed(peer, "request timeout")
  }

  def handleTerminated(): Unit = {
    val elapsed = timeTakenSoFar()
    log.warning(
      "PEER_REQUEST_DISCONNECTED: peer={}, reqType={}, elapsed={}ms - connection closed before response",
      peer.id,
      requestMsg.getClass.getSimpleName,
      elapsed
    )
    cleanupAndStop()
    initiator ! RequestFailed(peer, "connection closed")
  }

  def cleanupAndStop(): Unit = {
    timeout.cancel()
    peerEventBus ! UnsubscribeAllCmd(context.self)
    context.stop(self)
  }
}

object PeerRequestHandler {

  // ---- Classic API (unchanged) ----

  def props[RequestMsg <: Message, ResponseMsg <: Message: ClassTag](
      peer: Peer,
      responseTimeout: FiniteDuration,
      networkPeerManager: ActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      requestMsg: RequestMsg,
      responseMsgCode: Int
  )(implicit scheduler: Scheduler, toSerializable: RequestMsg => MessageSerializable): Props =
    Props(new PeerRequestHandler(peer, responseTimeout, networkPeerManager, peerEventBus, requestMsg, responseMsgCode))

  // ---- Shared result types (Classic and Typed callers) ----

  sealed trait Result
  final case class RequestFailed(peer: Peer, reason: String) extends Result
  final case class ResponseReceived[T](peer: Peer, response: T, timeTaken: Long) extends Result

  // ---- Typed API ----

  sealed trait Command

  final private case class MessageFromPeerCmd(msg: Message) extends Command
  final private case class PeerLeftCmd(peerId: com.chipprbots.ethereum.network.PeerId) extends Command
  private case object TimeoutCmd extends Command

  /** Typed factory: spawns one `PeerRequestHandler` per request. Replies to `replyTo` with `ResponseReceived` or
    * `RequestFailed` then stops. Callers pass an explicit `replyTo` because `context.parent` is unavailable in Pekko
    * Typed.
    */
  def behavior[RequestMsg <: Message, ResponseMsg <: Message: ClassTag](
      peer: Peer,
      responseTimeout: FiniteDuration,
      networkPeerManager: ActorRef,
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      requestMsg: RequestMsg,
      responseMsgCode: Int,
      replyTo: TypedActorRef[Result]
  )(implicit toSerializable: RequestMsg => MessageSerializable): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val startTime = System.currentTimeMillis()

        val expectedRequestId: Option[BigInt] = requestMsg match {
          case hasId: ETHPackets.HasRequestId => Some(hasId.requestId)
          case _                              => None
        }

        val msgAdapter = ctx.messageAdapter[MessageFromPeer] { case MessageFromPeer(m, _) => MessageFromPeerCmd(m) }
        val disconnectAdapter = ctx.messageAdapter[PeerDisconnected] { case PeerDisconnected(pid) => PeerLeftCmd(pid) }

        networkPeerManager.tell(
          NetworkPeerManagerActor.SendMessage(toSerializable(requestMsg), peer.id),
          ActorRef.noSender
        )
        peerEventBus ! SubscribeCmd(
          PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)),
          disconnectAdapter.toClassic
        )
        peerEventBus ! SubscribeCmd(
          MessageClassifier(Set(responseMsgCode), PeerSelector.WithId(peer.id)),
          msgAdapter.toClassic
        )
        timers.startSingleTimer("timeout", TimeoutCmd, responseTimeout)

        def timeTakenSoFar(): Long = System.currentTimeMillis() - startTime

        def cleanup(): Unit = {
          timers.cancel("timeout")
          peerEventBus ! UnsubscribeAllCmd(msgAdapter.toClassic)
          peerEventBus ! UnsubscribeAllCmd(disconnectAdapter.toClassic)
        }

        Behaviors.receiveMessage {
          case MessageFromPeerCmd(msg) =>
            msg match {
              case responseMsg: ResponseMsg =>
                (expectedRequestId, responseMsg) match {
                  case (Some(expected), hasId: ETHPackets.HasRequestId) if hasId.requestId != expected =>
                    ctx.log.debug(
                      "PEER_REQUEST_STALE: peer={}, expected requestId={}, got={} — ignoring",
                      peer.id,
                      expected,
                      hasId.requestId
                    )
                    Behaviors.same
                  case _ =>
                    val elapsed = timeTakenSoFar()
                    cleanup()
                    replyTo ! ResponseReceived(peer, responseMsg, elapsed)
                    Behaviors.stopped
                }
              case _ =>
                Behaviors.same
            }

          case TimeoutCmd =>
            val elapsed = timeTakenSoFar()
            ctx.log.warn(
              "PEER_REQUEST_TIMEOUT: peer={}, reqType={}, elapsed={}ms (timeout={}ms)",
              peer.id,
              requestMsg.getClass.getSimpleName,
              Long.box(elapsed),
              Long.box(responseTimeout.toMillis)
            )
            cleanup()
            replyTo ! RequestFailed(peer, "request timeout")
            Behaviors.stopped

          case PeerLeftCmd(peerId) if peerId == peer.id =>
            val elapsed = timeTakenSoFar()
            ctx.log.warn(
              "PEER_REQUEST_DISCONNECTED: peer={}, reqType={}, elapsed={}ms - connection closed before response",
              peer.id,
              requestMsg.getClass.getSimpleName,
              Long.box(elapsed)
            )
            cleanup()
            replyTo ! RequestFailed(peer, "connection closed")
            Behaviors.stopped

          case PeerLeftCmd(_) =>
            Behaviors.same
        }
      }
    }

  private case object Timeout
}
