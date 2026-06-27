package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.file.Files

import scala.collection.immutable.ArraySeq

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource.Key
import com.chipprbots.ethereum.db.dataSource.DataSource.Value
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** spec 002 US2 (T016): RocksDB block-cache statistics wiring on [[RocksDbDataSource]].
  *
  *   - With `enable-statistics = true`, `cacheStats` is `Some` and the block-cache tickers advance once reads are
  *     served from SST files through the block cache.
  *   - With the flag off (the default), `cacheStats` is `None`.
  *   - `close()` releases the Statistics handle without error.
  *
  * To exercise the block cache deterministically (rather than memtable reads, which bypass it), the test writes data
  * with one datasource, closes it — which flushes the memtable to SST on shutdown — then reopens a fresh datasource
  * over the same path so every read is served from SST through the block cache.
  */
class RocksDbDataSourceSpec extends AnyFlatSpec with Matchers:

  private val Ns: DataSource.Namespace = Namespaces.NodeNamespace

  private def config(dbPath: String, statisticsEnabled: Boolean): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
      override val enableStatistics: Boolean = statisticsEnabled

  private def key(i: Int): Key = ArraySeq.unsafeWrapArray(Array.fill(32)(i.toByte))
  // 256-byte values so that 2000 entries (~512KB) exceed the 16KB block size and span many data blocks.
  private def value(i: Int): Value = ArraySeq.unsafeWrapArray(Array.fill(256)((i % 251).toByte))

  private val N = 2000

  private def cacheActivity(stats: (Long, Long, Long, Long)): Long =
    val (hit, miss, idxHit, idxMiss) = stats
    hit + miss + idxHit + idxMiss

  private def withTempDir(test: String => Unit): Unit =
    val dbPath = Files.createTempDirectory("rocksdb-stats-test").toAbsolutePath.toString
    try test(dbPath)
    finally
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()

  "RocksDbDataSource with statistics enabled" should "advance the block-cache tickers on SST-served reads" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    // 1) Write the dataset, then close — closing flushes the memtable to SST files.
    val writer = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    writer.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
    writer.close()

    // 2) Reopen fresh: every read is now served from SST through the block cache.
    val reader = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    try
      reader.cacheStats should not be empty
      val before = reader.cacheStats.get

      (0 until N).foreach(i => reader.get(Ns, key(i)) should not be empty) // cold: misses
      (0 until N).foreach(i => reader.get(Ns, key(i))) // warm: hits on cached blocks

      val after = reader.cacheStats.get
      val (hit, miss, _, _) = after
      // Total ticker activity strictly increased, and BOTH a hit and a miss were observed.
      cacheActivity(after) should be > cacheActivity(before)
      miss should be > 0L
      hit should be > 0L
    finally reader.destroy()
  }

  "RocksDbDataSource with statistics disabled (default)" should "report cacheStats == None" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
    try
      ds.cacheStats shouldBe None
      ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(1) -> value(1)))))
      ds.get(Ns, key(1)) should not be empty
      ds.cacheStats shouldBe None
    finally ds.destroy()
  }

  it should "close() cleanly with statistics enabled (handle released, no error)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    ds.cacheStats should not be empty
    noException should be thrownBy ds.close()
    // After close the statistics handle is released; cacheStats reads None rather than touching a freed handle.
    ds.cacheStats shouldBe None
  }
