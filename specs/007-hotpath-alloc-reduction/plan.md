# Implementation Plan: Hot-Path Allocation Reduction for SNAP Sync

**Branch**: `007-hotpath-alloc-reduction` | **Date**: 2026-06-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-hotpath-alloc-reduction/spec.md`

## Summary

Reduce CPU allocations on the hottest sync code paths — keccak-256 and SNAP inline merkleization — to cut GC/allocation pressure and return CPU to useful work, **without changing a single byte of consensus output**. Three independently-shippable stories: **P1** replaces the per-call `new KeccakDigest(256)` with a thread-confined `ThreadLocal[KeccakDigest]` that is `reset()` on entry; **P2** reuses StackTrie transient scratch buffers (never the aliased final node blob); **P3** opportunistic wins (a single-`Array[Byte]` `kec256` overload that kills an accidental varargs `Seq` alloc, a coupled `SnapHashTrie.emit` clone elision, an `RLP.encode` O(n²)→O(n) fix) — each gated on proving parity. The load-bearing guardrail throughout is **byte-for-byte parity** (FR-005/SC-004), proven by the crypto/RLP/MPT/ethereum-tests corpus plus a dedicated keccak golden-vector + reset-after-abort + concurrency spec and an A/B SNAP-replay. Design and review follow BOTH the **`forge`** (ETC) and **`beacon`** (ETH/Sepolia) protocols — keccak is consensus-critical and chain-shared. Full Phase-0 analysis in [research.md](./research.md).

## Technical Context

**Language/Version**: Scala 3.3.8 LTS, JDK 25 (Temurin)

**Primary Dependencies**: BouncyCastle `bcprov-jdk18on` 1.84 (`KeccakDigest`); Apache Pekko (actor dispatchers); ScalaTest (tiered). No new dependencies introduced.

**Storage**: N/A for the change — the work is in-memory hashing/merkleization. The optimized output is later persisted to RocksDB unchanged.

**Testing**: ScalaTest, tiered (ADR-017): `testCrypto`/`testEssential` (keccak vectors + edge cases), `testMPT` (StackTrie root replay), `testEthereum`/`testComprehensive` (ethereum/tests post-state roots — the byte-for-byte golden gate). New micro-benchmarks under the `BenchmarkTest`-tagged `benchmark` config (excluded from CI tiers).

**Target Platform**: Linux/JVM server. The constrained 4-core i5-4430 barad-dûr host is the measurement vehicle, but the gains are host-independent.

**Project Type**: Single project (multi-module EVM client: `crypto`, `rlp`, `mpt` in root `main`, `Benchmark`).

**Performance Goals**: per-call keccak digest allocation → ~0 steady state (SC-001); measurable hot-path bytes-allocated/GC-time reduction on a fixed replay (SC-002); SNAP account+storage throughput improvement of an honest **low single-digit to low-double-digit percent** on the constrained host (SC-003) — allocation/GC relief, not a step-change.

**Constraints**: **Byte-for-byte consensus parity is NON-NEGOTIABLE** (Constitution I). Thread-confined reuse on Pekko **platform-thread** dispatchers only (documented vthread guard). Per-thread footprint bounded by live-thread count (FR-009). Tests deterministic — **no `Thread.sleep`** (concurrency test uses `Future`+fixed pool+`Await`). The keccak/StackTrie final blob must never be pooled (aliasing → chain split).

**Scale/Scope**: keccak-256 is invoked once per trie node across millions of nodes. P1 touches one production file (`crypto/.../package.scala`) + adds tests; P2 touches `mpt/StackTrie.scala`; P3 touches `package.scala`, `SnapHashTrie.scala`, `rlp/RLP.scala`. ~44 caller files are **unchanged** (public `kec256` signatures preserved).

## Constitution Check

*GATE: Must pass before Phase 0 (done) and re-checked after Phase 1 (below).*

| Principle | Status | Notes |
| :-- | :-- | :-- |
| **I. Consensus Determinism (NON-NEGOTIABLE)** | **PASS — with mandatory `forge` (ETC) AND `beacon` (ETH/Sepolia) sign-off before merge** | Pure performance; every change is designed byte-for-byte identical. keccak digest reuse is proven equivalent (reset == fresh-construct for fixed length 256; reset-on-entry covers the aborted-call window). StackTrie pools only copied-then-discarded scratch; the aliased final blob stays owned. **The `kec256` helper is CHAIN-SHARED** — consumed by ETC PoW (Ethash/ommers) AND ETH/Sepolia PoS (`EngineApiService` payload-id/block-hash) — so Workflow step 7 mandates BOTH `forge` and `beacon` review (FR-006 enforcement). Hard gate: zero diffs across crypto/MPT/VM/ethereum-tests (BOTH ETC and ETH/Sepolia post-state-root sets) + the dedicated parity spec (SC-004/SC-005). |
| **II. Spec-Driven Development** | PASS | Following spec → plan → tasks → implement. Spec has no `[NEEDS CLARIFICATION]`; this plan resolves the Phase-0 open questions with documented decisions. |
| **III. Test Discipline & Tiered Coverage** | PASS | New tests are deterministic (fixed seeds, no `Thread.sleep`; concurrency via `Future`+pool+`Await`). Coverage stays ≥70% (new code is small and fully covered by the parity specs). Right-tier gating documented. |
| **IV. Idiomatic, Formatted Scala 3** | PASS | Implementation must pass `scalafmt` (3.8.3, 120 cols) + `scalafix`. ThreadLocal via `ThreadLocal.withInitial`; overload addition watched for Scala 3 ambiguity. |
| **V. Quality Gates Mandatory** | PASS | `sbt pp` before PR; CI (formatCheck, compile-all, testEssential, testStandard+coverage) must be green. Perf benchmark is report-and-record, not a CI gate (host contention would make it flaky); parity is the hard gate. |
| **VI. Security & Operational Safety** | PASS | No secrets, keys, or credentials touched. No silent `catch {}` — reset-on-entry is explicit and failures crash loudly. |

**Result: PASS.** No violations → Complexity Tracking is empty. The single binding condition is **`forge` (ETC) AND `beacon` (ETH/Sepolia)** byte-for-byte sign-off before merge (keccak is chain-shared), recorded as tasks T012/T012b/T025.

## Project Structure

### Documentation (this feature)

```text
specs/007-hotpath-alloc-reduction/
├── plan.md              # This file
├── research.md          # Phase 0 — verified decisions, risks, open questions
├── data-model.md        # Phase 1 — resources & invariants
├── quickstart.md        # Phase 1 — how to validate (parity + benchmark)
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — kec256 + StackTrie ownership contracts
├── checklists/
│   └── requirements.md  # spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — /speckit-tasks (next)
```

### Source Code (repository root)

```text
crypto/
├── src/main/scala/com/chipprbots/ethereum/crypto/
│   └── package.scala                 # P1: ThreadLocal[KeccakDigest] reset-on-entry (lines 32/40/48/51); P3: single-Array overload
└── src/test/scala/com/chipprbots/ethereum/crypto/
    └── Keccak256Spec.scala           # NEW: golden vectors + reset-after-abort + concurrency parity

