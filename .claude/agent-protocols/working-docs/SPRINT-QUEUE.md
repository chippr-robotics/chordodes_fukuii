# Fukuii Modernization — Sprint Queue (Active)

**Branch:** `scala3-cleanup-june`
**Sprint history:** Wave 2 + Wave 3 (all groups complete) → `completed/SPRINT-QUEUE.md`

---

## Post-CAPSTONE Codebase Audit Fixes

Active findings tracked in `working-docs/CODEBASE-AUDIT.md`.

| Commit | What |
|--------|------|
| `660451a19` | C1/C3/W8 — FastSync null-init session state, dead if/else, sys.exit removal |
| `a5132aa80` | C2 — SyncController classic scheduler → ctx.scheduleOnce |
| `8a65bbdb7` | W3/W10/INFO-5/INFO-6 — SyncController HandshakedPeers guard, BehaviorSignalInterceptor, BigInt Try |
| `8bd4ed3f1` | W9 — SyncController bootstrapGeneration: Long reused for child name versioning |
| `a73ce7922` | INFO-2/4/11 — SyncController scaladoc fixes |
| `13aa7585e` | W5 by-design comment + W11 dead ETH69.BlockRangeUpdate inbound arms deleted (NPMA) |
| `6c8a07725` | S3-C — JwtAuthenticator: return Left → if/else expression |
| `89d6aadb2` | S3-A/F — EngineApiService pendingTransactionsManager → Option; EngineApiController var → val |
| `8ef187dfb` | S3-G — JsonRpcIpcServer: serverSocket → Option[ServerSocket] |
| `c37154287` | WormToBrainBar shared utility extracted (branding) |
| `cae6e0ab5` | SyncProgressMonitor: wormChasesBrainBar deleted; WormToBrainBar integrated |
| `31c51a7cc` | RegularSync: WormToBrainBar wired in after PrintStatusTick |
| `c8a1ddbfc` | FastSync: WormToBrainBar wired in (emoji → ASCII → restored via shared utility) |
| `3c6be4512` | INFO-3/INFO-12 — FastSync scaladoc Behavior[Any]→[Command]; emoji handled via WormToBrainBar |
| `12c23cf8a` | 8a-retro batch 3 part 1 — 14 specs to ActorTestKit (4 SNAP workers, 5 sync, 3 network, IORuntimeInit) |
| `a719520db` | 8a-retro batch 3 part 2 — 11 specs + NPMAFake `GetHandshakedPeers`→`GetHandshakedPeersCmd` fix |

**Audit sprint complete.** All 12 prompts (1–10 + 6b + 7b) resolved. Remaining items tracked in
`CHASE-QUEUE.md` (P1 W17 seal, P2 SyncControllerSpec), `PENDING.md` (P1 FORGE verdict, P2 E165 fix),
and `CODEBASE-AUDIT.md` (P1 S5 EYE sweep).

---

### §8a-retro batch 4 — coordinator/heal spec ActorTestKit migration

| 5eae34c21 | 8a-retro batch 4 — 14 coordinator/heal specs + HealingTrieFixtures migrated to ActorTestKit (135 tests, 0 failures) |
|------|------|

