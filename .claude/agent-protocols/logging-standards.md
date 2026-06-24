# Logging Standards Protocol

**Philosophy:** Logging is an observability instrument, not an audit trail.
A log line that says "processing block" tells an observer nothing. A log line that says
`block=14532010 txCount=87 gasUsed=12458901/30000000 (41%) elapsed=142ms peer=abc123`
tells an observer the system state, its efficiency, and whether something is wrong —
without needing to read source code or reproduce the scenario.

**Dashboard integration:** Logging and metrics are two tracks of the same observability
signal. Every significant measurement in a log line SHOULD also be emitted as a Micrometer
metric so it appears in Grafana. The two tracks are complementary: logs give context
and human-readable narrative; metrics give time-series trend, alerting, and aggregation.

**Metrics stack:** Kamon (actor auto-instrumentation + kanela agent) → Micrometer
(metrics facade) → Prometheus registry → Grafana. This stack is already wired in
`build.sbt`. New code adds Micrometer `Counter`, `Timer`, `Gauge`, and
`DistributionSummary` calls alongside the log statements.

Used by: ALL agents
Referenced by: inline-cleanup.md, loom.md, wraith.md

---

## The taxonomy of what to log (and meter)

### 1. Process lifecycle (start / stop / elapsed)

Every significant process emits start and stop log entries with elapsed time, AND
records a Timer metric for the dashboard.

```scala
val startMs = System.currentTimeMillis
ctx.log.info(
  "Sync started: mode=SNAP pivot={} peer={} queueDepth={}",
  pivotBlock, peerId, queueDepth
)

// ... do work ...

val elapsed = System.currentTimeMillis - startMs
ctx.log.info(
  "Sync phase complete: mode=SNAP phase=AccountRange accounts={} elapsed={}ms rate={}/s",
  accountsProcessed, elapsed, rate
)

// Dashboard: record the same data as a metric
syncPhaseTimer                          // Timer — Micrometer
  .tag("mode", "SNAP")
  .tag("phase", "AccountRange")
  .record(elapsed, TimeUnit.MILLISECONDS)
accountsProcessedCounter                // Counter
  .tag("mode", "SNAP")
  .increment(accountsProcessed)
```

**Log at process START:** process name, key inputs (mode, target, peer), starting state.
**Log at process STOP:** outcome (success/failure/timeout), totals, elapsed ms, rate/s.
**Log at process FAILURE:** what failed, why, elapsed before failure, exception as last arg.

---

### 2. Progress and completion metrics

For long-running operations, log progress at meaningful intervals AND update Gauges.

```scala
// Progress log (every 10,000 items or every 10%)
if (processed % 10000 == 0 || pct % 10 == 0)
  ctx.log.info(
    "Heal progress: nodes={}/{} pct={} rate={}/s queueDepth={} elapsed={}s eta={}s",
    healed, total, pct, rate, queueDepth, elapsed.toSeconds, eta.toSeconds
  )

// Dashboard: Gauges for current values (scraped on Prometheus interval)
healProgressGauge.set(pct)
healQueueDepthGauge.set(queueDepth)
healRateGauge.set(rate)
```

**What to surface in progress logs:**
- `processed=N/total` — current count and known total
- `pct=N` — percentage (integer, no % sign — easier to parse)
- `rate=N/s` — current processing rate
- `queueDepth=N` — backlog / pending work
- `elapsed=Ns` — wall-clock time so far
- `eta=Ns` — estimated time remaining (total/rate - elapsed)
- Rate drop to zero = first signal of a stall — critical to surface

---

### 3. Resource metrics

Log and meter resource consumption. This surfaces poor architecture (blocking actor),
OOM risks (unbounded queue), idle agents (zero work over time window), and stalled processes.

