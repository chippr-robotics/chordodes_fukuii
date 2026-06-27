package com.chipprbots.ethereum.network

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.event.ActorEventBus
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source

import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MaintainedPeersChanged
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.*
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.Message

object PeerEventBusActor {

  /** Classic-facing factory. Existing Classic callers (PeerActor, PeerManagerActor, PeerRequestHandler,
    * PivotBlockSelector, PeersClient, PeerListSupportNg) and the Akka-Streams [[messageSource]] keep working unchanged
    * through this shell: it captures `sender()` (the subscriber) and `Terminated`, enriches the wire messages with the
    * explicit subscriber, and forwards them to the Typed dispatch core spawned as a child.
    *
    * The dispatch logic lives in the Typed [[behavior]]; this Classic shell exists only as the `sender()` bridge and is
    * removed once the last Classic subscriber migrates (Group NET).
    */
  def props: Props = Props(new PeerEventBusActor)

  /** Handle subscription to the peer event bus via Akka Streams.
    *
    * @param peerEventBus
    *   ref to PeerEventBusActor
    * @param messageClassifier
    *   specify which messages to subscribe to
    * @return
    *   Source that subscribes to the peer event bus on materialization and unsubscribes on cancellation. It will
    *   complete when the event bus actor terminates.
    *
    * Note:
    *   - subscription is asynchronous so it may miss messages when starting.
    *   - it does not complete when a specified peerId disconnects.
    */
  def messageSource(peerEventBus: ActorRef, messageClassifier: MessageClassifier): Source[MessageFromPeer, NotUsed] =
    Source
      .fromMaterializer { (mat, _) =>
        val (actorRef, src) = Source
          // Buffer 64 + dropHead: an event-bus relay should absorb bursty peer messages, not die
          // on the first race. Buffer-1 + fail made PeerEventBusActorSpec flaky (BufferOverflowException).
          .actorRef[MessageFromPeer](PartialFunction.empty, PartialFunction.empty, 64, OverflowStrategy.dropHead)
          .watch(peerEventBus)
          .preMaterialize()(mat)
        peerEventBus
          .tell(Subscribe(messageClassifier), actorRef)
        src
      }
      .mapMaterializedValue(_ => NotUsed)

  sealed trait PeerSelector {
    def contains(peerId: PeerId): Boolean
  }

  object PeerSelector {

    case object AllPeers extends PeerSelector {
      override def contains(p: PeerId): Boolean = true
    }

    case class WithId(peerId: PeerId) extends PeerSelector {
      override def contains(p: PeerId): Boolean = p == peerId
    }
  }

  sealed trait SubscriptionClassifier

  object SubscriptionClassifier {
    case class MessageClassifier(messageCodes: Set[Int], peerSelector: PeerSelector) extends SubscriptionClassifier
    case class PeerDisconnectedClassifier(peerSelector: PeerSelector) extends SubscriptionClassifier
    case object PeerHandshaked extends SubscriptionClassifier
    case object MaintainedPeersClassifier extends SubscriptionClassifier
  }

  sealed trait PeerEvent

  object PeerEvent {
    case class MessageFromPeer(message: Message, peerId: PeerId) extends PeerEvent
    case class PeerDisconnected(peerId: PeerId) extends PeerEvent
    case class PeerHandshakeSuccessful[R <: HandshakeResult](peer: Peer, handshakeResult: R) extends PeerEvent
    case class MaintainedPeersChanged(nodeIds: Set[String]) extends PeerEvent
  }

  case class Subscription(subscriber: ActorRef, classifier: SubscriptionClassifier)

  class PeerEventBus extends ActorEventBus {

    override type Event = PeerEvent
    override type Classifier = SubscriptionClassifier

    private var messageSubscriptions: Map[(Subscriber, PeerSelector), Set[Int]] = Map.empty
    private var connectionSubscriptions: Seq[Subscription] = Nil

    /** Subscribes the subscriber to a requested event
      *
      * @param subscriber
      * @param to,
      *   classifier for the event subscribed
      * @return
      *   true if successful and false if not (because it was already subscribed to that Classifier, or otherwise)
      */
    override def subscribe(subscriber: ActorRef, to: Classifier): Boolean = to match {
      case msgClassifier: MessageClassifier => subscribeToMessageReceived(subscriber, msgClassifier)
      case _                                => subscribeToConnectionEvent(subscriber, to)
    }

