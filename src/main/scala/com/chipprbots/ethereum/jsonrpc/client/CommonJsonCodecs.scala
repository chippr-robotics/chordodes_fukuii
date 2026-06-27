package com.chipprbots.ethereum.jsonrpc.client

import org.apache.pekko.util.ByteString

import scala.util.Try

import io.circe.*
import io.circe.syntax.*
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.utils.NumericUtils.*
import com.chipprbots.ethereum.utils.StringUtils

object CommonJsonCodecs:
  given decodeBigInt: Decoder[BigInt] = (c: HCursor) =>
    // try converting from JSON number
    c.as[JsonNumber]
      .flatMap(n => n.toBigInt.toRight(DecodingFailure("Unable to convert to BigInt", c.history)))
      .left
      .flatMap { _ =>
        // if that fails, convert from JSON string
        c.as[String].flatMap(stringToBigInt).left.map(DecodingFailure.fromThrowable(_, c.history))
      }

  given encodeByteString: Encoder[ByteString] =
    (b: ByteString) => ("0x" + Hex.toHexString(b.toArray)).asJson

  given decodeByteString: Decoder[ByteString] =
    (c: HCursor) => c.as[String].map(s => ByteString(Hex.decode(StringUtils.drop0x(s))))

  given encodeAddress: Encoder[Address] =
    (a: Address) => a.toString.asJson

  given decodeAddress: Decoder[Address] =
    (c: HCursor) => c.as[String].map(Address(_))

  private def stringToBigInt(s: String): Either[Throwable, BigInt] =
    if s.isEmpty || s == "0x" then Right(BigInt(0)) else Try(parseHexOrDecNumber(s)).toEither