```scala
// Memory pressure at significant allocation points
val heapUsed = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
val heapMax  = Runtime.getRuntime.maxMemory
val heapPct  = heapUsed * 100 / heapMax
ctx.log.debug(
  "Batch allocated: size={} heapUsed={}MB heapMax={}MB pct={}",
  batchSize, heapUsed / MiB, heapMax / MiB, heapPct
)
// Dashboard
heapUsedGauge.set(heapUsed)
heapPctGauge.set(heapPct)

// Queue / mailbox depth
ctx.log.debug(
  "Queue state: pending={} maxSeen={} dropped={} rate={}/s",
  queue.size, maxQueueDepth, droppedCount, processRate
)
queueDepthGauge.set(queue.size)
droppedCounter.increment(newDrops)

// Threshold warning before hitting limit (80% rule)
if (queue.size > queueCapacity * 0.8)
  ctx.log.warn(
    "Queue near capacity: depth={}/{} pct={} — backpressure recommended",
    queue.size, queueCapacity, queue.size * 100 / queueCapacity
  )

// Idle agent detection
if (timeSinceLastWork > idleThreshold)
  ctx.log.warn(
    "Agent idle: actor={} idleFor={}s lastWork={} waitingFor={}",
    ctx.self.path.name, timeSinceLastWork.toSeconds, lastWorkDescription, blockingOn
  )
idleTimeCounter.increment(timeSinceLastWork.toSeconds)
```

**Resource signals to emit:**
- Heap used / max / pct — especially during state download
- Queue depth with capacity context — every bounded queue
- 80% threshold warning for any bounded resource
- Bytes read/written for I/O (not just item counts)
- Active peer count and connection pool utilization
- Idle time for agents that should be continuously busy

---

### 4. State transitions

Every behavior change and significant state machine transition logs previous → next + trigger.

```scala
ctx.log.info(
  "State transition: actor={} {} → {} trigger={} peer={} height={}",
  ctx.self.path.name, previousPhase, nextPhase, trigger, peerId, height
)
stateTransitionCounter.tag("from", previousPhase).tag("to", nextPhase).increment()

// Retry / backoff
ctx.log.warn(
  "Request retry: peer={} attempt={}/{} backoff={}ms reason={}",
  peerId, attempt, maxAttempts, backoff.toMillis, failure.getMessage
)
retryCounter.tag("peer", peerId).increment()

// Peer score change
ctx.log.info(
  "Peer score: peer={} {} → {} reason={} activePeers={}",
  peerId, oldScore, newScore, reason, activePeerCount
)

// Chain reorg
ctx.log.warn(
  "Chain reorg: depth={} oldHead={} newHead={} ancestor={} elapsed={}ms",
  reorgDepth, oldHead.short, newHead.short, ancestor.short, elapsed.toMillis
)
reorgCounter.tag("depth-bucket", depthBucket(reorgDepth)).increment()
```

**What makes a transition log useful:**
- Previous state (not just current)
- Trigger / cause (message or event)
- Context at transition time (height, peer, count)
- For retries: attempt number, max, backoff interval

**Multi-phase operations:** For operations that span multiple phases (SNAP sync phases,
block import pipeline stages, trie healing passes), log EACH phase boundary — not just
the top-level start and stop. A single elapsed-time metric covering the whole operation
hides which phase is slow. Require one log entry per phase transition with phase-specific
elapsed and item counts. The format in §1 (process lifecycle) applies at each phase boundary,
not only at the operation boundary.

---

### 5. Error paths and unhappy paths

Both happy and unhappy paths need explicit logging. A catch block with no log is invisible
data loss. A happy path with no log means you can't distinguish normal from degraded.

```scala
// Happy path milestone — explicit at meaningful points
ctx.log.info(
  "Block validated: hash={} number={} txCount={} gasUsed={}/{} elapsed={}ms",
  block.hash.short, block.number, block.transactions.size,
  block.gasUsed, block.gasLimit, elapsed.toMillis
)
validatedBlocksCounter.increment()
blockValidationTimer.record(elapsed, TimeUnit.MILLISECONDS)

// Unhappy path — full context, never swallowed silently
ctx.log.error(
  "Block validation failed: hash={} number={} reason={} peer={} elapsed={}ms",
  block.hash.short, block.number, failure.getMessage, peerId, elapsed.toMillis, failure
)
validationFailureCounter.tag("reason", reasonCode(failure)).increment()

// Partial success — distinct from full success
ctx.log.warn(
  "Partial response: requested={} received={} missing={} peer={} elapsed={}ms",
  requested, received, requested - received, peerId, elapsed.toMillis
)

// Silent catch blocks: NEVER leave these empty
try { ... } catch {
  case e: Exception =>
    ctx.log.error(
      "Unexpected error in {}: reason={} context={}",
      "operationName", e.getMessage, relevantContext, e
    )
    errorCounter.tag("op", "operationName").increment()
}
```

