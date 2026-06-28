# Scala 3 given/using Migration — Operational Notes

Pitfalls discovered during W2-P3a (`implicit → given/using`, 334 sites, commit
`7210311bb`). P3b and any future sprint touching `given` instances must read this
first.

Used by: MITHRIL (primary), LOOM, WRAITH
Referenced by: scala3-style.md (S3)

---

## G1 — Star imports do not pull `given` instances

Scala 3 changed wildcard semantics deliberately: `import X.*` imports terms and
types but NOT `given` instances. Call sites that relied on companion-object
`given` instances being auto-imported via `_` in Scala 2 will silently fail to
resolve in Scala 3 unless the import is made explicit.

**Required form when a companion object holds `given` instances:**
```scala
import JsonMethodsImplicits.{given, *}   // ✅ pulls both terms and given instances
import JsonMethodsImplicits.*            // ❌ given instances silently missing
import JsonMethodsImplicits._            // ❌ Scala 2 wildcard, same problem in Scala 3
```

**Where this bit us in P3a:** Every `JsonRpcController` call site that imported
a `JsonMethodsImplicits` companion (17 files). The companion held `Encoder` /
`Decoder` instances converted to `given`. After conversion, `import X.*` stopped
resolving them; `import X.{given, *}` fixed all sites.

**Grep to find affected call sites before running P3b:**
```bash
# Find all files importing a companion that now holds given instances
grep -rn "^import.*JsonMethodsImplicits\|^import.*Implicits\b" src/ --include="*.scala" \
  | grep -v "{given"
# Any hit that lacks {given in the import line needs updating
```

**Rule:** When you convert a companion object's `implicit val/def` to `given`,
immediately grep for all import sites of that companion and add `{given, *}`.

---

## G2 — Anonymous `given` instances need explicit type annotations

Scala 3 type inference for anonymous `given` instances is intentionally more
restrictive than Scala 2 `implicit val`. An anonymous `given` with an inferred
type can produce ambiguous implicit search if the inferred type is wider than
intended, or can fail to resolve at use sites.

**Symptom:** `given` instance defined, no compile error at the definition, but
call sites report "no implicit found for type X" or the wrong instance resolves.

**Pattern from DiscoveryServiceBuilder (P3a):**
```scala
// ❌ Anonymous given — inferred type may be too wide
given = IoRuntime.global

// ✅ Named given with explicit type — unambiguous
given ioRuntime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
```

**Rule:** Always provide an explicit type annotation on a `given` instance when:
- The inferred type could be a supertype of the intended type
- Multiple `given` instances of related types exist in scope
- The instance is used by library code whose implicit resolution is strict

**Grep to audit anonymous givens (no name or type):**
```bash
grep -rn "^\s*given\s*=" src/main/ --include="*.scala"
# Every hit is a candidate for adding an explicit type
```

---

## G3 — `given` is implicitly final — override chains must stay `implicit val/lazy val`

Scala 3 `given` instances are unconditionally `final`. A class or trait that
`extends` another and needs to override a `given` from the parent cannot do so —
the compiler will reject it with "cannot override final member."

**Override chains that were preserved as `implicit val/lazy val` in P3a:**
- `PeerDiscoveryManagerBuilder.ioRuntime` — subclasses override to inject a
  test runtime
- `PortForwardingBuilder.ioRuntime` — same pattern
- `JsonMethodsImplicits.formats` — multiple subclasses override the base formats
  for different RPC namespaces

**Rule:** Before converting any `implicit val/lazy val` to `given`, run a two-pass grep:

```bash
# Pass 1 — direct override in any subclass
grep -rn "override.*implicit.*<fieldName>\|override.*given.*<fieldName>" src/ --include="*.scala"

# Pass 2 — indirect: find all types that extend the parent, then check each for override
grep -rn "extends.*<ParentTrait>\|with.*<ParentTrait>" src/ --include="*.scala"
# For each found intermediate type, re-run pass 1 against that type name
```

Pass 1 catches direct subclass overrides. Pass 2 catches chains where a concrete
class extends an intermediate trait that itself overrides — the intermediate trait
may not use the keyword `override` if it's the first override in the chain.
If ANY type in the hierarchy overrides the field, leave it as `implicit val/lazy val`.

If any override exists anywhere in the hierarchy, leave it as `implicit val/lazy
val` and add a comment:
```scala
// implicit val (not given): overridden in subclasses — Scala 3 given is final
implicit lazy val ioRuntime: IORuntime = ...
```

**This is not a deficiency to clean up later.** The override requirement is
structural. Marking these with `@nowarn` or converting them anyway will break
subclass injection.

---

## P3b applicability

P3b converts `implicit class` → `extension`. These G1–G3 pitfalls apply in P3b
as follows:

| Pitfall | Applies in P3b? | How |
|---------|-----------------|-----|
| G1 — `{given, *}` imports | Indirectly | If an `extension` method body accesses a `given` from a companion, check the import form |
| G2 — anonymous given type | No | P3b converts `implicit class`, not `given` definitions |
| G3 — given is final | **Yes — do not "fix" these** | Override-chain `implicit val` sites that P3a left intentionally must not be touched in P3b. They are NOT missed conversions. |

**Before P3b begins:** run the following to confirm the intentional `implicit
val/lazy val` sites are still present and comment-annotated:
```bash
grep -rn "not given.*override\|given is final\|overridden in subclass" src/main/ --include="*.scala"
```
These comments are load-bearing documentation — do not remove them.
