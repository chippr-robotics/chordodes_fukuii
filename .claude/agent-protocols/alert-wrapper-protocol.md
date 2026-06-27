# Alert-Wrapper Protocol — STOP-AND-ALERT Supervision

Standardises the `STOP-AND-ALERT` supervision pattern for actors where restart
causes state corruption. Instead of adding restart supervision, the parent
(typically `NodeBuilder`) is amended to `watchWith` the critical child and emit
a structured alarm on failure.

---

## When to Use

Apply to actors classified as **STOP-AND-ALERT** in the §7c audit:

| Actor | Reason |
|---|---|
| `PeerEventBusActor` | Restart drops all subscribers silently |
| `PeerManagerActor` | Peer table rebuilt from scratch, connections severed |
| `NetworkPeerManagerActor` | Network state cannot be safely reconstructed |
| `SNAPSyncController` | Restart breaks in-flight SNAP session state |
| `SyncController` | Chain-sync progress lost; may trigger re-org |
| `SubscriptionManager` | Restarts drop all active subscriptions |

Do **not** use for **SAFE-TO-RESTART** actors — those receive
`Behaviors.supervise(...).onFailure(SupervisorStrategy.restartWithBackoff(...))`.

---

## Pattern

### 1. At the spawn site in the parent behavior

```scala
val criticalRef = ctx.spawn(CriticalActor(...), "critical-actor")
ctx.watchWith(criticalRef, CriticalActorFailed("critical-actor"))
```

### 2. Add `CriticalActorFailed` to the parent's Command ADT

```scala
sealed trait Command
// ... existing commands ...
private final case class CriticalActorFailed(name: String) extends Command
```

Make it `private` — it is an internal lifecycle signal, not a public API.

### 3. Handle in the parent's `receive` / `receiveMessage`

```scala
case CriticalActorFailed(name) =>
  log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", name)
  Behaviors.stopped
```

Stopping the parent propagates the failure signal up to the guardian, which
triggers a controlled node shutdown rather than a silent degraded state.

---

## Variant: Guardian with No Command ADT

If the parent is a `Behaviors.setup` block with no explicit `Command` trait
(e.g. a `Behaviors.setup[Any]` guardian), define a private sealed trait inline:

```scala
Behaviors.setup[Any] { ctx =>
  sealed trait GuardianMsg
  private final case class CriticalActorFailed(name: String) extends GuardianMsg

  val child = ctx.spawn(CriticalActor(...), "critical-actor")
  ctx.watchWith(child, CriticalActorFailed("critical-actor"))

  Behaviors.receiveMessage {
    case CriticalActorFailed(name) =>
      log.error("CRITICAL: {} stopped — node restart required", name)
      Behaviors.stopped
    case other =>
      Behaviors.same
  }
}
```

---

## Checklist

- [ ] `ctx.watchWith(ref, CriticalActorFailed(name))` — not bare `ctx.watch`
- [ ] `CriticalActorFailed` added to Command ADT (or inline trait for guardians)
- [ ] Handler calls `Behaviors.stopped` — not `Behaviors.same` or restart
- [ ] Log line includes `"CRITICAL"` keyword so ops alerting can grep for it
- [ ] No `SupervisorStrategy` wrapping the critical child (supervision defeats the intent)
- [ ] `DeathPactException` is not caught anywhere in the parent's error handler

---

## Anti-Patterns to Avoid

| Anti-pattern | Why it's wrong |
|---|---|
| `ctx.watch(ref)` without `watchWith` | Delivers `Terminated` signal, not a typed Command — requires pattern-matching on `Signal` |
| Wrapping critical child with `supervise(...).onFailure(restart)` | Defeats the alert — restarts hide the failure from the parent |
| Catching `DeathPactException` in parent | Silently swallows the critical failure signal |
| Logging at `warn` or `info` | Must be `error` — ops monitors threshold on this level |

---

## Related Protocols

- [`pekko-typed-api.md`](pekko-typed-api.md) — General Pekko Typed migration patterns
- [`pre-migration-checklist.md`](pre-migration-checklist.md) — Pre-flight checks before touching any actor
- [`risk-stratified-commit.md`](risk-stratified-commit.md) — Commit strategy for §7c actors
