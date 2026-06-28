package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.*
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Post-merge block header validator. Skips PoW (Ethash) validation entirely. Enforces: difficulty=0, nonce=0, empty
  * ommers. Validates withdrawalsRoot (Shanghai+) and blob gas fields (Cancun+).
  */
object PoSBlockHeaderValidator extends BlockHeaderValidatorSkeleton:

  private val EmptyNonce: ByteString = ByteString(Array.fill[Byte](8)(0))

  override protected def validateEvenMore(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    for
      _ <- validatePoSDifficulty(blockHeader)
      _ <- validatePoSNonce(blockHeader)
      _ <- validatePoSOmmers(blockHeader)
      _ <- validateWithdrawalsRoot(blockHeader)
      _ <- validateBlobGasFields(blockHeader)
    yield BlockHeaderValid

  private def validatePoSDifficulty(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.difficulty == Difficulty.Zero then Right(BlockHeaderValid)
    else Left(HeaderDifficultyError)

  private def validatePoSNonce(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.nonce == EmptyNonce then Right(BlockHeaderValid)
    else Left(PoSNonceError(blockHeader.nonce))

  private def validatePoSOmmers(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.ommersHash.value == BlockHeader.EmptyOmmers then Right(BlockHeaderValid)
    else Left(PoSOmmersError)

  private def validateWithdrawalsRoot(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    val isShanghaiActive = blockchainConfig.isShanghaiTimestamp(blockHeader.unixTimestamp)
    if isShanghaiActive then
      blockHeader.withdrawalsRoot match
        case Some(_) => Right(BlockHeaderValid)
        case None    => Left(MissingWithdrawalsRootError)
    else Right(BlockHeaderValid)

  private def validateBlobGasFields(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    val isCancunActive = blockchainConfig.isCancunTimestamp(blockHeader.unixTimestamp)
    if isCancunActive then
      (blockHeader.blobGasUsed, blockHeader.excessBlobGas, blockHeader.parentBeaconBlockRoot) match
        case (Some(_), Some(_), Some(_)) => Right(BlockHeaderValid)
        case _                           => Left(MissingBlobGasFieldsError)
    else Right(BlockHeaderValid)
