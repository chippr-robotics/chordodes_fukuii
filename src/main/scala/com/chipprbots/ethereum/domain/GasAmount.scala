package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type GasAmount = BigInt

object GasAmount:
  val Zero: GasAmount = BigInt(0)
  def apply(v: BigInt): GasAmount = v
  def apply(v: Long): GasAmount = BigInt(v)
  def apply(v: Int): GasAmount = BigInt(v)

  extension (g: GasAmount)
    def value: BigInt = g
    def toLong: Long = g.toLong
    def +(other: GasAmount): GasAmount = g + other
    def -(other: GasAmount): GasAmount = g - other
    def *(n: Long): GasAmount = g * n
    def *(n: BigInt): GasAmount = g * n
    def /(n: Long): GasAmount = g / n
    def /(n: Int): GasAmount = g / n
    def /(other: GasAmount): BigInt = g / other
    def compare(other: GasAmount): Int = g.compare(other)
    def >(other: GasAmount): Boolean = g > other
    def >=(other: GasAmount): Boolean = g >= other
    def <(other: GasAmount): Boolean = g < other
    def <=(other: GasAmount): Boolean = g <= other
    def abs: GasAmount = g.abs
    def min(other: GasAmount): GasAmount = if g < other then g else other
    def max(other: GasAmount): GasAmount = if g > other then g else other

  given rlpCodec: RLPCodec[GasAmount] = bigIntEncDec.xmap((v: BigInt) => GasAmount(v), _.value)
  given Ordering[GasAmount] = Ordering.by(_.value)
