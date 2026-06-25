# consensus/vm — EVM Opcode Dispatch and Gas Metering

**Package:** `vm/`
**Gate:** `forge` (ETC opcodes) / `beacon` (ETH opcodes, Osaka EIPs) on ALL changes
**Key files:** `VM.scala`, `OpCode.scala`, `PrecompiledContracts.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## §ETH-T2-A: `isPostMerge` → `isPoS` / add `isPoW` (2026-06-24)

#### `c470b3dac` — rename block-level PoS predicate to chain-agnostic vocabulary
- **Files:** `vm/OpCode.scala` (PREVRANDAO dispatch), `vm/VM.scala` (EIP-7610 CREATE guard + comment)
- **What:** `blockHeader.isPostMerge` → `blockHeader.isPoS` at both call sites. No logic change.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T2-A`

---

## §8e-FORGE: `return` → expression / `scalafix:ok` FORGE pass

#### `4544b8025` — §8e-FORGE: consensus `return` conversion (FORGE-reviewed, 2026-06-24)
- **`vm/OpCode.scala:989`** — CLEAR. Pure guard clause → `if cond then abort else { ...rest... }`. Byte-identical.
- **`vm/VM.scala:140`** — DEFER (`// scalafix:ok DisableSyntax.return`). Early exit before `onCallExit` tracer block; converting fires the tracer callback in the abort case — observable behaviour change. Ratchet satisfied via suppression.
- **`vm/PrecompiledContracts.scala:271, 649, 753, 762, 767, 772, 782`** — all DEFER (`// scalafix:ok`). EIP-2537 BLS and EIP-4844 KZG crypto primitives; `try`-nested return has catch-sensitive control flow. Conversion requires restructuring that is byte-level risky for precompile results. `:573` already carried `scalafix:ok` (untouched).
- **Gate:** FORGE sign-off. **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8e-FORGE`

---

## §8l-I: VM Tracer Balance Fix (2026-06-24)

#### `bda0228a4` — §8l-I: `VM.create()` tracer enter/exit balance restored
- **File:** `vm/VM.scala` — EIP-3860 initcode-too-large abort arm
- **What:** Early `return` before `tracer.foreach(_.onCallExit(...))` converted to expression arm; abort tuple now flows through the trailing `onCallExit` block. Removed `// scalafix:ok DisableSyntax.return` suppression (and DEFER comment) at former line 143. `DisableSyntax.noReturns` ratchet now covers this site without suppression.
- **Tests:** 2 regression tests added to `CallTracerSpec` — balanced push/pop assertion + abort-appears-in-parent with `InitCodeSizeLimit` error, no orphaned frame.
- **Beacon sign-off:** SAFE — tracer callback only; no gas/state-root/RLP impact.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8l-R1` (research) + `§8l-I` (this fix)

---

## Open

- `vm/OpCode.scala` — infix/wildcard warnings (9 hits, FORGE gate)

---

## §ETH-T4-A: KZG Point Evaluation Precompile fix (2026-06-25)

#### `02aaa05fc` — fix(eth): load KZG trusted setup at startup — point-evaluation precompile now rejects invalid proofs (EIP-4844)
- **Files:** `vm/PrecompiledContracts.scala` (catch fix), `src/main/resources/trusted_setup.txt` (new), `src/test/scala/.../vm/KzgPointEvaluationSpec.scala` (new, 4 tests)
- **What:** Silent exception catch in `KzgPointEvaluation.exec` (`PrecompiledContracts.scala:793-799`) replaced with explicit `return None` (revert). Trusted setup now loaded at startup (`Fukuii.scala`), so `CKZG4844JNI.verifyKzgProof` executes the real cryptographic check. Pre-fix: any well-formed KZG proof was accepted without crypto verification.
- **Gate:** BEACON sign-off. ETC unaffected — `cancunTimestamp.isDefined` false on all ETC configs.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T4-A`, `node/bootstrap.md §ETH-T4-A`
