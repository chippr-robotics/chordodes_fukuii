package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import boopickle.DefaultBasic.*
import boopickle.Pickler

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.ReceiptStorage.BlockHash
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.ByteUtils.byteSequenceToBuffer
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes

/** This class is used to store the Receipts, by using: Key: hash of the block to which the list of receipts belong
  * Value: the list of receipts
  */
class ReceiptStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[BlockHash, Seq[Receipt]]:

  import ReceiptStorage.{given, *}

  override val namespace: IndexedSeq[Byte] = Namespaces.ReceiptsNamespace

  override def keySerializer: BlockHash => IndexedSeq[Byte] = _.toIndexedSeq

  override def keyDeserializer: IndexedSeq[Byte] => BlockHash = k => ByteString.fromArrayUnsafe(k.toArray)

  override def valueSerializer: ReceiptSeq => IndexedSeq[Byte] = receipts =>
    compactPickledBytes(Pickle.intoBytes(receipts))

  override def valueDeserializer: IndexedSeq[Byte] => ReceiptSeq =
    byteSequenceToBuffer.andThen(Unpickle[Seq[Receipt]].fromBytes)

object ReceiptStorage:
  type BlockHash = ByteString
  type ReceiptSeq = Seq[Receipt]

  given byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  given hashOutcomePickler: Pickler[HashOutcome] = transformPickler[HashOutcome, ByteString] { hash =>
    HashOutcome(hash)
  }(outcome => outcome.stateHash)
  given successOutcomePickler: Pickler[SuccessOutcome.type] = transformPickler[SuccessOutcome.type, ByteString] { _ =>
    SuccessOutcome
  }(_ => ByteString(Array(1.toByte)))
  given failureOutcomePickler: Pickler[FailureOutcome.type] = transformPickler[FailureOutcome.type, ByteString] { _ =>
    FailureOutcome
  }(_ => ByteString(Array(0.toByte)))
  given transactionOutcomePickler: Pickler[TransactionOutcome] = compositePickler[TransactionOutcome]
    .addConcreteType[HashOutcome]
    .addConcreteType[SuccessOutcome.type]
    .addConcreteType[FailureOutcome.type]

  given addressPickler: Pickler[Address] =
    transformPickler[Address, ByteString](bytes => Address(bytes))(address => address.bytes)
  given txLogEntryPickler: Pickler[TxLogEntry] =
    transformPickler[TxLogEntry, (Address, Seq[ByteString], ByteString)] { case (address, topics, data) =>
      TxLogEntry(address, topics, data)
    }(entry => (entry.loggerAddress, entry.logTopics, entry.data))

  given legacyReceiptPickler: Pickler[LegacyReceipt] =
    transformPickler[LegacyReceipt, (TransactionOutcome, BigInt, ByteString, Seq[TxLogEntry])] {
      case (state, gas, filter, logs) => LegacyReceipt(state, gas, BloomFilter(filter), logs)
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter.value, receipt.logs)
    }

  given type01ReceiptPickler: Pickler[Type01Receipt] =
    transformPickler[Type01Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TxLogEntry])] {
      case (state, gas, filter, logs) => Type01Receipt(LegacyReceipt(state, gas, BloomFilter(filter), logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter.value, receipt.logs)
    }

  given type02ReceiptPickler: Pickler[Type02Receipt] =
    transformPickler[Type02Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TxLogEntry])] {
      case (state, gas, filter, logs) => Type02Receipt(LegacyReceipt(state, gas, BloomFilter(filter), logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter.value, receipt.logs)
    }

  given type03ReceiptPickler: Pickler[Type03Receipt] =
    transformPickler[Type03Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TxLogEntry])] {
      case (state, gas, filter, logs) => Type03Receipt(LegacyReceipt(state, gas, BloomFilter(filter), logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter.value, receipt.logs)
    }

  given type04ReceiptPickler: Pickler[Type04Receipt] =
    transformPickler[Type04Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TxLogEntry])] {
      case (state, gas, filter, logs) => Type04Receipt(LegacyReceipt(state, gas, BloomFilter(filter), logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter.value, receipt.logs)
    }

  given receiptPickler: Pickler[Receipt] = compositePickler[Receipt]
    .addConcreteType[LegacyReceipt]
    .addConcreteType[Type01Receipt]
    .addConcreteType[Type02Receipt]
    .addConcreteType[Type03Receipt]
    .addConcreteType[Type04Receipt]
