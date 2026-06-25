package com.chipprbots.ethereum.crypto

import ethereum.ckzg4844.CKZG4844JNI

/** EIP-7594 / PeerDAS KZG cell-proof operations.
  *
  * Wraps CKZG4844JNI.computeCellsAndKzgProofs, splitting the JNI's flat byte arrays into per-cell slices. Requires the
  * KZG trusted setup to be loaded before any call.
  *
  * Constants mirror the c-kzg-4844 spec: CELLS_PER_EXT_BLOB=128 cells per blob, each cell 2048 bytes, each proof a
  * 48-byte G1 point.
  */
object KzgCellProofs:
  val CELLS_PER_EXT_BLOB: Int = CKZG4844JNI.CELLS_PER_EXT_BLOB // 128
  val BYTES_PER_CELL: Int = CKZG4844JNI.BYTES_PER_CELL // 2048
  val BYTES_PER_PROOF: Int = CKZG4844JNI.BYTES_PER_PROOF // 48

  /** Compute EIP-7594 cell proofs for a single blob.
    *
    * @param blob
    *   131072-byte blob (4096 BLS field elements).
    * @return
    *   (cells, proofs) — each array has CELLS_PER_EXT_BLOB (128) elements; cells(i) is BYTES_PER_CELL (2048) bytes,
    *   proofs(i) is BYTES_PER_PROOF (48) bytes.
    * @throws ethereum.ckzg4844.CKZGException
    *   if the trusted setup is not loaded or the blob is malformed.
    */
  def computeCellsAndKzgProofs(blob: Array[Byte]): (Array[Array[Byte]], Array[Array[Byte]]) =
    val result = CKZG4844JNI.computeCellsAndKzgProofs(blob)
    val flatCells = result.getCells()
    val flatProofs = result.getProofs()
    val cells = flatCells.grouped(BYTES_PER_CELL).toArray
    val proofs = flatProofs.grouped(BYTES_PER_PROOF).toArray
    (cells, proofs)
