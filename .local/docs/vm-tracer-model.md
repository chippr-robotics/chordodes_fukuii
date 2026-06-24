# VM Tracer Model — Research (§8l-R1)

Origin: `scalafix:ok DisableSyntax.return` suppression at
`src/main/scala/com/chipprbots/ethereum/vm/VM.scala:143` (comment block lines
140-143). Question: when `create()` aborts on EIP-3860 initcode-too-large,
should `onCallExit` fire on the tracer?

## Spec verdict (SHOULD_FIRE)

**SHOULD_FIRE.** Within Fukuii's current control flow this is a latent tracer
bug, and the `scalafix:ok` suppression — while technically correct that the
early `return` *does* skip the trailing `onCallExit` — is masking an
**unbalanced enter/exit emission**.

Reasoning: in `VM.create()`, `onCallEnter` is fired unconditionally for any
sub-call (callDepth > 0) at lines 126-129, *before* the EIP-3860 check at line
138. The early `return` at line 143 then exits the method *before* the trailing
`onCallExit` block at lines 206-209. The net effect is that on an
initcode-too-large abort, a sub-call CREATE emits `onCallEnter` with **no
matching `onCallExit`**. Every consumer of `ExecutionTracer` maintains a frame
stack keyed on enter/exit balance:

- `CallTracer.onCallEnter` pushes a `CallFrame` (CallTracer.scala:101); the
  matching `onCallExit` is what pops it, sets `gasUsed`/`error`, and attaches it
  to the parent's `calls` (lines 108-118). A missing exit leaves an orphaned
  frame on `callStack` — the failed CREATE never appears in the call tree, and
  the stack-depth invariant for all *subsequent* sibling frames is corrupted.
- `VmTracer.onCallEnter` pushes a `VmFrame` (VmTracer.scala:129-130); the
  matching `onCallExit` pops and encodes it into the parent op's `sub` field
  (lines 133-140). A missing exit leaves the frame unencoded and shifts every
  later `sub` attachment.

Once `onCallEnter` has been emitted, the tracer contract obligates a matching
`onCallExit`. The correct fix is therefore to emit `onCallExit` on the abort
path (carrying `gasUsed = startGas` since the abort consumes all gas,
`output = empty`, `error = Some(InitCodeSizeLimit.toString)`) rather than to
short-circuit past it.

### Note on the deeper core-geth divergence (read before implementing §8l-I)

core-geth never reaches an enter/exit pair at all for this abort (see next
section): the EIP-3860 check fires in the *parent opcode's dynamic-gas stage*,
before `evm.create()` is entered. So the *fully* core-geth-faithful behaviour
would be to fire **neither** `onCallEnter` nor `onCallExit` for an
initcode-too-large CREATE, surfacing it instead as a parent-frame opcode fault.

Fukuii's architecture does not gate `onCallEnter` on the EIP-3860 check — the
enter is already out the door by the time the size limit is evaluated. Given
that constraint, the minimal correct fix is to **balance the already-emitted
enter with an exit** (SHOULD_FIRE). A larger, optional follow-up could move the
EIP-3860 short-circuit ahead of `onCallEnter` to match core-geth's
"neither-fires" semantics, but that is a behavioural change to enter-emission,
out of scope for the `:143` suppression and not required for trace correctness.

### scalafix suppression disposition

The `scalafix:ok DisableSyntax.return` at VM.scala:143 should be **removed** as
part of §8l-I when the abort arm is converted from an early `return` to an
expression arm (returning the abort `(PR, Address)` tuple) that flows through
the trailing `onCallExit` block. Do NOT edit VM.scala in this research task.

## core-geth CaptureExit reference behaviour

Reference: `reference-clients-evm/go-ethereum/core/vm/evm.go`,
`.../core/vm/interpreter.go`, `.../core/vm/gas_table.go`,
`.../core/vm/common.go`.

**Does CaptureExit (`OnExit`) fire on aborted creates?** Yes — for aborts that
occur *inside* `evm.create()`. `create()` registers both hooks at the very top,
unconditionally, with `OnExit` on a `defer` (evm.go:484-489):

