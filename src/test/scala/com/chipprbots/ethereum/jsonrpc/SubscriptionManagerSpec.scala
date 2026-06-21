package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager.*
import com.chipprbots.ethereum.testing.Tags.*

/** Unit tests for SubscriptionManager actor.
  *
  * Besu reference: ethereum/api/.../websocket/subscription/SubscriptionManager.java
  * ethereum/api/.../websocket/subscription/blockheaders/NewBlockHeadersSubscriptionService.java
  * ethereum/api/.../websocket/subscription/pending/PendingTransactionSubscriptionService.java
  * ethereum/api/.../websocket/subscription/logs/LogsSubscriptionService.java
  *
  * Tests cover: connection lifecycle, subscribe/unsubscribe, push notification dispatch.
  */
class SubscriptionManagerSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with NormalPatience {

  implicit val mat: Materializer = Materializer(testKit.system.classicSystem)
  implicit val formats: org.json4s.Formats = org.json4s.DefaultFormats

  val fixtureBlock: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

  // ── helpers ────────────────────────────────────────────────────────────────

  def makeManager(): ActorRef[SubscriptionManager.Command] =
    testKit.spawn(SubscriptionManager(new EphemBlockchainTestSetup {}.blockchainReader))

  /** Returns a preMaterialized queue + source pair. */
  def makeQueue(): (SourceQueueWithComplete[String], Source[String, NotUsed]) = Source
    .queue[String](64, OverflowStrategy.dropHead)
    .preMaterialize()(mat)

  /** Collects N messages from the queue source into a Future[Seq[String]]. */
  def collectN(source: org.apache.pekko.stream.scaladsl.Source[String, Any], n: Int): Future[Seq[String]] =
    source.take(n).runWith(Sink.seq)(mat)

  // ── helpers for typed ask ──────────────────────────────────────────────────

  def subscribe(
      mgr: ActorRef[SubscriptionManager.Command],
      connId: String,
      subType: String,
      params: Option[JValue] = None
  ): SubscribeResponse = {
    val probe = testKit.createTestProbe[SubscribeResponse]()
    mgr ! Subscribe(connId, subType, params, probe.ref)
    probe.receiveMessage(5.seconds)
  }

  def unsubscribe(mgr: ActorRef[SubscriptionManager.Command], connId: String, subId: Long): UnsubscribeResponse = {
    val probe = testKit.createTestProbe[UnsubscribeResponse]()
    mgr ! Unsubscribe(connId, subId, probe.ref)
    probe.receiveMessage(5.seconds)
  }

  // ── connection lifecycle ───────────────────────────────────────────────────

  "SubscriptionManager" should "accept RegisterConnection without error" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    mgr ! RegisterConnection("conn-1", queue)
    succeed
  }

  it should "clean up subscriptions when ConnectionClosed is received" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-cleanup"

    mgr ! RegisterConnection(connId, queue)
    val subResp = subscribe(mgr, connId, "newHeads")
    subResp.result.isRight shouldBe true

    mgr ! ConnectionClosed(connId)

    // Unsubscribe after close returns false (not found)
    val subId = subResp.result.toOption.get
    val unsubResp = unsubscribe(mgr, connId, subId)
    unsubResp.found shouldBe false
  }

  // ── subscribe / unsubscribe ────────────────────────────────────────────────

  it should "return a numeric subscription id for newHeads subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-newheads"

    mgr ! RegisterConnection(connId, queue)
    val resp = subscribe(mgr, connId, "newHeads")

    resp.result.isRight shouldBe true
    resp.result.toOption.get should be > 0L
  }

  it should "return a subscription id for logs subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-logs"

    mgr ! RegisterConnection(connId, queue)
    val resp = subscribe(mgr, connId, "logs")

    resp.result.isRight shouldBe true
  }

  it should "return a subscription id for newPendingTransactions" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-pending"

    mgr ! RegisterConnection(connId, queue)
    val resp = subscribe(mgr, connId, "newPendingTransactions")

    resp.result.isRight shouldBe true
  }

  it should "return an error for unknown subscription type" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-unknown"

    mgr ! RegisterConnection(connId, queue)
    val resp = subscribe(mgr, connId, "bogusType")

    resp.result.isLeft shouldBe true
    resp.result.swap.toOption.get should include("Unknown subscription type")
  }

  it should "return true when unsubscribing a valid subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-unsub"

    mgr ! RegisterConnection(connId, queue)
    val subId = subscribe(mgr, connId, "newHeads").result.toOption.get

    val resp = unsubscribe(mgr, connId, subId)
    resp.found shouldBe true
  }

  it should "return false when unsubscribing with wrong connection id" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue1, _) = makeQueue()
    val (queue2, _) = makeQueue()

    mgr ! RegisterConnection("conn-a", queue1)
    mgr ! RegisterConnection("conn-b", queue2)
    val subId = subscribe(mgr, "conn-a", "newHeads").result.toOption.get

    // conn-b trying to unsubscribe conn-a's subscription
    val resp = unsubscribe(mgr, "conn-b", subId)
    resp.found shouldBe false
  }

  // ── push notifications ─────────────────────────────────────────────────────

  it should "push newHeads notification to subscribed connection on NewBlockImported" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, source) = makeQueue()
    val connId = "conn-push-newheads"
    val messages = collectN(source, 1)

    mgr ! RegisterConnection(connId, queue)
    subscribe(mgr, connId, "newHeads")

    // Classic eventStream publish reaches the Typed messageAdapter
    testKit.system.classicSystem.eventStream.publish(NewBlockImported(fixtureBlock))

    val received = Await.result(messages, 5.seconds)
    received should have size 1

    val json = parse(received.head)
    val method = (json \ "method").values.toString
    method shouldBe "eth_subscription"
    val params = json \ "params"
    val number = (params \ "result" \ "number").values.toString
    val hash = (params \ "result" \ "hash").values.toString
    number should startWith("0x")
    hash should startWith("0x")
  }

  it should "not push newHeads to other connections" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue1, _) = makeQueue()
    val (queue2, source2) = makeQueue()
    val connId1 = "conn-iso-1"
    val connId2 = "conn-iso-2"

    mgr ! RegisterConnection(connId1, queue1)
    mgr ! RegisterConnection(connId2, queue2)

    // Only conn1 subscribes
    subscribe(mgr, connId1, "newHeads")

    testKit.system.classicSystem.eventStream.publish(NewBlockImported(fixtureBlock))

    // conn2 should receive nothing — take(1) with a short completion timeout: if nothing was
    // routed the stream never completes and completionTimeout fires, proving isolation.
    Thread.sleep(200)
    val messages2 = source2.take(1).completionTimeout(50.millis).runWith(Sink.seq)(mat)
    intercept[Exception](Await.result(messages2, 200.millis))
  }
}
