package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{EphemDataSource, RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.testing.Tags._

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/** Tests for FlatAccountStorage — O(1) account reads by keccak256(address).
  *
  * Verified against Besu BonsaiFullFlatDbStrategy.getFlatAccount: storage.get(ACCOUNT_INFO_STATE,
  * accountHash.getBytes()) → RLP(account)
  *
  * Spec 008 (T005) adds the C1 retained-account-store contract over a REAL RocksDB instance: writing N accounts then
  * `seekFrom(0x00..00)` returns exactly those N in strictly-ascending 32-byte order, no gaps, no duplicates; a re-put
  * of an existing key overwrites in place. This is the substrate the finalize-time local merkleization (US1) streams in
  * ascending order, so the ordering/overwrite guarantee is consensus-load-bearing. EphemDataSource cannot exercise
  * `seekFrom` (it returns `Stream.empty`), so these tests use a temp-dir RocksDB exactly like
  * `BfsQueueStorageSpec`/`HealingFrontierStorageSpec`.
  */
class FlatAccountStorageSpec extends AnyFlatSpec with Matchers {

  // ----- EphemDataSource tests (get/put/batch/overwrite — no seek) -----

  "FlatAccountStorage" should "store and retrieve an account by hash" taggedAs UnitTest in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xaa.toByte))
    val rlpAccount = ByteString(Array.fill(64)(0x01.toByte))

    storage.put(hash, rlpAccount).commit()
    storage.getAccount(hash) shouldBe Some(rlpAccount)
  }

  it should "return None for missing hash" taggedAs UnitTest in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xbb.toByte))
    storage.getAccount(hash) shouldBe None
  }

  it should "store multiple accounts in batch" taggedAs UnitTest in new TestSetup {
    val accounts = (1 to 5).map { i =>
      ByteString(Array.fill(32)(i.toByte)) -> ByteString(Array.fill(64)(i.toByte))
    }

    storage.putAccountsBatch(accounts).commit()

    accounts.foreach { case (hash, expected) =>
      storage.getAccount(hash) shouldBe Some(expected)
    }
  }

  it should "overwrite existing account on re-put" taggedAs UnitTest in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xcc.toByte))
    val v1 = ByteString(Array.fill(64)(0x01.toByte))
    val v2 = ByteString(Array.fill(64)(0x02.toByte))

    storage.put(hash, v1).commit()
    storage.getAccount(hash) shouldBe Some(v1)

    storage.put(hash, v2).commit()
    storage.getAccount(hash) shouldBe Some(v2)
  }

  it should "return Stream.empty from seekFrom with non-RocksDB backend" taggedAs UnitTest in new TestSetup {
    implicit val runtime: IORuntime = IORuntime.global

    val hash = ByteString(Array.fill(32)(0xaa.toByte))
    val rlpAccount = ByteString(Array.fill(64)(0x01.toByte))
    storage.put(hash, rlpAccount).commit()

    // EphemDataSource is not RocksDB — seekFrom falls through to Stream.empty
    val results = storage.seekFrom(ByteString(Array.fill(32)(0x00.toByte))).compile.toVector.unsafeRunSync()
    results shouldBe empty
  }

  // ----- RocksDB-backed C1 contract: seekFrom ascending full-keyspace enumeration -----

  it should "enumerate exactly N accounts in strictly-ascending order with no gaps or dupes (C1)" taggedAs UnitTest in
    withRocksDb { ds =>
      val storage = new FlatAccountStorage(ds)

      // Insert N accounts in a DELIBERATELY UNSORTED order so the ascending guarantee is the
      // store's, not the input's. Keys are distinct 32-byte big-endian counters.
      val n = 256
      val pairs = (0 until n).map { i =>
        key32(i) -> ByteString(Array.fill(8)((i & 0xff).toByte))
      }
      storage.putAccountsBatch(scala.util.Random.shuffle(pairs)).commit()

      val got = seekAll(storage)

      got.size shouldBe n
      // strictly ascending by 32-byte key, no duplicates
      val keys = got.map(_._1)
      keys shouldBe keys.sortWith((a, b) => compareBytes(a, b) < 0)
      keys.distinct.size shouldBe n
      // exact set parity with what was written (no gaps relative to input)
      keys.toSet shouldBe pairs.map(_._1).toSet
      // values round-trip
      got.toMap shouldBe pairs.toMap
    }

  it should "seek from a midpoint inclusive and return only keys >= startHash (C1)" taggedAs UnitTest in
    withRocksDb { ds =>
      val storage = new FlatAccountStorage(ds)
      val n = 64
      val pairs = (0 until n).map(i => key32(i) -> ByteString(Array.fill(4)(i.toByte)))
      storage.putAccountsBatch(pairs).commit()

      val start = key32(20)
      val got = drain(storage.seekFrom(start)).map(_._1)
      got.foreach(k => compareBytes(k, start) should be >= 0)
      got.head shouldBe start // inclusive
      got.size shouldBe (n - 20)
    }

  it should "overwrite an existing key in place — seek sees one entry with the new value (C1)" taggedAs UnitTest in
    withRocksDb { ds =>
      val storage = new FlatAccountStorage(ds)
      val k = key32(42)
      val v1 = ByteString(Array.fill(16)(0x01.toByte))
      val v2 = ByteString(Array.fill(16)(0x02.toByte))

      storage.putAccountsBatch(Seq(k -> v1)).commit()
      storage.putAccountsBatch(Seq(k -> v2)).commit() // re-put same key (a finalize re-fetch replacing a stale leaf)

      val got = seekAll(storage)
      got.size shouldBe 1 // no duplicate key materialised
      got.head shouldBe (k -> v2)
      storage.getAccount(k) shouldBe Some(v2)
    }

  it should "enumerate keccak-distributed keys in ascending order (realistic key shape, C1)" taggedAs UnitTest in
    withRocksDb { ds =>
      val storage = new FlatAccountStorage(ds)
      // Random 32-byte keys approximate keccak256(address) distribution; the store must still
      // return them strictly ascending regardless of insertion order.
      val rnd = new SecureRandom(Array[Byte](7, 7, 7, 7)) // fixed seed → deterministic
      val keys = (0 until 200).map { _ =>
        val b = new Array[Byte](32); rnd.nextBytes(b); ByteString(b)
      }.distinct
      val pairs = keys.map(k => k -> ByteString(k.take(4).toArray))
      storage.putAccountsBatch(pairs).commit()

      val gotKeys = seekAll(storage).map(_._1)
      gotKeys.size shouldBe keys.size
      gotKeys shouldBe gotKeys.sortWith((a, b) => compareBytes(a, b) < 0)
      gotKeys.toSet shouldBe keys.toSet
    }

  // ----- helpers -----

  /** 32-byte big-endian key for an int (ascending int ⇒ ascending key). */
  private def key32(i: Int): ByteString = {
    val b = new Array[Byte](32)
    b(28) = ((i >>> 24) & 0xff).toByte
    b(29) = ((i >>> 16) & 0xff).toByte
    b(30) = ((i >>> 8) & 0xff).toByte
    b(31) = (i & 0xff).toByte
    ByteString(b)
  }

  private def compareBytes(a: ByteString, b: ByteString): Int =
    java.util.Arrays.compareUnsigned(a.toArray, b.toArray)

  private def drain(
      stream: fs2.Stream[cats.effect.IO, Either[IterationError, (ByteString, ByteString)]]
  ): Vector[(ByteString, ByteString)] = {
    implicit val runtime: IORuntime = IORuntime.global
    stream.compile.toVector.unsafeRunSync().map {
      case Right(pair) => pair
      case Left(err)   => fail(s"seek iteration error: $err")
    }
  }

  private def seekAll(storage: FlatAccountStorage): Vector[(ByteString, ByteString)] =
    drain(storage.seekFrom(ByteString(Array.fill(32)(0x00.toByte))))

  private def withRocksDb(test: RocksDbDataSource => Unit): Unit = {
    val dbPath = Files.createTempDirectory("flat-account-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig {
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      },
      Namespaces.nsSeq
    )
    try test(dataSource)
    finally {
      dataSource.destroy()
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()
    }
  }

  trait TestSetup {
    val dataSource = EphemDataSource()
    val storage = new FlatAccountStorage(dataSource)
  }
}