---

### 6. Performance budgets and slow-path detection

Log when an operation exceeds its expected time. This surfaces latency regressions
before they become user-visible. Also emit a histogram metric so Grafana can show P95/P99.

```scala
val elapsed = System.currentTimeMillis - startMs
operationTimer.record(elapsed, TimeUnit.MILLISECONDS)  // histogram — captures P50/P95/P99

if (elapsed > budgetMs)
  ctx.log.warn(
    "Slow op: name={} elapsed={}ms budget={}ms ratio={}x context={}",
    opName, elapsed, budgetMs, f"${elapsed.toDouble / budgetMs}%.1f", context
  )
```

**Time budgets by operation type:**

| Operation | Expected | Warn threshold | Metric name |
|-----------|----------|---------------|------------|
| Block validation (ETC) | <50ms | >200ms | `fukuii.block.validation.duration` |
| Peer handshake | <2s | >5s | `fukuii.peer.handshake.duration` |
| Trie node fetch (single) | <500ms | >2s | `fukuii.snap.node.fetch.duration` |
| DB batch write | <100ms | >500ms | `fukuii.db.batch.write.duration` |
| Account range response | <5s | >15s | `fukuii.snap.account.range.duration` |
| Full state healing pass | <30min | >60min | `fukuii.snap.heal.duration` |

---

### 7. Per-peer and per-session metrics

Always include peer identity. Emit per-peer counters for dashboard aggregation.

```scala
// Per-peer session summary at disconnect
ctx.log.info(
  "Peer disconnected: peer={} addr={} connectedFor={}s blocks={} "
  + "msgsIn={} msgsOut={} bytesIn={} bytesOut={} errors={} score={}",
  peer.id.short, peer.remoteAddress, sessionSeconds,
  blocksDelivered, msgsIn, msgsOut, bytesIn, bytesOut, errors, finalScore
)
// Counters updated continuously during session:
peerBytesInCounter.tag("peer", peer.id.short).increment(bytesIn)
peerBlocksCounter.tag("peer", peer.id.short).increment(blocksDelivered)
peerErrorsCounter.tag("peer", peer.id.short).increment(errors)
```

---

### 8. Correlation IDs (cross-actor tracing)

Operations that span multiple actors need a correlation ID to link log entries.
Without it, distributed tracing requires timestamps and guesswork.

```scala
// Generate at operation start in the coordinator
val opId = s"heal-${pivotBlock}-${System.nanoTime % 1000000}"

ctx.log.info("Op start: opId={} pivot={} peer={}", opId, pivotBlock, peerId)
worker ! FetchNodes(nodes, replyTo, opId)  // propagate opId in the Command

// Worker logs with the same opId:
ctx.log.debug("Nodes fetched: opId={} count={} elapsed={}ms", opId, count, elapsed)

// Coordinator receives result:
ctx.log.info("Op complete: opId={} total={} elapsed={}ms", opId, total, elapsed)
```

Naming: `<operation>-<discriminator>-<nano-suffix>` — human-readable in logs,
stable enough to grep across a session.

---

### 9. Unhandled messages and dead letters

Unhandled messages in Typed actors are dropped silently by default. Always log them.

```scala
case unexpected =>
  ctx.log.warn(
    "Unhandled message: actor={} type={} state={} dropping",
    ctx.self.path.name, unexpected.getClass.getSimpleName, currentState
  )
  unhandledMessageCounter.tag("type", unexpected.getClass.getSimpleName).increment()
  Behaviors.unhandled
```

Kamon automatically instruments actor dead letters — ensure `kamon.pekko.dead-letters`
is enabled in `application.conf`.

