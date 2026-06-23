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

## Next Sprint Targets

When the clearout prompts above are done, the branch is ready for:

1. **testEssential gate** — run `./local/scripts/fukuii-test` to confirm 3,621/0 baseline holds
   after all clearout commits land
2. **PR open** — `white-b0x:scala3-cleanup-june` → `chippr-robotics:staging`
3. **DEFERRED-BACKLOG unblocked items** — see `working-docs/DEFERRED-BACKLOG.md` Clearout Prompts
   (3c isInstanceOf, 3d enum candidates, 3e console→logging, 8f dead code audit, 8g braceless scalafmt)
