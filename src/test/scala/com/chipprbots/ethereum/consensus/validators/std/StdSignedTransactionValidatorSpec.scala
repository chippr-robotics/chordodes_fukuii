package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionInitCodeSizeError
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
}
