package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Deterministic unit test for [[GcPressureSampler]] (spec 002 US2 / T015). The GC-bean-sum supplier and the wall clock
  * are injected fakes driven by mutable cells, so the test advances time/pauses explicitly with no `Thread.sleep`.
  */
class GcPressureSamplerSpec extends AnyFlatSpec with Matchers:

  /** A mutable cell the test can advance between samples. */
  private class Cell(var value: Long) extends (() => Long):
    def apply(): Long = value

  "GcPressureSampler" should "report the GC pause delta and fraction since the baseline" taggedAs UnitTest in {
    val gc = new Cell(1000L) // baseline GC collection time = 1000ms
    val clock = new Cell(50_000L) // baseline wall = 50_000ms

    val sampler = new GcPressureSampler(gc, clock)

    // Advance: +400ms of GC pause over +2000ms of wall time.
    gc.value = 1400L
    clock.value = 52_000L

    val (pauseMs, fraction) = sampler.sample()
    pauseMs shouldBe 400L
    fraction shouldBe (400.0 / 2000.0) +- 1e-9 // 0.2
  }

  it should "accumulate from the construction baseline, not from the previous sample" taggedAs UnitTest in {
    val gc = new Cell(0L)
    val clock = new Cell(0L)
    val sampler = new GcPressureSampler(gc, clock)

    gc.value = 100L
    clock.value = 1000L
    val (firstPause, firstFraction) = sampler.sample()
    firstPause shouldBe 100L
    firstFraction shouldBe 0.1 +- 1e-9

    // A later sample reflects the TOTAL since baseline, not since the prior sample.
    gc.value = 250L
    clock.value = 2000L
    val (secondPause, secondFraction) = sampler.sample()
    secondPause shouldBe 250L
    secondFraction shouldBe 0.125 +- 1e-9
  }

  it should "guard divide-by-zero when sampled in the same wall millisecond as construction" taggedAs UnitTest in {
    val gc = new Cell(500L)
    val clock = new Cell(7_777L)
    val sampler = new GcPressureSampler(gc, clock)

    // No wall-time progress at all; some GC pause recorded.
    gc.value = 530L
    // clock.value unchanged → wallMsSinceBaseline == 0

    val (pauseMs, fraction) = sampler.sample()
    pauseMs shouldBe 30L
    // max(1, 0) in the denominator ⇒ 30 / 1 == 30.0, never a division by zero / NaN / Infinity.
    fraction shouldBe 30.0 +- 1e-9
    fraction.isNaN shouldBe false
    fraction.isInfinite shouldBe false
  }

  it should "clamp a non-increasing bean sum or backwards clock to a non-negative delta" taggedAs UnitTest in {
    val gc = new Cell(1000L)
    val clock = new Cell(5000L)
    val sampler = new GcPressureSampler(gc, clock)

    // Both go backwards (e.g. a collector reset); deltas must clamp to >= 0, not go negative.
    gc.value = 900L
    clock.value = 4000L

    val (pauseMs, fraction) = sampler.sample()
    pauseMs shouldBe 0L
    fraction shouldBe 0.0 +- 1e-9
  }
