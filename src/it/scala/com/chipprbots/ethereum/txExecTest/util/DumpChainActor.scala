package com.chipprbots.ethereum.txExecTest.util

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

/** Empty-trie / empty-code marker hashes shared by the txExecTest fixture tooling.
  *
  * The original `DumpChainActor` (a Classic Pekko actor that downloaded blockchain data from a bootstrap peer and
  * dumped it to files) was unused dead code with no spawn site. Only these two constants are still referenced — by
  * [[FixtureProvider]] — so the actor was removed and the constants retained here.
  */
object DumpChainActor:
  val emptyStorage: ByteString = ByteString(
    Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")
  )
  val emptyEvm: ByteString = ByteString(Hex.decode("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))
