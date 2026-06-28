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
class EthSmokeSpec extends EthereumTestsSpec:

  private def smoke(path: String, name: String): Unit =
    runSingleTest(path, name) match
      case Right(_)    => info(s"  ✓ $name")
      case Left(error) => fail(s"$name failed: $error")

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

  // Post-merge vectors (Cancun + Prague): active as of G5.
  //
  // These 10 vectors come from ethereum/tests. They are now driven through the same
  // ETH execution path as the Berlin/Istanbul vectors above. The adapter was extended
  // (G5) to make them pass:
  //   - TestTransaction now decodes `gasPrice` as OPTIONAL. Type-0x02 (EIP-1559) and
  //     type-0x03 (EIP-4844) transactions omit gasPrice (they carry maxFeePerGas /
  //     maxPriorityFeePerGas instead); TestConverter defaults the absent value to 0,
  //     which the dynamic-fee/blob branches never read.
  //   - TestBlockHeader now decodes the post-merge header fields (baseFeePerGas,
  //     withdrawalsRoot, blobGasUsed, excessBlobGas, parentBeaconBlockRoot,
  //     requestsHash). TestConverter.toBlockHeader selects the matching
  //     HeaderExtraFields variant so the reconstructed genesis hashes byte-identically
  //     to block[0].parentHash, fixing the prior MissingParentError parent linkage.

  it should "execute Cancun basefeeExample (EIP-1559) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/basefeeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/basefeeExample.json::basefeeExample_Cancun"
    )
  }

  it should "execute Prague basefeeExample (EIP-1559) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/basefeeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/basefeeExample.json::basefeeExample_Prague"
    )
  }

  it should "execute Cancun mergeExample (EIP-3675) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/mergeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/mergeExample.json::mergeExample_Cancun"
    )
  }

  it should "execute Prague mergeExample (EIP-3675) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/mergeExample.json",
      "BlockchainTests/ValidBlocks/bcExample/mergeExample.json::mergeExample_Prague"
    )
  }

  it should "execute Cancun shanghaiExample (EIP-4895 withdrawals) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/shanghaiExample.json",
      "BlockchainTests/ValidBlocks/bcExample/shanghaiExample.json::shanghaiExample_Cancun"
    )
  }

  it should "execute Prague shanghaiExample (EIP-4895 withdrawals) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/shanghaiExample.json",
      "BlockchainTests/ValidBlocks/bcExample/shanghaiExample.json::shanghaiExample_Prague"
    )
  }

  it should "execute Cancun tloadDoesNotPersistCrossTxn (EIP-1153) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/tloadDoesNotPersistCrossTxn.json",
      "BlockchainTests/ValidBlocks/bcEIP1153-transientStorage/tloadDoesNotPersistCrossTxn.json::tloadDoesNotPersistCrossTxn_Cancun"
    )
  }

  it should "execute Prague tloadDoesNotPersistCrossTxn (EIP-1153) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/tloadDoesNotPersistCrossTxn.json",
      "BlockchainTests/ValidBlocks/bcEIP1153-transientStorage/tloadDoesNotPersistCrossTxn.json::tloadDoesNotPersistCrossTxn_Prague"
    )
  }

  it should "execute Cancun blockWithAllTransactionTypes (EIP-4844 blobs) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/blockWithAllTransactionTypes.json",
      "BlockchainTests/ValidBlocks/bcEIP4844-blobtransactions/blockWithAllTransactionTypes.json::blockWithAllTransactionTypes_Cancun"
    )
  }

  it should "execute Prague blockWithAllTransactionTypes (EIP-4844 blobs) [G5]" taggedAs EthSmoke in {
    smoke(
      "/ethereum-tests/blockWithAllTransactionTypes.json",
      "BlockchainTests/ValidBlocks/bcEIP4844-blobtransactions/blockWithAllTransactionTypes.json::blockWithAllTransactionTypes_Prague"
    )
  }
