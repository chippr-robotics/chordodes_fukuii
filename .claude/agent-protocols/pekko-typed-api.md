# Pekko Typed API Protocol

Preferred patterns for Pekko Typed actors in the fukuii codebase. Applied when
writing new actors, migrating Classic actors (LOOM), and reviewing existing Typed
code (PRISM). Each preference has a grep pattern for regression detection.

Used by: LOOM (primary), PRISM, MITHRIL
Referenced by: loom.md, prism.md

---

## Migration state context

The codebase is mid-migration from Classic to Typed. During migration, Classic
adapters (`.toClassic`, `PropsAdapter`, `Behavior[Any]`) are intentional scaffolding —
do not flag them as violations until CAPSTONE. Post-CAPSTONE, all of the below
apply universally.

---

## P1 — `Behaviors.withTimers` over raw scheduler

**Status:** Enforced in all migrated actors.

```bash
grep -rn "context\.system\.scheduler\|system\.scheduler" src/main/ --include="*.scala" \
  | grep -v "//\|test\|Classic\|\.toClassic"
# Target: 0 hits in Typed actors
```

**Prefer:**
```scala
Behaviors.withTimers { timers =>
  timers.startTimerWithFixedDelay("key", Tick, 30.seconds)
  timers.startSingleTimer("key", CheckCompletion, 100.millis)
  // timers cancel automatically on behavior stop
}
```

**Avoid:**
```scala
val task = context.system.scheduler.scheduleAtFixedRate(...)  // manual cancellation required
task.cancel()  // in postStop — easy to forget
```

---

## P2 — `PostStop` signal over `preStop` lifecycle hook

**Status:** Enforced in migrated actors.

```bash
grep -rn "override def preStop\|override def postStop" src/main/ --include="*.scala" \
  | grep -v "//\|extends Actor"  # Classic actors legitimately use these
```

**Prefer:**
```scala
Behaviors.receiveMessage[Command] { ... }
  .receiveSignal {
    case (ctx, PostStop) =>
      cleanup()
      Behaviors.same
  }
```

---

## P3 — Explicit `replyTo` over `sender()`

**Status:** Enforced in migrated actors. Zero tolerance in new Typed code.

```bash
grep -rn "sender()" src/main/ --include="*.scala" | grep -v "extends Actor\|//\|test"
# Target: 0 hits in Typed actors
```

**Pattern:** Request carries its own reply address.
```scala
case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
// Handler:
case GetStatus(replyTo) => replyTo ! StatusResponse(currentStatus)
```

---

## P4 — Named child actors

**Status:** Recommended for new code.

```scala
// ✅ Named — visible in logs, dead letters, actor hierarchy
ctx.spawn(WorkerActor(config), s"worker-${task.id}")

// ❌ Anonymous — invisible in diagnostics
ctx.spawn(WorkerActor(config), "")
```

Name format: `<role>-<discriminator>` where discriminator is stable (task ID, address hash, index).
Do not use random UUIDs — logs become useless.

---

## P5 — AskPattern for request-response across Classic boundary

When a Classic actor needs a response from a Typed actor:

```scala
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
implicit val timeout: Timeout = 5.seconds

val result: Future[Response] =
  typedRef.ask(replyTo => TypedCommand(replyTo))(timeout, system.scheduler)
```

When a Typed actor needs a response from a Classic actor (avoid if possible — prefer migrating the Classic side):
```scala
context.ask(classicRef.toTyped[ClassicCommand])(replyTo => ClassicCommand(replyTo)) {
  case Success(r) => AdaptedResponse(r)
  case Failure(e) => RequestFailed(e)
}
```

---

## P6 — Two-behavior pattern for state machines

Classic `context.become` → return next behavior from message handler.

```scala
def receive(): Behavior[Command] =
  Behaviors.receiveMessage {
    case Start => working()     // transition by returning new behavior
    case other => Behaviors.unhandled
  }

def working(): Behavior[Command] =
  Behaviors.receiveMessage {
    case Complete => receive()  // back to initial
    case other => Behaviors.unhandled
  }
```

PostStop signal attaches to each behavior independently if cleanup differs per state.

---

## P7 — Non-sealed Command ADT when spanning files

When Command cases are defined in a shared `Messages.scala` across package boundaries,
Scala 3 cannot seal the trait. This is by design in the S3 coordinator group.

```scala
// In coordinator companion:
trait Command  // non-sealed — intentional, multi-file constraint

// In Messages.scala (different package):
case class GetProgress(replyTo: ActorRef[ProgressReport]) extends CoordinatorName.Command
```

Document with a comment: `// non-sealed: cases in Messages.scala span package boundary`
Post-CAPSTONE, consolidate into companion objects and seal (Part 7a).

---

## P8 — `Behaviors.setup` for initialization side effects