    /** Unsubscribes the subscriber from a requested event
      *
      * @param subscriber
      * @param from,
      *   classifier for the event to unsubscribe
      * @return
      *   true if successful and false if not (because it wasn't subscribed to that Classifier, or otherwise)
      */
    override def unsubscribe(subscriber: ActorRef, from: Classifier): Boolean = from match {
      case msgClassifier: MessageClassifier => unsubscribeFromMessageReceived(subscriber, msgClassifier)
      case _                                => unsubscribeFromConnectionEvent(subscriber, from)
    }

    /** Unsubscribes the subscriber from all events it was subscribed
      *
      * @param subscriber
      */
    override def unsubscribe(subscriber: ActorRef): Unit = {
      messageSubscriptions = messageSubscriptions.filter { case ((sub, _), _) =>
        sub != subscriber
      }
      connectionSubscriptions = connectionSubscriptions.filterNot(_.subscriber == subscriber)
    }

    override def publish(event: PeerEvent): Unit = {
      val interestedSubscribers = event match {
        case MessageFromPeer(message, peerId) =>
          messageSubscriptions
            .flatMap { sub =>
              val ((subscriber, peerSelector), messageCodes) = sub
              if peerSelector.contains(peerId) && messageCodes.contains(message.code) then Some(subscriber)
              else None
            }
            .toSeq
            .distinct
        case PeerDisconnected(peerId) =>
          connectionSubscriptions.collect {
            case Subscription(subscriber, classifier: PeerDisconnectedClassifier)
                if classifier.peerSelector.contains(peerId) =>
              subscriber
          }
        case _: PeerHandshakeSuccessful[?] =>
          connectionSubscriptions.collect { case Subscription(subscriber, PeerHandshaked) =>
            subscriber
          }
        case MaintainedPeersChanged(_) =>
          connectionSubscriptions.collect { case Subscription(subscriber, MaintainedPeersClassifier) =>
            subscriber
          }
      }
      interestedSubscribers.foreach(_ ! event)
    }

    /** Subscribes the subscriber to a requested message received event
      *
      * @param subscriber
      * @param to,
      *   classifier for the message received event subscribed
      * @return
      *   true if successful and false if not (because it was already subscribed to that Classifier, or otherwise)
      */
    private def subscribeToMessageReceived(subscriber: ActorRef, to: MessageClassifier): Boolean = {
      val newSubscriptions = messageSubscriptions.get((subscriber, to.peerSelector)) match {
        case Some(messageCodes) =>
          messageSubscriptions + ((subscriber, to.peerSelector) -> (messageCodes ++ to.messageCodes))
        case None => messageSubscriptions + ((subscriber, to.peerSelector) -> to.messageCodes)
      }
      if newSubscriptions == messageSubscriptions then false
      else {
        messageSubscriptions = newSubscriptions
        true
      }
    }

    /** Subscribes the subscriber to a requested connection event (new peer handshaked or peer disconnected)
      *
      * @param subscriber
      * @param to,
      *   classifier for the connection event subscribed
      * @return
      *   true if successful and false if not (because it was already subscribed to that Classifier, or otherwise)
      */
    private def subscribeToConnectionEvent(subscriber: ActorRef, to: Classifier): Boolean = {
      val subscription = Subscription(subscriber, to)
      if connectionSubscriptions.contains(subscription) then {
        false
      } else {
        connectionSubscriptions = connectionSubscriptions :+ subscription
        true
      }
    }

    /** Unsubscribes the subscriber from a requested received message event event
      *
      * @param subscriber
      * @param from,
      *   classifier for the message received event to unsubscribe
      * @return
      *   true if successful and false if not (because it wasn't subscribed to that Classifier, or otherwise)
      */
    private def unsubscribeFromMessageReceived(subscriber: ActorRef, from: MessageClassifier): Boolean =
      messageSubscriptions.get((subscriber, from.peerSelector)).exists { messageCodes =>
        val newMessageCodes = messageCodes -- from.messageCodes
        if messageCodes == newMessageCodes then false
        else {
          if newMessageCodes.isEmpty then
            messageSubscriptions = messageSubscriptions - ((subscriber, from.peerSelector))
          else messageSubscriptions = messageSubscriptions + ((subscriber, from.peerSelector) -> newMessageCodes)
          true
        }
      }

