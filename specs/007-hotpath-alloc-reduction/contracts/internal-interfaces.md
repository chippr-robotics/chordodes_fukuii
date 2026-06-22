# Internal Interface Contracts: Hot-Path Allocation Reduction (Spec 007)

This feature exposes no external/public API change. The contracts below are **internal invariants** that callers and future maintainers rely on. They are the testable surface of FR-001…FR-010.

## Contract A — `kec256` public surface (UNCHANGED signatures, identical output)

The four `kec256` entry points in `crypto/.../package.scala` keep **byte-identical signatures and output**; only their internal allocation changes. The ThreadLocal digest rewrite touches only the **three digest-owning bodies** (`:32`, `:40`, `:51`); `kec256(ByteString):48` delegates to the `Array[Byte]` form and is covered transitively (no separate rewrite).

```
kec256(input: Array[Byte], start: Int, length: Int): Array[Byte]   // package.scala:32
kec256(input: Array[Byte]*): Array[Byte]                            // package.scala:40
kec256(input: ByteString): ByteString                              // package.scala:48
kec256PoW(header: Array[Byte], nonce: Array[Byte]): Array[Byte]    // package.scala:51
// NEW (P3): kec256(input: Array[Byte]): Array[Byte]               // delegates to 3-arg; removes varargs Seq wrapper
```

**Guarantees the contract makes to callers**:
1. Output bytes are identical to the pre-change implementation for every input (INV-4). The ~44 caller files compile and behave identically with no edits.
2. Thread-safe to call concurrently from any platform thread (each thread hashes independently; no shared mutable state observable across threads).
3. Re-entrant-safe within a thread only insofar as no `kec256` variant calls another mid-hash (verified true today; a nested-call regression test guards it).
4. The returned array is freshly owned by the caller (safe to retain/mutate).

**Caller obligations**: do NOT extract or retain the internal digest (it is not exposed). Do NOT call `kec256` on a per-task virtual-thread executor without revisiting the ThreadLocal design (documented guard).

**P3 overload caveat**: adding the single-`Array[Byte]` overload MUST NOT introduce a Scala 3 ambiguous-overload error or change which overload any existing `kec256(x)` call binds to in a way that alters output bytes (it only removes the varargs `Seq` wrapper).

## Contract B — StackTrie node-ownership contract (P2)

`StackTrie` is a single-writer, thread-confined streaming builder. Its consensus-visible outputs are the `(hash, blob)` emitted at `finalise()` and `node.value`.

**Invariant the builder guarantees**:
- Every emitted node's final `blob`/`value` is a **freshly-owned array**, never reused across nodes (INV-5). A parent may alias a child's `value` into its own RLP; the builder MUST keep that alias stable until the trie is complete.
- Reused scratch (refs array, encode intermediates, path buffers) is **never** aliased into a node's final encoding and is **never** read after the encode call returns (INV-6).

**Doc-contract coupling**: `StackTrie.scala:22` currently documents "the StackTrie reuses internal buffers across calls," which is why `SnapHashTrie.emit` defensively `clone()`s the blob. If P3 elides that clone, the StackTrie doc-contract MUST be updated in the same change to promise an owned (non-reused) emitted blob — change both or neither.

## Contract C — Parity verification contract (proves the above)

The implementation is correct iff ALL of these pass with **zero diffs**:

1. **`Keccak256Spec`** (`crypto`, `testCrypto`): fixed golden vectors (incl. `keccak256("") = c5d2…a470` and the empty-trie root via `kec256(0x80)`); all overloads agree with an independent per-call `new KeccakDigest(256)` oracle; **reset-after-abort** (throw between `update` and `doFinal`, re-hash on the **same** thread, assert correct); empty/single/max inputs; concurrency (N threads vs single-thread oracle, `Future`+pool+`Await`, no `Thread.sleep`).
2. **`StackTrieSpec`** (node, `testMPT`): StackTrie root == `MerklePatriciaTrie` root over fixed-seed corpora; per-node `kec256(blob) == hash`; `ProofTrieInserter` insert/hash path covered.
3. **Corpus** (`testRLP`, `testMPT`, `testVM`, `testEthereum`/comprehensive): ethereum/tests post-state roots match (the file-backed byte-for-byte golden gate).
4. **A/B replay**: state roots over a fixed recorded SNAP account+storage range, old vs new binary, zero diffs.

## Contract D — Measurement contract (proves the win, not a gate)

- Allocation measured via `ThreadMXBean.getThreadAllocatedBytes` on the **measuring thread** (build loop must run on it, not a dispatcher).
- Baseline captured from the **parent commit** first; same seed/N/JVM flags; warmup ≥3 + median-of-≥5.
- Run **offline** on an in-memory corpus only — never instrument the live node.
- Reported and recorded in the PR; **not** a hard CI gate (host contention → flaky). Parity (Contract C) is the only hard gate.