Root cause fixed: `HealingTrieFixtures.coordinatorProps` (PropsAdapter→Props) crashed the typed
`ActorTestKitGuardian` on child stop. Now `spawnCoordinator(...)(implicit ActorTestKit)` spawns the
`Behavior[Command]` natively. The four S3 coordinator specs + ten heal specs moved off
`TestKit(ActorSystem)`+`ImplicitSender` to `ScalaTestWithActorTestKit(ConfigFactory.load())`
(explicit config load is required — the typed kit defaults to `application-test.conf`, which omits
the `sync-dispatcher` the coordinators' worker children spawn on).

Note: `TestProbe()` call sites are still **classic/unnarrowed** (kept via `system.classicSystem`),
so this batch does NOT reduce the E165 unnarrowed-`TestProbe` count — it only removes the spawn
crash that blocked migration. `TestProbe[M]` narrowing is a separate follow-up.

---

## Next Sprint Targets

When the clearout prompts above are done, the branch is ready for:

1. **testEssential gate** — run `./local/scripts/fukuii-test` to confirm 3,621/0 baseline holds
   after all clearout commits land
2. **PR open** — `white-b0x:scala3-cleanup-june` → `chippr-robotics:staging`
3. **DEFERRED-BACKLOG unblocked items** — see `working-docs/DEFERRED-BACKLOG.md` Clearout Prompts
   (3c isInstanceOf, 3d enum candidates, 3e console→logging, 8f dead code audit, 8g braceless scalafmt)
   — 8d-J1/J2/J3 jsonrpc IO boundary fixes ✅ DONE 2026-06-24; §8l-R1 VM tracer research ✅ DONE 2026-06-24 (§8l-I implementation open)

### Classic Bridge Elimination Track (pre-CAPSTONE, sequential)

Source: `§8k-R1` audit complete 2026-06-23 — `.local/docs/classic-interop-audit.md`. ~130 prod bridge sites, ~126 eliminatable. Run in order; each sprint gates the next.

| Sprint | Work | Agent | Sites eliminated | Gate |
|--------|------|-------|-----------------|------|
| ~~**§8k-A**~~ | ~~All 4 SNAP worker `coordinator: ActorRef` → Typed (AccountRange/ByteCode/StorageRange/TrieNodeHealing)~~ | ~~MITHRIL~~ | ~~12 prod + 2 test~~ | ✅ DONE `791c0211f` — docs `4c333b178` |
| ~~**§8k-C**~~ | ~~SNAP coordinator `snapSyncController: ActorRef` → Typed (4 coordinators + SSC spawn sites)~~ | ~~MITHRIL~~ | ~~7~~ | ✅ DONE `b4453d117` — docs `9b34401d5` |
| ~~**§8k-D**~~ | ~~`PeerEventBusActor.SubscribeCmd(subscriber: ActorRef)` → Typed (Clusters A+M, 9 files)~~ | ~~HERALD+MITHRIL~~ | ~~27~~ | ✅ DONE `93bcedb12` — docs `8748d6e35` |
| ~~**§8k-E**~~ | ~~`NPMA.GetHandshakedPeersCmd(replyTo: ActorRef)` → Typed (Cluster B, 7 files)~~ | ~~MITHRIL~~ | ~~15~~ | ✅ DONE `c42316b39` — docs `7bd607a87` |
| ~~**§8k-F**~~ | ~~RegularSync Classic→Typed migration (full LOOM; Clusters C/D/N)~~ | ~~LOOM~~ | ~~15~~ | ✅ DONE `b24515637` — docs `806202cb9` |
| ~~**§8k-G**~~ | ~~OQ-5 kill: jsonrpc callers → Typed ask; delete AkkaTaskOps (Clusters C+E+L)~~ | ~~CONDUIT+MITHRIL~~ | ~~74~~ | ✅ COMMITTED `2ef2b6637` — testEssential PENDING — docs clearout PENDING |
| **§8k-G2** | Cluster E immediate cohort: FastSync + NPMA spawn-site `.toClassic` (constructor param lift) | PRISM+MITHRIL | ~4 | §8k-G committed |
| ~~**§8k-H**~~ | ~~PeerActor `watchWith` — remove `context.toClassic.parent` sends (Clusters G+H)~~ | ~~MITHRIL~~ | ~~8~~ | ✅ DONE `222623960` — docs `53edef1b9` |
| ~~**§8k-I**~~ | ~~NodeBuilder 3 Classic bridge actors → callers use Typed ask (Cluster J)~~ | ~~MITHRIL~~ | ~~21~~ | ✅ DONE `4613e398f` — docs `b5f47116c` |
| **§8k-B** | Post-CAPSTONE: verify TCP floor (4 bridges), delete adapter imports | PRISM | — | §8k-I + CAPSTONE |

---

## ETH69/ETH70 PoW Safety Track (security — run independently of Classic Bridge track)

**Source:** Wire Protocol audit 2026-06-23 — `.local/Wire-Protocol-Modernization/`
**Risk level:** P0 items must ship before ETH69 pivot election is used on ETC mainnet.
**Branch:** post-PR-#1333 merge. These items are independent of the Classic Bridge track.

| Sprint | Work | Agent | Severity | Gate |
|--------|------|-------|----------|------|
| ~~**§ETH69-A**~~ | ~~`collectVoters`: add TD consensus gate — filter peer pool by `chainWeight.totalDifficulty >= ourBestTD × 0.8`~~ | ~~FORGE~~ | ~~**P0 CRITICAL**~~ | ✅ DONE `12d2ede7e` |
| ~~**§ETH69-B**~~ | ~~`PivotBlockSelector`: parent-chain backlink validation (N=20 headers) before SNAP bootstrap~~ | ~~FORGE~~ | ~~**P0 HIGH**~~ | ✅ DONE `0092e5f03` |
| ~~**§ETH69-F**~~ | ~~`PeerActor:551` + `BlockFetcher:486`: `ETH69.BlockRangeUpdate` → `ETHPackets.BlockRangeUpdate`; fix `BlockFetcherSpec:298-305`~~ | ~~BEACON~~ | ~~**P1 HIGH**~~ | ✅ DONE `931c615dd` (Part 14 §ETH-BRU) — PeerActor:551 + BlockFetcher:486 fixed; see network/peers.md |

P1/P2 hardening items → `DEFERRED-BACKLOG.md Part 16` (§ETH69-C/D/E)


#### §ETH69-F — BEACON: BlockRangeUpdate type mismatch — dead protocol-breach disconnect (G6)

**Agent:** BEACON
**Risk:** HIGH (ETH/Sepolia path) — malformed BRU from ETH69 peer not rejected; chain-tip follow silently dropped
**Gate:** None — runs parallel with §ETH69-B
**Source:** CHASE-QUEUE.md Part 13 findings; confirmed in audit 2026-06-23

**Background:**
The inbound `BlockRangeUpdate` decoder emits `ETHPackets.BlockRangeUpdate`
(`MessageDecoders.scala:234`), but two production handlers match on the **wrong type**:
- `PeerActor.scala:551`: `case bru: ETH69.BlockRangeUpdate` → BreachOfProtocol disconnect
  arm is dead. Malformed BRU from an ETH/69 peer no longer triggers a disconnect.
- `BlockFetcher.scala:486`: `case AdaptedMessageFromEventBus(msg: ETH69.BlockRangeUpdate, _)` →
  chain-tip follow (`withPossibleNewTopAt`) is dead. On ETH/Sepolia sync, peer-pushed chain-tip
  advances via BRU are silently dropped; head-following degrades to periodic re-probe only.
- `BlockFetcherSpec.scala:298-305`: test constructs `ETH69.BlockRangeUpdate`, matches the buggy
  arm, passes — masks the gap with a false-positive.

**Steps:**
1. **Read** `PeerActor.scala` lines 540-570 — locate the BRU match arm, understand context
   (what triggers BreachOfProtocol disconnect, what happens after the dead arm is reached).
2. **Read** `BlockFetcher.scala` lines 475-500 — locate the BRU match arm in
   `AdaptedMessageFromEventBus`, understand `withPossibleNewTopAt` call and what it needs.
3. **Read** `BlockFetcherSpec.scala` lines 290-315 — understand the failing test pattern.
4. **Fix `PeerActor.scala:551`:**
   ```scala
   // Before:
   case bru: ETH69.BlockRangeUpdate => ...
   // After:
   case bru: ETHPackets.BlockRangeUpdate => ...
   ```
5. **Fix `BlockFetcher.scala:486`:**
   ```scala
   // Before:
   case AdaptedMessageFromEventBus(msg: ETH69.BlockRangeUpdate, _) => ...
   // After:
   case AdaptedMessageFromEventBus(msg: ETHPackets.BlockRangeUpdate, _) => ...
   ```
6. **Fix `BlockFetcherSpec.scala:298-305`:** Rebuild test to feed decoder output type
   (`ETHPackets.BlockRangeUpdate`) so it exercises the real inbound path.
   ```scala
   // Before: ETH69.BlockRangeUpdate(...)
   // After: ETHPackets.BlockRangeUpdate(...)
   ```
7. `sbt compile-all` after each file. `sbt "testOnly *BlockFetcher*"` after spec fix.

**Verify:**
```bash
grep -rn "ETH69\.BlockRangeUpdate" \
  src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockFetcher.scala
# Expected: 0 matches (all changed to ETHPackets.BlockRangeUpdate)

sbt "testOnly *BlockFetcher*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../network/PeerActor.scala src/main/scala/.../sync/regular/BlockFetcher.scala src/test/.../BlockFetcherSpec.scala`
3. `git commit -m "fix(p2p): ETH69 BlockRangeUpdate type mismatch — restore BreachOfProtocol disconnect and chain-tip follow (G6)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-f): clearout — $SHA"`
5. **DELETE §ETH69-F**
