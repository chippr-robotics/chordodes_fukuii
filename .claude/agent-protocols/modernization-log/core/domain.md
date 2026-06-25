# core/domain — Domain Types

**Package:** `domain/`
**Gate:** `forge` (ETC block/tx semantics) / `beacon` (ETH execution payload, withdrawals)
**Key files:** `Block.scala`, `Transaction.scala`, `WorldState.scala`, `Account.scala`

---

## §ETH-T1-C: Stateless mempool EIP-3860 correctness fix

#### `89863ac80` — `SignedTransactionWithSender.getStatelessValidTransactions` — timestamp-aware EvmConfig
- **File:** `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`
- **Fix:** ETH chains now derive `latestTimestamp` from `forkTimestamps` and call the 3-arg
  `EvmConfig.forBlock` overload so EIP-3860 initcode word cost is included in the stateless
  intrinsic-gas pre-filter. ETC keeps the 2-arg path unchanged.
- **Test:** `SignedTransactionStatelessFilterSpec` (new, 3 tests) — boundary at gasLimit 69384/69448
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T1-C`; sister fixes `ed4db9df9` (T1-A) / `6f8f74708` (T1-B)

---

## §ETH-T2-A: `BlockHeader.isPostMerge` → `isPoS`, add `isPoW` (2026-06-24)

#### `c470b3dac` — rename block-level PoS predicate to chain-agnostic vocabulary
- **File:** `domain/BlockHeader.scala`
- **What:** `isPostMerge` → `isPoS`; `isPoW = !isPoS` added. `prevRandao` updated to use `isPoS`.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T2-A`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Scope:** All domain/ files
- **Cross-refs:** `INDEX.md` (cross-cutting)
