package com.chipprbots.ethereum.domain

opaque type ChainId = BigInt

object ChainId:
  def apply(v: BigInt): ChainId = v

  extension (c: ChainId) def value: BigInt = c

  given Ordering[ChainId] = Ordering.BigInt.asInstanceOf[Ordering[ChainId]]
