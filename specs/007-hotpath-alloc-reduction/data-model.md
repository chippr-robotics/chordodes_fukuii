# Data Model: Hot-Path Allocation Reduction (Spec 007)

This feature introduces no persisted data, no schema, and no wire change. The "model" here is the set of **in-memory resources** whose allocation lifecycle changes, plus the **invariants** that keep consensus output byte-for-byte identical. Each resource is defined by its ownership, lifetime, mutation rules, and the invariant it must never violate.

## Resource 1: Reusable keccak-256 digest (P1)

| Aspect | Definition |
| :-- | :-- |
| **What** | A `bouncycastle KeccakDigest(256)` reused across calls instead of allocated per call. |
| **Where** | One `ThreadLocal[KeccakDigest]` (`ThreadLocal.withInitial(() => new KeccakDigest(256))`) in the crypto package object. |
| **Ownership** | Thread-confined. Exactly one digest per live thread; never shared, returned, stored in a field, or captured by a `Future`/closure. |
| **Lifetime** | Lives with the thread; dies when the thread dies. Retained footprint ≈ live-thread-count × ~500 B (bounded — FR-009). |
| **Mutation** | `reset()` on entry (first statement) → `update(...)` → `doFinal(out, 0)`. Reset-on-entry, not relying on `doFinal`'s success-path-only reset. |
| **State** | `{ state[25] long, dataQueue[~192] byte, rate, bitsInQueue, fixedOutputLength, squeezing }`. A `reset()` digest is observably identical to a freshly-constructed one for fixed length 256. |

**Invariants** (violation = chain split):
- **INV-1 (reset-on-entry)**: the digest MUST be `reset()` before any `update()` in every entry point, so a prior aborted hash (threw between `update` and `doFinal`) cannot fold stale bytes into the next hash. *(R1, FR-002.)*
- **INV-2 (no escape)**: the digest reference MUST NOT leave its `kec256` method body. *(FR-003/R5.)*
- **INV-3 (output owned)**: the returned 32-byte `output` array MUST remain freshly allocated per call (it escapes to callers, e.g. stored as `StackTrie.node.value`); it is NOT pooled.
- **INV-4 (byte identity)**: output MUST equal `new KeccakDigest(256)`-per-call output for all inputs, all four overloads, empty/single/max inputs, and any `update` ordering. *(FR-005/SC-004.)*

## Resource 2: StackTrie transient scratch buffers (P2)

| Aspect | Definition |
| :-- | :-- |
| **What** | Per-node temporary arrays produced and fully consumed inside one encode/descent, then discarded. |
| **In scope (poolable)** | `encodeBranch` `refs: Array[Array[Byte]]` scratch; `encodeBytes`/`listHeader`/`lengthAsBytes` intermediates (arraycopy'd into the node blob then dropped); `appendNibble`/`appendNibbles` path buffers (path ignored on HashScheme; reuse for both schemes); `sliceFrom`/`sliceRange` split buffers; the `new Array[StNode](16)` for leaf/ext (slim-node candidate, profile-gated). |
| **Ownership** | StackTrie **instance fields** (the StackTrie is already thread-confined — one per task/trie). NOT static/ThreadLocal. Trivially bounds footprint (FR-009). |
| **Lifetime** | Reused across nodes within a single StackTrie's lifetime. |

**Invariants**:
- **INV-5 (final blob owned — CRITICAL)**: the per-node final `blob` / `node.value` MUST stay a freshly-owned array per node. `child.value` is aliased into a parent's RLP (`encodeChildRef` raw inline splice at StackTrie.scala:329, arraycopy at :311); reusing it mutates an already-emitted parent → state-root divergence. *(R2.)*
- **INV-6 (transient only)**: only buffers that are copied-then-discarded within one call (never read after the call returns, never aliased into another node) may be pooled.
- **INV-7 (per-node hash equality)**: every emitted `(hash, blob)` MUST satisfy `kec256(blob) == hash`, and the StackTrie root MUST equal the `MerklePatriciaTrie` oracle root for the same input.

## Resource 3: kec256 single-arg overload + RLP encode buffer (P3, FR-010-gated)

| Aspect | Definition |
| :-- | :-- |
| **What** | (a) A `def kec256(input: Array[Byte]): Array[Byte]` overload delegating to the 3-arg form — removes the per-call `Seq`/`ArraySeq` wrapper that `kec256(x)` currently allocates by binding to the varargs overload. (b) `RLP.encode`'s O(n²) `foldLeft ++` → a single sized builder. (c) `SnapHashTrie.emit` `blob.clone()` elision, **coupled** to P2's ownership contract. |
| **Invariant** | **INV-8**: each P3 item ships only if it proves BOTH byte-for-byte parity AND a measurable allocation reduction (FR-010). The clone elision and the StackTrie ownership doc-contract change together or not at all (Chesterton's fence). |

## Explicitly excluded resource

- **ReferenceCountNodeStorage per-node wrapping** — OFF the `deferred=false` hot path (inline SNAP writes raw nodes via `storeRawNodes`), and load-bearing for pruning/rollback. Low value × high risk ⇒ EXCLUDED per FR-010.

## State transitions

None. No entity has lifecycle states; the only "transition" is the per-call digest sequence `reset → update* → doFinal`, which returns the digest to a clean state for the next call on that thread.
