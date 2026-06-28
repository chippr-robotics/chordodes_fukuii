# Loop Handoff Protocol

The contract for transferring a completed iteration from maker to checker, and
for the orchestrator to record the verdict and advance the loop state.

---

## When This Protocol Applies

This protocol runs at the end of every EXECUTE phase in a loop, before the loop
can advance to the next iteration or declare DONE.

It does NOT apply to one-off manual sessions (use migration-handoff.md for those).

---

## 1. What the Maker Must Surface

After EXECUTE completes, the maker must produce all of the following before
control passes to the checker:

1. **Change summary** — one-line diffstat or commit SHA. Example:
   ```
   Change: 3 files changed, 42 insertions(+), 18 deletions(-)
   ```
   or
   ```
   Change: commit abc1234 "fix: promote cat=deprecation to error in ScalacOptions"
   ```

2. **verify.sh output** — the maker runs:
   ```sh
   .claude/looping/bin/verify.sh <recipe-id> <ledger-dir>
   ```
   and lets the complete output (including every `GATE:` line and the
   `LOOP:` aggregate) appear in the transcript. The maker does not summarize
   or paraphrase the output. The raw lines must be visible.

3. **Open delta** — a short statement of remaining work:
   - If this was the final iteration: "Open delta: none"
   - If work remains: "Open delta: <what is left to fix>"

The maker must NOT assert "the build is clean" or "I believe this passes"
without the sentinel lines from verify.sh backing that claim.

---

## 2. What the Checker Reads

The checker reads only:

1. The `GATE:<name> RESULT:<PASS|FAIL>` lines from the transcript (from
   verify.sh output, not from the maker's narrative).
2. The `LOOP:<id> ALL_GATES:<PASS|FAIL> ...` aggregate line.
3. The open delta statement.
4. For conformance gates: the conformance report body (not just the sentinel).

The checker does NOT:
- Accept the maker's claim of success as a substitute for sentinel lines.
- Skip reading the gate output because the maker said it passed.
- Issue CONFIRM:DONE if the aggregate line is missing from the transcript.

---

## 3. Checker Verdicts

The checker issues exactly one of:

```
CONFIRM:DONE
```
When all of these hold:
- `LOOP:<id> ALL_GATES:PASS` is present in the transcript
- All `GATE:` lines show RESULT:PASS
- Open delta is "none"
- No constraint in the recipe spec was violated

```
CONFIRM:ITERATE reason=<what failed or remains>
```
When any of these hold:
- Any `GATE:` line shows RESULT:FAIL
- `LOOP:<id> ALL_GATES:FAIL` is present
- Open delta is non-empty
- A recipe constraint was violated (e.g., a new @nowarn appeared)

The checker must include the reason even on CONFIRM:ITERATE — "reason=see-transcript"
is not sufficient.

---

## 4. Orchestrator Records the Verdict

After the checker issues its verdict, the orchestrator:

1. Appends to `state/<id>-<ts>/ledger.md`:
   ```markdown
   ## Iteration N
   Plan: <the PLAN statement from this iteration>
   Change: <from maker surface>
   Gates: <paste GATE: lines verbatim>
   Result: PASS|FAIL
   Failed: <gate names or none>
   Checker verdict: CONFIRM:DONE|CONFIRM:ITERATE reason=<text>
   Next delta: <open delta or none>
   ```

2. Updates `state/<id>-<ts>/attempts.json`:
   ```json
   {
     "iteration": N,
     "approach": "<brief description of what was tried>",
     "outcome": "accepted|rejected",
     "reason": "<why it was rejected, or what it fixed>"
   }
   ```
   Record rejected approaches so the next iteration does not repeat a dead end.

3. Runs `budget-check.sh` to confirm the next iteration is within cap.

4. If CONFIRM:DONE: closes the loop, writes a final summary to the ledger.
   If CONFIRM:ITERATE: starts the next DISCOVER->PLAN->EXECUTE->VERIFY cycle.

---

## 5. Ralph Guard (Anti-Self-Grading)

The Ralph guard is violated if any of these occur:

- The maker issues CONFIRM:DONE for itself.
- The orchestrator (main session) issues CONFIRM:DONE without invoking the checker.
- The transcript contains "all gates should pass" without the `LOOP:` sentinel line.
- CONFIRM:DONE is issued when the ledger shows an open delta.

If a Ralph guard violation is detected, treat it as CONFIRM:ITERATE and flag the
violation explicitly in the ledger.

---

## 6. Relationship to Other Protocols

- **migration-handoff.md** — for interrupted sessions (not for loop iteration handoffs)
- **testing-protocol.md** — governs when tests run during EXECUTE; the checker does not
  re-run tests; it reads the verify.sh output
- **risk-stratified-commit.md** — applies to what the maker commits during EXECUTE
- **consensus-change-protocol.md** — overrides this protocol for consensus surfaces:
  the proactive checker (forge/beacon) must be consulted in DISCOVER, not just in VERIFY
