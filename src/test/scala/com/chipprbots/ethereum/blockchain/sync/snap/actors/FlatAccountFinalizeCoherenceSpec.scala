package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.{AccountTask, SNAPRequestTracker, SNAPSyncController}
import com.chipprbots.ethereum.blockchain.sync.snap.fixtures.FlatAccountMerkleizeFixtures
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{FlatAccountStorage, Namespaces}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

import java.io.File
import java.nio.file.Files

import cats.effect.unsafe.IORuntime

/** Spec 008 US3 (T018) — THE COHERENCE PROOF.
  *
  * This is the crux of the whole feature: it proves that the finalize re-fetch makes the retained flat-account leaves
  * coherent for ONE final pivot, so the local-merkleize gate (`computeLocalStateRoot ==
  * pivotHeader(R_final).stateRoot`) flips from FAIL (stale mosaic) to PASS (single coherent state).
  *
  * Scenario modeled:
  *   - The flat-account store starts with a STALE leaf set: every account is the final-pivot leaf EXCEPT one
  *     (`perturbedKey`), whose `storageRoot` reflects an earlier pivot. This is exactly the cross-pivot mosaic the
  *     status-quo (PivotRefreshed re-tags only pending tasks; completed ranges are never re-fetched) produces.
  *   - Pre-state: `computeLocalStateRoot` over the stale set yields `perturbedStateRoot`, which does NOT equal the
  *     final pivot's canonical `stateRoot` ⇒ the gate FAILS (the node would fail closed — safe but not complete).
  *   - The re-fetch mechanism re-downloads the (re-armed) range against `R_final` and the FINAL-pivot leaves flow back
  *     through `handleStoreAccountChunk`, which OVERWRITES the stale leaf in `FlatAccountStorage` (C1).
  *   - Post-state: the previously-stale leaf is the FINAL value, and `computeLocalStateRoot` now equals the canonical
  *     `stateRoot` (and no longer equals the stale root) ⇒ the gate PASSES and SNAP completes.
  *
  * The re-fetch is exercised at the MECHANISM level (direct `StoreAccountChunk` injection of the final-pivot leaves
  * through a real `AccountRangeCoordinator` wired to a real RocksDB-backed `FlatAccountStorage`, the same path a worker
  * uses after `BeginFinalizing` re-arms the range). A full mock-peer GetAccountRange(R_final) integration over the wire
  * is impractical in the unit harness; that residual coverage — peers actually serving R_final end-to-end, the freeze
  * latch holding across a live pivot advance, and the controller's `FinalizingRefetchComplete → gate → finalize`
  * transition — is deferred to the fresh-Mordor E2E (T023). What this spec proves is the load-bearing invariant T023
  * cannot cheaply isolate: feeding final-pivot leaves through the retention write makes the recomputed root flip from
  * stale to final, byte-for-byte.
  */
