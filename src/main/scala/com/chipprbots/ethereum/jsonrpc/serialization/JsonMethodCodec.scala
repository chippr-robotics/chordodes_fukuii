package com.chipprbots.ethereum.jsonrpc.serialization
import org.json4s.JArray
import org.json4s.JValue

import com.chipprbots.ethereum.jsonrpc.JsonRpcError

trait JsonMethodCodec[Req, Res] extends JsonMethodDecoder[Req] with JsonEncoder[Res]
object JsonMethodCodec:
  import scala.language.implicitConversions

  implicit def decoderWithEncoderIntoCodec[Req, Res](
      decEnc: JsonMethodDecoder[Req] & JsonEncoder[Res]
  ): JsonMethodCodec[Req, Res] = new JsonMethodCodec[Req, Res]:
    def decodeJson(params: Option[JArray]): Either[JsonRpcError, Req] = decEnc.decodeJson(params)
    def encodeJson(t: Res): JValue = decEnc.encodeJson(t)
