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
CE IO computation, BFS walk, or worker closure violates Pekko's thread-safety contract
on `ActorContext`. The violation is often silent — code compiles, but tests fail
selectively depending on which code paths trigger the log call and under what thread
scheduling. The error manifests as a runtime exception from inside `ActorContext`,
not a compile error.

**S4 migration bug (io-compute thread variant):** `processNodes` was a `private def`
containing `ctx.log.debug(...)`. The method was invoked from within a CE IO computation
running on `io-compute-2`. Tests for `FullResponse` and `PartialResponse` passed because
those paths never hit the `ctx.log` call. Only the `RequestFailed` branch triggered it,
and only then did the actor context access blow up — making the bug appear specific to one
message type when the root cause was the CE thread boundary.

**Rule:** Any code that executes on a non-actor thread must use `asyncLog`. This includes:
- `Future { }` bodies and `.map`, `.flatMap`, `.recover`, `.onComplete` callbacks
- `IO { }` bodies and any Cats Effect `.flatMap`, `.map`, `.evalMap` chain
- BFS / traversal callbacks passed to a non-actor `ExecutionContext`
- Any `private def` that is called from within any of the above (indirect violation)

The indirect case is the most dangerous: a `private def` using `ctx.log` looks fine at its
definition site but becomes a violation the moment any caller invokes it from a CE fiber or
Future callback. Defaulting `private def` helpers that do logging to `asyncLog` avoids this
entirely.

**Pattern (from TNHC BFS + S4 CE IO fix):**

```scala
// Declare alongside ctx — safe from any thread:
private val asyncLog = LoggerFactory.getLogger(getClass)

// ❌ private def with ctx.log — unsafe if called from CE IO or Future:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n =>
    ctx.log.debug("Processing node: {}", n.hash)  // blows up on io-compute thread
  }

// ✅ Use asyncLog in any private def that may run off the actor thread:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n =>
    asyncLog.debug("Processing node: {}", n.hash)  // safe from any thread
  }

// ✅ Actor-thread message handlers: ctx.log is fine here
case StartPhase(pivot) =>
  ctx.log.info("Phase started: pivot={}", pivot)
```

**Sweeps — run during pre-migration checklist and after each migration:**

```bash
# Direct violation: ctx.log inside a CE IO block
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep "ctx\.log\|context\.log"

# Direct violation: ctx.log inside Future/callback closures
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|\.flatMap\|\.recover"

# Indirect risk: private defs in Typed actor files that use ctx.log
# Manual review required — check if any are called from IO / Future chains
grep -rn "private def" src/main/ --include="*.scala" -A20 \
  | grep "ctx\.log\|context\.log"
```

---

## P12 — `mapMaterializedValue` over `preMaterialize` for subscription setup

**Status:** Enforced after CAPSTONE. `preMaterialize()` in streaming subscription setup is forbidden.

**Root cause (CAPSTONE bug, `bc2a7a2fc`):** `Source.fromMaterializer { (mat, _) => val (ref, src) = Source.actorRef(...).preMaterialize()(mat); ... src }` creates a Reactive Streams Publisher/Subscriber async boundary via `Sink.asPublisher(false)`. Under Pekko 1.6.0, elements pushed by the `actorRef` source don't reach downstream consumers through this boundary. `take(N).runWith(Sink.seq).futureValue` hangs indefinitely.

**Anti-pattern — async boundary, never use for subscription setup:**
```scala
// ❌ preMaterialize() = hidden Publisher/Subscriber boundary = elements silently dropped
def messageSource(pea: TypedActorRef[Command], mc: MessageClassifier): Source[Msg, NotUsed] =
  Source
    .fromMaterializer { (mat, _) =>
      val (actorRef, src) = Source.actorRef[Msg](...).watch(pea.toClassic).preMaterialize()(mat)
      pea ! SubscribeCmd(mc, actorRef)
      src
    }
    .mapMaterializedValue(_ => NotUsed)
```

**Pattern — single graph materialization:**
```scala
// ✅ mapMaterializedValue fires at graph materialization — no async boundary
def messageSource(pea: TypedActorRef[Command], mc: MessageClassifier): Source[Msg, NotUsed] =
  Source
    .actorRef[Msg](PartialFunction.empty, PartialFunction.empty, 64, OverflowStrategy.dropHead)
    .watch(pea.toClassic)
    .mapMaterializedValue { actorRef =>
      pea ! SubscribeCmd(mc, actorRef)
      NotUsed
    }
```

**Sweep:**
```bash
grep -rn "preMaterialize\|fromMaterializer" src/ --include="*.scala"
# Legitimate uses of fromMaterializer: acquiring the Materializer for downstream ops.
# Illegitimate use: calling preMaterialize() inside fromMaterializer to "eagerly" subscribe.
# When in doubt: ask whether a simpler mapMaterializedValue achieves the same result.
```

---

## P13 — Stream test synchronization: syncProbe barrier + `take(N)`

**Status:** Established pattern after CAPSTONE. Do not use `Await.result` + `PoisonPill` for stream termination in tests.

