package com.chipprbots.ethereum.vm

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps

import Fixtures.blockchainConfig

/** Tests for EvmConfig.forBlock timestamp-based fork dispatch (ETH/Sepolia path).
  *
  * The 3-arg forBlock overload applies Shanghai/Cancun/Prague/Osaka overrides on top of the block-based config. This
  * spec guards against regressions in that dispatch, including CLZ (0x1e, EIP-7939) which has no other unit test.
  *
  * ETC chain sanity: OlympiaConfigBuilder uses EtcOlympiaOpCodes — no BLOBHASH/BLOBBASEFEE (ETH-only EIPs), CLZ present
  * per ECIP-1121.
  */
class EvmConfigTimestampForkSpec extends AnyWordSpec with Matchers:

  private val ShanghaiTs: Long = 1_000L
  private val CancunTs: Long = 2_000L
  private val PragueTs: Long = 3_000L
  private val OsakaTs: Long = 4_000L

  // Default test config: ETC-specific forks at 1e18, byzantium at 4_370_000.
  // At block 0 this resolves to FrontierConfigBuilder — eip3860Enabled=false, no PUSH0.
  // The 3-arg forBlock then applies timestamp overrides on top.
  private val baseEthConfig = Config.blockchains.blockchainConfig.copy(
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(ShanghaiTs),
      cancunTimestamp = Some(CancunTs),
      pragueTimestamp = Some(PragueTs),
      osakaTimestamp = Some(OsakaTs)
    )
  )

  private def evmAt(ts: Long): EvmConfig = EvmConfig.forBlock(0, Timestamp(ts), baseEthConfig)

  private val configEtcOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  "EvmConfig.forBlock timestamp dispatch" when {

    "pre-Shanghai (ts = 0)" should {
      "have eip3860Enabled = false" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(0L).eip3860Enabled shouldBe false
      }
      "not include PUSH0 (0x5F)" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(0L).byteToOpCode.get(0x5f.toByte) shouldBe None
      }
    }

    "at Shanghai (ts = ShanghaiTs)" should {
      "have eip3860Enabled = true" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(ShanghaiTs).eip3860Enabled shouldBe true
      }
      "include PUSH0 (0x5F) per EIP-3855" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(ShanghaiTs).byteToOpCode.get(0x5f.toByte) shouldBe Some(PUSH0)
      }
    }

    "at Cancun (ts = CancunTs)" should {
      "include BLOBHASH (0x49) per EIP-4844" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(CancunTs).byteToOpCode.get(0x49.toByte) shouldBe Some(BLOBHASH)
      }
      "include BLOBBASEFEE (0x4A) per EIP-7516" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(CancunTs).byteToOpCode.get(0x4a.toByte) shouldBe Some(BLOBBASEFEE)
      }
      "have eip6780Enabled = true per EIP-6780" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(CancunTs).eip6780Enabled shouldBe true
      }
    }

    "at Prague (ts = PragueTs)" should {
      "use PragueFeeSchedule for EIP-7623 calldata floor" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(PragueTs).feeSchedule shouldBe a[FeeSchedule.PragueFeeSchedule]
      }
    }

    "at Osaka (ts = OsakaTs)" should {
      "include CLZ (0x1E) per EIP-7939" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(OsakaTs).byteToOpCode.get(0x1e.toByte) shouldBe Some(CLZ)
      }
      "use OsakaFeeSchedule" taggedAs (UnitTest, ConsensusTest) in {
        evmAt(OsakaTs).feeSchedule shouldBe a[FeeSchedule.OsakaFeeSchedule]
      }
    }
  }

  "ETC Olympia (block-based, OlympiaConfigBuilder)" when {

    "using ETC blockchainConfig at Olympia" should {
      "not include BLOBHASH (0x49) — EIP-4844 is ETH-only" taggedAs (UnitTest, ConsensusTest) in {
        configEtcOlympia.byteToOpCode.get(0x49.toByte) shouldBe None
      }
      "not include BLOBBASEFEE (0x4A) — EIP-7516 is ETH-only" taggedAs (UnitTest, ConsensusTest) in {
        configEtcOlympia.byteToOpCode.get(0x4a.toByte) shouldBe None
      }
      "include CLZ (0x1E) per ECIP-1121" taggedAs (UnitTest, ConsensusTest) in {
        configEtcOlympia.byteToOpCode.get(0x1e.toByte) shouldBe Some(CLZ)
      }
    }
  }
