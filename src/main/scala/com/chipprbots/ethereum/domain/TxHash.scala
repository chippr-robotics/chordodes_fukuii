package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

opaque type TxHash = ByteString
object TxHash:
  def apply(bs: ByteString): TxHash = bs
  def fromBytes(bs: ByteString): TxHash = bs
  extension (th: TxHash)
    def value: ByteString = th
    def toHexString: String = th.map("%02x".format(_)).mkString("0x", "", "")
    def toHex: String = th.map("%02x".format(_)).mkString
