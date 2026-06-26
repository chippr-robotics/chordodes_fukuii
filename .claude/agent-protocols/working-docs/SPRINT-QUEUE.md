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
| ~~**§8k-G**~~ | ~~OQ-5 kill: jsonrpc callers → Typed ask; delete AkkaTaskOps (Clusters C+E+L)~~ | ~~CONDUIT+MITHRIL~~ | ~~74~~ | ✅ DONE `2ef2b6637` — testEssential ✅ 3,621/0 — docs `e0cebcd72` |
| ~~**§8k-G2**~~ | ~~Cluster E immediate cohort: FastSync + NPMA spawn-site `.toClassic` (constructor param lift)~~ | ~~PRISM+MITHRIL~~ | ~~4~~ | ✅ ABSORBED by §8k-G3/G4 — Cluster E fully done |
| ~~**§8k-H**~~ | ~~PeerActor `watchWith` — remove `context.toClassic.parent` sends (Clusters G+H)~~ | ~~MITHRIL~~ | ~~8~~ | ✅ DONE `222623960` — docs `53edef1b9` |
| ~~**§8k-I**~~ | ~~NodeBuilder 3 Classic bridge actors → callers use Typed ask (Cluster J)~~ | ~~MITHRIL~~ | ~~21~~ | ✅ DONE `4613e398f` — docs `b5f47116c` |
| ~~**§8k-B**~~ | ~~Post-CAPSTONE: verify TCP floor (7 calls / 5 lines), delete adapter imports~~ | ~~PRISM~~ | ~~—~~ | ✅ DONE `68035cb85` — TCP floor confirmed 2026-06-25 |