rlp/
└── src/main/scala/com/chipprbots/ethereum/rlp/
    └── RLP.scala                     # P3: encode O(n²) foldLeft ++ → sized builder (deferred/import path)

src/main/scala/com/chipprbots/ethereum/
├── mpt/StackTrie.scala               # P2: reuse transient scratch (refs array, encode intermediates, path buffers); final blob stays owned
└── blockchain/sync/snap/
    └── SnapHashTrie.scala            # P3: emit blob.clone() elision (coupled to P2 ownership contract)

src/test/scala/com/chipprbots/ethereum/mpt/
└── StackTrieSpec.scala               # EXTEND: per-node hash equality + larger fixed-seed corpus (MPT oracle)

src/benchmark/scala/com/chipprbots/ethereum/
└── HotPathAllocBenchmark.scala       # NEW: getThreadAllocatedBytes micro-benchmarks (kec256 loop, StackTrie build loop)
```

**Structure Decision**: Single multi-module project (existing layout). The change is confined to `crypto` (P1/P3), `mpt` (P2), `rlp` (P3), with consensus parity verified in `crypto`/`mpt`/`ethereum-tests` tiers and allocation measured in the `benchmark` config. No new modules, no dependency changes.

## Phase 0 decisions resolved (from research.md open questions)

- **Reset discipline**: `digest.reset()` as the first statement on the digest in every `kec256` body (reset-on-entry), not `try/finally` — simpler and a strictly stronger invariant. *(Load-bearing for FR-002.)*
- **Story sequencing**: **P1 ships first as the standalone MVP** (smallest parity surface, independently valuable). P2 lands only after the A/B replay harness is proven; P3 items each ship only if they prove parity + measurable win (FR-010). Not atomic — decoupled per the spec's independent-story design.
- **`kec256PoW`**: included in the ThreadLocal rewrite for consistency (same digest, same reset-on-entry), but it is **dead code** (zero callers, grep-verified) — it does NOT cover the PoW path. The LIVE Ethash/PoW + ommers parity surface is `kec256`/`kec512` in `EthashUtils`/`EthashBlockHeaderValidator`/`EthashMiner`/`StdBlockValidator`, gated by `testEthereum` (T012). The ETH/Sepolia consensus consumers of `kec256` (`EngineApiService` payload-id/block-hash) are gated by `beacon` + an ETH/Sepolia parity run (T012b).
- **Entry-point count**: only **three** `kec256` bodies own a `KeccakDigest` and get the rewrite — `:32` (`Array,start,len`), `:40` (`Array*`), `:51` (`kec256PoW`). `kec256(ByteString)` (`:48`) owns no digest; it delegates to the `Array` form and is covered transitively (earlier "four entry points" phrasing corrected).
- **P2 path handling**: reuse a path buffer (correct for both HashScheme and PathScheme) rather than "skip path" — `SnapPathTrie` consumes the path.
- **Test placement**: keccak vectors + reset-after-abort + concurrency in `crypto` (`testCrypto`); StackTrie root replay extends `StackTrieSpec` in node (`testMPT`); A/B SNAP replay + ethereum/tests at `testEthereum`/comprehensive.
- **Benchmark**: existing `BenchmarkTest`-tagged ScalaTest idiom + `ThreadMXBean.getThreadAllocatedBytes` (no `sbt-jmh`); baseline captured from the **parent-commit worktree** first; perf is report-and-record, not a CI gate; run **offline only**, never on the live node.
- **bcprov 1.84 confirmation**: a one-line decompile/`javap` of the exact pinned 1.84 `KeccakDigest` to confirm reset/init/squeeze semantics match the inspected bytecode — a pre-merge task, not a blocker.

## Complexity Tracking

> No Constitution violations. No entries.
