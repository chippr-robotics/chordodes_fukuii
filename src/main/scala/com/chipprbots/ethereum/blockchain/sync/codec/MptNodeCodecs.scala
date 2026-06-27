package com.chipprbots.ethereum.blockchain.sync.codec

import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.rlp.*

/** RLP codecs for Merkle Patricia Trie nodes.
  *
  * Moved from ETH63.MptNodeEncoders (network message layer) to the sync codec package. Matches the Besu
  * `ethereum/core/encoding/` pattern: codec infrastructure separate from protocol definitions.
  */
object MptNodeCodecs:
  val BranchNodeChildLength = 16
  val BranchNodeIndexOfValue = 16
  val ExtensionNodeLength = 2
  val LeafNodeLength = 2
  val MaxNodeValueSize = 31
  val HashLength = 32

  implicit class MptNodeEnc(obj: MptNode) extends RLPSerializable:
    def toRLPEncodable: RLPEncodeable = MptTraversals.encode(obj)

  extension (bytes: Array[Byte]) def toMptNode: MptNode = MptTraversals.decodeNode(bytes)

  extension (rlp: RLPEncodeable)
    def toMptNode: MptNode = rlp match
      case RLPValue(bytes) => MptTraversals.decodeNode(bytes)
      case _               => throw new RuntimeException("Cannot decode MptNode from non-RLPValue")
