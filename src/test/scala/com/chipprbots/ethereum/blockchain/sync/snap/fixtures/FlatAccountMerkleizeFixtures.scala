package com.chipprbots.ethereum.blockchain.sync.snap.fixtures

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.TestMptStorage

/** Deterministic fixtures for spec 008 (flat-account retention + local state-root merkleization).
  *
  * Provides:
  *   - a small, fixed account set with a KNOWN canonical account-trie state root (computed via the SAME
  *     `MerklePatriciaTrie[ByteString, Account]` path production uses to derive the trie root), consumed by the US1
  *     merkleize-parity test (later batch) to assert `computedRoot == canonical`;
  *   - a perturbed-leaf variant (exactly ONE account's `storageRoot` made stale) whose canonical root differs, consumed
  *     by the US1 fail-closed test.
  *
  * Batch 1 (US2) does not yet read these; they are minimal and live here so the US1/US3 batches can consume them
  * without re-deriving the root. Determinism: all keys/values are constant literals (no RNG, no clock), so the
  * canonical roots are reproducible across runs and machines.
  */
object FlatAccountMerkleizeFixtures {

  /** Account-leaf key: keccak256 of a fixed seed → 32-byte key in the keccak distribution, exactly the SNAP
    * account-hash key shape. Deterministic per seed.
    */
  def accountKey(seed: String): ByteString = kec256(ByteString(seed))

  /** A non-empty per-account storage root standing in for a contract. Deterministic. */
  private def storageRootOf(seed: String): ByteString = kec256(ByteString("storage:" + seed))

  /** A non-empty code hash standing in for a contract's code. Deterministic. */
  private def codeHashOf(seed: String): ByteString = kec256(ByteString("code:" + seed))

  /** The canonical (coherent) account set: a fixed mix of EOAs and contracts. Stored as a map from the 32-byte keccak
    * account hash to the account leaf. The merkleizer must stream these in ascending key order; this map's iteration
    * order is irrelevant (the trie / `seekFrom` sorts).
    */
  val canonicalAccounts: Map[ByteString, Account] = {
    val eoas = (0 until 6).map { i =>
      accountKey(s"eoa-$i") -> Account.empty(startNonce = 0).copy(nonce = i, balance = i * 1000)
    }
    val contracts = (0 until 4).map { i =>
      accountKey(s"contract-$i") -> Account(
        nonce = i + 1,
        balance = i * 7,
        storageRoot = storageRootOf(s"contract-$i"),
        codeHash = codeHashOf(s"contract-$i")
      )
    }
    (eoas ++ contracts).toMap
  }

  /** The canonical account set sorted strictly ascending by 32-byte keccak key — the order the finalize merkleizer
    * streams `seekFrom(0x00..)` in.
    */
  val canonicalAccountsAscending: Seq[(ByteString, Account)] =
    canonicalAccounts.toSeq.sortWith { case ((a, _), (b, _)) =>
      java.util.Arrays.compareUnsigned(a.toArray, b.toArray) < 0
    }

  /** RLP-encoded leaves in ascending key order, exactly as written to / read from `FlatAccountStorage`. */
  val canonicalLeavesAscending: Seq[(ByteString, ByteString)] =
    canonicalAccountsAscending.map { case (k, acc) =>
      k -> ByteString(Account.accountSerializer.toBytes(acc))
    }

  /** The KNOWN canonical account-trie state root for `canonicalAccounts`, computed via the same
    * `MerklePatriciaTrie[ByteString, Account]` production uses. This is the value a correct local merkleization MUST
    * reproduce byte-for-byte.
    */
  val canonicalStateRoot: ByteString = {
    val trie = canonicalAccounts.foldLeft(
      MerklePatriciaTrie[ByteString, Account](new TestMptStorage)(
        com.chipprbots.ethereum.mpt.byteStringSerializer,
        Account.accountSerializer
      )
    ) { case (t, (k, acc)) => t.put(k, acc) }
    ByteString(trie.getRootHash)
  }

  /** The account hash whose leaf is perturbed in [[perturbedAccounts]] / [[perturbedLeavesAscending]]. */
  val perturbedKey: ByteString = accountKey("contract-1")

  /** A perturbed variant: exactly ONE account (`perturbedKey`) carries a STALE `storageRoot` (as if its leaf reflected
    * an earlier pivot). Every other leaf is canonical. Its root MUST differ from [[canonicalStateRoot]], so a
    * merkleization over this set fails the `computedRoot == header` gate (fail-closed).
    */
  val perturbedAccounts: Map[ByteString, Account] =
    canonicalAccounts.updated(
      perturbedKey,
      canonicalAccounts(perturbedKey).copy(storageRoot = storageRootOf("STALE-contract-1"))
    )

  val perturbedAccountsAscending: Seq[(ByteString, Account)] =
    perturbedAccounts.toSeq.sortWith { case ((a, _), (b, _)) =>
      java.util.Arrays.compareUnsigned(a.toArray, b.toArray) < 0
    }

  val perturbedLeavesAscending: Seq[(ByteString, ByteString)] =
    perturbedAccountsAscending.map { case (k, acc) =>
      k -> ByteString(Account.accountSerializer.toBytes(acc))
    }

  /** The perturbed set's canonical root — differs from [[canonicalStateRoot]] by construction. */
  val perturbedStateRoot: ByteString = {
    val trie = perturbedAccounts.foldLeft(
      MerklePatriciaTrie[ByteString, Account](new TestMptStorage)(
        com.chipprbots.ethereum.mpt.byteStringSerializer,
        Account.accountSerializer
      )
    ) { case (t, (k, acc)) => t.put(k, acc) }
    ByteString(trie.getRootHash)
  }
}
