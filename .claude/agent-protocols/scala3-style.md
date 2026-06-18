# Scala 3 Style Protocol

Standards for idiomatic Scala 3 code in the fukuii codebase. Agents apply these
when writing new code and opportunistically when touching existing code (see
`inline-cleanup.md` for scope discipline). Each standard has a grep pattern so
regressions are detectable.

Used by: MITHRIL (primary), LOOM, WRAITH, all agents writing Scala
Referenced by: mithril.md, inline-cleanup.md

---

## The ratchet model

Each standard below has a current state (enforced / partially enforced / not yet enforced).
Once a standard is fully enforced via scalafix rule or compiler flag, it becomes a build error.
That is the definition of done for that standard — not "we cleaned it once."

---

## Standards

### S1 — No `return` statements
**Status:** Partially enforced. DisableSyntax.noReturns not yet in `.scalafix.conf` (C2 chore).

```bash
grep -rn "\breturn\b" src/main/ --include="*.scala" | grep -v "//\|\""
# Target: 0 hits
```

Fix: restructure as expression. `if (cond) return` → `if (!cond) { ... }`.
Ratchet: `DisableSyntax.noReturns = true` in `.scalafix.conf`.

---

### S2 — No `null`
**Status:** Not yet audited.

```bash
grep -rn "\bnull\b" src/main/ --include="*.scala" | grep -v "//\|test\|@null"
# Target: 0 hits (use Option, empty collections, sentinel values)
```

Fix: `null` → `Option`, `None`, or appropriate empty value.
Ratchet: `DisableSyntax.noNulls = true` in `.scalafix.conf` (after audit).
Exception: JNI/interop boundary code — narrow `@nowarn` + comment.

---

### S3 — `given/using` over `implicit`
**Status:** Not yet started (Part 3a, after Pekko migration).

```bash
grep -rn "implicit val\|implicit def\|implicit lazy val" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|//\|@nowarn"
# Target: 0 hits (consensus excluded — FORGE review required)
```

Fix: `implicit val x: T = ...` → `given x: T = ...`; function params `(implicit x: T)` → `(using x: T)`.
Ratchet: GivenUsing scalafix rule (add to `.scalafix.conf` after Part 3a complete).
**Do not start until Pekko migration (Part 2) is complete.**

---

### S4 — Extension methods over `implicit class`
**Status:** Not yet started (Part 3b, after Part 3a).

```bash
grep -rn "implicit class\b" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/"
# Target: 0 hits
```

Fix: `implicit class Foo(val x: T) { def bar = ... }` → `extension (x: T) def bar = ...`
**Do not start until Part 3a complete.**

---

### S5 — Scala 3 enum over sealed trait + case objects
**Status:** Not yet started (Part 3d, opportunistic).

Target candidates: `SyncPhase`, `BlacklistReason`, `ForkId` codes.
```bash
grep -rn "sealed trait\|sealed abstract class" src/main/ --include="*.scala" | grep -v "consensus/\|vm/"
```

Fix: Replace where all cases are objects with no fields, or simple value enums.
**Skip enums with methods or fields unless clearly cleaner.**

---

### S6 — `A & B` intersection syntax over `A with B` in self-types
**Status:** 337 occurrences (E003 warning). Part 1 backlog.

```bash
grep -rn "self:.*with\b" src/main/ --include="*.scala" | grep "=>"
# Target: 0 hits
```

Fix: `self: A with B =>` → `self: A & B =>`
Mechanical (bucket A). Safe scalafix or sed pass.
Ratchet: `-source:future` flag already set — `with` in self-types is a warning. Promote to error after cleanup.

---

### S7 — No `asInstanceOf`
**Status:** Not yet audited.

```bash
grep -rn "asInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|//"
# Target: 0 hits outside consensus paths
```

Fix: Replace with pattern matching or type-safe alternatives.
Each occurrence is bucket C (semantic risk) — prove behavior-preserving before fixing.
Route to FORGE if in consensus paths.

---

### S8 — No `isInstanceOf` outside pattern matching
**Status:** 59 occurrences. Part 3c backlog.

```bash
grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/"
# Target: 0 hits
```

Fix: Replace with `match { case _: T => }` or sealed ADT exhaustiveness.
Bucket B/C depending on context.

---

### S9 — No `println` / `System.out` in main sources
**Status:** 28 occurrences. C5 chore.

```bash
grep -rn "println\|System\.out\|System\.err" src/main/ --include="*.scala" | grep -v "//\|Benchmark"
# Target: 0 hits
```

Fix: Replace with SLF4J logger (`ctx.log.info(...)` in Typed actors, `logger.info(...)` elsewhere).
Ratchet: DisableSyntax can flag `println` — add after cleanup.

---

### S10 — Braceless syntax for new code
**Status:** Config change pending (C1 chore). No mass rewrite.

New code written after C1 (scalafmt config): use braceless where scalafmt accepts it.
Do not rewrite existing brace-based code in bulk — creates noise in diffs.
Opportunistic: if you're rewriting a method body anyway, write it braceless.

---

## Writing new Scala 3 code (standards to apply from the start)

```scala
// ✅ Preferred
given config: BlockchainConfig = ...
extension (block: Block) def isValid: Boolean = ...
enum SyncPhase { case Idle, Fetching, Healing }
type BlockHash = opaque type ByteString

// ❌ Avoid
implicit val config: BlockchainConfig = ...
implicit class BlockOps(val block: Block) { def isValid = ... }
sealed trait SyncPhase; case object Idle extends SyncPhase
```

---

## Consensus-critical exception

Standards S3–S8 do NOT apply to `consensus/`, `vm/`, `crypto/`, `domain/` without
specialist review. Those paths require FORGE (ETC) or BEACON (ETH) before any
idiom modernization. See `consensus-change-protocol.md`.
