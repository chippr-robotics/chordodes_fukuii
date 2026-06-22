# Phase 0 Research: Hot-Path Allocation Reduction (Spec 007)

**Feature**: Reduce hot-path CPU allocations in keccak-256 + SNAP inline merkleization.
**Branch**: `007-hotpath-alloc-reduction`
**Status**: Phase 0 complete — consolidated for `/speckit-plan`.

> **Overriding constraint (Constitution Principle I):** this is **pure performance**.
> Every change MUST be **byte-for-byte identical** in consensus output (state roots,
> trie node hashes, account/storage roots, hashed wire/serialization output). "A
> single non-deterministic line can split the chain." keccak and merkleization are
> consensus-critical, and `kec256` is **chain-shared**, so BOTH the **`forge`** (ETC)
> and **`beacon`** (ETH/Sepolia) protocols (design + byte-for-byte review *before*
> implementing) govern every decision below.

---

## 1. Summary

The chosen approach is three independently-shippable stories, ordered by value/risk:

- **P1 (keccak ThreadLocal reuse)** — replace the per-call `new KeccakDigest(256)` in
  every `kec256` entry point with a thread-confined `ThreadLocal[KeccakDigest]` that is
  **`reset()` on entry** before any `update()`. This removes the per-hash digest
  allocation (keccak is the single hottest op, called once per trie node across
  millions of nodes) **without** the lock contention of the existing shared-`kec512`
  pattern. Lowest risk, highest leverage, foundation for the rest.
- **P2 (StackTrie scratch reduction)** — in the `deferred-merkleization=false` inline
  builder (`StackTrie`), pool/elide only the **transient, copied-then-discarded** scratch
  (path-append buffers, the `encodeBranch` refs array, RLP encode intermediates). The
  per-node **final blob/`value`** — what feeds `kec256` and is aliased into the parent's
  RLP — stays a freshly-owned array. More parity surface; ships after P1.