---

### 10. Dashboard wiring (Micrometer + Prometheus + Grafana)

#### Metric naming convention

```
fukuii.<subsystem>.<operation>.<measurement>
```

Examples:
```
fukuii.snap.heal.nodes.processed       Counter — total nodes healed
fukuii.snap.heal.phase.duration        Timer   — phase wall-clock time
fukuii.snap.heal.queue.depth           Gauge   — current queue depth
fukuii.peer.active.count               Gauge   — live peer count
fukuii.block.validation.duration       Timer   — per-block validation time
fukuii.db.batch.write.duration         Timer   — per-batch write time
fukuii.heap.used.bytes                 Gauge   — JVM heap used
fukuii.heap.utilization.pct            Gauge   — heap used / max (%)
fukuii.actor.errors.total              Counter — errors per actor (tag: actor=)
fukuii.peer.bytes.in.total             Counter — bytes received per peer (tag: peer=)
fukuii.chain.reorg.total               Counter — reorg events (tag: depth-bucket=)
```

#### Metric types

| Type | Use for | Example |
|------|---------|---------|
| `Counter` | Monotonically increasing counts | blocks validated, bytes received, errors |
| `Gauge` | Current value that can go up or down | queue depth, peer count, heap pct |
| `Timer` | Operation duration (provides P50/P95/P99) | validation duration, fetch duration |
| `DistributionSummary` | Size distributions | batch size, response payload size |

#### Tags — use sparingly

Tags multiply the metric cardinality. Only tag by dimensions you actually query.

```scala
// ✅ Low cardinality tags — OK
metricsCounter.tag("mode", "SNAP").tag("phase", "AccountRange").increment()
peerCounter.tag("chain", "ETC").increment()

// ❌ High cardinality tags — never use peer ID or block hash as a tag
metricsCounter.tag("peer", peer.fullId).increment()  // 100s of unique values → memory leak
metricsCounter.tag("hash", block.hash.hex).increment()  // millions of values
```

#### Where to initialize metrics

Register in the actor's `Behaviors.setup` block or in the service class constructor.
Use `MeterRegistry` injected from the application wiring.

```scala
Behaviors.setup[Command] { ctx =>
  val registry = MeterRegistry.global  // or injected
  val processedCounter = Counter.builder("fukuii.snap.heal.nodes.processed")
    .description("Total trie nodes processed during SNAP heal")
    .register(registry)
  val phaseTimer = Timer.builder("fukuii.snap.heal.phase.duration")
    .description("Wall-clock time per heal phase")
    .tag("mode", "SNAP")
    .register(registry)
  new ActorImpl(ctx, processedCounter, phaseTimer).receive()
}
```

#### Kamon auto-instrumentation (no code needed)

Kamon + Kanela automatically meters:
- Actor mailbox size (depth, wait time)
- Actor processing time per message type
- ActorSystem thread pool utilization
- Dead letter count

These appear in Prometheus as `pekko.*` metrics. Do NOT re-implement these manually.

---

### 11. Structured field naming conventions

Consistent key names across all log messages enable log aggregation (Loki, grep, Elasticsearch).

| Field | Type | Format | Example |
|-------|------|--------|---------|
| `actor` | string | class simple name | `actor=AccountRangeCoordinator` |
| `peer` | string | first 8 hex chars | `peer=abc123de` |
| `height` | long | bare integer | `height=14532010` |
| `hash` | string | `0x` + first 4 + `…` + last 4 | `hash=0x1234…abcd` |
| `elapsed` | ms | integer + `ms` | `elapsed=142ms` |
| `rate` | per-sec | integer + `/s` | `rate=312/s` |
| `count` | long | bare integer | `count=4521` |
| `total` | long | bare integer | `total=10000` |
| `pct` | int | bare integer (no `%`) | `pct=45` |
| `depth` | int | bare integer | `depth=128` |
| `bytes` | long | bare integer (raw) | `bytes=1048576` |
| `opId` | string | `op-discriminator-suffix` | `opId=heal-14532010-83421` |
| `reason` | string | kebab-case code | `reason=timeout` `reason=invalid-hash` |
| `attempt` | int | bare integer | `attempt=2` |
| `mode` | string | UPPERCASE | `mode=SNAP` `mode=FULL` |
| `phase` | string | PascalCase | `phase=AccountRange` |
| `state` | string | PascalCase | `state=Healing` |

