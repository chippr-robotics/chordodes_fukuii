package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type BlockNumber = BigInt

object BlockNumber:
  val Zero: BlockNumber = BigInt(0)
  val Genesis: BlockNumber = BigInt(0)

  def apply(v: BigInt): BlockNumber = v
  def apply(v: Long): BlockNumber = BigInt(v)
  def apply(v: Int): BlockNumber = BigInt(v)

  extension (n: BlockNumber)
    def value: BigInt = n
    def toLong: Long = n.toLong
    def toInt: Int = n.toInt
    def +(other: BlockNumber): BlockNumber = n + other
    def +(delta: Long): BlockNumber = n + delta
    def -(other: BlockNumber): BlockNumber = n - other
    def -(delta: Long): BlockNumber = n - delta
    def *(k: Long): BlockNumber = n * k
    def /(k: Long): BlockNumber = n / k
    def compare(other: BlockNumber): Int = n.compare(other)
    def >(other: BlockNumber): Boolean = n > other
    def >=(other: BlockNumber): Boolean = n >= other
    def <(other: BlockNumber): Boolean = n < other
    def <=(other: BlockNumber): Boolean = n <= other
    def min(other: BlockNumber): BlockNumber = if n < other then n else other
    def max(other: BlockNumber): BlockNumber = if n > other then n else other
    def abs: BlockNumber = n.abs

  given rlpCodec: RLPCodec[BlockNumber] = bigIntEncDec.xmap((v: BigInt) => BlockNumber(v), _.value)
  given Ordering[BlockNumber] = Ordering.by(_.value)
