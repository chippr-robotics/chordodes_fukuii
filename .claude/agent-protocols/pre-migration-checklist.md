# Pre-Migration Checklist (LOOM Pre-Flight)

Run this checklist before touching any file in a Pekko Classic→Typed migration session.
Findings from this checklist populate the Phase 0 facts block in the LOOM prompt
and inform migration complexity and ordering.

Used by: LOOM (primary), any agent preparing a migration plan
Referenced by: loom.md, `LOOM-S3-ARC-prompt.md`, `SPRINT-QUEUE.md`

---

## Why pre-flight matters

Discovering a 12-sender() actor mid-migration is a sprint blocker. Discovering a `@volatile`
field means the thread model needs analysis. Discovering workers already Typed means
the migration touches their API. Pre-flight prevents mid-session surprises.

Complete all steps below before Phase 1 begins. Write findings into the LOOM session
prompt as a "Pre-flight facts" block.

---

## Checklist

### 1. Line count and behavior count

```bash
# Total lines
wc -l src/main/scala/path/to/ActorName.scala

# Count behaviors (def receive, def finalizing, def idle, etc.)
grep -n "def receive\|def idle\|def working\|def finalizing\|def draining\|context\.become" \
  src/main/scala/path/to/ActorName.scala
```

Record: `N LOC, M behaviors`

---

### 2. `sender()` call sites

```bash
grep -n "sender()" src/main/scala/path/to/ActorName.scala
```

Each `sender()` call requires:
- Identifying the message case that triggers it
- Adding `replyTo: ActorRef[ResponseType]` to the corresponding Command case in Messages.scala
- Replacing `sender() ! Response(...)` with `replyTo ! Response(...)`

Record: count and which message cases.

---

### 3. `return` statements

```bash
# Exact returns in the actor file
grep -n "\breturn\b" src/main/scala/path/to/ActorName.scala

# Broader pattern (catches early returns that start the line with spaces)
grep -n "return\b" src/main/scala/path/to/ActorName.scala | grep -v "//\|\".*return"
```

Record: count. Determines Phase 1 scope.

---

### 4. `log.warning` sites

```bash
grep -n "log\.warning" src/main/scala/path/to/ActorName.scala
```

These are Phase 0.5 inline cleanup targets (→ `log.warn`).
Record: count and line numbers.

---

### 5. Timers and schedulers

```bash
grep -n "scheduler\|schedule\|schedulerOnce\|scheduleAtFixedRate\|scheduleWithFixedDelay\|Cancellable" \
  src/main/scala/path/to/ActorName.scala
```

Each scheduler call becomes `Behaviors.withTimers { timers => timers.startTimerWith... }`.
Record: count and timer keys used.

---

### 6. `context.become` / multiple behaviors

```bash
grep -n "context\.become\|context\.unbecome\|become(" src/main/scala/path/to/ActorName.scala
```

Each `become` call maps to a behavior-returning method in the Typed version.
If `context.unbecome` is used (stack-based), the migration needs careful analysis.

---

### 7. Worker actors spawned by this actor

```bash
# Direct spawning
grep -n "context\.actorOf\|Props(" src/main/scala/path/to/ActorName.scala

# What workers are spawned?
grep -n "context\.actorOf(.*Props(" src/main/scala/path/to/ActorName.scala
```

For each worker:
- Is it already Typed? Check its file for `extends AbstractBehavior` or `Behaviors.`
- If Classic: does this migration require it to be typed first? (dependency ordering)
- If Typed: use `context.spawn(Worker(args), "worker-name")` directly

---

### 8. `@volatile` fields and mutable state

```bash
grep -n "@volatile\|var " src/main/scala/path/to/ActorName.scala
```

- `@volatile` in Classic = cross-thread access. In Typed actors, all state is single-threaded.
  Typed actors must NOT use `@volatile` — it signals a threading misunderstanding.
  Plan to remove `@volatile` and verify state is accessed only within the behavior.
- `var` fields = actor state. Map each to constructor params or local behavior vars.

---

### 9. `ActorLogging` and logging pattern

```bash
grep -n "ActorLogging\|extends.*ActorLogging\|log\." src/main/scala/path/to/ActorName.scala | head -20
```

Migration removes `extends ActorLogging` and replaces `log.` with `ctx.log.`.
SLF4J API differences:
- `log.warning(...)` → `ctx.log.warn(...)` (SLF4J uses warn, not warning)
- `log.error(msg, exception)` → `ctx.log.error(msg, exception)` (same)
- `log.debug(...)` → `ctx.log.debug(...)` (same)

---

### 10. `preStart` / `postStop` lifecycle hooks

```bash
grep -n "override def preStart\|override def postStop" src/main/scala/path/to/ActorName.scala
```

