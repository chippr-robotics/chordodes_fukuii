# Implementation Plan: Flat-Account Retention & Local State-Root Merkleization for SNAP Completion

**Branch**: `008-flat-account-merkleize` | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-flat-account-merkleize/spec.md`

## Summary

Make a fresh ETC SNAP-from-scratch COMPLETE against snapshot-root-only peers (core-geth) by computing and self-verifying the state root **locally** at finalize, instead of relying on the heal's `GetTrieNodes` path (which provably cannot reconcile an already-formed multi-lineage trie mosaic). The enabling change is to **retain account leaves during download** (write `FlatAccountStorage`, symmetric to the already-written `FlatSlotStorage`) so the complete account set can be streamed in canonical order and merkleized in a single pass; the computed root is then checked byte-for-byte against the canonical pivot header state root before finalize (an additional gate, never a relaxation), and the node fails closed if it cannot produce a verified root.

**Phase 0 resolved the feasibility gate: YELLOW — feasible on Mordor (see `research.md`).** The chosen mechanism is **freeze + targeted re-fetch (Mechanism C)** with `FlatAccountStorage` retention as the streaming substrate:
1. **Retain** account leaves during download — write `FlatAccountStorage` in `AccountRangeCoordinator.handleStoreAccountChunk` (symmetric to the already-written `FlatSlotStorage`).
2. At finalize, **freeze** one servable `R_final = networkBest − offset` and **latch both coordinators to ignore pivot-advance** (the named hard constraint — without it the re-fetch re-tags mid-stream and recreates the mosaic).
3. **Re-fetch** the stale account ranges against `R_final` via `GetAccountRange(R_final)` into `FlatAccountStorage` (full re-fetch ≈ 11 min for 2.71M Mordor accounts at raw range throughput — fits the ~28-min window), and reconcile changed contracts' slots into `FlatSlotStorage` for import coherence.
4. **Merkleize** in one pass: stream `FlatAccountStorage` in ascending-hash order into a single `StackTrie`, taking each account's `storageRoot` **directly off the re-fetched leaf** (RLP field — not re-derived from slots).
5. **Gate**: require `computedRoot == pivotHeader.stateRoot` before finalize (additional check; the existing anchor guard and content-hash gate stay byte-untouched). Mismatch ⇒ fail closed (retry on fresher `R_final`, or the existing handoff).

**RED fallback** remains checkpoint import — relevant only if the peer pool cannot hold a servable `R_final` for the full re-fetch pass (a real risk at ETC-mainnet 86M-account scale; Mordor is GREEN-leaning).

## Technical Context

**Language/Version**: Scala 3.3.8 LTS, JDK 21 (CI also JDK 25)

**Primary Dependencies**: Apache Pekko (classic actors) for the SNAP coordinator pool; RocksDB via the project's `KeyValueStorage`/namespace layer; existing SNAP subsystem (`SNAPSyncController`, `AccountRangeCoordinator`, `StorageRangeCoordinator`, `ByteCodeCoordinator`, `TrieNodeHealingCoordinator`); `StackTrie`/`SnapHashTrie` merkleization; `MerklePatriciaTrie`; `FlatSlotStorage` (written) and `FlatAccountStorage` (exists, currently unwritten); `crypto` (keccak-256)

**Storage**: RocksDB. `FlatSlotStorage` persists `accountHash++slotHash → slotValue` (namespace `'d'`) and supports ordered `seekStorageRange`. `FlatAccountStorage` must persist `accountHash → accountRLP` in its own namespace with ordered ascending-hash enumeration (`seekFrom`) — the symmetric counterpart that this feature wires into the download path

**Testing**: ScalaTest unit (`testEssential`/Tier 1); integration (`IntegrationTest`/Tier 2 `testStandard`); consensus compliance (`testCrypto`, `testMPT`, `testEthereum`/Tier 3 `testComprehensive`); A/B replay (flag off vs on → identical finalized root); deterministic only (no `Thread.sleep`)

**Target Platform**: Linux JVM server. Reference/validation host is CPU-constrained (i5-4430, 4C/4T, 16 GB) — the worst case the design must complete on

**Project Type**: Multi-network EVM client (single SBT project; root `main` module plus `bytes`/`crypto`/`rlp`/`Evm`). This feature touches `main` (sync + db/storage) only

**Performance Goals**: Parity is the hard gate (computed root byte-equal to canonical); throughput is report-and-record. Local merkleization of ~2.71M Mordor accounts must be bounded enough to finish within operational limits (relate to spec-007 hot-path allocation work). Any peer re-fetch needed for reconciliation must fit within one ~28-minute serve window (≈128 blocks × ~13 s) before the frozen root ages out of core-geth's snapshot

**Constraints**: (1) byte-for-byte deterministic ETC state root — NON-NEGOTIABLE; (2) the content-hash store gate (`keccak(node)==hash`) and `finalizeSnapSync` anchor guard (`snapStateRoot==pivotHeader.stateRoot`) stay byte-untouched; the computed-root==header check is additive; (3) fail-closed — never finalize an unverified/absent root, never partial-commit; (4) in-window peer reconciliation; (5) config-gated, default-on for fresh ETC SNAP, togglable for A/B + rollback; (6) no-op on the deferred-merkleization path; zero effect on ETH/Sepolia

**Scale/Scope**: Mordor ~2.71M accounts / ~1.2M storage slots (validation target); ETC mainnet ~86M accounts (must not regress; in-window convergence harder there — characterize, do not necessarily solve mainnet in v1). Scope is the SNAP account-download retention path + the SNAP finalize merkleize/verify/reconcile path

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — This feature computes and finalizes a state root: squarely consensus-critical on the ETC domain. Compliance plan: (a) designed and reviewed by `forge` BEFORE implementation (Phase 0 research is a forge task; implementation + review follow the forge protocol); (b) the computed state root MUST be byte-for-byte the canonical header root — verified by an additive computed-root==header gate plus the unchanged anchor guard; (c) the content-hash store gate is byte-untouched; (d) parity proven by `testCrypto`+`testMPT`+`testEthereum` and an A/B replay (flag off vs on → identical finalized root, equal to the canonical Mordor header root, cross-checked vs core-geth); (e) ETC-only — uses the block-number/`forBlock` PoW path, never `forTimestamp`/PoS; no ETH path touched. **Status: PASS, contingent on forge Phase-0 GREEN + forge implementation sign-off.**
- **III. Test Discipline & Tiered Coverage** — Deterministic tests only (no `Thread.sleep`); statement coverage ≥ 70% on new code; tests at the right tier (unit for retention/merkleize/verify; integration for the finalize flow; consensus suites for parity). A/B replay is the headline parity test. **Status: PASS (tests enumerated in Phase 1 + tasks).**
- **Scala 3 LTS + scalafmt/scalafix; `sbt pp` before PR; CI green to merge** — code will conform; format/compile gates apply. **Status: PASS.**
- **Spec-Driven flow + constitution binding** — this plan follows `/speckit-specify → plan → tasks → implement`; the feasibility gate is honored (RED → stop, fall back to checkpoint). **Status: PASS.**

No constitution violations requiring Complexity Tracking at this stage (the feature reuses existing storage/merkleization abstractions; it adds writes to an already-existing `FlatAccountStorage` and a finalize-time merkleize/verify, not new architectural layers). Re-evaluate after Phase 1 once the reconciliation mechanism is fixed.

## Project Structure

### Documentation (this feature)

```text
specs/008-flat-account-merkleize/
├── plan.md              # This file
├── research.md          # Phase 0 — feasibility + reconciliation-mechanism decision (forge)
├── data-model.md        # Phase 1 — retained-account store + finalize entities/state transitions
├── quickstart.md        # Phase 1 — how to validate (fresh Mordor A/B run)
├── contracts/           # Phase 1 — internal contracts (flat-account store API; finalize merkleize/verify contract)
├── checklists/
│   └── requirements.md  # spec quality checklist (done)
└── tasks.md             # Phase 2 — /speckit-tasks (NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── blockchain/sync/snap/
│   ├── SNAPSyncController.scala                 # finalize: local merkleize + computed-root==header gate + storage reconciliation + fail-closed
│   └── actors/
│       ├── AccountRangeCoordinator.scala        # write FlatAccountStorage during handleStoreAccountChunk (retain leaves)
│       └── StorageRangeCoordinator.scala        # (reference) existing FlatSlotStorage write pattern; possible reconciliation hook
├── db/storage/
│   ├── FlatAccountStorage.scala                 # wire production writes (putAccountsBatch) + ordered seekFrom enumeration
│   └── FlatSlotStorage.scala                    # (reference) the symmetric, already-written counterpart
├── mpt/
│   └── StackTrie.scala                          # (reference) single-pass forward-only builder used for the finalize merkleization
└── resources/conf/base/sync.conf                # config gate (flat-account-write / local-merkleize-finalize, default-on fresh ETC SNAP)