**The problem with PoisonPill termination:**
1. `pea.toClassic ! PoisonPill` sends a system message with priority over regular mailbox messages.
2. Pekko delivers system messages (including `Terminated`) before pending regular messages.
3. Stream actors may have buffered elements that haven't been forwarded downstream when termination arrives.
4. `Await.result(stream, timeout)` succeeds but the seq is incomplete or never arrived.

**The problem with `Await.result` + fixed timeout:**
- Brittle under load (NUC at full testEssential load) — 5s timeout can expire.
- Hides ordering bugs: if elements arrived but the future completed before assertion, test passes incorrectly.

**Pattern — demand-driven self-termination with FIFO barrier:**
```scala
// Subscribe streams FIRST (mapMaterializedValue fires synchronously at runWith):
val stream1 = PeerEventBusActor.messageSource(pea, classifier1).take(1).runWith(Sink.seq)
val stream2 = PeerEventBusActor.messageSource(pea, classifier2).take(2).runWith(Sink.seq)

// FIFO barrier: subscribe syncProbe AFTER stream subscriptions (same test thread).
// When syncProbe.expectMsg confirms receipt, all earlier PEA mailbox messages (the
// stream SubscribeCmds + PublishCmd) have been processed — FIFO guarantee.
val syncProbe = TestProbe()
pea ! SubscribeCmd(classifier2, syncProbe.ref)

pea ! PublishCmd(msg1)
syncProbe.expectMsg(msg1)      // ← barrier: confirms msg1 delivered to all classifier2 subs

pea ! PublishCmd(msg2)
syncProbe.expectMsg(msg2)      // ← barrier: confirms msg2 delivered

// Elements are buffered; Futures complete as soon as take(N) demand is satisfied:
stream1.futureValue shouldEqual Seq(msg1)
stream2.futureValue shouldEqual Seq(msg1, msg2)
```

**Why this works:**
- `take(N).runWith(Sink.seq)` is demand-driven — the Future completes the moment N elements are delivered, with no termination race.
- `syncProbe` subscribed last from the test thread → PEA processes SubscribeCmds in FIFO order → by the time `syncProbe.expectMsg` returns, all stream actors have their elements buffered.
- `ScalaFutures.futureValue` (from `with ScalaFutures with NormalPatience`) is non-timeout-race-sensitive because the elements are already in the buffer.

**NEVER in stream tests:**
```scala
// ❌ PoisonPill races with buffered elements
pea.toClassic ! PoisonPill
Await.result(stream, 5.seconds)  // may be incomplete

// ❌ Fixed sleep before assertion
Thread.sleep(500)
stream.futureValue  // timing-dependent

// ❌ Await without a synchronization barrier
Await.result(stream, 5.seconds)  // may time out under load
```

---

## Test-kit pitfalls (discovered §8a-retro batch 4, 2026-06-23)

**P14 — Missing `application-test.conf` causes `ConfigurationException` with bare ctor**

`ScalaTestWithActorTestKit()` (bare ctor) loads `application-test.conf` from the classpath. If it doesn't exist, Pekko falls back to jar-bundled `reference.conf` only — which does **not** include `src/test/resources/application.conf`. Any actor spawned with `withDispatcherFromConfig("sync-dispatcher")` throws `ConfigurationException: Dispatcher [sync-dispatcher] not configured`.

**Proper fix:** Create `src/test/resources/application-test.conf` (§8a-infra backlog item):
```hocon
include "application.conf"
pekko.actor.default-dispatcher.throughput = 1
```

**Fixed in `8b9bef67d` (2026-06-23):** `src/test/resources/application-test.conf` created; `ConfigFactory.load()` stripped from 25 specs. Bare ctor now works.

---

**P15 — `testKit.stop` is a silent no-op for classic-resolved worker children**

`testKit.stop(ref)` only terminates actors returned by `testKit.spawn(...)`. Coordinator workers are spawned internally via classic `context.actorOf` and resolved by tests via `actorSelection` — these hold classic `ActorRef` values. Calling `testKit.stop` on them does nothing, leaving workers running between tests.

**Proper fix:** Audit affected specs and add `testKit.system.classicSystem.stop(workerRef)` in teardown for classic-resolved refs, OR verify the coordinator's own `PostStop` handler stops workers (§8a-infra-b backlog item).

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
| `ScalaTestWithActorTestKit()` bare ctor without `application-test.conf` | `sync-dispatcher` not found → `ConfigurationException` | Create `application-test.conf` (P14 / §8a-infra) |
| `testKit.stop(classicWorkerRef)` | Silent no-op; worker resource leak | `testKit.system.classicSystem.stop(workerRef)` (P15 / §8a-infra-b) |

---

## CAPSTONE cleanup targets

After all actors are Typed, sweep for:
```bash
grep -rn "\.toClassic\b" src/main/ --include="*.scala"        # remove adapters
grep -rn "PropsAdapter\b" src/main/ --include="*.scala"       # remove adapters
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"      # narrow to real type
grep -rn "ActorSystem\b" src/main/ --include="*.scala"        # flip to ActorSystem[Nothing]
grep -rn "@volatile" src/main/ --include="*.scala"            # should be 0 in Typed actors (P10)
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 \
  | grep "ctx\.log\|context\.log"                             # P11: CE IO thread violation
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|flatMap\|\.recover"  # P11: Future thread violation
```