class FlatAccountFinalizeCoherenceSpec
    extends TestKit(ActorSystem("FlatAccountFinalizeCoherenceSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  import FlatAccountMerkleizeFixtures._

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private val RFinal: ByteString = canonicalStateRoot

  "the US3 finalize re-fetch" should "make a STALE leaf set coherent for the final pivot: recomputed root flips from stale to final (T018 coherence proof)" taggedAs UnitTest in
    withFlatRocksDb { ds =>
      val flat = new FlatAccountStorage(ds)

      // --- (1) Pre-state: download produced a STALE flat set (one account's leaf reflects an earlier pivot). ---
      flat.putAccountsBatch(perturbedLeavesAscending).commit()

      // The stale leaf for `perturbedKey` is present and is NOT the final-pivot value.
      val staleLeaf = readLeaf(flat, perturbedKey)
      val finalLeaf = canonicalLeavesAscending.toMap.apply(perturbedKey)
      staleLeaf shouldBe defined
      staleLeaf.get should not be finalLeaf

      // The gate FAILS on the stale mosaic: computed root == perturbedStateRoot != R_final.
      val staleRoot = SNAPSyncController.computeLocalStateRoot(flat).toOption.get
      staleRoot shouldBe perturbedStateRoot
      staleRoot should not be RFinal
      (staleRoot == RFinal) shouldBe false // ⇒ fail closed (pre-US3)

      // --- (2) Re-fetch: feed the FINAL-pivot leaf for the re-armed range through the retention write path. ---
      // A real worker, after BeginFinalizing(R_final) re-arms the range, re-downloads GetAccountRange(R_final) and the
      // coordinator's handleStoreAccountChunk writes each (accountHash, accountRLP) into the SAME flatAccountStorage,
      // overwriting the stale leaf. We drive that exact write path with the final-pivot leaves.
      val coord = TestActorRef[AccountRangeCoordinator](
        AccountRangeCoordinator.props(
          stateRoot = RFinal,
          networkPeerManager = TestProbe().ref,
          requestTracker = new SNAPRequestTracker()(system.scheduler),
          mptStorage = new TestMptStorage(),
          concurrency = 1,
          snapSyncController = TestProbe().ref,
          accountTrieEcOverride = Some(system.dispatcher),
          flatAccountStorage = Some(flat),
          flatAccountMerkleize = true
        )
      )

      // Inject the FINAL-pivot leaves for the (single, full-keyspace) re-armed range in ascending order. The StackTrie
      // path requires ascending inserts within a chunk; canonicalAccountsAscending already satisfies that.
      val task = AccountTask(
        next = ByteString(Array.fill[Byte](32)(0x00)),
        last = AccountTask.MaxHash32,
        rootHash = RFinal
      )
      coord ! Messages.StoreAccountChunk(
        task,
        canonicalAccountsAscending,
        canonicalAccountsAscending.size,
        storedSoFar = 0,
        isTaskRangeComplete = false
      )
      coord ! Messages.GetProgress
      expectMsgType[AccountRangeStats](3.seconds) // barrier

      // --- (3) Post-state: the stale leaf is OVERWRITTEN with the final value; the recomputed root is now canonical. ---
      val overwritten = readLeaf(flat, perturbedKey)
      overwritten shouldBe Some(finalLeaf) // C1: overwrite replaces the stale leaf

      val coherentRoot = SNAPSyncController.computeLocalStateRoot(flat).toOption.get
      coherentRoot shouldBe RFinal // == canonical final-pivot stateRoot
      coherentRoot shouldBe canonicalStateRoot
      coherentRoot should not be perturbedStateRoot // no longer the stale root
      (coherentRoot == RFinal) shouldBe true // ⇒ gate PASSES, SNAP completes

      system.stop(coord)
    }

  it should "leave the coherent root byte-identical to the legacy MPT root over the final leaves (A/B parity through the re-fetch, SC-003)" taggedAs UnitTest in
    withFlatRocksDb { ds =>
      val flat = new FlatAccountStorage(ds)
      // Start stale, overwrite via the retention write with the final leaves, then assert parity with the canonical
      // MPT root the fixtures derived through the production MerklePatriciaTrie path.
      flat.putAccountsBatch(perturbedLeavesAscending).commit()
      flat.putAccountsBatch(canonicalLeavesAscending).commit() // models the re-fetch overwriting every range

      val computed = SNAPSyncController.computeLocalStateRoot(flat).toOption.get
      computed shouldBe canonicalStateRoot // StackTrie(final leaves) == MPT(final leaves), byte-for-byte
    }

  // ---- helpers ----

  private def readLeaf(flat: FlatAccountStorage, key: ByteString): Option[ByteString] = {
    implicit val rt: IORuntime = IORuntime.global
    flat
      .seekFrom(ByteString(Array.fill(32)(0x00.toByte)))
      .compile
      .toVector
      .unsafeRunSync()
      .map {
        case Right(pair)               => pair
        case Left(err: IterationError) => fail(s"seek iteration error: $err")
      }
      .collectFirst { case (k, v) if k == key => v }
  }

  private def withFlatRocksDb(test: RocksDbDataSource => Unit): Unit = {
    val dbPath = Files.createTempDirectory("flat-account-coherence-rocksdb").toAbsolutePath.toString
    val ds = RocksDbDataSource(
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
    try test(ds)
    finally {
      ds.destroy()
      val dir = new File(dbPath)
      val _ = !dir.exists() || dir.delete()
    }
  }
}