src/test/scala/com/chipprbots/ethereum/
├── blockchain/sync/snap/                        # finalize merkleize/verify/reconcile + fail-closed unit/integration specs
└── db/storage/                                  # FlatAccountStorage write + ordered-enumeration specs
```

**Structure Decision**: Single project, `main` module. The change is localized to the SNAP download path (`AccountRangeCoordinator` retention write), the flat-storage layer (`FlatAccountStorage` production wiring + ordered enumeration), and the SNAP finalize path (`SNAPSyncController` local merkleize + verify + reconcile + fail-closed), plus the config gate. No new module or architectural layer — it makes `FlatAccountStorage` symmetric to `FlatSlotStorage` and adds a finalize-time merkleize/verify step.

## Complexity Tracking

> Phase 0 selected pivot-freeze coordination (Mechanism C). It adds a `finalizing` latch to two coordinators — modest, with in-codebase precedent — not a new architectural layer. No constitution principle is violated; recorded here for transparency.

| Added machinery | Why Needed | Simpler Alternative Rejected Because |
|-----------------|------------|-------------------------------------|
| `finalizing` freeze latch in `AccountRangeCoordinator` + `StorageRangeCoordinator` (ignore pivot-advance during the finalize re-fetch) | Without it the re-fetch against `R_final` is re-tagged mid-stream by an incoming pivot advance, recreating the multi-lineage mosaic the feature eliminates → wrong root → feature is RED | Letting pivot-advance proceed during finalize (status quo) cannot produce a coherent single-root state; delta-only re-fetch (Mechanism B) can't identify the changed set without `GetTrieNodes`; pinning the pivot for the whole download ages it out of the serve window |
| Finalize-time full/targeted re-fetch + single-pass local merkleization in `SNAPSyncController` | The on-disk account state is a multi-lineage mosaic with no coherent root; the only way to a present, verifiable root without `GetTrieNodes` is to re-acquire final-pivot leaves (servable) and merkleize locally | Reconciling via the heal (`GetTrieNodes`) is provably blocked against snapshot-root-only peers; relying on retained leaves alone yields a stale (wrong) root |
