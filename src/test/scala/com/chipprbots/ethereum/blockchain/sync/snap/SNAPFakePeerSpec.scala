package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.testing.Tags.*

/** Smoke tests verifying SNAPFakePeer infrastructure works as expected.
  *
  * These test the test helper itself, not production code. They serve as executable documentation and as a guard
  * against accidental breakage of the fake peer infrastructure.
  *
  * Model: core-geth eth/downloader/skeleton_test.go skeletonTestPeer usage pattern.
  */
class SNAPFakePeerSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val dummyRoot = ByteString(Array.fill(32)(0xca.toByte))
  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill(32)(0xff.toByte))

  "SNAPFakePeer.empty" should "respond to GetAccountRange with empty AccountRange and boundary proof" taggedAs UnitTest in {
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.empty(classicSystem, "fp-empty-1")
    val req = GetAccountRange(
      requestId = BigInt(1),
      rootHash = dummyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    // SNAPFakePeer sends AccountRangeResponseMsg back to the sender (replyProbe here)
    val msg = replyProbe.expectMsgType[AccountRangeCoordinator.AccountRangeResponseMsg](1.second)
    msg.response.requestId shouldBe BigInt(1)
    msg.response.accounts shouldBe empty
    msg.response.proof should not be empty // boundary proof present

    fakePeer.served.get() shouldBe 1
    fakePeer.dropped.get() shouldBe false
  }

  "SNAPFakePeer.nonResponsive" should "not send any response and keep served count at 0" taggedAs UnitTest in {
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.nonResponsive(classicSystem, "fp-nonresp-1")
    val req = GetAccountRange(
      requestId = BigInt(2),
      rootHash = dummyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    replyProbe.expectNoMessage(200.millis)
    fakePeer.served.get() shouldBe 0
  }

  "SNAPFakePeer.proofless" should "respond with empty AccountRange and no proof" taggedAs UnitTest in {
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.proofless(classicSystem, "fp-proofless-1")
    val req = GetAccountRange(
      requestId = BigInt(3),
      rootHash = dummyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    val msg = replyProbe.expectMsgType[AccountRangeCoordinator.AccountRangeResponseMsg](1.second)
    msg.response.accounts shouldBe empty
    msg.response.proof shouldBe empty // no proof — triggers stateless marking in coordinator
    fakePeer.served.get() shouldBe 1
  }

  "SNAPFakePeer.drop()" should "stop responding after drop() is called" taggedAs UnitTest in {
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.empty(classicSystem, "fp-drop-1")

    fakePeer.drop()
    fakePeer.dropped.get() shouldBe true

    val req = GetAccountRange(
      requestId = BigInt(4),
      rootHash = dummyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    replyProbe.expectNoMessage(200.millis) // no response after drop()
    fakePeer.served.get() shouldBe 0
  }

  "SNAPFakePeer.reconnect()" should "resume responding after reconnect() following drop()" taggedAs UnitTest in {
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.empty(classicSystem, "fp-reconnect-1")

    fakePeer.drop()
    fakePeer.reconnect()
    fakePeer.dropped.get() shouldBe false

    val req = GetAccountRange(
      requestId = BigInt(5),
      rootHash = dummyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    val msg = replyProbe.expectMsgType[AccountRangeCoordinator.AccountRangeResponseMsg](1.second)
    msg.response.requestId shouldBe BigInt(5)
    fakePeer.served.get() shouldBe 1
  }

  "SNAPFakePeer.custom" should "invoke the supplied handler for account range requests" taggedAs UnitTest in {
    val customRoot = ByteString(Array.fill(32)(0xde.toByte))
    val replyProbe = TestProbe()
    val fakePeer = SNAPFakePeer.custom(
      classicSystem,
      "fp-custom-1",
      accountRangeHandler = req =>
        Some(
          AccountRange(
            requestId = req.requestId + 100, // deliberate offset to verify handler was called
            accounts = Seq.empty,
            proof = Seq(ByteString("custom-proof"))
          )
        )
    )

    val req = GetAccountRange(
      requestId = BigInt(6),
      rootHash = customRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1024 * 1024)
    )

    fakePeer.probe.ref.tell(
      NetworkPeerManagerActor.SendMessageCmd(
        new GetAccountRange.GetAccountRangeEnc(req),
        fakePeer.peer.id
      ),
      replyProbe.ref
    )

    val msg = replyProbe.expectMsgType[AccountRangeCoordinator.AccountRangeResponseMsg](1.second)
    msg.response.requestId shouldBe BigInt(106) // handler applied +100 offset
    msg.response.proof shouldBe Seq(ByteString("custom-proof"))
    fakePeer.served.get() shouldBe 1
  }