- **P3 (opportunistic)** — a dedicated single-`Array[Byte]` `kec256` overload (kills an
  accidental varargs `Seq` wrapper alloc), the `SnapHashTrie.emit` `blob.clone()` elision
  (coupled to P2's ownership contract), and the `RLP.encode` O(n²) `foldLeft ++` on the
  deferred/import path. Each only ships if it proves BOTH parity AND a measurable
  allocation drop (FR-010); the ref-count node-storage wrapping is explicitly **excluded**.

Parity is proven by the existing correctness corpus (crypto, RLP, MPT, VM,
ethereum/tests) run at the right tier with zero diffs, **plus** a new dedicated keccak
golden-vector + reset-after-abort + concurrency spec, and the existing
`StackTrieSpec` MerklePatriciaTrie oracle extended to per-node hash equality.

---

## 2. Decisions

### 2.a — ThreadLocal digest reuse design + reset discipline

**Decision.** Introduce one `private val kec256Digest = ThreadLocal.withInitial(() => new
KeccakDigest(256))` in the crypto package object
(`crypto/src/main/scala/com/chipprbots/ethereum/crypto/package.scala`). Every `kec256`
entry point becomes: `val d = kec256Digest.get(); d.reset(); d.update(...);
d.doFinal(out, 0); out`. The **`reset()` is the first statement on the digest in every
body** — reset-on-**entry**, not relying on `doFinal`'s trailing reset. Apply this rewrite to
the **three digest-owning bodies**: `kec256(Array, start, len)` (package.scala:32),
`kec256(Array*)` (package.scala:40), and `kec256PoW` (package.scala:51). `kec256(ByteString)`
(package.scala:48) owns **no** digest — it delegates to the `Array` form — so it is covered
**transitively** and is **not** rewritten. Keep the per-call `output = Array.ofDim[Byte](32)` (it is the returned
value, escapes to callers, cannot be pooled without cross-call aliasing). **Do not touch**
`kec512` (low frequency; keep its shared-`synchronized` instance, package.scala:60).

**Rationale.**
- *Byte-for-byte identical to per-call `new KeccakDigest(256)`.* The complete mutable
  state of `KeccakDigest` is `{state[25] long, dataQueue[~192] byte, rate int, bitsInQueue
  int, fixedOutputLength int, squeezing bool}` (~450–500 B total, verified via `javap` on
  the pinned `bcprov-jdk18on` — `project/Dependencies.scala:139`). The constructor runs
  `init(256) -> initSponge(...)` and `reset()` calls the **identical** `init(fixedOutputLength)
  -> initSponge` path: zero `state[]`, fill `dataQueue` with 0, `bitsInQueue=0`,
  `squeezing=false`. A reset digest is therefore in the same observable state as a freshly
  constructed one, and for fixed length 256 the output is a pure function of the absorbed
  bytes ⇒ identical input ⇒ identical 32 bytes. Empty input is safe: `squeeze()`
  unconditionally calls `padAndSwitchToSqueezingPhase()`. Multi-arg ordering is preserved —
  the varargs body still `update(i, 0, i.length)` per arg in `Seq` order before one
  `doFinal`, and absorb is associative over concatenation (`{update(a);update(b)} ==
  update(a++b)`).
- *Why reset-on-entry (FR-002 — the load-bearing parity mechanism).* `doFinal` resets the
  digest **only on the success path**. If a previous `kec256` on this thread threw *after*
  some `update()` but *before* `doFinal` (OOM, an interrupt observed between updates in the
  varargs loop, any `RuntimeException`), the per-thread digest is left dirty with stale
  `bitsInQueue`/`dataQueue`. Without reset-on-entry the **next** hash on that thread would
  silently fold residual bytes into the result — a wrong root, a chain split. reset-on-entry
  is one `initSponge` call (~few hundred ns), negligible vs a per-call allocation, and makes
  the operation idempotent w.r.t. any prior aborted state. It is strictly stronger than a
  `try/finally { reset() }` (robust even if a `finally` reset itself throws) and needs no
  call-site wrapping.
- *Why ThreadLocal, not shared+synchronized or an object pool.* Thread-confinement is the
  cheapest correct model: the JVM guarantees the value is reachable by exactly one thread,
  so the mutable, non-thread-safe `KeccakDigest` is never observed by two threads (FR-003)
  with **zero** synchronization — no lock, no CAS, no contention. A shared digest +
  `synchronized` (the `kec512` pattern at package.scala:60) would serialize *all* hashing
  across every dispatcher thread — catastrophic on the hottest op; the spec explicitly
  forbids replicating it. An object pool adds borrow/return bookkeeping (a `finally` on
  every hash) and queue lock/CAS for a resource that maps 1:1 to threads. None of the
  `kec256` variants is reentrant (verified: none calls `kec256` or each other internally;
  the varargs form fully evaluates inputs before entry), so a single per-thread instance is
  safe.

**Alternatives considered.**
- *Keep per-call `new KeccakDigest(256)`* — zero risk, but the allocation the feature
  targets (fails SC-001).
- *Single shared digest + `synchronized`* (the existing `kec512` anti-pattern) — REJECTED:
  trades allocation for lock contention on the hottest path.
- *Rely only on `doFinal`'s trailing reset (no reset-on-entry)* — REJECTED: leaves the
  aborted-mid-update window open ⇒ latent chain split.
- *Pool the 32-byte output via a second ThreadLocal* — REJECTED: the output escapes to and
  is retained by callers (e.g. `StackTrie` stores it as `node.value`, ByteString-wraps it),
  so reuse causes cross-call aliasing/corruption. Only the **digest** (transient scratch) is
  poolable.
- *Add a `kec256Into(out, ...)` zero-alloc variant for hot callers* — deferred to a later
  story; not needed for FR-001 and only worthwhile where the caller owns a reusable buffer.

---

### 2.b — Which StackTrie / RLP / ref-count allocations are in vs out of scope

The real inline-merkleization engine is `final class StackTrie` at
`src/main/scala/com/chipprbots/ethereum/mpt/StackTrie.scala:27` (NOT the test
`StackTrieSpec`). It is a single-writer streaming builder; the only consensus-visible
outputs are (1) the `blob` passed to `crypto.kec256` and `onTrieNode` at `finalise()`
(StackTrie.scala:260–266) and (2) `node.value`, which a parent splices via `encodeChildRef`
(StackTrie.scala:324). **Crucial aliasing constraint:** `encodeChildRef` does **not** copy
for the inline `<32 B` case (StackTrie.scala:329 splices `child.value` raw), and the parent
reads it by `arraycopy` after the child is finalised — so `child.value` is **aliased** into
the parent's RLP.

**Decision (P2 — IN scope, structurally unchanged):** pool/reuse ONLY buffers fully consumed
within a single encode call that never escape:
- the per-branch `refs: Array[Array[Byte]]` scratch in `encodeBranch` (StackTrie.scala:294);
- the intermediate `encodeBytes` / `listHeader` / `lengthAsBytes` output arrays
  (StackTrie.scala:337+) — their bytes are `System.arraycopy`'d into the node's final `blob`
  then discarded;
- transient path buffers: `appendNibble`/`appendNibbles` (StackTrie.scala:494/502) allocate
  a new `Array[Byte]` per descent, and the path is **ignored** by `SnapHashTrie.emit` on the
  default HashScheme (`(_, hash, blob) => emit(hash, blob)`) — the path never enters the hash,
  so reusing a path buffer is byte-identical;
- `sliceFrom`/`sliceRange` per node-split (StackTrie.scala:472/483);
- the `new Array[StNode](16)` allocated by `newLeaf`/`newExt` even though only slot 0 (Ext) or
  none (Leaf) is used (StackTrie.scala `object StNode`, ~426).

**Decision (P2 — OUT of bounds):** the per-node **final blob / `node.value`** MUST stay a
freshly-owned array per node. Reusing it would mutate an already-emitted parent's encoding
(via the :329 alias / :311 arraycopy) ⇒ state-root divergence. `finalise` already sets
`node.value` to a freshly-allocated value (`kec256` returns a new array; `encode*` return new
arrays), so leaving that path alone is the safe default.

**Decision (P2 has NO ThreadLocal):** `StackTrie` instances are already **thread-confined**
(one per task/trie, never shared) — construction sites `SnapPathTrie.scala:66`,
`SnapHashTrie.scala:57`, `ProofTrieInserter.scala:21`, and
`AccountRangeCoordinator.getOrCreateTaskStackTrie` (per-task, actor-owned map). Keep reusable
scratch as **instance fields**, not statics/ThreadLocals — trivially bounds the footprint
(FR-009) and avoids contention when concurrent storage tries are built on different threads.

**Decision (P3 — IN, gated by FR-010):**
- A dedicated `def kec256(input: Array[Byte]): Array[Byte]` overload calling the 3-arg form.
  Verified there is **no** single-`Array[Byte]` overload today (`grep` count 0), so every
  `kec256(x)` binds to the varargs `kec256(Array[Byte]*)` (package.scala:40), allocating a
  `Seq`/`ArraySeq` wrapper **per call** on top of digest+output. The overload removes the
  wrapper for all single-arg sites at once (e.g. StackTrie.scala:265, :54/58/91/92;
  `kec256(key).slice(...)` in `SignedTransaction`, `BlockPreparator`, `PrecompiledContracts`);
  varargs is kept for genuine multi-arg callers (`AuthHandshaker`). Byte-identical (same
  bytes, only dispatch changes). Must compile-check no caller's bytes change and watch for
  Scala 3 ambiguous-overload errors.
- `SnapHashTrie.emit`'s `blob.clone()` per emitted node (SnapHashTrie.scala:~94–96) can be
  elided **iff** P2 makes StackTrie hand off an owned (non-reused) blob — the clone exists
  because the class doc (StackTrie.scala:22 "the StackTrie reuses internal buffers across
  calls") currently promises reuse. Coupled change: change the doc-contract and elide the
  clone together, or not at all (Chesterton's fence).
- `RLP.encode` (`rlp/.../RLP.scala:90–101`) uses `foldLeft(Array[Byte]())((acc,item) => acc ++
  encode(item))` — O(n²) array concatenation on the **deferred-merkleization / block-import**
  encode path (distinct from StackTrie's own encoder). Replace `++` with a single sized
  buffer/builder. Byte-identical RLP; helps `deferred=true` + import, a secondary beneficiary.

**Decision (P3 — EXCLUDED):** `ReferenceCountNodeStorage.update`
(`db/storage/ReferenceCountNodeStorage.scala:49–84`) per-node ref-count wrapping is **off**
the `deferred=false` hot path — the inline SNAP path writes RAW, unwrapped nodes
(`storeRawNodes` via `AccountRangeCoordinator`/`StorageRangeCoordinator`), and the deferred
path also sidesteps it (`DeferredWriteMptStorage`). Ref-count wrapping is load-bearing for
pruning/rollback correctness (Chesterton's fence). LOW SNAP value, MED-HIGH pruning risk ⇒
EXCLUDE per FR-010. (The known O(n²)→O(n) `::`+reverse fix is already applied.)

**Rationale.** The aliasing read at StackTrie.scala:329/:311 makes the final blob/`value` the
one buffer that can never be pooled; everything copied-then-dropped is free to reuse. The
ref-count path is both off the targeted hot path and pruning-critical, so it fails the
value-vs-risk bar.

**Alternatives considered.** Reuse a single mutable buffer for `node.value` across nodes —
REJECTED (corrupts already-emitted parents). Leave StackTrie entirely untouched and ship only
P1 — VIABLE fallback (P1 is independently shippable, far smaller parity surface); recommend
gating P2 behind the A/B replay passing before merge. Rewrite all single-arg call sites to the
3-arg form instead of adding the overload — REJECTED as churny.

---

### 2.c — Thread-safety model under Pekko dispatchers

**Decision.** Keep all hashing on **platform-thread** Pekko dispatchers (the status quo) and
document a guard rule at the ThreadLocal definition: do **not** run `kec256` on a
`Thread.ofVirtual` / per-task virtual-thread executor without revisiting this design. The
ThreadLocal and rewritten bodies live in the crypto package object; all four public
signatures stay byte-identical so the 44+ caller files change not at all. Never expose the
digest reference outside its `kec256` method body (no return, no field, no `Future` capture).

**Rationale.**
- A grep of the entire Scala source found **no** virtual-thread usage (no `Thread.ofVirtual`,
  `newVirtualThreadPerTaskExecutor`, `newThreadPerTaskExecutor`). The hot hashing paths run on
  **bounded platform-thread pools**: StackTrie's storage build on the
  `storage-writer-dispatcher`, all other `kec256` callers on Pekko fork-join/default/blocking-io
  dispatchers and mining threads — tens of threads, bounded by CPU count.
- With ThreadLocal-per-platform-thread at ~500 B/digest, total retained footprint is ~tens of
  KB — **bounded by live thread count exactly as FR-009 requires** — with no leak: dispatcher
  threads are long-lived and pooled, and the ThreadLocal value dies with the thread.
- The build targets JDK 25 (temurin 25 in CI), where virtual threads are GA — so the concern is
  real-but-dormant. The **only** way this balloons is if hashing later moves to a per-task
  virtual-thread executor: each of potentially millions of vthreads would lazily allocate its
  own un-pooled digest. The cheap mitigation is the documented "platform threads only" rule, not
  a speculative pooling redesign now.

**Alternatives considered.** Defensive scoped/pooled design for hypothetical vthreads —
REJECTED (adds contention/complexity now to defend a non-existent, project-controlled usage). A
carrier-thread-pinned pool — no clean API; ThreadLocal already binds to carrier semantics for
platform threads. A bounded CPU-sized shared pool — a valid **future** option *if* hashing ever
moves to vthreads, but reintroduces borrow/return + contention; out of scope until then.

---

### 2.d — Parity-test strategy (proves FR-005 / SC-004)

**Decision.** Four layers.

1. **Dedicated keccak golden-vector + edge-case spec** at
   `crypto/src/test/scala/com/chipprbots/ethereum/crypto/Keccak256Spec.scala` (tag
   `CryptoTest`+`UnitTest`; runs under `testCrypto`/`testEssential`; fast, deterministic, no
   Pekko). There is currently **no** dedicated keccak vector test in `crypto/src/test`
   (verified — only Aes/ECDSA/ECIES/Pbkdf2/Ripemd160/Scrypt specs), so this fills a real gap.
   Cover: (a) fixed vectors — `keccak256("") =
   c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470`, `keccak256("abc")`,
   1-byte, 32-byte, and the EMPTY_TRIE root (`56e81f17…` via `kec256(0x80)`); (b) all four
   overloads (`Array,start,len` / `Array*` / `ByteString` / `kec256PoW`) agree with an
   independent inline `new KeccakDigest(256)` oracle; (c) the three FR-002 edge cases as
   explicit tests — **reset-after-abort** (a hashing closure that throws between `update` and
   `doFinal` on the **same** thread, then assert the next hash on that thread is correct),
   empty/single-byte/max-node-size inputs, and a **concurrency** test (N threads hashing
   independent inputs via `Future` + a fixed pool + `Await`, **no `Thread.sleep`**) asserting
   every result equals the single-threaded oracle (FR-003/FR-007 no cross-thread bleed).
2. **StackTrie root equivalence** — reuse and EXTEND the existing `StackTrieSpec` oracle
   (StackTrie root == `MerklePatriciaTrie` root; the reference oracle): add fixed-seed corpora
   (e.g. 50k sorted 32-byte keys) and an emission-level assertion that every emitted
   `(hash, blob)` still satisfies `kec256(blob) == hash`. Because `MerklePatriciaTrie` hashes via
   `Node.hashFn -> crypto.kec256`, this replay transitively exercises the optimized kec256 too.
   `ProofTrieInserter` shares StackTrie's hasher — its tests are a second consumer that must stay
   green, and the parity extension must cover its insert/hash path, not just `update()`/`hash()`.
3. **Full correctness corpus** — `testCrypto`, `testRLP`, `testMPT`, `testVM`, and
   `testComprehensive` (ethereum/tests, ETC-filtered) at the comprehensive tier. ethereum/tests
   `BlockchainTests`/`GeneralStateTests` assert exact post-state roots — a single wrong keccak
   byte flips a root, making them the strongest **file-backed golden** byte-for-byte gate.
4. **A/B replay** — compute state roots over a fixed recorded SNAP account+storage range with
   old vs new binaries and assert zero diffs; this doubles as the FR-008 measurement vehicle for
   the `deferred=false` inline path. Pin the input corpus and RNG seed (`StackTrieSpec` uses
   `scala.util.Random` — fix the seed) so a "diff" can never be a test artifact.

**Rationale.** FR-005 demands parity "across the correctness corpus AND a dedicated parity
check." The `MerklePatriciaTrie` oracle is a live independent cross-check (no golden-file
maintenance); ethereum/tests is the maintained reference-aligned golden layer. The
reset-after-abort test is the **only** thing that exercises the reset-on-entry guarantee — without
it FR-002 is unproven — and it must inject the throw and re-hash on the **same** thread (a fresh
thread would not catch the bug).

**Alternatives considered.** Root-only equality without per-node hash checks — WEAKER; the
per-node check is cheap and closes the (vanishingly unlikely) consistent-collision case. A
bespoke "replay a captured block range and diff roots" tool — REJECTED as redundant;
ethereum/tests already is that. Putting the keccak spec in the node module — REJECTED; the
optimization lives in crypto, so the test must run in the crypto tier and not depend on node.

---

### 2.e — Measurement / benchmark approach + baseline capture

**Decision.** Add deterministic micro-benchmarks under `src/benchmark` (the existing
`config("benchmark").extend(Test)` in `build.sbt:237`; tagged `BenchmarkTest`, so **excluded**
from `testEssential`/`testStandard` via the `-l BenchmarkTest` filter, build.sbt:544). There is
**no** `sbt-jmh` plugin (verified); the project idiom is wall-clock `BenchmarkTest`-tagged
ScalaTest specs (e.g. `MerklePatriciaTreeSpeedSpec`, `RLPSpeedSuite`). Use
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes(threadId)` sampled before/after as a
zero-dependency, JMH-grade allocation metric (directly evidences SC-001 / the bytes-allocated
half of SC-002), `System.nanoTime` for throughput (SC-003 proxy), and `-Xlog:gc` on the fork for
GC pause/alloc-rate.

- Two benchmark methods: a **pure `kec256` loop** (Story 1; e.g. N=5M ~136-byte node-blob inputs)
  and a **StackTrie-builds-N-sorted-leaves loop** (Story 2; e.g. 100k and 1M sorted 32-byte
  leaves). The build loop MUST run on the **measured thread** (not handed to a dispatcher) or
  `getThreadAllocatedBytes` reads zero/wrong.
- **Baseline capture is mandatory and grounds SC-002/SC-003.** Run the benchmark on a
  `git` worktree/stash of the **parent commit FIRST** to record baseline numbers into the plan,
  then again post-change for the delta. Same commit-of-corpus, same RNG seed, same N, same
  production JVM flags (`-Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200` from
  `src/universal/conf/application.ini`), with a warmup phase (≥3 iterations discarded for JIT)
  and ≥5 measured iterations reporting median + min/max.
- Measure **OFFLINE** on a fixed in-memory corpus (`EphemDataSource`/test MPT storage, no
  RocksDB, no peers) — **NEVER** instrument the live barad-dûr node. MEMORY.md repeatedly
  documents that builds/tests over-subscribe the 15–16 GiB host and freeze the desktop / kill
  in-progress heal; run the benchmark only when the node and Barad-dûr are idle.

**Rationale.** `getThreadAllocatedBytes` gives per-thread allocation accounting without a profiler
(no `async-profiler` on host; attaching it risks the very CPU/mem pressure the spec relieves). The
honest expectation is **low single-digit to low-double-digit percent** throughput (allocation/GC
relief, not order-of-magnitude). Warmup is mandatory — hive flakiness in this repo was traced to
JVM cold-start. The perf benchmark is **report-and-record** (not a hard CI gate — a contended host
would make it flaky); the **parity tests** are the only hard gate.

**Alternatives considered.** `sbt-jmh` — REJECTED for this scope (a build-config change the
constitution would want reviewed separately; `getThreadAllocatedBytes` already gives JMH-grade
allocation numbers). End-to-end SNAP sync time on Cirith Ungol/Mordor before/after — valid as an
**optional secondary** SC-003 datapoint, but not the gating measurement (≈1h/run, peer-dependent,
confounds network/dispatch with the hot path). Live JFR on barad-dûr — REJECTED per the freeze
history; reserve JFR for a one-off deeper look if numbers are ambiguous.

---

## 3. Resolved unknowns

| Was unclear | Resolution |
| :-- | :-- |
| Is there an existing single-`Array[Byte]` `kec256` overload? | **No** (grep count 0). Every `kec256(x)` binds to the varargs form and allocates a `Seq` wrapper per call ⇒ P3 overload is a real, free win. |
| Does `doFinal` reset the digest? | **Yes, on the success path only** (bytecode calls `reset()` after `squeeze()`). The aborted-mid-update path is NOT reset by `doFinal` — hence reset-on-entry is required (load-bearing for FR-002). |
| Is `KeccakDigest` thread-safe / how big? | **Not** thread-safe (mutable); ~450–500 B retained state. ThreadLocal-per-platform-thread footprint is bounded and ~tens of KB total ⇒ satisfies FR-009. |
| Are `kec256` variants reentrant (could a single per-thread instance be corrupted mid-use)? | **No** today — none calls `kec256`/each other; varargs fully evaluates inputs before entry. A single per-thread instance is safe (noted as an assumption + nested-call test guards regression). |
| Does any caller run `kec256` on virtual threads? | **No** virtual-thread usage anywhere in the source. Hashing runs on bounded platform-thread Pekko dispatchers ⇒ ThreadLocal is bounded; documented guard rule covers future vthread adoption. |
| Where is the real merkleization engine? | `final class StackTrie` at `StackTrie.scala:27` (NOT the test). Consensus outputs = the `kec256(blob)` at :265 and `node.value` aliased into parents at :329. |
| Is the StackTrie final blob safe to pool? | **No** — `child.value` is aliased into the parent's RLP (StackTrie.scala:329 raw splice, :311 arraycopy). Only copied-then-discarded transient scratch is poolable. |
| Does the inline SNAP write path hit ref-count wrapping? | **No** — it writes RAW nodes (`storeRawNodes`); the deferred path also sidesteps it. ⇒ ref-count path is OUT of scope. |
| Is there a JMH harness? | **No** `sbt-jmh`. Use the existing `BenchmarkTest`-tagged ScalaTest idiom + `getThreadAllocatedBytes`. |
| Is StackTrie shared across threads (does P2 need a ThreadLocal)? | **No** — one StackTrie per task/trie (`SnapPathTrie.scala:66`, `SnapHashTrie.scala:57`, `ProofTrieInserter.scala:21`, per-task `AccountRangeCoordinator` map). Use instance fields, no ThreadLocal for P2. |

---

## 4. Risks & mitigations

| # | Risk | Severity | Mitigation |
| :-- | :-- | :-- | :-- |
| R1 | **Chain split if reset-on-entry is omitted.** A dispatcher thread whose prior `kec256` threw after `update()` but before `doFinal` carries stale absorbed bytes; the next hash is wrong and is written into the trie/state root. | **CRITICAL** | `digest.reset()` is the literal first statement on the digest in **every** body. Dedicated reset-after-abort parity test (throw mid-update, re-hash on the **same** thread). |
| R2 | **Chain split if StackTrie final blob/`value` is pooled.** `child.value` is aliased into a parent's RLP (StackTrie.scala:329/:311) after the child is finalised; reusing it mutates an already-formed parent encoding. | **CRITICAL** | Pool ONLY copied-then-discarded transient scratch (refs array, encode intermediates, path buffers); keep the per-node final blob/`value` freshly owned. Gate P2 behind the A/B replay + per-node hash equality. |
| R3 | **Silent reset-elision / overload regression.** A future refactor adds a `kec256` entry point bypassing the reset-on-entry core, or the new single-arg overload changes a caller's bytes. | High | Funnel **all** entry points through one private reset-on-entry core; the aborted-call test asserts each public overload. `compile-all` + crypto/MPT/ethereum-tests confirm zero diff; watch Scala 3 ambiguous-overload. |
| R4 | **ThreadLocal lifetime / virtual-thread balloon.** A per-task vthread executor would allocate one un-pooled digest per (millions of) vthreads, defeating the win and risking OOM. | Med (dormant) | No vthread usage today; documented "platform threads only for hashing" guard at the definition; revisit before any vthread adoption. Never expose the digest reference outside the method body. |
| R5 | **Cross-thread state bleed.** Digest captured out of its ThreadLocal and shared via a `Future`/actor. | Med | Digest never returned/stored/closed-over; comment forbids extraction; concurrency parity test asserts no bleed (real multiple threads vs single-thread oracle). |
| R6 | **Measurement validity.** Wall-clock benchmark variance; baseline captured on a different commit/flags/corpus; benchmark run on a building/healing host. | Med | Warmup ≥3 + median-of-≥5; baseline from the actual parent-commit worktree; same seed/N/JVM flags; run only when node + Barad-dûr idle. Perf is report-only, not a CI gate. |
| R7 | **Coverage blind spot.** ethereum/tests runs at the ~3h comprehensive tier and is easy to skip; SC-004 is only proven once it runs green. | Med | Plan states which tier ran with `VERIFY: ran <cmd> — PASS/FAIL/DID NOT RUN`. Do not treat `testEssential`/`testStandard` as sufficient for the consensus gate. |
| R8 | **ProofTrieInserter regression.** P2 scratch aliasing could corrupt SNAP proof verification without failing root-only StackTrieSpec cases. | Med | Extend parity to the `ProofTrieInserter` insert/hash path, not just `update()`/`hash()`. |
| R9 | **No silent `catch {}`.** Reset-on-entry must not be wrapped in a swallow that hides an aborted hash. | Low | Reset-on-entry is explicit; let any genuine failure crash (Constitution: fail loudly). |

---

## 5. Open questions for `/speckit-tasks` / planning

1. **reset-on-entry vs `try/finally{reset}`** — both correct given `doFinal` resets on success;
   reset-on-entry is the recommendation (simpler, stronger invariant). Confirm preference since
   it is the load-bearing parity mechanism.
2. **Story 2 atomic with Story 1, or sequenced?** P1 is independently shippable with far smaller
   parity surface. Recommend landing P1 + the A/B replay harness first, then P2 — unless the user
   wants both atomically.
3. **`kec256PoW` in the ThreadLocal rewrite?** It is on the Ethash verify/mine (consensus) path.
   Same per-thread digest + reset-on-entry works; include it, flag for `forge` review since it
   touches PoW validation output.
4. **P2 path handling — skip vs reuse buffer?** `SnapHashTrie` (HashScheme) ignores `path`, but
   `SnapPathTrie` (PathScheme, `SnapPathTrie.scala:66`) **consumes** it. So the optimization is
   likely "reuse a path buffer" (correct for both schemes) rather than "skip path" — confirm
   during design whether path-skip can be scheme-gated.
5. **Bench N / corpus size.** Proposal: `kec256` loop N=5M ~136-byte inputs; StackTrie loop 100k
   and 1M sorted 32-byte leaves. Needs `forge`/`eye` sign-off on what is representative without
   being too large for the constrained host.
6. **Confirm bcprov 1.84 `KeccakDigest` is byte-identical to the inspected 1.83 bytecode** — the
   init/reset/squeeze logic is stable across 1.7x–1.8x, but a one-line decompile of the exact
   pinned 1.84 jar removes all doubt before relying on this for consensus.
   **RESOLVED (T001, 2026-06-22).** `javap -c` of `KeccakDigest.class` from the pinned
   `bcprov-jdk18on-1.84.jar` (coursier cache) confirms `reset()` == fresh-construct for fixed length
   256: the constructor `KeccakDigest(int,purpose)` allocates `state[25]`+`dataQueue[192]` then calls
   `init(bitLength)`; `reset()` calls the SAME `init(this.fixedOutputLength)`; `init(256)` calls
   `initSponge(1600 - (256<<1))` which sets `rate`, zeroes all 25 `state[]` longs, `Arrays.fill(dataQueue,0)`,
   `bitsInQueue=0`, `squeezing=false`, and recomputes `fixedOutputLength`. A reset digest is therefore
   observably identical to `new KeccakDigest(256)` for output. Independently, a per-call
   `new KeccakDigest(256)` oracle on bcprov 1.84 reproduced every golden vector used by `Keccak256Spec`
   (`""`→c5d2…a470, `0x80`→56e8…b421, "abc", 1-byte, 32-byte, ~532-byte). PROCEED.
7. **Test placement** — keccak vectors + reset-after-abort + concurrency in `crypto` (`testCrypto`);
   StackTrie root-replay extends `StackTrieSpec` in node (`testMPT`); A/B SNAP replay + ethereum/tests
   at `testEthereum`/comprehensive. Confirm so the right tiers gate the change.
8. **`forge` (ETC) AND `beacon` (ETH/Sepolia) sign-off (SC-004 zero-diff)** before merge — `kec256`
   is chain-shared, so confirm the per-thread digest + single-arg
   overload + StackTrie scratch reuse produce byte-identical output across the full
   ethereum/tests + crypto + MPT corpus.
9. **Baseline numbers** — capture per-hash digest allocation rate and hot-path GC time on the 4-core
   host (and a CI box) **before** any edit, to set grounded SC-002/SC-003 targets.
10. **GC evidence format** — `-Xlog:gc:file=…` parse (zero-dep) is the proposal; reserve JFR for a
    one-off deeper look if numbers are ambiguous.
11. **Perf gate** — report-and-record (recommended), not a hard CI gate, given host contention;
    parity tests are the only hard gate. Confirm.

---

## Key file references

> **Caveat:** the line numbers cited below are **approximate** (expect ~1 line of drift vs the
> live source). Edits MUST bind to the method **signatures**, not the raw line offsets.

| Path | Role |
| :-- | :-- |
| `crypto/src/main/scala/com/chipprbots/ethereum/crypto/package.scala:32` | `kec256(Array,start,len)` — per-call `new KeccakDigest(256)` (P1 primary edit) |
| `…/crypto/package.scala:40` | `kec256(Array*)` varargs — per-call digest + accidental `Seq` wrapper (P1/P3) |
| `…/crypto/package.scala:48` | `kec256(ByteString)` |
| `…/crypto/package.scala:51` | `kec256PoW` — Ethash path |
| `…/crypto/package.scala:60` | `kec512` shared `synchronized` — the anti-pattern NOT to replicate |
| `project/Dependencies.scala:139` | `bcprov-jdk18on` pin — `KeccakDigest` semantics source |
| `src/main/scala/com/chipprbots/ethereum/mpt/StackTrie.scala:22` | doc: "the StackTrie reuses internal buffers" — the contract coupling P2↔P3 |
| `…/mpt/StackTrie.scala:27` | `final class StackTrie` — the real inline-merkleization engine |
| `…/mpt/StackTrie.scala:260` / `:265` | `finalise` / `kec256(blob)` + `onTrieNode` emit — the consensus output |
| `…/mpt/StackTrie.scala:294` | `encodeBranch` — `refs` scratch array (poolable) |
| `…/mpt/StackTrie.scala:324` / `:329` | `encodeChildRef` — parent embeds child hash; **raw inline splice = aliasing constraint** |
| `…/mpt/StackTrie.scala:337` | `encodeBytes` — transient output (poolable if copied) |
| `…/mpt/StackTrie.scala:472` / `:483` | `sliceFrom` / `sliceRange` — per node-split alloc |
| `…/mpt/StackTrie.scala:494` / `:502` | `appendNibble` / `appendNibbles` — per-descent path alloc (path ignored on HashScheme) |
| `…/mpt/StackTrie.scala:426` | `object StNode` — `new Array[StNode](16)` for leaf/ext (slim-node candidate) |
| `…/mpt/Node.scala:50` | `Node.hashFn` → 3-arg `kec256` = deferred / block-import trie-hash path |
| `rlp/src/main/scala/com/chipprbots/ethereum/rlp/RLP.scala:90` | `encode` O(n²) `foldLeft ++` (P3, deferred/import path) |
| `…/db/storage/ReferenceCountNodeStorage.scala:49` | ref-count wrapping — **EXCLUDED** (off hot path + pruning-load-bearing) |
| `…/sync/snap/SnapHashTrie.scala:57` / `:94` | `onTrieNode` lambda ignores path (HashScheme); `emit` `blob.clone()` (P3 coupling) |
| `…/sync/snap/SnapPathTrie.scala:66` | PathScheme — **consumes** path, gates P2 path handling |
| `…/sync/snap/actors/AccountRangeCoordinator.scala:1488` | `getOrCreateTaskStackTrie` — per-task, thread-confined StackTrie |
| `…/sync/snap/actors/StorageRangeCoordinator.scala` | `deferred=false` inline storage build path; `SnapHashTrie` wiring |
| `src/test/scala/com/chipprbots/ethereum/mpt/StackTrieSpec.scala` | MerklePatriciaTrie parity oracle — extend for FR-005 |
| `crypto/src/test/scala/com/chipprbots/ethereum/crypto/` | NO keccak vector spec today — add `Keccak256Spec.scala` |
| `src/benchmark/scala/.../MerklePatriciaTreeSpeedSpec.scala`, `RLPSpeedSuite.scala` | `BenchmarkTest` idiom for the new micro-benchmarks |
| `build.sbt:237` / `:544` | `config("benchmark")` + `-l BenchmarkTest` exclusion |
| `src/universal/conf/application.ini` | production JVM flags for representative benchmark runs |