```scala
// ✅ Side effects in setup, not in apply()
def apply(config: Config): Behavior[Command] =
  Behaviors.setup { ctx =>
    ctx.log.info("Starting with config {}", config)
    val resource = openResource(config)
    new ActorImpl(ctx, resource).receive()
  }

// ❌ Side effects at object construction time
object MyActor {
  val resource = openResource(globalConfig)  // runs at class load, untestable
```

---

## P9 — `context.watchWith` over `context.watch` + `Terminated`

```scala
// ✅ Typed — message carries identity
ctx.watchWith(child, ChildStopped(child.path.name))

// ❌ Classic-style in Typed code
ctx.watch(child)  // then: case Terminated(ref) => ...  (weaker typing)
```

---

## P10 — Internal Commands for Future → actor-state writes

When a Future needs to communicate a decision that mutates actor state, create
dedicated internal Commands to marshal the result back to the actor thread. Do
not share mutable state via `@volatile` or other synchronization — that is the
Classic workaround, not the Typed pattern.

**Pattern (from TNHC `verificationBFSRunning`):**

```scala
// ❌ Classic workaround — @volatile on shared actor state
@volatile var verificationRunning: Boolean = false  // race with Future writes

// ✅ Typed — Future sends a Command, actor writes state on-thread
private case object RestartResumeVerification extends Command
private case object RestartFullRebuild        extends Command

// Before launching Future, capture selfRef:
val selfRef = context.self
crashRecoveryFuture.onComplete {
  case Success(canResume) =>
    if (canResume) selfRef ! RestartResumeVerification
    else           selfRef ! RestartFullRebuild
  case Failure(_) => selfRef ! RestartFullRebuild
}(recoveryEc)

// In behavior — state written only on actor thread:
case RestartResumeVerification =>
  verificationRunning = true   // safe: actor thread, plain var
  resumeVerification()
  Behaviors.same
```

**Pre-migration checklist addition:** Before migrating any Classic actor, grep for
`@volatile` fields. Each site requires internal Command pairs in the Typed version —
one per logical decision the Future can make.

```bash
grep -n "@volatile" src/main/scala/com/chipprbots/ethereum/...ActorFile.scala
```

**CAPSTONE sweep:**
```bash
grep -rn "@volatile" src/main/ --include="*.scala" | grep -v "extends Actor\b"
# Target: 0 hits in Typed actors post-migration
```

---

## P11 — `asyncLog` (plain SLF4J) for off-thread code

`context.log` is only safe on the actor thread. Calling it from inside a Future,
BFS walk, or worker closure contaminates MDC, can produce interleaved output, and
has undefined thread-safety guarantees under Pekko's internal implementation.

**Pattern (from TNHC BFS walk + restart Future):**

```scala
// Private plain SLF4J logger — safe from any thread
private val asyncLog = LoggerFactory.getLogger(getClass)

// ✅ In Future / BFS body / worker thread:
asyncLog.info("Heal BFS step: node={} depth={}", nodeHash.short, depth)
asyncLog.warn("Restart decision: reason={} canResume={}", reason, canResume)

// ✅ In actor-thread message handlers:
ctx.log.info("Verification started: pivot={} queueDepth={}", pivot, queue.size)
```

**Rule:** Any code that executes inside a `Future`, `onComplete`, `map`, or BFS/traversal
callback passed to a non-actor `ExecutionContext` must use `asyncLog`, not `context.log`.
Actor-thread message handlers always use `ctx.log`.

**Actionable sweep — may catch existing correctness issues:**
```bash
# context.log used inside Future closures — potential MDC contamination
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|\.flatMap\|\.recover"
# Review each hit — if it's inside a closure body, replace with asyncLog
```

---

## Anti-patterns — flag in PRISM review

| Pattern | Problem | Correct |
|---------|---------|---------|
| `sender()` in Typed actor | Not available — compile error | Explicit `replyTo` in Command |
| `Behavior[Any]` in permanent code | Defeats type safety | Typed Command ADT |
| `.toClassic` outside migration scaffolding | Classic leak post-CAPSTONE | Full migration |
| `context.system.scheduler` in Typed actor | Manual lifecycle | `withTimers` |
| Unnamed child actors | Invisible in diagnostics | Named with stable discriminator |
| `preStop` / `postStop` override in Typed | Classic API | `PostStop` signal |

---

## CAPSTONE cleanup targets

After all actors are Typed, sweep for:
```bash
grep -rn "\.toClassic\b" src/main/ --include="*.scala"        # remove adapters
grep -rn "PropsAdapter\b" src/main/ --include="*.scala"       # remove adapters
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"      # narrow to real type
grep -rn "ActorSystem\b" src/main/ --include="*.scala"        # flip to ActorSystem[Nothing]
grep -rn "@volatile" src/main/ --include="*.scala"            # should be 0 in Typed actors (P10)
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|flatMap\|\.recover"  # asyncLog violations (P11)
```
