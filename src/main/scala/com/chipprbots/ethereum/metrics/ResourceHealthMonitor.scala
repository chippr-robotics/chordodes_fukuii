package com.chipprbots.ethereum.metrics

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import com.chipprbots.ethereum.blockchain.sync.snap.actors.GcPressureSampler
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource

/** Node-wide resource observability — emits a [[ResourceHealthMonitor.LogTag]] line every 60 seconds covering JVM heap,
  * GC pressure, off-heap, free physical RAM, swap, system load, and RocksDB memtable footprint.
  *
  * Sync coordinators call [[ResourceHealthMonitor.UpdatePhaseContext]] to annotate the pulse with their current phase
  * and key counters. The monitor is purely observational: it reads metrics and logs; it never influences any consensus
  * or sync logic.
  *
  * Spawn once at node startup via the Classic→Typed adapter:
  * {{{
  * import org.apache.pekko.actor.typed.scaladsl.adapter._
  * system.spawn(ResourceHealthMonitor(dataSource), ResourceHealthMonitor.ActorName)
  * }}}
  *
  * Coordinators annotate via path-selection (no constructor changes needed):
  * {{{
  * context.system
  *   .actorSelection(s"/user/${ResourceHealthMonitor.ActorName}")
  *   .tell(ResourceHealthMonitor.UpdatePhaseContext("HEAL-BFS-L6", Map("frontier" -> "42")), ActorRef.noSender)
  * }}}
  */
object ResourceHealthMonitor {
  val ActorName: String = "resource-health-monitor"
  val LogTag: String = "RESOURCE-PULSE"

  sealed trait Command
  private[metrics] case object Tick extends Command
  case class UpdatePhaseContext(phase: String, context: Map[String, String]) extends Command

  def apply(dataSource: DataSource): Behavior[Command] = {
    val rocksOpt: Option[RocksDbDataSource] = dataSource match {
      case rdb: RocksDbDataSource => Some(rdb)
      case _                      => None
    }
    val rt    = Runtime.getRuntime
    val memMX = ManagementFactory.getMemoryMXBean
    val osMX  = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]

    Behaviors.setup { ctx =>
      var phaseCtx: Map[String, String] = Map("phase" -> "INIT")
      var lastGcMs: Long = GcPressureSampler.defaultGcCollectionTimeMs()
      var lastGcCount: Long = ManagementFactory.getGarbageCollectorMXBeans.asScala
        .map(b => math.max(0L, b.getCollectionCount))
        .sum

      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(Tick, 60.seconds)
        ctx.log.info(s"[$LogTag] ResourceHealthMonitor started — 60s observability pulse active")

        def emitPulse(): Unit = {
          val gcMsNow = GcPressureSampler.defaultGcCollectionTimeMs()
          val gcCntNow = ManagementFactory.getGarbageCollectorMXBeans.asScala
            .map(b => math.max(0L, b.getCollectionCount))
            .sum

          val gcPct   = (gcMsNow - lastGcMs) / 600.0
          val gcCalls = gcCntNow - lastGcCount
          lastGcMs = gcMsNow
          lastGcCount = gcCntNow

          val heapUsed = (rt.totalMemory - rt.freeMemory) >> 20
          val heapMax  = rt.maxMemory >> 20
          val heapPct  = if (heapMax > 0) heapUsed * 100 / heapMax else 0L
          val offHeap  = memMX.getNonHeapMemoryUsage.getUsed >> 20
          val sysFreeGB = f"${osMX.getFreePhysicalMemorySize / 1e9}%.1f"
          val swap     = readSwapUsedMB()
          val load     = f"${osMX.getSystemLoadAverage}%.1f"
          val rdbMem   = rocksOpt.map(_.totalMemtableSizeMB).getOrElse(0L)

          val ctxStr = phaseCtx.map { case (k, v) => s"$k=$v" }.mkString(" ")
          ctx.log.info(
            s"[$LogTag] $ctxStr heap=$heapUsed/${heapMax}MB($heapPct%) " +
              s"gc-pressure=${f"$gcPct%.1f"}% gc-calls=$gcCalls off-heap=${offHeap}MB " +
              s"sys-free=${sysFreeGB}GB swap=${swap}MB load=$load rocksdb-mem=${rdbMem}MB"
          )
        }

        Behaviors.receiveMessage {
          case Tick =>
            emitPulse()
            Behaviors.same
          case UpdatePhaseContext(phase, context) =>
            phaseCtx = Map("phase" -> phase) ++ context
            Behaviors.same
        }
      }
    }
  }

  private def readSwapUsedMB(): Long =
    try {
      val path = Paths.get("/proc/meminfo")
      if (!Files.exists(path)) return -1L
      var total = 0L
      var free  = 0L
      Files.readAllLines(path).asScala.foreach { line =>
        if (line.startsWith("SwapTotal:")) total = line.split("\\s+")(1).toLong
        else if (line.startsWith("SwapFree:")) free = line.split("\\s+")(1).toLong
      }
      (total - free) / 1024L
    } catch {
      case _: Exception => -1L
    }
}