    /** Unsubscribes the subscriber from a requested event
      *
      * @param subscriber
      * @param from,
      *   classifier for the connection event to unsubscribe
      * @return
      *   true if successful and false if not (because it wasn't subscribed to that Classifier, or otherwise)
      */
    private def unsubscribeFromConnectionEvent(subscriber: ActorRef, from: Classifier): Boolean = {
      val subscription = Subscription(subscriber, from)
      if connectionSubscriptions.contains(subscription) then {
        connectionSubscriptions = connectionSubscriptions.filterNot(_ == subscription)
        true
      } else {
        false
      }
    }

  }

  case class Subscribe(to: SubscriptionClassifier)

  object Unsubscribe {
    def apply(): Unsubscribe = Unsubscribe(None)

    def apply(from: SubscriptionClassifier): Unsubscribe = Unsubscribe(Some(from))
  }

  case class Unsubscribe(from: Option[SubscriptionClassifier] = None)

  case class Publish(ev: PeerEvent)

  /** Typed dispatch protocol.
    *
    * The Classic wire messages [[Subscribe]] / [[Unsubscribe]] / [[Publish]] carry no subscriber — the subscriber is
    * the Classic `sender()`. The Typed core cannot observe `sender()`, so each subscriber is carried explicitly.
    * Subscribers are held as Classic [[ActorRef]] because the dispatch class [[PeerEventBus]] delivers to Classic refs;
    * a Typed subscriber supplies one via `messageAdapter[PeerEvent](...).toClassic` (the established HERALD-2 path).
    */
  sealed trait Command

  /** Subscribe `subscriber` to events matching `to`. */
  final case class SubscribeCmd(to: SubscriptionClassifier, subscriber: ActorRef) extends Command

  /** Unsubscribe `subscriber` from events matching `from`. */
  final case class UnsubscribeCmd(from: SubscriptionClassifier, subscriber: ActorRef) extends Command

  /** Unsubscribe `subscriber` from all events. */
  final case class UnsubscribeAllCmd(subscriber: ActorRef) extends Command

  /** Publish `ev` to all interested subscribers. */
  final case class PublishCmd(ev: PeerEvent) extends Command

  /** Internal: a watched subscriber terminated; drop all its subscriptions. */
  final private case class SubscriberTerminated(subscriber: ActorRef) extends Command

  /** Typed dispatch core. Holds the classifier state in a [[PeerEventBus]] and watches subscribers so their
    * subscriptions are dropped on termination (replacing the Classic `context.watch` + `Terminated`).
    */
  def behavior(): Behavior[Command] =
    Behaviors.setup { ctx =>
      val peerEventBus: PeerEventBus = new PeerEventBus

      Behaviors.receiveMessage {
        case SubscribeCmd(to, subscriber) =>
          peerEventBus.subscribe(subscriber, to)
          // watchWith lifts the subscriber's death into a typed Command (no Classic Terminated in Typed).
          ctx.watchWith(subscriber.toTyped[Nothing], SubscriberTerminated(subscriber))
          Behaviors.same

        case UnsubscribeCmd(from, subscriber) =>
          peerEventBus.unsubscribe(subscriber, from)
          Behaviors.same

        case UnsubscribeAllCmd(subscriber) =>
          peerEventBus.unsubscribe(subscriber)
          Behaviors.same

        case PublishCmd(ev) =>
          peerEventBus.publish(ev)
          Behaviors.same

        case SubscriberTerminated(subscriber) =>
          peerEventBus.unsubscribe(subscriber)
          Behaviors.same
      }
    }
}

/** Classic `sender()` bridge over the Typed [[PeerEventBusActor.behavior]] dispatch core.
  *
  * Classic callers send the wire messages [[PeerEventBusActor.Subscribe]] / [[PeerEventBusActor.Unsubscribe]] /
  * [[PeerEventBusActor.Publish]] with no subscriber field — the subscriber is `sender()`. This actor captures
  * `sender()` and forwards an enriched [[PeerEventBusActor.Command]] to the Typed core (spawned as a child). The Typed
  * core owns subscriber lifecycle watching, so this shell holds no subscription state of its own.
  */
class PeerEventBusActor extends Actor {
  import PeerEventBusActor.*

  private val core: TypedActorRef[Command] =
    context.spawn(PeerEventBusActor.behavior(), "core")

  override def receive: Receive = {
    case Subscribe(to) =>
      core ! SubscribeCmd(to, sender())

    case Unsubscribe(Some(from)) =>
      core ! UnsubscribeCmd(from, sender())

    case Unsubscribe(None) =>
      core ! UnsubscribeAllCmd(sender())

    case Publish(ev: PeerEvent) =>
      core ! PublishCmd(ev)
  }
}
