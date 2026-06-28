# Consensus Change Protocol

Any change touching consensus-critical code requires specialist review before
implementation. This protocol prevents behavior changes hidden inside "cleanup"
or migration commits from silently altering chain state, wire behavior, or
cryptographic correctness.

Used by: ALL agents (hard stop — non-negotiable)
Referenced by: `fukuii/CLAUDE.md`, loom.md, wraith.md, mithril.md

---

## What counts as consensus-critical

**ETC/Mordor (route to FORGE):**
- `consensus/` — fork dispatch, block validation, reward calculation
- `vm/` — EVM opcode execution, gas computation
- `crypto/` — hashing, signing, key recovery
- `domain/` — Block, BlockHeader, Transaction, Account types
- `network/p2p/messages/` — RLP wire encoding/decoding
- `db/storage/` — state trie, receipt storage, block persistence
- Anything touching: ECIP fork blocks, ECIP-1017 rewards, Ethash, chain ID 61/63

**ETH/Sepolia (route to BEACON):**
- Same file paths as above but for ETH chain paths
- PoS consensus, timestamp fork dispatch, EIP execution
- Withdrawals, blob transactions (EIP-4844), execution payload encoding
- Chain ID 1/11155111, Osaka opcodes, `forTimestamp()`

**P2P / wire protocol (route to HERALD):**
- `network/rlpx/` — RLPx handshake, Snappy, framing
- `network/p2p/` — devp2p protocol, peer management
- ETH68/69/70/71 message types, ForkId computation
- SNAP protocol messages and encoding
- DNS discovery, ENR records

**Storage layer (route to VAULT):**
- `db/` — RocksDB config, WAL, batch writes, LRU cache
- EphemDataSource, DataSource contracts
- State trie reads/writes that aren't purely domain-level

---

## The hard stop rule

**Before editing any file in the paths above:**

1. STOP — do not write a single line of implementation
2. Identify which specialist applies (FORGE / BEACON / HERALD / VAULT)
3. Produce an impact analysis: what changes, what invariants must hold, what tests cover it
4. Get specialist review or hand off entirely

**This applies even for "obvious" changes:**
- Renaming a variable in `consensus/` → FORGE review
- Reformatting a method in `vm/` → FORGE review  
- Adding a log line in `network/p2p/messages/` → HERALD review
- "It's just a cleanup" is not an exception

---

## Routing table

| Symptom / Change | Agent |
|-----------------|-------|
| ETC fork activation, block rewards, mining | FORGE |
| ETC opcode / gas / EVM execution | FORGE |
| ETH timestamp forks, Osaka EIPs, PoS | BEACON |
| ETH blob transactions, withdrawals | BEACON |
| devp2p handshake, ETH wire messages | HERALD |
| SNAP protocol, ETH68/69/70/71 | HERALD |
| RocksDB, WAL, state persistence | VAULT |
| RLP encoding of domain types | FORGE + HERALD jointly |
| Both ETC and ETH affected | FORGE first, then BEACON |

---

## What agents CAN do without specialist review

In consensus-adjacent files (not consensus-critical):
- Read and analyze — always safe
- Add logging with no logic change — flag it but proceed
- Fix `log.warning` → `log.warn` — safe (API rename, no logic)
- Fix unused imports — safe

If uncertain: treat it as consensus-critical and route to specialist. The cost
of an unnecessary review is low. The cost of a silent consensus bug is a chain fork.

---

## Inline cleanup in consensus files

The inline-cleanup protocol (`inline-cleanup.md`) applies EVERYWHERE except
consensus-critical paths. In those paths: flag patterns for specialist review,
do not fix opportunistically. Write the flag in the continuation file or
surface it to the user.

---

## Post-implementation caller verification

After any consensus-boundary change, before closing the task, grep for callers
of the changed types in non-consensus files:

```bash
grep -rn "ChangedType\|ChangedMessage\|newMethodName" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|domain/\|network/p2p/messages/"
```

For each caller not in scope for this task:
- If the caller uses Classic bridge patterns (`.toClassic`, `ctx.toClassic.sender()`) against the
  updated API → add a CHASE-QUEUE entry (`type: CLASSIC`) so the bridge is not forgotten
- If the caller has a stale method signature after the change → surface to user for triage

Consensus changes often require coordinated updates in the surrounding non-consensus call sites.
Skipping this step leaves Classic bridge debt that compiles fine but accumulates silently.
