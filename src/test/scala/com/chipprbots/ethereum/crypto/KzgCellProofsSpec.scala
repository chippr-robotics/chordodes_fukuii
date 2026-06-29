package com.chipprbots.ethereum.crypto

import ethereum.ckzg4844.CKZG4844JNI
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Tests for EIP-7594 / PeerDAS KzgCellProofs wrapper.
  *
  * Loads the KZG trusted setup (same resource as KzgPointEvaluationSpec) and verifies that computeCellsAndKzgProofs
  * returns CELLS_PER_EXT_BLOB (128) cells and proofs per blob, each of the expected byte length.
  */
class KzgCellProofsSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers:

  // Load once per JVM via the shared fixture; never free here. Freeing the process-global native setup
  // in afterAll races with KzgPointEvaluationSpec when suites run concurrently (parallelExecution = true).
  override def beforeAll(): Unit = KzgTestSetup.ensureLoaded()

  // A zero-filled blob: all 4096 field elements are 0, which is a valid BLS12-381 field element.
  private lazy val zeroBlobBytes: Array[Byte] = Array.fill[Byte](CKZG4844JNI.BYTES_PER_BLOB)(0)

  test("computeCellsAndKzgProofs returns CELLS_PER_EXT_BLOB proofs per blob", SlowTest, CryptoTest) {
    val (cells, proofs) = KzgCellProofs.computeCellsAndKzgProofs(zeroBlobBytes)
    cells.length shouldBe KzgCellProofs.CELLS_PER_EXT_BLOB
    proofs.length shouldBe KzgCellProofs.CELLS_PER_EXT_BLOB
  }

  test("computeCellsAndKzgProofs: each proof is BYTES_PER_PROOF (48) bytes", SlowTest, CryptoTest) {
    val (_, proofs) = KzgCellProofs.computeCellsAndKzgProofs(zeroBlobBytes)
    proofs.foreach { proof =>
      proof.length shouldBe KzgCellProofs.BYTES_PER_PROOF
    }
  }

  test("computeCellsAndKzgProofs: each cell is BYTES_PER_CELL (2048) bytes", SlowTest, CryptoTest) {
    val (cells, _) = KzgCellProofs.computeCellsAndKzgProofs(zeroBlobBytes)
    cells.foreach { cell =>
      cell.length shouldBe KzgCellProofs.BYTES_PER_CELL
    }
  }

  test("CELLS_PER_EXT_BLOB constant is 128", UnitTest, CryptoTest) {
    KzgCellProofs.CELLS_PER_EXT_BLOB shouldBe 128
  }
