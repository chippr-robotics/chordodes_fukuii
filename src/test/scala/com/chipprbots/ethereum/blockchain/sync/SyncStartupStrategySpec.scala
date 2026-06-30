package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** Tests the pure [[SyncController.selectSyncMode]] pre-flight function.
  *
  * The function picks the startup sync mode from peer counts and config without any actor or storage involvement. All
  * branches are exercised: optimistic SNAP (no peers yet), SNAP downgrade (too few snap-capable peers), confirmed SNAP
  * (≥ 3 snap peers), fast-only, and regular.
  */
class SyncStartupStrategySpec extends AnyFunSuite with ParallelTestExecution with TestSyncConfig:

  import SyncController.SyncMode
  import SyncController.selectSyncMode

  private val snapCfg = defaultSyncConfig.copy(doSnapSync = true, doFastSync = false)
  private val fastCfg = defaultSyncConfig.copy(doSnapSync = false, doFastSync = true)
  private val regularCfg = defaultSyncConfig.copy(doSnapSync = false, doFastSync = false)

  test("returns Snap with 0 peers — optimistic startup, no capability data yet", UnitTest) {
    assert(selectSyncMode(0, 0, 0L, snapCfg) == SyncMode.Snap)
  }

  test("downgrades to Fast when only 1 peer is snap-capable (below threshold of 3)", UnitTest) {
    assert(selectSyncMode(5, 1, 0L, snapCfg) == SyncMode.Fast)
  }

  test("returns Snap when exactly 3 snap-capable peers are available", UnitTest) {
    assert(selectSyncMode(5, 3, 0L, snapCfg) == SyncMode.Snap)
  }

  test("returns Snap when snap-capable peers are the majority", UnitTest) {
    assert(selectSyncMode(10, 8, 0L, snapCfg) == SyncMode.Snap)
  }

  test("returns Fast for fast-only config regardless of peer snap capability", UnitTest) {
    assert(selectSyncMode(0, 0, 0L, fastCfg) == SyncMode.Fast)
    assert(selectSyncMode(5, 0, 0L, fastCfg) == SyncMode.Fast)
  }

  test("returns Regular when neither snap nor fast is enabled", UnitTest) {
    assert(selectSyncMode(5, 5, 0L, regularCfg) == SyncMode.Regular)
  }
