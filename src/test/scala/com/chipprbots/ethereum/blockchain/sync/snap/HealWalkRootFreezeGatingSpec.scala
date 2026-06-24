package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Regression for the heal walk-root freeze fix (fix/heal-freeze-walk-root), adversarial-review must-fix.
  *
  * The freeze fix HOLDS the heal WALK root on an all-peers-stateless event (and on a stray pivot refresh) during
  * StateHealing instead of rolling it to the live head — so the completion frontier is finite and strictly shrinking,
  * exactly as core-geth heals against a root fixed at sync start.
  *
  * The must-fix: that HOLD is only valid when the spec-004 serve-root channel exists (`decoupledHealServeRoot ==
  * true`). With the flag FALSE there is NO serve-root channel (serve root == walk root), so holding the walk root
  * leaves the heal with no forward path once the root ages out of every peer's serve window → all-peers-stateless stall
  * (violates spec-004 FR-008 / SC-006). In that mode the old roll (refreshPivotInPlace) is the only forward path and
  * MUST fire.
  *
  * The gating decision is a pure function of `(phase, decoupledHealServeRoot)`, extracted to
  * `SNAPSyncController.healAllPeersStatelessAction` / `useHealingServeRootRefresh` so it is deterministically testable
  * without an actor harness. The two call sites delegate to these helpers verbatim.
  */
class HealWalkRootFreezeGatingSpec extends AnyFlatSpec with Matchers {

  import SNAPSyncController._

  // (a) decoupledHealServeRoot == TRUE during StateHealing → HOLD the walk root.
  //     The controller resumes dispatch + advances the serve root; it does NOT call refreshPivotInPlace, so no
  //     roll happens and the coordinator is never sent HealingPivotRefreshed (the walk-root-mutating message).
  "all-peers-stateless during StateHealing with decoupledHealServeRoot=true" should
    "HOLD the walk root (no roll)" taggedAs UnitTest in {
      healAllPeersStatelessAction(StateHealing, decoupledHealServeRoot = true) shouldBe HoldWalkRoot
    }

  // (b) decoupledHealServeRoot == FALSE during StateHealing → the heal must keep a forward path: ROLL.
  //     Without a serve-root channel, holding stalls — so the legacy refreshPivotInPlace roll must fire.
  "all-peers-stateless during StateHealing with decoupledHealServeRoot=false" should
    "ROLL the walk root (legacy forward path)" taggedAs UnitTest in {
      healAllPeersStatelessAction(StateHealing, decoupledHealServeRoot = false) shouldBe RollWalkRoot
    }

  // Coordinator-notify gate in completePivotRefreshWithStateRoot, mirrored property:
  //   - StateHealing + decoupled ON  → HealingServeRootRefresh (push fresh root to the SERVE root only; HOLD).
  //   - StateHealing + decoupled OFF → HealingPivotRefreshed (legacy roll; the only forward path).
  "the healing coordinator-notify gate" should
    "send HealingServeRootRefresh only during StateHealing with decoupling on" taggedAs UnitTest in {
      useHealingServeRootRefresh(StateHealing, decoupledHealServeRoot = true) shouldBe true
      useHealingServeRootRefresh(StateHealing, decoupledHealServeRoot = false) shouldBe false
    }

  // Download phases are UNTOUCHED: they always chase the pivot (HealingPivotRefreshed / roll), regardless of the
  // serve-root flag. Strictly scoped to StateHealing.
  "download phases" should "always roll (never engage the serve-root hold), regardless of the flag" taggedAs UnitTest in {
    val downloadPhases = Seq(AccountRangeSync, ByteCodeAndStorageSync, StateValidation, ChainDownloadCompletion)
    downloadPhases.foreach { phase =>
      healAllPeersStatelessAction(phase, decoupledHealServeRoot = true) shouldBe RollWalkRoot
      healAllPeersStatelessAction(phase, decoupledHealServeRoot = false) shouldBe RollWalkRoot
      useHealingServeRootRefresh(phase, decoupledHealServeRoot = true) shouldBe false
      useHealingServeRootRefresh(phase, decoupledHealServeRoot = false) shouldBe false
    }
  }
}
