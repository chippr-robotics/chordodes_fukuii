package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.fixtures.FlatAccountMerkleizeFixtures
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{FlatAccountStorage, Namespaces}
import com.chipprbots.ethereum.testing.Tags._

import java.io.File
import java.nio.file.Files

/** Spec 008 US1 (T011 + T012) — consensus-critical parity tests for the finalize-time LOCAL state-root merkleization
  * (`SNAPSyncController.computeLocalStateRoot`).
  *
  * These exercise the C3 contract directly as a pure function over a REAL RocksDB-backed `FlatAccountStorage` (the same
  * harness `FlatAccountStorageSpec` uses — `EphemDataSource` cannot serve `seekFrom`). The actor is NOT instantiated:
  * the merkleization core is state-free, so the byte-for-byte parity gate is proven in isolation, deterministically, no
  * `Thread.sleep`.
  *
  * The hard gate (FR-006): the StackTrie single-pass `computedRoot` MUST equal `FlatAccountMerkleizeFixtures`'
  * `canonicalStateRoot`, which the fixtures derived via the SAME production `MerklePatriciaTrie[ByteString, Account]`
  * path. MPT and StackTrie MUST agree on the canonical root for the same leaves — if they don't, that is a real parity
  * bug, not a test to relax.
  */
class FlatAccountMerkleizeSpec extends AnyFlatSpec with Matchers {

  import FlatAccountMerkleizeFixtures._

  // ---- T011(a) — canonical fixture → computedRoot == canonicalStateRoot (byte-for-byte parity) ----

  "computeLocalStateRoot" should "reproduce the canonical MPT state root from the retained leaves (C3 parity gate)" taggedAs UnitTest in
    withFlatAccountStorage { storage =>
      // Write the canonical leaves in DELIBERATELY SHUFFLED order — the ascending guarantee (and thus the
      // canonical root) is the store's seekFrom + the StackTrie's, NOT the input order.
      storage.putAccountsBatch(scala.util.Random.shuffle(canonicalLeavesAscending)).commit()

      val result = SNAPSyncController.computeLocalStateRoot(storage)

      result shouldBe Right(canonicalStateRoot)
    }

  it should "match the canonical root regardless of write order (determinism)" taggedAs UnitTest in
    withFlatAccountStorage { storage =>
      // Reverse order this time — same retained set ⇒ same root.
      storage.putAccountsBatch(canonicalLeavesAscending.reverse).commit()
      SNAPSyncController.computeLocalStateRoot(storage) shouldBe Right(canonicalStateRoot)
    }

  // ---- T011(b) — perturbed fixture → computedRoot != header → fail closed (no finalize) ----

  it should "compute the PERTURBED root (!= canonical) when exactly one leaf is stale (fail-closed input)" taggedAs UnitTest in
    withFlatAccountStorage { storage =>
      storage.putAccountsBatch(perturbedLeavesAscending).commit()

      val result = SNAPSyncController.computeLocalStateRoot(storage)

      // The merkleization itself is honest: it computes the root of WHATEVER leaves are retained.
      result shouldBe Right(perturbedStateRoot)
      // And — the safety property — that root does NOT equal the canonical header root, so the finalize
      // gate (`computedRoot == pivotHeader.stateRoot`) FAILS and the node must fail closed.
      perturbedStateRoot should not be canonicalStateRoot
      result.toOption.get should not be canonicalStateRoot
    }

  it should "model the finalize gate decision: canonical leaves PASS, perturbed leaves FAIL CLOSED against the header" taggedAs UnitTest in {
    // `pivotHeader.stateRoot` at finalize is the canonical root. This asserts the exact boolean the
    // production gate evaluates (`computedRoot == pivotHeader.stateRoot`) for both fixtures, proving the
    // perturbed set is structurally incapable of finalizing.
    val headerStateRoot = canonicalStateRoot

    withFlatAccountStorage { storage =>
      storage.putAccountsBatch(canonicalLeavesAscending).commit()
      val computed = SNAPSyncController.computeLocalStateRoot(storage).toOption.get
      (computed == headerStateRoot) shouldBe true // → finalize proceeds
    }

    withFlatAccountStorage { storage =>
      storage.putAccountsBatch(perturbedLeavesAscending).commit()
      val computed = SNAPSyncController.computeLocalStateRoot(storage).toOption.get
      (computed == headerStateRoot) shouldBe false // → fail closed, do NOT finalize
    }
  }

  // ---- empty-store fail-closed: nothing retained ⇒ Left (never a fabricated/absent root) ----

  it should "fail closed (Left) on an empty flat-account store — never fabricate a root" taggedAs UnitTest in
    withFlatAccountStorage { storage =>
      SNAPSyncController.computeLocalStateRoot(storage) match {
        case Left(msg) => msg should include("empty")
        case Right(r)  => fail(s"empty store must fail closed, got Right($r)")
      }
    }

  // ---- T012 — A/B parity: flag off (legacy MPT) vs on (local merkleize) → IDENTICAL finalized root (SC-003) ----

  it should "produce a root byte-identical to the legacy MPT path on the canonical fixture (A/B parity, SC-003)" taggedAs UnitTest in
    withFlatAccountStorage { storage =>
      storage.putAccountsBatch(canonicalLeavesAscending).commit()

      // Feature ON: the local StackTrie merkleization.
      val featureOnRoot = SNAPSyncController.computeLocalStateRoot(storage).toOption.get

      // Feature OFF (legacy): the canonical root is what the production MerklePatriciaTrie computes over the
      // SAME leaves — that is exactly `canonicalStateRoot` (the fixtures build it via that very path). So
      // "legacy finalized root" == canonicalStateRoot. Enabling the feature must never yield a DIFFERENT
      // finalized root: it either equals this or fails closed.
      val legacyRoot = canonicalStateRoot

      featureOnRoot shouldBe legacyRoot
    }

  // ---- helpers (mirror FlatAccountStorageSpec's proven RocksDB harness) ----

  private def withFlatAccountStorage(test: FlatAccountStorage => Unit): Unit = {
    val dbPath = Files.createTempDirectory("flat-account-merkleize-rocksdb").toAbsolutePath.toString
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
    try test(new FlatAccountStorage(dataSource))
    finally {
      dataSource.destroy()
      val dir = new File(dbPath)
      val _ = !dir.exists() || dir.delete()
    }
  }
}
