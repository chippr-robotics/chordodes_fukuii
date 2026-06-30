package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Unit coverage for [[BlockHeader.validateFieldCount]].
  *
  * Verifies that a decoded header whose ExtraFields shape is inconsistent with the fork timestamps active at its
  * timestamp is rejected early — before the full [[com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator]]
  * runs. ETC chains are unaffected (no timestamp forks).
  */
// scalastyle:off magic.number
class BlockHeaderFieldCountSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider:

  private val ShanghaiTs: Long = 1_000L
  private val CancunTs: Long = 2_000L
  private val PreShanghaiTs: Long = 500L
  private val PostCancunTs: Long = 3_000L

  private val withdrawalsRoot: ByteString = Fixtures.Blocks.ValidBlock.header.stateRoot.value
  private val beaconRoot: ByteString = Fixtures.Blocks.ValidBlock.header.parentHash.value

  private val ethConfig: BlockchainConfig = blockchainConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(ShanghaiTs),
      cancunTimestamp = Some(CancunTs)
    )
  )

  private val etcConfig: BlockchainConfig = blockchainConfig.copy(networkType = NetworkType.ETC)

  private def baseHeader(ts: Long): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(unixTimestamp = Timestamp(ts))

  "BlockHeader.validateFieldCount" when {

    "chain is ETC (networkType = ETC)" should {
      "accept any field shape regardless of timestamp" taggedAs (UnitTest, ConsensusTest) in {
        // HefEmpty shape at a timestamp that would be Cancun-era on ETH — ETC has no timestamp forks.
        val header = baseHeader(PostCancunTs).copy(extraFields = HefEmpty)
        BlockHeader.validateFieldCount(header, etcConfig) shouldBe Right(())
      }
    }

    "chain is ETH and timestamp is pre-Shanghai" should {
      "accept HefEmpty (15-item RLP shape)" taggedAs (UnitTest, ConsensusTest) in {
        val header = baseHeader(PreShanghaiTs).copy(extraFields = HefEmpty)
        BlockHeader.validateFieldCount(header, ethConfig) shouldBe Right(())
      }
    }

    "chain is ETH and Shanghai is active but withdrawalsRoot is absent" should {
      "return Left with a descriptive message" taggedAs (UnitTest, ConsensusTest) in {
        // HefEmpty carries no withdrawalsRoot — decoded from 15-item RLP at a Shanghai-active timestamp.
        val header = baseHeader(ShanghaiTs).copy(extraFields = HefEmpty)
        val result = BlockHeader.validateFieldCount(header, ethConfig)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include("withdrawalsRoot")
      }
    }

    "chain is ETH and Cancun is active but blobGasUsed is absent (17-item RLP shape)" should {
      "return Left with a descriptive message" taggedAs (UnitTest, ConsensusTest) in {
        // HefPostShanghai carries withdrawalsRoot but no blobGasUsed — decoded from 17-item RLP
        // at a Cancun-active timestamp. This is the §ETH-T9-B motivating case.
        val header = baseHeader(PostCancunTs).copy(
          extraFields = HefPostShanghai(baseFee = BigInt(1), withdrawalsRoot = withdrawalsRoot)
        )
        val result = BlockHeader.validateFieldCount(header, ethConfig)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include("blobGasUsed")
      }
    }

    "chain is ETH and Cancun is active with all blob fields present" should {
      "accept HefPostCancun (20-item RLP shape)" taggedAs (UnitTest, ConsensusTest) in {
        val header = baseHeader(PostCancunTs).copy(
          extraFields = HefPostCancun(
            baseFee = BigInt(1),
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = BigInt(0),
            excessBlobGas = BigInt(0),
            parentBeaconBlockRoot = beaconRoot
          )
        )
        BlockHeader.validateFieldCount(header, ethConfig) shouldBe Right(())
      }
    }

    "chain is ETH and timestamp is exactly at CancunTs boundary" should {
      "apply Cancun rules (boundary is inclusive)" taggedAs (UnitTest, ConsensusTest) in {
        val shanghaiShape = baseHeader(CancunTs).copy(
          extraFields = HefPostShanghai(baseFee = BigInt(1), withdrawalsRoot = withdrawalsRoot)
        )
        BlockHeader.validateFieldCount(shanghaiShape, ethConfig) shouldBe a[Left[?, ?]]
      }
    }
  }
// scalastyle:on magic.number
