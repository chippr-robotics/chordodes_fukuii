package com.chipprbots.ethereum.utils

import com.chipprbots.ethereum.domain.UInt256

object BigIntExtensionMethods:
  extension (srcBigInteger: BigInt)
    def toUnsignedByteArray: Array[Byte] =
      ByteUtils.bigIntToUnsignedByteArray(srcBigInteger)

    def u256: UInt256 = UInt256(srcBigInteger)
