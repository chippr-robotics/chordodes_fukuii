package com.chipprbots.ethereum.consensus.engine

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** Fork-version gating for engine_newPayloadV4 — Prague/Osaka acceptance window (T10-C).
  *
  * V4 must be accepted for Prague and Osaka timestamps, and rejected (UNSUPPORTED_FORK -38005) for any pre-Prague
  * timestamp. When Amsterdam is defined, V4 should also be rejected at Amsterdam+ (V5 takes over), but that guard is
  * not exercised here since Amsterdam is undefined.
  *
  * Timestamps used are far-future sentinels from src/test/resources/application.conf: prague-timestamp = 9999999998
  * osaka-timestamp = 9999999999
  */
class EngineApiNewPayloadV4Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val PragueTs: Long = 9999999998L
  private val PrePragueTs: Long = 9999999997L // one second before Prague

  private val zeroHash32 = "0x" + "00" * 32
  private val zeroAddr20 = "0x" + "00" * 20
  private val zeroBloom = "0x" + "00" * 256

  /** Minimal well-formed execution payload JSON for the given timestamp. */
  private def payloadJson(timestamp: Long): JObject =
    JObject(
      "parentHash" -> JString(zeroHash32),
      "feeRecipient" -> JString(zeroAddr20),
      "stateRoot" -> JString(zeroHash32),
      "receiptsRoot" -> JString(zeroHash32),
      "logsBloom" -> JString(zeroBloom),
      "prevRandao" -> JString(zeroHash32),
      "blockNumber" -> JString("0x1"),
      "gasLimit" -> JString("0x1c9c380"),
      "gasUsed" -> JString("0x0"),
      "timestamp" -> JString("0x" + timestamp.toHexString),
      "extraData" -> JString("0x"),
      "baseFeePerGas" -> JString("0x3b9aca00"),
      "blockHash" -> JString(zeroHash32),
      "transactions" -> JArray(Nil),
      "withdrawals" -> JArray(Nil),
      "blobGasUsed" -> JString("0x0"),
      "excessBlobGas" -> JString("0x0")
    )

  private def newPayloadV4Request(timestamp: Long): JsonRpcRequest =
    JsonRpcRequest(
      "2.0",
      "engine_newPayloadV4",
      Some(
        JArray(
          List(
            payloadJson(timestamp), // params[0]: execution payload
            JArray(Nil), // params[1]: expectedBlobVersionedHashes
            JString(zeroHash32), // params[2]: parentBeaconBlockRoot
            JArray(Nil) // params[3]: executionRequests
          )
        )
      ),
      Some(JInt(1))
    )

  private def stubService: EngineApiService =
    new EngineApiService(null, null, null, null, None)(null, null):
      override def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] =
        IO.pure(PayloadStatusV1(PayloadStatus.Syncing, latestValidHash = None, validationError = None))

  "engine_newPayloadV4" should {

    "pass the Prague fork gate and reach the service for a Prague-era payload" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(PragueTs)).unsafeRunSync()

      // Fork gate must not fire — error code -38005 must not be present.
      response.error.map(_.code) should not be Some(-38005)
      response.result.isDefined shouldBe true
    }

    "reject a pre-Prague payload with -38005 UNSUPPORTED_FORK" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(PrePragueTs)).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
    }
  }
