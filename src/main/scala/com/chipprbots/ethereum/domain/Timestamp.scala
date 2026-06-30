package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.longEncDec

opaque type Timestamp = Long

object Timestamp:
  val Zero: Timestamp = 0L
  val MaxValue: Timestamp = Long.MaxValue

  def apply(v: Long): Timestamp = v
  def apply(v: Int): Timestamp = v.toLong

  extension (t: Timestamp)
    def toLong: Long = t
    def underlying: Long = t
    def -(other: Timestamp): Long = t - other
    def +(delta: Long): Timestamp = t + delta
    def +(delta: Int): Timestamp = t + delta.toLong
    def >(other: Timestamp): Boolean = t > other
    def >=(other: Timestamp): Boolean = t >= other
    def <(other: Timestamp): Boolean = t < other
    def <=(other: Timestamp): Boolean = t <= other
    def ==(other: Timestamp): Boolean = t == other
    def !=(other: Timestamp): Boolean = t != other
    def min(other: Timestamp): Timestamp = if t < other then t else other
    def max(other: Timestamp): Timestamp = if t > other then t else other
    def toHexString: String = t.toHexString

  given rlpCodec: RLPCodec[Timestamp] = longEncDec.xmap((v: Long) => Timestamp(v), _.toLong)
  given Ordering[Timestamp] = Ordering.by(_.toLong)
