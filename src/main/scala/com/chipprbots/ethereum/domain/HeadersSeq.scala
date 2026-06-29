package com.chipprbots.ethereum.domain

object HeadersSeq:
  def lastNumber(headers: HeadersSeq): Option[BigInt] = headers.lastOption.map(_.number.value)

  def areChain(headers: HeadersSeq): Boolean =
    if headers.length > 1 then
      headers.zip(headers.tail).forall { case (parent, child) =>
        parent.hash == child.parentHash && parent.number + 1 == child.number
      }
    else headers.nonEmpty
