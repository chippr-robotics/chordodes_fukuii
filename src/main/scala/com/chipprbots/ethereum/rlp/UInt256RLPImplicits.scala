package com.chipprbots.ethereum.rlp

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.rlp.RLP.*

object UInt256RLPImplicits:

  extension (obj: UInt256)
    def toRLPEncodable: RLPEncodeable =
      RLPValue(if obj.equals(UInt256.Zero) then Array.empty[Byte] else obj.bytes.dropWhile(_ == 0).toArray[Byte])

  extension (bytes: ByteString) def toUInt256: UInt256 = rawDecode(bytes.toArray).toUInt256

  extension (rLPEncodeable: RLPEncodeable)
    def toUInt256: UInt256 = rLPEncodeable match
      case RLPValue(b) => UInt256(b)
      case _           => throw RLPException("src is not an RLPValue")