**Rules:**
- Always `key=value` — never positional-only: `"peer {} height {}"` → `"peer={} height={}"`
- Unit suffixes on ambiguous numeric values: `elapsed=142ms` not `elapsed=142`
- Rates always `/s`: `rate=312/s`
- Percentages without `%` (parseability): `pct=45`
- Hash truncation: `0x` + first 4 hex + `…` + last 4 hex — full hash bloats logs
- Peer IDs: first 8 hex chars only

---

## Preferred logging API

### In Pekko Typed actors

```scala
ctx.log.info("key={} key2={}", value1, value2)      // SLF4J parameterized — no string concat
ctx.log.warn("key={} key2={}", value1, value2)       // NOT log.warning — SLF4J uses warn
ctx.log.error("key={} reason={}", value, e.getMessage, e)  // exception always last arg
ctx.log.debug("key={} key2={}", value1, value2)
```

### In non-actor classes

```scala
private val logger = LoggerFactory.getLogger(getClass)
logger.info("key={} key2={}", value1, value2)
```

### In Classic actors (pre-migration)

```scala
log.warn("...")   // NOT log.warning — fix inline as bucket-A cleanup
```

### In Futures, CE IO computations, BFS walks, and off-thread callbacks (Typed actors)

`context.log` is only safe on the actor thread. Any code inside a `Future`, a Cats Effect
`IO { }` block, an `onComplete`, BFS traversal, or closure passed to a non-actor
`ExecutionContext` or CE scheduler must use a plain SLF4J logger (`asyncLog`) instead.

The violation is often silent: code compiles, most tests pass, but a specific code path that
only runs off-thread will blow up at runtime. Classic symptom (found in S4 migration): tests
for the happy path pass but a single error-branch test fails — the error path was the only one
that called `ctx.log` from a CE `io-compute-N` thread.

The **indirect** case is the most dangerous: a `private def` using `ctx.log` looks fine at
its definition site but becomes a violation the moment it is called from a CE fiber or Future
callback. Default `private def` helpers that do any logging to `asyncLog` to prevent this.

```scala
// Declare alongside ctx at actor construction:
private val asyncLog = LoggerFactory.getLogger(getClass)

// ✅ Off-thread (Future body, IO block, BFS walk, onComplete):
asyncLog.info("key={} key2={}", value1, value2)
asyncLog.warn("reason={}", reason)

// ✅ On actor thread (message handlers):
ctx.log.info("key={} key2={}", value1, value2)

// ❌ private def with ctx.log — unsafe if called from CE IO or Future:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n => ctx.log.debug("node={}", n.hash) }  // blows up on io-compute thread

// ✅ Use asyncLog in any private def that may be called off-thread:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n => asyncLog.debug("node={}", n.hash) }
```

**Rule:** If the call site is inside a lambda passed to an `ExecutionContext` or CE scheduler,
use `asyncLog`. If it is directly in a `Behaviors.receive*` message handler `case`, use `ctx.log`.
When in doubt: `asyncLog` is always safe; `ctx.log` is only safe on the actor mailbox thread.

**Sweeps for misuse:**
```bash
# CE IO thread violation: ctx.log inside IO { } block
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep "ctx\.log\|context\.log"

# Future thread violation: ctx.log inside Future/callback closures
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|\.flatMap\|\.recover"

# Indirect risk: private defs in Typed actors using ctx.log (manual review)
grep -rn "private def" src/main/ --include="*.scala" -A20 \
  | grep "ctx\.log\|context\.log"
```

---

## Log level guide

| Level | When to use |
|-------|------------|
| `error` | Non-recoverable failure; unexpected exception needing a stack trace |
| `warn` | Recoverable anomaly; retry triggered; threshold exceeded; partial failure |
| `info` | Major lifecycle events: start/stop, peer connect/disconnect, sync milestone, fork activated |
| `debug` | Per-request detail; useful for diagnosis, too noisy in production |
| `trace` | Byte-level detail; only during protocol debugging, never in production |

