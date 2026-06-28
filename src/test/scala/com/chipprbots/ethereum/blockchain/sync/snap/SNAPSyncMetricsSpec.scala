package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.testing.Tags.*

/** Setter round-trip for the spec 002 US2 healing-walk observability gauges (T019). The `SNAPSyncMetrics` push gauges
  * are registered directly on the (static default) `SimpleMeterRegistry` via `registry.gauge(name, AtomicLong)`, so the
  * meter name is the literal series string with no `app.` prefix. This test pushes a value through each new setter and
  * reads it back from the registry to confirm the wiring.
  */
class SNAPSyncMetricsSpec extends AnyFlatSpec with Matchers:

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    gauge should not be null
    gauge.value()

  "SNAPSyncMetrics" should "round-trip the new healing-walk phase/GC/inflation gauges via the registry" taggedAs UnitTest in {
    // Touch the object so the gauges are registered before we read them.
    SNAPSyncMetrics.setHealingPhaseQueueReadMs(12L)
    SNAPSyncMetrics.setHealingPhaseTrieReadMs(345L)
    SNAPSyncMetrics.setHealingPhaseQueueWriteMs(6L)
    SNAPSyncMetrics.setHealingGcPauseMs(78L)
    SNAPSyncMetrics.setHealingGcFraction(0.0125)
    SNAPSyncMetrics.setHealingInflationRatio(1.75)

    gaugeValue("snapsync.healing.phase.queue_read_ms.gauge") shouldBe 12.0 +- 1e-9
    gaugeValue("snapsync.healing.phase.trie_read_ms.gauge") shouldBe 345.0 +- 1e-9
    gaugeValue("snapsync.healing.phase.queue_write_ms.gauge") shouldBe 6.0 +- 1e-9
    gaugeValue("snapsync.healing.gc.pause_ms.gauge") shouldBe 78.0 +- 1e-9
    gaugeValue("snapsync.healing.gc.fraction.gauge") shouldBe 0.0125 +- 1e-9
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 1.75 +- 1e-9
  }

  it should "reflect the latest pushed value (last-write-wins gauges)" taggedAs UnitTest in {
    SNAPSyncMetrics.setHealingInflationRatio(1.0)
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 1.0 +- 1e-9

    SNAPSyncMetrics.setHealingInflationRatio(3.5)
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 3.5 +- 1e-9
  }
