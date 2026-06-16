package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized

/** Tracks which trie nodes have been enqueued during a BFS heal walk, preventing re-enqueue of already-visited child
  * references.
  *
  * Two implementations: [[RocksDbHealingVisitedStorage]] (production, CF-backed, no heap cap) and
  * [[InMemoryHealingVisitedStorage]] (tests / fallback). The disk-backed implementation eliminates the ~480–640 MB heap
  * pressure of the prior `LinkedHashMap`-backed bounded set (OPT-059).
  */
trait HealingVisitedStorage {

  /** Returns true if `key` was newly recorded (not seen before), and records it. Not guaranteed atomic under concurrent
    * access: two threads may both see the same key as absent and both return true. This causes a duplicate BFS-queue
    * entry, which is benign — equivalent to the re-walk the previous FIFO-eviction design permitted.
    */
  def markIfNew(key: ByteString): Boolean

  /** Delete all stored entries. Called at the start of each BFS walk to ensure a clean slate. */
  def clear(): Unit

  /** Returns the pivot root hash stored from the last BFS walk, if any. Used to detect whether the visited set is still
    * valid for the current walk (same pivot root → skip clear, resume). Default: None (always treat as fresh, i.e. full
    * clear on next walk).
    */
  def storedRoot(): Option[ByteString] = None

  /** Clear all visited entries and record `root` as the active pivot root for this walk. Called when a BFS walk starts
    * on a DIFFERENT root than the last walk (or no prior root exists). Default: delegates to clear() — correct for
    * in-memory and test implementations.
    */
  @annotation.nowarn("id=E198")
  def clearAndSetRoot(_root: ByteString): Unit = clear()
}

object RocksDbHealingVisitedStorage {

  /** 21-byte sentinel key stored in HealingVisitedNamespace to record the active BFS pivot root. 21 bytes sorts BEFORE
    * all 32-byte keccak hash keys in RocksDB lexicographic order, so it lies outside the deleteRange end key ([FF×32 +
    * 0x00]) — explicit toRemove delete is required in both clear() and clearAndSetRoot().
    */
  val RootMarkerKey: Array[Byte] = "__visited_root__".getBytes("UTF-8")
}

/** RocksDB column-family-backed implementation. Keys are raw 32-byte keccak hashes; values are empty. No cap — disk is
  * unbounded, so every visited node is recorded for the full walk lifetime. RocksDB point-get latency at warm
  * block-cache: ~1–5 µs.
  *
  * Writes are buffered: [[markIfNew]] accumulates keys in a [[java.util.concurrent.ConcurrentLinkedQueue]] and flushes
  * every [[FlushThreshold]] keys as a single RocksDB WriteBatch. This reduces 77M individual commits (one per L7 key)
  * to ~7,700, eliminating the L0 SST accumulation that would otherwise trigger `level0_slowdown_writes_trigger`.
  */
class RocksDbHealingVisitedStorage(dataSource: DataSource, namespace: Namespace) extends HealingVisitedStorage {

  private val FlushThreshold = 10_000
  private val pendingAdds = new java.util.concurrent.ConcurrentLinkedQueue[Array[Byte]]()
  // AtomicInteger alongside the queue: ConcurrentLinkedQueue.size() is O(n),
  // which accumulates significant cost at 77M L7 keys. O(1) threshold check.
  private val pendingSize = new java.util.concurrent.atomic.AtomicInteger(0)

  private def has(key: ByteString): Boolean =
    dataSource.getOptimized(namespace, key.toArray).isDefined

  // Drains the entire pending queue into a single RocksDB WriteBatch.
  // Two threads may both call this concurrently when both see size >= threshold;
  // poll() is lock-free and per-element atomic so each key ends up in exactly one
  // thread's batch. No elements are lost or duplicated.
  private def flushPending(): Unit = {
    val batch = new java.util.ArrayList[Array[Byte]]()
    var k = pendingAdds.poll()
    while (k != null) { batch.add(k); k = pendingAdds.poll() }
    pendingSize.set(0)
    if (!batch.isEmpty) {
      import scala.jdk.CollectionConverters._
      dataSource.update(
        Seq(
          DataSourceUpdateOptimized(
            namespace,
            toRemove = Nil,
            toUpsert = batch.asScala.toSeq.map(_ -> Array.emptyByteArray)
          )
        )
      )
    }
  }

  override def markIfNew(key: ByteString): Boolean =
    if (has(key)) false
    else {
      pendingAdds.add(key.toArray)
      if (pendingSize.incrementAndGet() >= FlushThreshold) flushPending()
      true
    }

  /** Returns the pivot root hash stored from the last BFS walk, if any. */
  override def storedRoot(): Option[ByteString] =
    dataSource.getOptimized(namespace, RocksDbHealingVisitedStorage.RootMarkerKey).map(ByteString(_))

  /** Clear all 32-byte hash keys and write the new pivot root marker atomically. deleteRange first (canonical RocksDB
    * state), then explicit root marker update. The root marker (21-byte key) lies outside the hash range tombstone — it
    * requires an explicit toRemove then re-upsert in the same update call.
    */
  override def clearAndSetRoot(root: ByteString): Unit = {
    dataSource.deleteRange(
      namespace,
      Array.fill(32)(0x00.toByte),
      Array.fill(32)(0xff.toByte) :+ 0x00.toByte
    )
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace,
          toRemove = Seq(RocksDbHealingVisitedStorage.RootMarkerKey),
          toUpsert = Seq(RocksDbHealingVisitedStorage.RootMarkerKey -> root.toArray)
        )
      )
    )
    pendingAdds.clear()
    pendingSize.set(0)
  }

  override def clear(): Unit = {
    // deleteRange first: makes RocksDB the canonical authority for the cleared range.
    // Draining pendingAdds after prevents any buffered key from surviving as a
    // post-tombstone write in a subsequent flushPending() call.
    // Single range tombstone covering all possible 32-byte keccak hash keys:
    // the 33-byte end key sorts strictly after the maximum 32-byte key.
    dataSource.deleteRange(
      namespace,
      Array.fill(32)(0x00.toByte),
      Array.fill(32)(0xff.toByte) :+ 0x00.toByte
    )
    // Root marker (21-byte key) lies outside the hash range — explicit delete required.
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace,
          toRemove = Seq(RocksDbHealingVisitedStorage.RootMarkerKey),
          toUpsert = Nil
        )
      )
    )
    pendingAdds.clear()
    pendingSize.set(0)
  }
}

/** In-memory implementation for unit tests. `markIfNew` is truly atomic via `ConcurrentHashMap.putIfAbsent`.
  */
class InMemoryHealingVisitedStorage extends HealingVisitedStorage {
  private val map = new java.util.concurrent.ConcurrentHashMap[ByteString, java.lang.Boolean]()

  override def markIfNew(key: ByteString): Boolean =
    map.putIfAbsent(key, java.lang.Boolean.TRUE) eq null

  override def clear(): Unit = map.clear()

  // No persistent root concept in-memory — always treat as fresh on restart.
  override def storedRoot(): Option[ByteString] = None
  override def clearAndSetRoot(root: ByteString): Unit = map.clear()
}
