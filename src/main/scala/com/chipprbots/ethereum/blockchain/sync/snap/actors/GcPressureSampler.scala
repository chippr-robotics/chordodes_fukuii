package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.lang.management.ManagementFactory

import scala.jdk.CollectionConverters.*

/** Windowed JVM garbage-collection pressure sampler for the post-SNAP frontier-rebuild walk (US2 / FR-007).
  *
  * Captures, at construction, a baseline equal to the sum of every GC collector's cumulative `getCollectionTime` (ms)
  * plus a baseline wall-clock reading. `sample()` returns the GC pause time and the pause fraction accumulated SINCE
  * that baseline — i.e. over the walk window — complementing (not replacing) the cumulative `jvm_gc_*` Prometheus
  * series, which are not windowed to the walk.
  *
  * This is observation-only: it reads JVM bean counters and never influences which trie nodes the walk
  * enqueues/visits/declares missing.
  *
  * The bean-sum supplier and the wall clock are injected (defaulting to the real ones) so a unit test can drive the
  * sampler deterministically with no `Thread.sleep`.
  *
  * @param gcCollectionTimeMsSupplier
  *   returns the current sum of all GC collectors' cumulative collection time in ms.
  * @param wallClockMsSupplier
  *   returns the current wall-clock time in ms.
  */
class GcPressureSampler(
    gcCollectionTimeMsSupplier: () => Long = GcPressureSampler.defaultGcCollectionTimeMs,
    wallClockMsSupplier: () => Long = () => System.currentTimeMillis()
):

  private val baselinePauseMs: Long = gcCollectionTimeMsSupplier()
  private val baselineWallMs: Long = wallClockMsSupplier()

  /** Sample the GC pressure accumulated since this sampler was constructed.
    *
    * @return
    *   a pair `(deltaPauseMs, fraction)` where `deltaPauseMs` is the GC pause time (ms) since the baseline and
    *   `fraction = deltaPauseMs / max(1, wallMsSinceBaseline)` — guarded against divide-by-zero so a sample taken in
    *   the same millisecond as construction yields `fraction == 0.0` rather than throwing.
    */
  def sample(): (Long, Double) =
    val deltaPauseMs = math.max(0L, gcCollectionTimeMsSupplier() - baselinePauseMs)
    val wallMsSinceBaseline = math.max(0L, wallClockMsSupplier() - baselineWallMs)
    val fraction = deltaPauseMs.toDouble / math.max(1L, wallMsSinceBaseline).toDouble
    (deltaPauseMs, fraction)

object GcPressureSampler:

  /** Sum of every registered GC collector's cumulative collection time (ms). A collector reporting `-1` (collection
    * time unavailable) contributes 0 so it never drags the sum negative.
    */
  def defaultGcCollectionTimeMs(): Long =
    ManagementFactory.getGarbageCollectorMXBeans.asScala.iterator
      .map(bean => math.max(0L, bean.getCollectionTime))
      .sum
