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
   — 8d-J1/J2/J3 jsonrpc IO boundary fixes ✅ DONE 2026-06-24; §8l-R1 VM tracer research ✅ DONE 2026-06-24; **§7c Supervision ✅ DONE 2026-06-27**
   — **Next: §8b Opaque types H3–H8** (Difficulty → TotalDifficulty → GasAmount → GasPrice → BlockNumber → ChainId); gate met (H2 done)

### §7c Supervision Sprint — COMPLETE 2026-06-27

Pekko supervision hierarchy established across all 49 actors (zero prior `Behaviors.supervise` wrappers).

| Commit | What |
|--------|------|
| `d28a803f7` | P0 — `alert-wrapper-protocol.md` new protocol doc |
| `d3399f562` | D — 6 STOP-AND-ALERT actors via `CriticalActorAlerter` (watchWith + parent stops on failure) |
| `429b8678b` | A — 10 Group-A infrastructure actors: ServerActor backoff 2s/60s, PeerActor backoff 1s/30s, etc. |
| `fbce2cc28` | B — SNAP workers `restart.withLimit(5,1m)`; 4 coordinators backoff 1s/10s; 11 sync-support actors |
| `a0f7fcb40` | C — BlockFetcher ghost-child comment (RF-2 Option A); BlockImporter restartWithBackoff 1s/30s/max=3 (E1 verdict: both chains idempotent) |
| `b1aefaaba` | E3 — SyncStateSchedulerActor restartWithBackoff(5s,60s,0.3,max=2) replacing unbounded restart |

Merge: `--no-ff` from `wt/7c-sprint`. 16 files, +1002/-492 lines. New file: `CriticalActorAlerter.scala`.

---

### Classic Bridge Elimination Track — COMPLETE

**§8k-R1 audit** 2026-06-23 → **§8k-B TCP floor confirmed** 2026-06-25. All Clusters A/C/D/E/F/G/H/I/B done (~130 prod bridge sites eliminated). TCP floor: 5 permanent `.toClassic` bridges in `ServerActor` + `RLPxConnectionHandler` + `PeerEventBus`.
Detail: `completed/DEFERRED-BACKLOG.md §8k`.