```go
if evm.Config.Tracer != nil {
    evm.captureBegin(evm.depth, typ, caller, address, code, gas.RegularGas, value.ToBig())
    defer func(startGas uint64) {
        evm.captureEnd(evm.depth, startGas, leftOverGas.RegularGas, ret, err)
    }(gas.RegularGas)
}
```

The `defer` guarantees `captureEnd` → `OnExit` fires on **every** return path
inside `create()`:
- ErrDepth (depth > 1024), evm.go:493
- ErrInsufficientBalance, evm.go:496
- ErrNonceUintOverflow, evm.go:500
- **ErrContractAddressCollision** (contract-already-exists), evm.go:535
- ErrMaxCodeSizeExceeded (deployed runtime code too big) via
  `initNewContract`, evm.go:583/595
- EIP-3541 InvalidCode, evm.go:600
- ErrCodeStoreOutOfGas, evm.go:583/607

So for collision and max-*runtime*-code-size, core-geth fires a balanced
enter/exit pair, exactly as the SHOULD_FIRE verdict prescribes for Fukuii.

**The EIP-3860 initcode-too-large case is special and does NOT reach
`create()`.** `CheckMaxInitCodeSize` (common.go:29-37) is invoked from
`gasCreateEip3860` / `gasCreate2Eip3860` — the *dynamic-gas function* of the
CREATE/CREATE2 opcode (gas_table.go:324, 343). In the interpreter loop, dynamic
gas is computed *before* `operation.execute()` (interpreter.go:222-226); on
error the loop `break`s and returns `ErrOutOfGas` **before** the CREATE opcode's
`execute` ever calls `evm.create()`. Hence for initcode-too-large, core-geth
fires **neither** `OnEnter` nor `OnExit` for the create frame — the failure is
recorded as an opcode-level fault on the *parent* frame (`OnFault`/`OnOpcode`
with error, interpreter.go:148-159, 244-247).

**Arguments `OnExit` receives in the failure case** (evm.go:682-697):
`OnExit(depth, ret, startGas-leftOverGas, VMErrorFromErr(err), reverted)` —
i.e. depth, return data (typically nil/empty on abort), gas used (full
allocation for all-gas-consuming aborts since `gas.Exhaust()` sets leftover to
0 → gasUsed == startGas), the VM error, and a `reverted` bool (true for all
errors except the Homestead `ErrCodeStoreOutOfGas` exception).

**Always paired with CaptureEnter for create?** Yes, *for aborts inside
`create()`* — the `captureBegin` + `defer captureEnd` pairing makes enter/exit
atomically balanced. The only create-path failure that produces *no* pair is
EIP-3860 initcode-too-large, because it short-circuits at the parent opcode's
gas stage before `create()` is entered.

## Call site map

`VM.scala` tracer emission sites (`tracer.foreach(...)` plus
`state.env.tracer.foreach`):

| Method   | Line    | Event        | Fires before/after any `return` in method? |
|----------|---------|--------------|---------------------------------------------|
| `call`   | 58-67   | onCallEnter  | No `return` in method; fires at entry for sub-calls |
| `call`   | 100-102 | onCallExit   | No `return` in method; fires at tail (value-style) |
| `create` | 127-129 | onCallEnter  | Fires **before** the early `return` at :143 (enter is emitted, exit is not) |
| `create` | 207-209 | onCallExit   | Fires **after** / is **skipped by** the early `return` at :143 — the bug |
| `exec`   | 222     | onStep (VM-level tracer)        | In `@tailrec` loop; no `return` |
| `exec`   | 223     | onStep (state.env.tracer)       | In `@tailrec` loop; no `return` |

The only `return` in the file is the `scalafix:ok` one at VM.scala:143. It sits
between the `create` `onCallEnter` (:127) and `onCallExit` (:207), which is
exactly why it produces an unbalanced emission.

## Current tracer type and threading

- **Type:** `trait ExecutionTracer`
  (`vm/ExecutionTracer.scala`). A plain Scala 3 trait with default no-op method
  bodies — a callback/observer interface, **not** an actor, not a `given`, not
  an opaque type. Modelled on Besu's `OperationTracer`.
- **Methods (events):** `onStep[W,S](opCode, prevState, nextState)`,
  `onTxStart(from, to, gas, value, input)`,
  `onTxEnd(gasUsed, output, error)`,
  `onCallEnter(opCode, from, to, gas, value, input)`,
  `onCallExit(gasUsed, output, error)`,
  `getResult: org.json4s.JValue`.
