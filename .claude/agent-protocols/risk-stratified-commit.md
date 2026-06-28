# Risk-Stratified Commit Protocol

Any sweep change — warnings, idiom modernization, migration, inline cleanup —
must split commits by risk bucket. Mechanical and semantic changes mixed in one
commit obscure behavior changes and make git bisect unreliable.

Used by: ALL agents making sweep changes
Referenced by: wraith.md, mithril.md, loom.md

---

## The three buckets

**Bucket A — Mechanical (behavior-preserving by definition)**
Changes where the compiler guarantees identical runtime behavior:
- Deprecated API with a direct replacement (`_` → `*` imports, `xs: _*` → `xs*`)
- Syntax-only rewrites (`with` → `&` in self-types, procedure syntax removal)
- Unused import removal
- `log.warning` → `log.warn` (API rename, no logic)
- `println` → `logger.info` (output destination change, not logic)
- `return` removal from Unit methods (restructured as expression — verify first)
- Formatting / braceless config changes

**Bucket B — Idiom (likely safe, optional)**
Changes where the intent is clear but a human should sanity-check:
- `implicit val` → `given` (Scala 3 idiom — same resolution rules, verify with compile)
- `implicit class` → `extension` (same semantics, verify call sites compile)
- Sealed trait + case objects → `enum` (same ADT, verify exhaustiveness checks hold)
- Type alias modernization

**Bucket C — Semantic risk (requires proof)**
Changes where the compiler cannot guarantee identical behavior:
- Any change to implicit/given resolution order (different given in scope = different behavior)
- Type inference changes (Scala 3 infers differently in some cases)
- Collection method changes (`filter` on `Map` returns `Map` in Scala 3 vs `Iterable` in 2.13)
- Overload resolution changes
- Anything in a consensus-critical path (see `consensus-change-protocol.md`)

---

## Commit structure

### Sweep with only bucket-A items
```
ONE commit: all bucket-A fixes
chore(cleanup): <description> — mechanical, behavior-preserving
```

### Sweep with A + B items
```
commit 1: all bucket-A fixes
chore(cleanup): <description> — mechanical

commit 2: all bucket-B fixes (after A is green)
chore(modernize): <description> — idiom, verify compile
```

### Sweep with any bucket-C items
```
commit 1: bucket-A fixes
commit 2: bucket-B fixes (optional, if time)
commit 3+: ONE bucket-C item each, with:
  - Before/after code in commit message
  - Argument for why semantics are identical
  - Test covering this behavior cited by name
  - If proof is not available: DO NOT commit — flag for human review
```

### Never
- A + C in the same commit
- B + C in the same commit
- Multiple unrelated C items in one commit
- "Misc cleanup" commits mixing any buckets

---

## Proof standard for bucket-C

A bucket-C change is approved when you can state:
> "This change is behavior-preserving because [specific reason]. The test
> [TestName.scala:lineN] covers this behavior and passes after the change."

If you cannot complete that sentence, the change does not ship. Add `@nowarn`
or leave it, document it in the continuation file, and move on.

---

## Risk-path override

Any bucket-A or bucket-B item in a **consensus-critical path**
(`consensus/`, `vm/`, `crypto/`, `domain/`, `network/p2p/messages/`, `db/storage/`)
is automatically treated as bucket-C. Route to the appropriate specialist
(`consensus-change-protocol.md`) before committing.

---

## Quick triage checklist

Before each commit in a sweep:
```
□ All items in this commit are the same bucket
□ No consensus-critical files mixed with non-consensus files
□ Bucket-C items have proof written in the commit message
□ sbt compile-all green after this commit alone
□ No unrelated primary-task changes mixed in
```
