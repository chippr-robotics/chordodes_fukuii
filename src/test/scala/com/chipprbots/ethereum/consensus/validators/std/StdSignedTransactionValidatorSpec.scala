package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionInitCodeSizeError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionNotEnoughGasForIntrinsicError
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Unit coverage for EIP-3860 initcode size enforcement in [[StdSignedTransactionValidator]].
  *
  * EIP-3860 caps CREATE initcode at 2 * MAX_CODE_SIZE (49152 bytes). It activates at Shanghai on ETH/Sepolia
  * (timestamp-gated) and is NOT active on ETC (no shanghaiTimestamp in the ETC fork config).
  *
  * The test matrix verifies three cases:
  *   - ETH/Sepolia post-Shanghai: oversized initcode → TransactionInitCodeSizeError
  *   - ETH/Sepolia pre-Shanghai: oversized initcode → accepted (any other error is fine)
  *   - ETC at any block: oversized initcode → accepted (EIP-3860 never activates)
  */
class StdSignedTransactionValidatorSpec extends AnyFlatSpec with Matchers {

  private val etcConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  private val ShanghaiTs: Long = 1_000L

  // Minimal ETH/Sepolia-like config: Shanghai at ts=1000, ETH network type so
  // validateOlympiaTxTypes passes immediately (ETH gates those types via London/Prague).
  private val sepoliaConfig: BlockchainConfig = etcConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(ShanghaiTs))
  )

  // §ETH-T1-B: The default test config has all ETC-specific forks (Atlantis through Olympia)
  // at 1e18, and byzantium at 4370000. The forBlock selector uses maxBy((blockNum, priority)),
  // so block 4370000 beats any ETC fork at 0 — resulting in ByzantiumFeeSchedule with
  // G_txdatanonzero=68 and G_initcode_word=0. To get MystiqueFeeSchedule (G_txdatanonzero=16,
  // G_initcode_word=2) active at block 21M, we place mystiqueBlockNumber at 5000000 (above
  // byzantium's 4370000). Spiral stays at 1e18 so EIP-3860 does NOT activate via the
  // block-based fork on ETC — only the timestamp path enables it on ETH/Sepolia.
  private val etcMystiqueConfig: BlockchainConfig = etcConfig.withUpdatedForkBlocks(
    _.copy(mystiqueBlockNumber = BigInt(5_000_000))
  )

  private val sepoliaLondonConfig: BlockchainConfig = etcMystiqueConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(ShanghaiTs))
  )

  // EIP-3860: max initcode = 2 * MAX_CODE_SIZE = 2 * 24576 = 49152 bytes.
  // One byte over the limit to trigger the rejection.
  private val overLimitPayload: ByteString = ByteString(Array.fill(49153)(0.toByte))

  // Real r/s from a known ETC tx — syntactically valid, so checkSyntacticValidity passes.
  private val realR = ByteString(Hex.decode("f337e8ca3306c131eabb756aa3701ec7b00bef0d6cc21fbf6a6f291463d58baf"))
  private val realS = ByteString(Hex.decode("72216654137b4b58a4ece0a6df87aa1a4faf18ec4091839dd1c722fa9604fd09"))

  // Contract creation (receivingAddress = None) with oversized initcode.
  private val initcodeTx: LegacyTransaction = LegacyTransaction(
    nonce = 0,
    gasPrice = BigInt("1000000000"),
    gasLimit = BigInt("1000000"),
    receivingAddress = None,
    value = BigInt(0),
    payload = overLimitPayload
  )

  private val signedInitcodeTx: SignedTransaction = SignedTransaction(
    initcodeTx,
    pointSign = 0x1b.toByte,
    signatureRandom = realR,
    signature = realS
  )

  private val senderAccount: Account =
    Account.empty(UInt256(0)).copy(balance = UInt256(BigInt("1000000000000000000")))

  private val baseHeader: BlockHeader =
    Fixtures.Blocks.Block3125369.header.copy(gasLimit = BigInt("10000000"))

  private def validate(stx: SignedTransaction, blockHeader: BlockHeader)(implicit cfg: BlockchainConfig) =
    StdSignedTransactionValidator.validate(
      stx = stx,
      senderAccount = senderAccount,
      blockHeader = blockHeader,
      upfrontGasCost = UInt256(0),
      accumGasUsed = BigInt(0)
    )

  // ── ETH/Sepolia post-Shanghai ───────────────────────────────────────────────

  it should "reject CREATE with initcode > 49152 bytes on ETH/Sepolia post-Shanghai" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaConfig
    val postShanghaiHeader = baseHeader.copy(unixTimestamp = ShanghaiTs + 1)
    validate(signedInitcodeTx, postShanghaiHeader) match {
      case Left(_: TransactionInitCodeSizeError) => succeed
      case other                                 => fail(s"Expected TransactionInitCodeSizeError, got: $other")
    }
  }

  // ── ETH/Sepolia pre-Shanghai ────────────────────────────────────────────────

  it should "accept CREATE with large initcode on ETH/Sepolia pre-Shanghai (EIP-3860 not yet active)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaConfig
    val preShanghaiHeader = baseHeader.copy(unixTimestamp = ShanghaiTs - 1)
    validate(signedInitcodeTx, preShanghaiHeader) match {
      case Left(_: TransactionInitCodeSizeError) => fail("Large initcode must be accepted pre-Shanghai")
      case _                                     => succeed
    }
  }

  // ── ETC (no Shanghai timestamp configured) ──────────────────────────────────

  it should "accept CREATE with large initcode on ETC (EIP-3860 never activates)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    val etcHeader = baseHeader.copy(number = BigInt(21_000_000), unixTimestamp = ShanghaiTs + 1)
    validate(signedInitcodeTx, etcHeader) match {
      case Left(_: TransactionInitCodeSizeError) => fail("EIP-3860 must not be active on ETC")
      case _                                     => succeed
    }
  }

  // ── §ETH-T1-B: EIP-3860 initcode word cost in validateGasLimitEnoughForIntrinsicGas ──
  //
  // EIP-3860 activates at Shanghai and adds a word cost of 2 gas per 32-byte word of initcode.
  // Pre-Shanghai the 2-arg EvmConfig.forBlock returned London config with eip3860Enabled=false,
  // so the word cost was never included in intrinsic gas at the validator boundary.
  //
  // Test transaction: 200 non-zero bytes of initcode, 7 words (ceil(200/32) = 7).
  // Pre-Shanghai intrinsic = 21000 + 32000 + 200*16 + 0      = 56200
  // Post-Shanghai intrinsic = 21000 + 32000 + 200*16 + 2*7   = 56214
  // gasLimit = 56213 → accepted pre-Shanghai, rejected post-Shanghai.

  // 200 non-zero bytes, well under the 49152-byte size limit; 7 words for EIP-3860 word cost.
  private val wordCostPayload: ByteString = ByteString(Array.fill(200)(1.toByte))

  private val wordCostTx: LegacyTransaction = LegacyTransaction(
    nonce = 0,
    gasPrice = BigInt("1000000000"),
    gasLimit = BigInt(56213), // 56214 - 1: below post-Shanghai intrinsic, above pre-Shanghai
    receivingAddress = None,
    value = BigInt(0),
    payload = wordCostPayload
  )

  private val signedWordCostTx: SignedTransaction = SignedTransaction(
    wordCostTx,
    pointSign = 0x1b.toByte,
    signatureRandom = realR,
    signature = realS
  )

  it should "reject CREATE with gas limit below EIP-3860 word cost on ETH/Sepolia post-Shanghai" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // sepoliaLondonConfig has Mystique fee schedule active at block 0 (G_txdatanonzero=16,
    // G_initcode_word=2). Post-Shanghai: eip3860Enabled=true → intrinsic = 56214 > gasLimit 56213.
    implicit val cfg: BlockchainConfig = sepoliaLondonConfig
    val postShanghaiHeader = baseHeader.copy(unixTimestamp = ShanghaiTs + 1)
    validate(signedWordCostTx, postShanghaiHeader) match {
      case Left(_: TransactionNotEnoughGasForIntrinsicError) => succeed
      case other => fail(s"Expected TransactionNotEnoughGasForIntrinsicError, got: $other")
    }
  }

  it should "accept CREATE with same gas limit on ETC (EIP-3860 word cost not active pre-Olympia)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // etcMystiqueConfig has Mystique fee schedule active at block 0 but no shanghaiTimestamp,
    // so eip3860Enabled stays false. Intrinsic = 21000+32000+200*16+0 = 56200 ≤ 56213 → accepted.
    implicit val cfg: BlockchainConfig = etcMystiqueConfig
    val etcHeader = baseHeader.copy(number = BigInt(21_000_000), unixTimestamp = ShanghaiTs + 1)
    validate(signedWordCostTx, etcHeader) match {
      case Left(_: TransactionNotEnoughGasForIntrinsicError) =>
        fail("EIP-3860 word cost must not apply on ETC pre-Olympia")
      case _ => succeed
    }
  }
}