- **Concrete implementations:** `StructLogTracer`, `CallTracer`,
  `PrestateTracer`, `VmTracer` (all in `vm/`). Each overrides only the hooks it
  needs; all keep mutable internal frame state (`mutable.Stack`).
- **Threading into VM:** constructor parameter on the `VM` class —
  `class VM[W, S](val tracer: Option[ExecutionTracer] = None)` (VM.scala:20-22).
  A second, independent tracer can also ride on the program context:
  `state.env.tracer` (fired at VM.scala:223). Both are fired in `exec` so a
  consumer wired either way observes steps. `onCallEnter`/`onCallExit` are fired
  only through the **VM-level** `tracer` field (VM.scala:58/100/127/207), *not*
  through `state.env.tracer` — a pre-existing asymmetry worth noting but out of
  scope for §8l-R1. Wiring originates in the ledger/RPC layers
  (`ledger/StxLedger.scala`, `ledger/BlockPreparator.scala`,
  `jsonrpc/DebugService.scala`, `jsonrpc/TraceService.scala`).

## Modernisation recommendation and proposed next step

`ExecutionTracer` is a synchronous callback interface with stateful (mutable)
implementations driven inline by the single-threaded VM exec loop. It is **not**
an actor and is **not** a LOOM (Pekko Classic→Typed) migration candidate — there
is no `Actor`/`receive` and no message protocol; introducing actor indirection
would add async hops on the hot consensus execution path for zero benefit and
real risk.

The trait is already idiomatic Scala 3 (default method bodies, type-parameterised
`onStep`). No `given`/typeclass conversion is warranted: tracers are runtime-
selected per RPC request (callTracer vs prestateTracer vs structLog vs vmTrace),
which is a value-level / `Option[ExecutionTracer]` decision, not a compile-time
typeclass resolution. An ADT event-stream refactor (emit a sealed `TraceEvent`
ADT, fold over it) is theoretically cleaner but is a non-trivial rewrite of four
tracers plus the VM emission sites for marginal gain — defer unless a future
multi-consumer requirement appears.

**Proposed next step (§8l-I, implementation):** keep the tracer type as-is; fix
only the emission balance.

1. In `VM.create()`, convert the EIP-3860 abort arm (VM.scala:138-147) from an
   early `return` to an expression arm of the `if/else` that produces
   `(result, newAddress)`, so the abort result flows into the existing trailing
   `onCallExit` block (lines 206-209). The abort tuple is already the right
   shape:
   `(invalidCallResult(...).copy(error = Some(InitCodeSizeLimit), gasRemaining = 0), Address(0))`.
   This makes `onCallExit` fire with `gasUsed = startGas - 0 = startGas`
   (full-consumption), `output = empty`, `error = Some("InitCodeSizeLimit")` —
   matching core-geth's all-gas-consumed exit semantics for the analogous
   in-`create` aborts.
2. Remove the `scalafix:ok DisableSyntax.return` suppression and the DEFER
   comment at VM.scala:140-143 once the `return` is gone.
3. Add/extend a `CallTracerSpec` / `VmTracer`-level test asserting that a
   sub-call CREATE with oversized initcode produces a balanced frame (exactly
   one push and one pop; failed CREATE appears in the parent's `calls` with the
   InitCodeSizeLimit error and no orphaned stack frame).

Optional, larger follow-up (not required for §8l-I correctness): to fully match
core-geth's "neither enter nor exit fires for initcode-too-large", move the
EIP-3860 size check ahead of the `onCallEnter` emission at VM.scala:126-129 and
suppress both hooks for that abort. This changes enter-emission behaviour and
should be specced separately if trace-format parity with go-ethereum's
callTracer for this specific abort is ever required.

## Verification

VERIFY: read VM.scala (full), ExecutionTracer.scala, VmTracer.scala,
CallTracer.scala; read core-geth evm.go (create/captureBegin/captureEnd),
interpreter.go (Run loop), gas_table.go (gasCreateEip3860/gasCreate2Eip3860),
common.go (CheckMaxInitCodeSize). No tests run (read-only research task). No
consensus code modified.
