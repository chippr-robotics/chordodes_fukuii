# Quickstart / Validation Guide: Hot-Path Allocation Reduction (Spec 007)

How to validate the feature end-to-end. **Parity is the hard gate; the benchmark is report-and-record.** Run benchmarks **only when the barad-dûr node and all builds are idle** — this host over-subscribes easily and a concurrent benchmark can freeze the desktop or disrupt the live sync.

## Prerequisites

- Repo on branch `007-hotpath-alloc-reduction`, JDK 25, sbt.
- barad-dûr node **idle** (or stopped) before any `sbt` invocation (per the host constraint).
- For the baseline step: a clean worktree of the **parent commit** (no changes applied yet).

## 1. Parity — the consensus gate (MUST be zero-diff)

Run in increasing strength. Each must pass before merge.

```bash
sbt testCrypto        # Keccak256Spec: golden vectors, all overloads vs oracle,
                      # reset-after-abort, concurrency (no Thread.sleep)
sbt testMPT           # StackTrieSpec: StackTrie root == MerklePatriciaTrie oracle,
                      # per-node kec256(blob) == hash, ProofTrieInserter path
sbt testRLP           # RLP encode byte-identity (P3)
sbt testEthereum      # ethereum/tests post-state roots (ETC-filtered) — file-backed golden
sbt testComprehensive # full ethereum/tests at the ~3h tier (the authoritative SC-004 gate)
```

**Expected**: every suite green, zero root/hash diffs. Record which tier ran with `VERIFY: ran <cmd> — PASS | FAIL | DID NOT RUN`. Do NOT treat `testEssential`/`testStandard` alone as sufficient for the consensus gate — `testComprehensive` (ethereum/tests) is the authoritative byte-for-byte check (SC-004).

**A/B replay** (the dedicated parity check + the `deferred=false` measurement vehicle): compute state roots over a fixed, seed-pinned SNAP account+storage corpus with the parent-commit binary and the changed binary; assert zero diffs. (Implemented as a `BenchmarkTest`-tagged spec so it runs offline on an in-memory data source, never against peers/RocksDB.)

## 2. forge (ETC) AND beacon (ETH/Sepolia) sign-off (mandatory before merge)

keccak/merkleization are consensus-critical. **The `kec256` helper is CHAIN-SHARED** — consumed by ETC PoW (Ethash/ommers) AND ETH/Sepolia PoS (`EngineApiService` payload-id/block-hash) — so BOTH `forge` (ETC) AND `beacon` (ETH/Sepolia) must review the diff before merge (mirrors plan.md Constitution Check + tasks T012b/T025). Before merge, `forge` confirms byte-for-byte parity for ETC against core-geth-aligned reference behavior (SC-004), and `beacon` confirms parity for ETH/Sepolia — the ETH/Sepolia post-state-root parity run is mandatory too, not just the ETC-filtered one. Include `kec256PoW` (PoW/Ethash path) explicitly in the `forge` review.

## 3. Benchmark — evidence the win (report-and-record, not a CI gate)

First capture the **baseline** on the parent commit, then re-run post-change.

```bash
# BASELINE (parent commit worktree, node idle):
sbt "benchmark:testOnly *HotPathAllocBenchmark*"   # records getThreadAllocatedBytes + nanoTime + -Xlog:gc

# POST-CHANGE (this branch):
sbt "benchmark:testOnly *HotPathAllocBenchmark*"
```

Use the same RNG seed, same N (proposal: `kec256` loop N=5M ~136-byte inputs; StackTrie loop 100k and 1M sorted 32-byte leaves), same production JVM flags (`src/universal/conf/application.ini`), warmup ≥3 discarded + ≥5 measured iterations, report median + min/max.

**Expected outcomes** (record in the PR):
- **SC-001**: per-hash digest allocation ≈ 0 bytes steady-state (only the 32-byte output remains), vs ~500 B/call baseline.
- **SC-002**: measurable drop in bytes-allocated and GC pause time on the fixed replay.
- **SC-003**: honest **low single-digit to low-double-digit %** throughput improvement — allocation/GC relief, not a step-change.

## 4. Quality gates (before PR)

```bash
sbt pp            # compile-all → scalafmt → quick + integration tests
sbt formatCheck   # scalafmt + scalafix clean
```

CI must be green (formatCheck, compile-all, testEssential, testStandard + coverage ≥70%). The benchmark is NOT a CI gate.

## Validation checklist

- [ ] `testCrypto` green incl. reset-after-abort + concurrency (FR-002/FR-003/FR-007)
- [ ] `testMPT` green incl. per-node hash equality (FR-004, INV-7)
- [ ] `testComprehensive` (ethereum/tests) green — zero post-state-root diffs (FR-005/SC-004)
- [ ] A/B replay zero-diff (dedicated parity check)
- [ ] BOTH `forge` (ETC, incl. `kec256PoW`) AND `beacon` (ETH/Sepolia, incl. the mandatory ETH/Sepolia post-state-root parity run) byte-for-byte sign-off recorded — `kec256` is chain-shared (mirrors plan.md Constitution Check + tasks T012b/T025)
- [ ] Baseline + post-change benchmark recorded; SC-001 confirmed; SC-002/SC-003 deltas reported
- [ ] `sbt pp` + `formatCheck` clean; CI green
- [ ] P3 items: each shipped only with proven parity + measured allocation drop (FR-010), else deferred
