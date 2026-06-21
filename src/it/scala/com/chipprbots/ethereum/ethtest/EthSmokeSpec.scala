package com.chipprbots.ethereum.ethtest

import com.chipprbots.ethereum.testing.Tags.*

/** Fast ETH-path smoke spec (< 60s) — exercises the ETH execution path (chainId=1, `forTimestamp` dispatch) below
  * `testComprehensive`.
  *
  * Each case drives one named vector through `runSingleTest`. Paths and keys are taken from the locally-reachable
  * classpath resources under `/ethereum-tests/` that sibling specs (`SimpleEthereumTest`, `BlockchainTestsSpec`) already
  * load — no invented paths. The reachable resource vectors cover Berlin and Istanbul only; London/Cancun/4844 vectors
  * are not present in the classpath (they live in the CI-only `ets/tests` submodule), so they are intentionally not
  * referenced here.
  */
class EthSmokeSpec extends EthereumTestsSpec {

  private def smoke(path: String, name: String): Unit =
    runSingleTest(path, name) match {
      case Right(_)    => info(s"  ✓ $name")
      case Left(error) => fail(s"$name failed: $error")
    }

  "EthSmoke" should "execute Berlin SimpleTx" taggedAs EthSmoke in {
    smoke("/ethereum-tests/SimpleTx.json", "SimpleTx_Berlin")
  }

  it should "execute Istanbul SimpleTx" taggedAs EthSmoke in {
    smoke("/ethereum-tests/SimpleTx.json", "SimpleTx_Istanbul")
  }

  it should "execute Berlin add11" taggedAs EthSmoke in {
    smoke("/ethereum-tests/add11.json", "add11_d0g0v0_Berlin")
  }

  it should "execute Berlin dataTx" taggedAs EthSmoke in {
    smoke("/ethereum-tests/dataTx.json", "dataTx_Berlin")
  }

  it should "execute Berlin ExtraData32" taggedAs EthSmoke in {
    smoke("/ethereum-tests/ExtraData32.json", "ExtraData32_Berlin")
  }
}
