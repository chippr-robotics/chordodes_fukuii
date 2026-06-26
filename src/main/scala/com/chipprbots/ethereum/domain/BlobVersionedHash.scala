package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.byteStringEncDec

opaque type BlobVersionedHash = ByteString
object BlobVersionedHash:
  def apply(bs: ByteString): BlobVersionedHash = bs
  extension (bvh: BlobVersionedHash)
    def value: ByteString = bvh
    def toArray: Array[Byte] = bvh.toArray[Byte]
  given rlpCodec: RLPCodec[BlobVersionedHash] =
    byteStringEncDec.xmap(BlobVersionedHash.apply, _.value)
