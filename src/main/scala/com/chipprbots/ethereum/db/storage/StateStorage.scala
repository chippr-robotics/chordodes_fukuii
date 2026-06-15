package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage.FlushSituation
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs._
import com.chipprbots.ethereum.utils.NodeCacheConfig

// scalastyle:off
trait StateStorage {
  def getBackingStorage(bn: BigInt): MptStorage
  def getReadOnlyStorage: MptStorage

  def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit
  def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit

  def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit
  def getNode(nodeHash: NodeHash): Option[MptNode]
  def forcePersist(reason: FlushSituation): Boolean

  /** Flush any accumulated pending pruning deletions to RocksDB. Must be called during graceful shutdown to prevent the
    * final partial batch from being silently discarded. Default no-op for storage implementations that prune eagerly
    * (Archive, Cached).
    */
  def flushPendingPrunes(): Unit = ()

  /** Replay any pruning blocks missed by a prior crash (gap between the persisted watermark and bestBlock -
    * pruningHistory). Called at node startup, before sync resumes. Default no-op for Archive and Cached implementations
    * that prune eagerly.
    */
  def replayMissedPrunes(bestBlock: BigInt): Unit = ()
}

class ArchiveStateStorage(private val nodeStorage: NodeStorage) extends StateStorage {

  override def forcePersist(reason: FlushSituation): Boolean = true

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new ArchiveNodeStorage(nodeStorage)))

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ArchiveNodeStorage(nodeStorage))

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    nodeStorage.put(nodeHash, nodeEncoded)

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    nodeStorage.get(nodeHash).map(_.toMptNode)
}

class ReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: BigInt
) extends StateStorage {

  // Batch pruning deletes across blocks: flush when accumulated byte size exceeds the threshold
  // or every PruneSafetyInterval blocks, whichever comes first. Terminal flush on graceful shutdown
  // via flushPendingPrunes() prevents the last partial batch from being silently discarded.
  private val PruneByteThreshold: Long = 64L * 1024 * 1024 // 64 MB
  private val PruneSafetyInterval: Int = 1000 // blocks
  // Atomic watermark: written in the same WriteBatch as the deletes. On restart, replayMissedPrunes
  // reads this to detect any gap left by a prior crash and replays it before sync resumes.
  private val LastPrunedBlockKey: NodeHash = ByteString("prune-lwm".getBytes("UTF-8"))

  private var pendingDeletes: List[NodeHash] = List.empty
  private var pendingByteSize: Long = 0L
  private var blocksSincePruneFlush: Int = 0
  private var highestPendingBlock: BigInt = BigInt(0)

  override def forcePersist(reason: FlushSituation): Boolean = true

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit = {
    val blockToPrune = bn - pruningHistory
    val deletes = ReferenceCountNodeStorage.collectPruneTargets(blockToPrune, nodeStorage)
    if (deletes.nonEmpty) {
      pendingDeletes = deletes.toList ::: pendingDeletes
      pendingByteSize += deletes.view.map(_.length.toLong).sum
      if (blockToPrune > highestPendingBlock) highestPendingBlock = blockToPrune
    }
    blocksSincePruneFlush += 1
    if (pendingByteSize >= PruneByteThreshold || blocksSincePruneFlush >= PruneSafetyInterval)
      doFlushPendingPrunes()
    updateBestBlocksData()
  }

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit = {
    ReferenceCountNodeStorage.rollback(bn, nodeStorage, inMemory = bn > currentBestSavedBlock)
    updateBestBlocksData()
  }

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn))

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new FastSyncNodeStorage(nodeStorage, 0)))

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    new FastSyncNodeStorage(nodeStorage, bn).update(Nil, Seq(nodeHash -> nodeEncoded))

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    new FastSyncNodeStorage(nodeStorage, 0).get(nodeHash).map(_.toMptNode)

  override def flushPendingPrunes(): Unit = doFlushPendingPrunes()

  /** On restart after crash, replay any pruning blocks missed while pendingDeletes was in-memory only. Chunked into
    * ≤1000-block sub-batches with intermediate watermark flushes to bound memory for months-long gaps (N blocks × M
    * snapshots per block → tens of millions of keys).
    */
  override def replayMissedPrunes(bestBlock: BigInt): Unit = {
    val lastFlushed = nodeStorage
      .get(LastPrunedBlockKey)
      .map(bytes => if (bytes.isEmpty) BigInt(0) else BigInt(bytes.toArray))
      .getOrElse(BigInt(0))
    val gapStart = lastFlushed + 1
    val gapEnd = bestBlock - pruningHistory
    if (gapStart > gapEnd) return

    val ChunkSize = BigInt(1000)
    var chunkStart = gapStart
    while (chunkStart <= gapEnd) {
      val chunkEnd = (chunkStart + ChunkSize - 1).min(gapEnd)
      val chunkDeletes = (chunkStart to chunkEnd)
        .flatMap(bn => ReferenceCountNodeStorage.collectPruneTargets(bn, nodeStorage))
        .toList
      if (chunkDeletes.nonEmpty)
        nodeStorage.updateCond(
          chunkDeletes,
          Seq(LastPrunedBlockKey -> chunkEnd.toByteArray),
          inMemory = false
        )
      chunkStart = chunkEnd + 1
    }
  }

  private def doFlushPendingPrunes(): Unit = {
    if (pendingDeletes.nonEmpty) {
      // Only write the watermark when at least one block had pruning data.
      // Guards against persisting BigInt(0) on threshold-triggered flushes where
      // every block in the interval had no prune targets (highestPendingBlock stays 0).
      val watermarkUpsert =
        if (highestPendingBlock > 0) Seq(LastPrunedBlockKey -> highestPendingBlock.toByteArray)
        else Nil
      // Atomic: deletes + watermark in one WriteBatch — either both commit or neither does.
      nodeStorage.updateCond(pendingDeletes, watermarkUpsert, inMemory = false)
      pendingDeletes = List.empty
      pendingByteSize = 0L
      highestPendingBlock = BigInt(0)
    }
    blocksSincePruneFlush = 0
  }
}

class CachedReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: Int,
    private val lruCache: LruCache[NodeHash, HeapEntry]
) extends StateStorage {

  private val changeLog = new ChangeLog(nodeStorage)

  override def forcePersist(reason: FlushSituation): Boolean =
    reason match {
      case GenesisDataLoad => CachedReferenceCountedStorage.persistCache(lruCache, nodeStorage, forced = true)
    }

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit = {
    val blockToPrune = bn - pruningHistory
    changeLog.persistChangeLog(bn)
    changeLog.getDeathRowFromStorage(blockToPrune).foreach { deathRow =>
      CachedReferenceCountedStorage.prune(deathRow, lruCache, blockToPrune)
    }
    if (CachedReferenceCountedStorage.persistCache(lruCache, nodeStorage)) {
      updateBestBlocksData()
    }
    changeLog.removeBlockMetaData(blockToPrune)
  }

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit = {
    changeLog.getChangeLogFromStorage(bn).foreach { changeLog =>
      CachedReferenceCountedStorage.rollback(lruCache, nodeStorage, changeLog, bn)
    }
    changeLog.removeBlockMetaData(bn)
  }

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new NoHistoryCachedReferenceCountedStorage(nodeStorage, lruCache, 0)))

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new CachedReferenceCountedStorage(nodeStorage, lruCache, changeLog, bn))

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    nodeStorage.put(nodeHash, HeapEntry.toBytes(HeapEntry(nodeEncoded, 1, bn)))

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    lruCache
      .get(nodeHash)
      .map(_.nodeEncoded.toMptNode)
      .orElse(
        nodeStorage
          .get(nodeHash)
          .map(enc => HeapEntry.fromBytes(enc).nodeEncoded.toMptNode)
      )
}

object StateStorage {
  def apply(
      pruningMode: PruningMode,
      nodeStorage: NodeStorage,
      lruCache: LruCache[NodeHash, HeapEntry]
  ): StateStorage =
    pruningMode match {
      case ArchivePruning                   => new ArchiveStateStorage(nodeStorage)
      case pruning.BasicPruning(history)    => new ReferenceCountedStateStorage(nodeStorage, history)
      case pruning.InMemoryPruning(history) => new CachedReferenceCountedStateStorage(nodeStorage, history, lruCache)
    }

  def getReadOnlyStorage(source: EphemDataSource): MptStorage =
    mptStorageFromNodeStorage(new NodeStorage(source))

  def mptStorageFromNodeStorage(storage: NodeStorage): SerializingMptStorage =
    new SerializingMptStorage(new ArchiveNodeStorage(storage))

  def createTestStateStorage(
      source: DataSource,
      pruningMode: PruningMode = ArchivePruning
  ): (StateStorage, NodeStorage, CachedNodeStorage) = {
    val testCacheSize = 10000
    val testCacheConfig = new NodeCacheConfig {
      override val maxSize: Long = 10000
      override val maxHoldTime: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES)
    }
    val nodeStorage = new NodeStorage(source)
    val cachedNodeStorage = new CachedNodeStorage(nodeStorage, MapCache.createTestCache(testCacheSize))

    (
      StateStorage(pruningMode, nodeStorage, new LruCache[NodeHash, HeapEntry](testCacheConfig)),
      nodeStorage,
      cachedNodeStorage
    )
  }

  sealed abstract class FlushSituation
  case object GenesisDataLoad extends FlushSituation

}
