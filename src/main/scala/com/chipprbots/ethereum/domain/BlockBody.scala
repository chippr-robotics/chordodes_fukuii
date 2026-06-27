package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.domain.Withdrawal.*
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.rlp.rawDecode

case class BlockBody(
    transactionList: Seq[SignedTransaction],
    uncleNodesList: Seq[BlockHeader],
    withdrawals: Option[Seq[Withdrawal]] = None
):
  override def toString: String =
    s"BlockBody{ transactionList: $transactionList, uncleNodesList: $uncleNodesList, withdrawals: $withdrawals }"

  def toShortString: String =
    s"BlockBody { transactionsList: ${transactionList.map(_.hash.toHex)}, uncleNodesList: ${uncleNodesList.map(_.hashAsHexString)}, withdrawals: ${withdrawals.map(_.size)} }"

  lazy val numberOfTxs: Int = transactionList.size

  lazy val numberOfUncles: Int = uncleNodesList.size

object BlockBody:

  val empty: BlockBody = BlockBody(Seq.empty, Seq.empty)

  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction.*

  def blockBodyToRlpEncodable(
      blockBody: BlockBody,
      signedTxToRlpEncodable: SignedTransaction => RLPEncodeable,
      blockHeaderToRlpEncodable: BlockHeader => RLPEncodeable
  ): RLPEncodeable =
    // EIP-2718: typed transactions in a block body must be encoded as RLP byte strings
    // (RLPValue(typeByte || rlp(payload))), not as a raw concatenation. PrefixedRLPEncodable
    // alone serializes as `prefix || rlp(payload)` without the byte-string length prefix,
    // which breaks cross-client decoding (e.g. go-ethereum re-requests bodies indefinitely).
    val txItems: Seq[RLPEncodeable] = blockBody.transactionList.map { stx =>
      signedTxToRlpEncodable(stx) match
        case p: PrefixedRLPEncodable => RLPValue(encode(p))
        case other                   => other
    }
    val baseParts: Seq[RLPEncodeable] = Seq(
      RLPList(txItems*),
      RLPList(blockBody.uncleNodesList.map(blockHeaderToRlpEncodable)*)
    )
    val withdrawalsPart: Seq[RLPEncodeable] = blockBody.withdrawals match
      case Some(ws) => Seq(RLPList(ws.map(w => WithdrawalEnc(w).toRLPEncodable)*))
      case None     => Seq.empty
    RLPList((baseParts ++ withdrawalsPart)*)

  implicit class BlockBodyEnc(msg: BlockBody) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*

      blockBodyToRlpEncodable(
        msg,
        stx => SignedTransactionEnc(stx).toRLPEncodable,
        header => BlockHeaderEnc(header).toRLPEncodable
      )

  implicit class BlockBlodyDec(val bytes: Array[Byte]) extends AnyVal:
    def toBlockBody: BlockBody = BlockBodyRLPEncodableDec(rawDecode(bytes)).toBlockBody

  def rlpEncodableToBlockBody(
      rlpEncodeable: RLPEncodeable,
      rlpEncodableToSignedTransaction: RLPEncodeable => SignedTransaction,
      rlpEncodableToBlockHeader: RLPEncodeable => BlockHeader
  ): BlockBody =
    rlpEncodeable match
      case rlpList: RLPList if rlpList.items.length >= 2 =>
        val transactions = rlpList.items(0) match
          case rl: RLPList => rl
          case _ => throw new RuntimeException("Cannot decode BlockBody: expected RLPList at index 0 (transactions)")
        val uncles = rlpList.items(1) match
          case rl: RLPList => rl
          case _ => throw new RuntimeException("Cannot decode BlockBody: expected RLPList at index 1 (uncles)")
        val withdrawals =
          if rlpList.items.length >= 3 then
            rlpList.items(2) match
              case rl: RLPList => Some(rl.items.map(_.toWithdrawal))
              case _ => throw new RuntimeException("Cannot decode BlockBody: expected RLPList at index 2 (withdrawals)")
          else None
        BlockBody(
          transactions.items.toTypedRLPEncodables.map(rlpEncodableToSignedTransaction),
          uncles.items.map(rlpEncodableToBlockHeader),
          withdrawals
        )
      case _ => throw new RuntimeException("Cannot decode BlockBody")

  implicit class BlockBodyRLPEncodableDec(val rlpEncodeable: RLPEncodeable):
    def toBlockBody: BlockBody =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*

      rlpEncodableToBlockBody(
        rlpEncodeable,
        rlp => rlp.toSignedTransaction,
        rlp => BlockHeaderDec(rlp).toBlockHeader
      )
