package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256

opaque type CodeHash = ByteString
object CodeHash:
  val Empty: CodeHash = kec256(ByteString.empty)
  def apply(bs: ByteString): CodeHash = bs
  extension (ch: CodeHash)
    def value: ByteString = ch
    def isEmpty: Boolean = ch == Empty
