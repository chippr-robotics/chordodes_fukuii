package com.chipprbots.ethereum.ethtest

import com.chipprbots.ethereum.testing.Tags.*

/** Fast ETH-path smoke spec (< 60s) — exercises the ETH execution path (chainId=1, `forTimestamp` dispatch) below
  * `testComprehensive`.
  *
  * Each case drives one named vector through `runSingleTest`. Paths and keys are taken from the locally-reachable
  * classpath resources under `/ethereum-tests/` that sibling specs (`SimpleEthereumTest`, `BlockchainTestsSpec`)
  * already load — no invented paths. The reachable resource vectors cover Berlin and Istanbul only; London/Cancun/4844
  * vectors are not present in the classpath (they live in the CI-only `ets/tests` submodule), so they are intentionally
  * not referenced here.
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

  // Post-merge vectors (Cancun + Prague): wired but IGNORED until G5.
  //
  // These 10 vectors were copied from ethereum/tests and wired here, but they
  // cannot pass against the current EthereumTestsAdapter, which is pre-merge-shaped:
  //   - TestTransaction decodes `gasPrice` as a REQUIRED field. Type-0x02 (EIP-1559)
  //     and type-0x03 (EIP-4844) transactions omit gasPrice (they carry maxFeePerGas /
  //     maxPriorityFeePerGas instead), so 8 of these vectors fail at JSON decode with
  //     "DecodingFailure ... gasPrice: Missing required field".
  //   - TestBlockHeader drops baseFeePerGas, withdrawalsRoot, excessBlobGas,
  //     blobGasUsed, parentBeaconBlockRoot, and TestBlock ignores the withdrawals
  //     array. The shanghaiExample vectors (legacy gasPrice txns) therefore decode
  //     but fail execution with MissingParentError: block[0].parentHash binds to a
  //     genesis hash computed WITH withdrawalsRoot/baseFeePerGas, but the adapter
  //     rebuilds the genesis header without those fields and recomputes a different
  //     hash, breaking parent linkage.
  //
  // Extending the adapter (optional gasPrice + maxFee threading, post-merge header
  // fields, withdrawals array) is tracked as DEFERRED-BACKLOG Part 9 / G5 (BEACON,
  // Medium). When G5 lands, change `ignore` back to `it` and `taggedAs EthSmoke`,
  // and switch these to byte-exact header/withdrawal conformance.

  ignore should "execute Cancun basefeeExample (EIP-1559) [G5]" in {
    smoke(
      "/ethereum-tests/basefeeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/basefeeExample.json::basefeeExample_Cancun"
    )
  }

  ignore should "execute Prague basefeeExample (EIP-1559) [G5]" in {
    smoke(
      "/ethereum-tests/basefeeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/basefeeExample.json::basefeeExample_Prague"
    )
  }

  ignore should "execute Cancun mergeExample (EIP-3675) [G5]" in {
    smoke(
      "/ethereum-tests/mergeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/mergeExample.json::mergeExample_Cancun"
    )
  }

  ignore should "execute Prague mergeExample (EIP-3675) [G5]" in {
    smoke(
      "/ethereum-tests/mergeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/mergeExample.json::mergeExample_Prague"
    )
  }

  ignore should "execute Cancun shanghaiExample (EIP-4895 withdrawals) [G5]" in {
    smoke(
      "/ethereum-tests/shanghaiExample.json",
      "BlockchainTests/ValidBlocks/bcExample/shanghaiExample.json::shanghaiExample_Cancun"
    )
  }

  ignore should "execute Prague shanghaiExample (EIP-4895 withdrawals) [G5]" in {
    smoke(
      "/ethereum-tests/shanghaiExample.json",
      "BlockchainTests/ValidBlocks/bcExample/shanghaiExample.json::shanghaiExample_Prague"
    )
  }

  ignore should "execute Cancun tloadDoesNotPersistCrossTxn (EIP-1153) [G5]" in {
    smoke(
      "/ethereum-tests/tloadDoesNotPersistCrossTxn.json",
      "BlockchainTests/ValidBlocks/bcEIP1153-transientStorage/tloadDoesNotPersistCrossTxn.json::tloadDoesNotPersistCrossTxn_Cancun"
    )
  }

  ignore should "execute Prague tloadDoesNotPersistCrossTxn (EIP-1153) [G5]" in {
    smoke(
      "/ethereum-tests/tloadDoesNotPersistCrossTxn.json",
      "BlockchainTests/ValidBlocks/bcEIP1153-transientStorage/tloadDoesNotPersistCrossTxn.json::tloadDoesNotPersistCrossTxn_Prague"
    )
  }

  ignore should "execute Cancun blockWithAllTransactionTypes (EIP-4844 blobs) [G5]" in {
    smoke(
      "/ethereum-tests/blockWithAllTransactionTypes.json",
      "BlockchainTests/ValidBlocks/bcEIP4844-blobtransactions/blockWithAllTransactionTypes.json::blockWithAllTransactionTypes_Cancun"
    )
  }

  ignore should "execute Prague blockWithAllTransactionTypes (EIP-4844 blobs) [G5]" in {
    smoke(
      "/ethereum-tests/blockWithAllTransactionTypes.json",
      "BlockchainTests/ValidBlocks/bcEIP4844-blobtransactions/blockWithAllTransactionTypes.json::blockWithAllTransactionTypes_Prague"
    )
  }
}
