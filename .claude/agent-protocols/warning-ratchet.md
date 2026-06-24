# Warning Ratchet Protocol

Systematic approach for clearing compiler warnings category-by-category with a ratchet —
each cleared category becomes a build error so it cannot silently regress.

Applies to any Scala 3 / Pekko codebase where warnings were blanket-suppressed during
a Scala 2→3 migration. Correctness outranks tidiness: a behavior change hidden inside
a "cleanup" is worse than leaving the warning.

Used by: WRAITH (warning-cleanup sessions), MITHRIL (Scala 3 idiom cleanup)
Project reference: `fukuii/.claude/agent-protocols/completed/CHORE-QUEUE.md` (archive, C1-C14 done)

---

## Step 1 — Inventory only. Do NOT edit yet.

Collect every occurrence of the target warning category in scope:

```bash
sbt compile-all 2>&1 | grep -i "warning" | grep "<CATEGORY>" | sort | uniq -c | sort -rn
```

Produce a triage table:

| file:line | warning text | bucket | proposed action | risk-path? |
|-----------|-------------|--------|-----------------|------------|

Classify each into exactly one bucket:

- **A (forced-upgrade)**: deprecated API slated for removal in a future compiler version.
  Mechanical, behavior-preserving by definition.
- **B (idiom)**: compiles; Scala 2 idiom where Scala 3 idiom is cleaner.
  Behavior-preserving but optional — skip unless time permits.
- **C (semantic risk)**: might resolve a different given/implicit, infer a different type,
  or carry Scala 2.13 collection/eta/overload semantics. NOT obviously behavior-preserving.

Mark **risk-path? = YES** for anything in consensus-critical code:
- `consensus/`, `vm/`, `crypto/`, `domain/` — EVM, gas, state trie, block validation
- `network/p2p/messages/` — RLP serialization / wire encoding
- `db/storage/` — state persistence
- Any path touching: block rewards, fork dispatch, signatures, hash computation

**Present the triage table and STOP. Wait for user approval before any edits.**

**Bucket-C consensus gate:** Before Step 2 begins, check whether any bucket-C items
are in consensus-critical paths (`consensus/`, `vm/`, `crypto/`, `domain/`,
`network/p2p/messages/`). If yes:
- Remove those items from Step 2's scope entirely
- Add them to CHASE-QUEUE with a `FORGE-gate` (ETC) or `BEACON-gate` (ETH) note
- Do not include them in the mechanical commit, and do not attempt to prove them
  behavior-preserving without specialist review — the cost of a wrong call is a chain fork

---

## Step 2 — Fix, split strictly by risk (after approval)

Separate commits, never mixed:

1. **ONE mechanical commit** — all bucket-A + provably zero-semantic-change bucket-B
   (pure syntax: `_`→`*` imports, `xs: _*`→`xs*`, unused param with no callers, etc.)

2. **ONE commit PER bucket-C or risk-path item**, each including:
   - Before/after code
   - Argument for why it is semantically identical
   - The test covering this behavior
   - If you cannot prove it behavior-preserving: **do NOT change it — flag for human review**

Never put a behavior-affecting change in the mechanical commit.

---

## Step 3 — Defer visibly, never re-suppress

Anything not fixed gets a narrow site-level suppression only:

```scala
@nowarn("cat=deprecation") // <one-line reason> — DEFERRED-BACKLOG §<ref>
def foo(...) = ...
```

- `@nowarn` at the **exact site** — not file-level, not build-level
- One-line reason required; backlog reference required
- **No blanket `-Wconf` silence, ever**

---

## Step 4 — Ratchet (definition of done)

Promote the cleared category to a build error:

```scala
// build.sbt scalacOptions:
"-Wconf:cat=<CATEGORY>:e"
```

The Step-3 `@nowarn` sites carry their narrow exemptions so the build stays green.

**Not done until:**
- (a) Category escalated to error in `build.sbt` (or rule added to `.scalafix.conf` for scalafix categories)
- (b) `sbt compile-all` is green with 0 new errors
- (c) Deferral list written down

Verify precedence works on this Scala version:
```bash
sbt compile-all 2>&1 | grep -c "error:"                          # must be 0
sbt compile-all 2>&1 | grep "warning:" | grep "<CATEGORY>"       # must be 0
```

---

## Consensus-safety hard stops

- Do NOT edit `consensus/`, `vm/`, `crypto/` without FORGE review (ETC) or BEACON (ETH)
- Do NOT edit RLP codecs (`network/p2p/messages/`) without verifying wire behavior
- Do NOT edit `db/storage/` without VAULT review
- Bucket-C risk-path items: leave them, add `@nowarn`, document — never guess

---

## Output per session

1. Triage table (buckets + risk-path flags)
2. Commits split as above
3. The `-Wconf` or `.scalafix.conf` change
4. Deferral list: each deferred site + reason + backlog ref

## Commit message convention

```
chore(quality): clear <CATEGORY> warnings in <scope> [ratchet step N/4]
chore(quality): escalate <CATEGORY> to build error (ratchet complete)
```
