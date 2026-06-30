package com.chipprbots.ethereum.consensus.eip1559

import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

class BaseFeeCalculatorSpec
    extends AnyFlatSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider
    with ParallelTestExecution:

  val olympiaBlock: BigInt = 10

  val config: BlockchainConfig = blockchainConfig
    .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock))
    .copy(baseFeeFloor = BaseFeeCalculator.InitialBaseFee)

  // ETH / Hive config: baseFeeFloor = 0 (Big0). This is the regime where the EIP-1559
  // decrease-branch off-by-one is observable — the prior specs pinned baseFeeFloor to
  // 1 gwei (InitialBaseFee), which clamped every decrease above the wei range where the
  // bug shows, masking it. With floor = 0 the raw 1/8 delta can integer-floor to 0 and
  // must HOLD the base fee, exactly as go-ethereum CalcBaseFee does.
  val configFloorZero: BlockchainConfig = blockchainConfig
    .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock))
    .copy(baseFeeFloor = BigInt(0))

  def makeHeader(
      number: BigInt,
      gasLimit: BigInt,
      gasUsed: BigInt,
      baseFee: Option[BigInt] = None
  ): BlockHeader =
    val extraFields = baseFee match
      case Some(fee) => HefPostOlympia(fee)
      case None      => HefEmpty
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(gasLimit),
      gasUsed = GasAmount(gasUsed),
      extraFields = extraFields
    )

  "BaseFeeCalculator" should "return initial baseFee (1 gwei) when parent is pre-Olympia" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val parent = makeHeader(number = olympiaBlock - 1, gasLimit = 8000000, gasUsed = 4000000)
    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe BaseFeeCalculator.InitialBaseFee
    result shouldBe BigInt(1000000000)
  }

  it should "keep baseFee unchanged when parent gasUsed equals target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val gasTarget = gasLimit / BaseFeeCalculator.ElasticityMultiplier
    val parentBaseFee = BigInt(1000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasTarget,
      baseFee = Some(parentBaseFee)
    )

    BaseFeeCalculator.calcBaseFee(parent, config) shouldBe parentBaseFee
  }

  it should "increase baseFee when parent gasUsed exceeds target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(1000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasLimit,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee + parentBaseFee / 8
    result should be > parentBaseFee
  }

  it should "decrease baseFee when parent gasUsed is below target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    // Start at 2 gwei (above the ECIP-1111 1-gwei floor `config` sets) so the 1/8 decrease to
    // 1.75 gwei is actually observable rather than being clamped to the floor.
    val parentBaseFee = BigInt(2000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = 0,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee - parentBaseFee / 8
    result should be < parentBaseFee
  }

  it should "increase baseFee by at least 1 even with small parentBaseFee" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(7)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasLimit,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee + 1
  }

  it should "never decrease baseFee below InitialBaseFee" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BaseFeeCalculator.InitialBaseFee

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = 0,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result should be >= BaseFeeCalculator.InitialBaseFee
  }

  it should "floor at InitialBaseFee after 1000 consecutive empty blocks" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    var currentBaseFee = BaseFeeCalculator.InitialBaseFee

    (1 to 1000).foreach { i =>
      val parent = makeHeader(
        number = olympiaBlock + i,
        gasLimit = gasLimit,
        gasUsed = 0,
        baseFee = Some(currentBaseFee)
      )
      currentBaseFee = BaseFeeCalculator.calcBaseFee(parent, config)
      withClue(s"block $i: ") {
        currentBaseFee should be >= BaseFeeCalculator.InitialBaseFee
      }
    }
  }

  // ==========================================================================================
  // EIP-1559 decrease-branch off-by-one regression vectors (baseFeeFloor = 0, ETH/Hive regime).
  //
  // go-ethereum CalcBaseFee applies max(delta, 1) ONLY on the increase/above-target branch.
  // On the below-target branch the raw 1/8 delta is used as-is; it may integer-floor to 0,
  // which HOLDS the base fee constant. Only the FINAL result is floored (Big0 for ETH).
  // The historical fukuii bug applied .max(1) on BOTH branches, over-decrementing small base
  // fees by 1 and producing INVALID_BASE_FEE_PER_GAS (have 7, want 6, parentGasUsed 0) — it
  // dominated the run/sync and run/consume-rlp Hive suites. These vectors lock the fix.
  // ==========================================================================================

  // The Hive genesis gasLimit; gasTarget = 37_699_104 / 2 = 18_849_552.
  val hiveGasLimit: BigInt = BigInt(37699104)

  it should "HOLD baseFee (have 7 / want 7) for an empty block under baseFeeFloor=0 (decrease off-by-one regression)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    // gasUsed = 0 < gasTarget => decrease branch. delta = 7 * gasTarget / gasTarget / 8 = 7/8 = 0.
    // Correct result holds at 7. The pre-fix .max(1) wrongly forced delta=1 -> 6.
    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = hiveGasLimit,
      gasUsed = 0,
      baseFee = Some(BigInt(7))
    )
    val result = BaseFeeCalculator.calcBaseFee(parent, configFloorZero)
    withClue("empty-block hold: a zero 1/8 delta must NOT be floored to 1 on the decrease branch: ") {
      result shouldBe BigInt(7)
    }
  }

  it should "still increase baseFee by at least 1 (have 7 / want 8) on a full block under baseFeeFloor=0 (increase min-1 intact)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    // gasUsed = gasLimit > gasTarget => increase branch. delta = (7/8).max(1) = 1 -> 8.
    // Proves the fix leaves the legitimate min-1 floor on the INCREASE branch untouched.
    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = hiveGasLimit,
      gasUsed = hiveGasLimit,
      baseFee = Some(BigInt(7))
    )
    val result = BaseFeeCalculator.calcBaseFee(parent, configFloorZero)
    result shouldBe BigInt(8)
  }

  it should "decrease by exactly parentBaseFee/8 (no min-1 artifact) for a normal-magnitude baseFee under baseFeeFloor=0" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    // 1 gwei base fee, gasUsed = 0 => decrease branch with delta = parentBaseFee/8 = 125_000_000 (>> 1).
    val parentBaseFee = BigInt(1000000000)
    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = hiveGasLimit,
      gasUsed = 0,
      baseFee = Some(parentBaseFee)
    )
    val result = BaseFeeCalculator.calcBaseFee(parent, configFloorZero)
    result shouldBe parentBaseFee - parentBaseFee / 8
    result shouldBe BigInt(875000000)
  }

  it should "suppress the fee market (return InitialBaseFee) when parent is pre-Olympia under baseFeeFloor=0" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    // parent.number = olympiaBlock - 1 => pre-activation: calc returns InitialBaseFee regardless of floor.
    val parent = makeHeader(
      number = olympiaBlock - 1,
      gasLimit = hiveGasLimit,
      gasUsed = 0,
      baseFee = Some(BigInt(7))
    )
    val result = BaseFeeCalculator.calcBaseFee(parent, configFloorZero)
    result shouldBe BaseFeeCalculator.InitialBaseFee
  }
