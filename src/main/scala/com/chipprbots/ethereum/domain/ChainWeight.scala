package com.chipprbots.ethereum.domain

object ChainWeight:
  def totalDifficultyOnly(td: BigInt): ChainWeight =
    ChainWeight(TotalDifficulty(td))

  val zero: ChainWeight =
    ChainWeight(TotalDifficulty.Zero)

/** Represents the weight of a blockchain chain.
  *
  * ChainWeight is used to compare competing chains and determine the canonical chain based on total difficulty. MESS
  * anti-reorg protection is applied at the fork choice level (in BranchResolution), not per-block.
  *
  * @param totalDifficulty
  *   Sum of all block difficulties in the chain
  */
case class ChainWeight(
    totalDifficulty: TotalDifficulty
) extends Ordered[ChainWeight]:

  override def compare(that: ChainWeight): Int =
    this.totalDifficulty.compare(that.totalDifficulty)

  /** Increase the chain weight by adding a new block header.
    *
    * @param header
    *   The block header to add
    * @return
    *   New ChainWeight with the block incorporated
    */
  def increase(header: BlockHeader): ChainWeight =
    ChainWeight(totalDifficulty + header.difficulty)

  // Test API

  def increaseTotalDifficulty(td: BigInt): ChainWeight =
    copy(totalDifficulty = TotalDifficulty(totalDifficulty.value + td))
