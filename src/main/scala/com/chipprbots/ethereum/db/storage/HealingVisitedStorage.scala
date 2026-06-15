package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized

/** Tracks which trie nodes have been enqueued during a BFS heal walk, preventing re-enqueue of
  * already-visited child references.
  *
  * Two implementations: [[RocksDbHealingVisitedStorage]] (production, CF-backed, no heap cap) and
  * [[InMemoryHealingVisitedStorage]] (tests / fallback). The disk-backed implementation eliminates
  * the ~480–640 MB heap pressure of the prior `LinkedHashMap`-backed bounded set (OPT-059).
  */
trait HealingVisitedStorage {

  /** Returns true if `key` was newly recorded (not seen before), and records it. Not guaranteed
    * atomic under concurrent access: two threads may both see the same key as absent and both
    * return true. This causes a duplicate BFS-queue entry, which is benign — equivalent to the
    * re-walk the previous FIFO-eviction design permitted.
    */
  def markIfNew(key: ByteString): Boolean

  /** Delete all stored entries. Called at the start of each BFS walk to ensure a clean slate. */
  def clear(): Unit
}

/** RocksDB column-family-backed implementation. Keys are raw 32-byte keccak hashes; values are
  * empty. No cap — disk is unbounded, so every visited node is recorded for the full walk lifetime.
  * RocksDB point-get latency at warm block-cache: ~1–5 µs.
  */
class RocksDbHealingVisitedStorage(dataSource: DataSource, namespace: Namespace)
    extends HealingVisitedStorage {

  private def has(key: ByteString): Boolean =
    dataSource.getOptimized(namespace, key.toArray).isDefined

  private def add(key: ByteString): Unit =
    dataSource.update(
      Seq(DataSourceUpdateOptimized(namespace, toRemove = Nil, toUpsert = Seq((key.toArray, Array.emptyByteArray))))
    )

  override def markIfNew(key: ByteString): Boolean =
    if (has(key)) false else { add(key); true }

  override def clear(): Unit =
    // Single range tombstone covering all possible 32-byte keccak hash keys.
    // A 33-byte end key (32 × 0xFF followed by 0x00) sorts strictly after the maximum 32-byte key
    // in lexicographic order because a string is less than any string of which it is a prefix.
    dataSource.deleteRange(
      namespace,
      Array.fill(32)(0x00.toByte),
      Array.fill(32)(0xFF.toByte) :+ 0x00.toByte
    )
}

/** In-memory implementation for unit tests. `markIfNew` is truly atomic via
  * `ConcurrentHashMap.putIfAbsent`.
  */
class InMemoryHealingVisitedStorage extends HealingVisitedStorage {
  private val map = new java.util.concurrent.ConcurrentHashMap[ByteString, java.lang.Boolean]()

  override def markIfNew(key: ByteString): Boolean =
    map.putIfAbsent(key, java.lang.Boolean.TRUE) eq null

  override def clear(): Unit = map.clear()
}
