package com.chipprbots.ethereum.consensus.difficulty

import com.chipprbots.ethereum.consensus.pow.difficulty.EthashDifficultyCalculator
import com.chipprbots.ethereum.consensus.pow.difficulty.TargetTimeDifficultyCalculator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.BlockchainConfig

trait DifficultyCalculator:
  def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parent: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Difficulty

object DifficultyCalculator extends DifficultyCalculator:

  def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parent: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Difficulty =
    (blockchainConfig.powTargetTime match
      case Some(targetTime) => new TargetTimeDifficultyCalculator(targetTime)
      case None             => EthashDifficultyCalculator
    ).calculateDifficulty(blockNumber, blockTimestamp, parent)

  val DifficultyBoundDivision: Int = 2048
  val FrontierTimestampDiffLimit: Int = -99
  val MinimumDifficulty: Difficulty = Difficulty(131072)