**Rule:** No `info` in tight loops. Use `debug` for anything firing >1/s during normal operation.

---

## What NOT to log

| Avoid | Reason |
|-------|--------|
| Per-item in tight loops | O(n) noise — use a counter, log once after |
| Logging inside `map`/`flatMap` on collections | Same — aggregate, then log |
| Duplicate events at different levels | Pick one level |
| Sensitive data: private keys, tx signatures, raw seed | Security |
| Full `.toString` on large objects | Unbounded output |
| `s"${expr}"` interpolation in log calls | Always evaluates even at DEBUG level |
| `"text " + value` concatenation | Same as above |
| Entering/exiting every method | Use trace sparingly; not method-level |

---

## Inline logging additions (when to add)

When opening a file for primary work:

**Always add:**
- Process start / stop with elapsed (if missing)
- Silent catch blocks that currently swallow without logging
- State transitions missing previous → next + trigger
- Threshold warnings at 80% of any bounded resource

**Add when time permits:**
- Progress milestones for long operations (every N% or N items)
- Per-peer summaries at disconnect
- Slow-path detection for operations with known time budgets
- Corresponding Micrometer metric alongside each new significant log line

**Do NOT:**
- Chase into other files to follow a log gap (log it in CHASE-QUEUE)
- Restructure try/catch just to add logging (that's bucket B/C)
- Add logging in `consensus/`, `vm/`, `crypto/` without FORGE review

---

## Known system-wide logging gaps

When you encounter these in a file you're already editing, add the logging.
Otherwise log to CHASE-QUEUE.

| Gap area | Status | Priority |
|----------|--------|---------|
| SNAP sync phase transitions | No start/stop/elapsed | HIGH |
| Account range heal loop | No rate or ETA logged | HIGH |
| Heap pressure during state download | No memory metric at batch points | HIGH |
| DB batch write | No elapsed or batch size | MEDIUM |
| RLPx handshake failure | Not all failure paths log reason | MEDIUM |
| Block validation rejection | Some paths log hash only, no reason | MEDIUM |
| Actor mailbox depth | No threshold warning | MEDIUM |
| Peer score changes | No score delta logged | LOW |
| EVM opcode exceptions | Debug logging inconsistent | LOW |

---

## Grep-verifiable ratchet targets

```bash
# log.warning — target: 0
grep -rn "log\.warning" src/main/ --include="*.scala" | wc -l

# println in main sources — target: 0
grep -rn "^\s*println\|System\.out\.print" src/main/ --include="*.scala" | wc -l

# String interpolation in log calls — target: 0
grep -rn 'log\.\(debug\|info\|warn\|error\)(s"' src/main/ --include="*.scala" | wc -l

# String concatenation in log calls — target: 0
grep -rn 'log\.\(debug\|info\|warn\|error\)(.*" +' src/main/ --include="*.scala" | wc -l

# Silent catch blocks (catch with no log) — target: 0
# (approximate — verify manually)
grep -rn "} catch {" src/main/ --include="*.scala" -A5 | grep -v "log\.\|logger\." | grep "case.*=>"

# Unhandled message handlers with no log — target: 0
grep -rn "Behaviors\.unhandled" src/main/ --include="*.scala" -B3 | grep -v "log\."

# context.log inside CE IO blocks — target: 0 (use asyncLog instead)
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep "ctx\.log\|context\.log"

# context.log inside Future/callback closures — target: 0 (use asyncLog instead)
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|\.flatMap\|\.recover"

# private defs in Typed actors using ctx.log — manual review: may be called from CE/Future
grep -rn "private def" src/main/ --include="*.scala" -A20 | grep "ctx\.log\|context\.log"

# Positional log messages (no key= prefix) — target: 0
grep -rn 'log\.\(info\|warn\|error\|debug\)("[A-Za-z][^=]*{}' src/main/ --include="*.scala" | wc -l
```