- `preStart` → initialization in `Behaviors.setup { ctx => ... }`
- `postStop` → `receiveSignal { case (ctx, PostStop) => ... }`

---

### 11. `context.watch` / death watch

```bash
grep -n "context\.watch\|context\.unwatch\|Terminated" src/main/scala/path/to/ActorName.scala
```

In Typed: `ctx.watchWith(ref, ChildStopped(ref.path.name))` — eliminates `Terminated` case.

---

### 12. Classic refs that must stay Classic throughout

```bash
# Who calls this actor?
grep -rn "ActorName\b" src/main/ --include="*.scala" | grep -v "//\|ActorNameSpec"

# Does any caller pass this actor an untyped ActorRef?
grep -rn "ActorRef\[_\]\|ActorRef\b" src/main/ --include="*.scala" | grep "ActorName"
```

If callers are not being migrated in this session, the actor needs:
- A Classic-facing adapter (`.toClassic`) on its typed ref
- OR callers updated to use ask-pattern

Record: list of caller files and whether they're in-scope for this session.

---

### 13. Constructor params — spawn-site `.toClassic` audit (MANDATORY)

**This step is the primary cause of post-migration `.toClassic` slippage.** Migrating an actor's
internals without updating its constructor params leaves every Typed caller still writing `.toClassic`
at the spawn site — the bridge survives invisibly because it compiles fine.

```bash
# Find all Classic ActorRef params in this actor's constructor
grep -n "ActorRef\b" src/main/scala/path/to/ActorName.scala \
  | grep -v "typed\.ActorRef\|ActorRef\[" \
  | head -20

# Find all spawn sites in Typed callers
grep -rn "ActorName\b\|ActorName\.apply\|Props(.*ActorName" src/main/ --include="*.scala" \
  | grep -v "//\|Spec\|test"
```

For each Classic `ActorRef` param found:
1. **Update the param** from `ActorRef` to `ActorRef[T]` where T is the specific message type
   the caller should send (or `ActorRef[Any]` if the adapter pattern requires it).
2. **Update all spawn sites**: callers that currently write `someTypedRef.toClassic` or
   `externalAdapter.toClassic` to fill this param can drop `.toClassic` once the type is updated.
3. **If the caller is not in scope for this session**: add a CHASE-QUEUE entry (`type: CLASSIC`)
   flagging the spawn site so it is not forgotten.

**Do not close a LOOM migration without either:**
- Updating all constructor `ActorRef` params to `ActorRef[T]`, OR
- Adding CHASE-QUEUE entries for each param that cannot be updated yet (with the gate condition)

**Consensus-boundary spawn sites:** For each spawn site found, check whether the caller
file is in `consensus/`, `vm/`, `crypto/`, `domain/`, or `network/p2p/messages/`. If yes:
- Do NOT update the param type without FORGE (ETC) or BEACON (ETH) review
- Add a CHASE-QUEUE entry flagged `consensus-boundary — route to FORGE/BEACON before updating`
- The actor's internal Typed migration can proceed, but the spawn-site param update is gated on specialist review

Record: param names, their proposed typed equivalent, and which spawn sites are affected.

---

## Pre-flight facts block format (for LOOM prompt)

```
### Pre-flight facts — ActorName

- LOC: N
- Behaviors: M (receive, finalizing)
- sender() sites: K  → lines [12, 45, 78, ...]
- return statements: J → lines [34, 56, ...]
- log.warning sites: P → lines [...]
- Schedulers: Q timer(s), keys: [...]
- context.become sites: R
- Workers spawned: [WorkerA (Classic→migrate first), WorkerB (already Typed)]
- @volatile fields: S (all to be removed — [field names])
- preStart hook: yes/no
- postStop hook: yes/no
- context.watch: yes/no → replace with watchWith
- Classic callers not in session scope: [CallerA.scala, CallerB.scala]
  → adapter needed: yes/no
```

---

## Red flags — stop and consult

| Finding | Action |
|---------|--------|
| Actor spawns 10+ workers | Map all workers first; may need separate sprint |
| Actor uses `context.system.eventStream` to publish/subscribe | Route to FORGE/HERALD — PSH serialization pre-flight required before migrating |
| Worker is in `consensus/` or `vm/` | FORGE (ETC paths) or BEACON (ETH paths) review before touching it — both if shared |
| Actor is accessed from Java code | Java interop analysis needed before migration |
| Actor uses `akka.remote` / `pekko.remote` | Serialization review (PSH) — route to FORGE |
| `@SerializationProxy` or `readResolve` methods | Serialization review required |
| Actor file is >2,000 LOC with 4+ behaviors | Split into subsession plan before starting |
