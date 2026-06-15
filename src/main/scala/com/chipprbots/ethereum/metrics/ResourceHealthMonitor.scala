package com.chipprbots.ethereum.metrics

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Props

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
  * Spawn once at node startup:
  * {{{
  * system.actorOf(ResourceHealthMonitor.props(dataSource), ResourceHealthMonitor.ActorName)
  * }}}
  *
  * Coordinators annotate via path-selection (no constructor changes needed):
  * {{{
  * context.system
  *   .actorSelection(s"/user/${ResourceHealthMonitor.ActorName}")
  *   .tell(ResourceHealthMonitor.UpdatePhaseContext("HEAL-BFS-L6", Map("frontier" -> "42")), ActorRef.noSender)
  * }}}
  */
class ResourceHealthMonitor(dataSource: DataSource) extends Actor with ActorLogging {
  import ResourceHealthMonitor._

  private val rt = Runtime.getRuntime
  private val memMX = ManagementFactory.getMemoryMXBean
  private val osMX = ManagementFactory.getOperatingSystemMXBean
    .asInstanceOf[com.sun.management.OperatingSystemMXBean]

  private val rocksOpt: Option[RocksDbDataSource] = dataSource match {
    case rdb: RocksDbDataSource => Some(rdb)
    case _                      => None
  }

  private var ticker: Cancellable = _
  private var phaseCtx: Map[String, String] = Map("phase" -> "INIT")
  private var lastGcMs: Long = GcPressureSampler.defaultGcCollectionTimeMs()
  private var lastGcCount: Long = ManagementFactory.getGarbageCollectorMXBeans.asScala
    .map(b => math.max(0L, b.getCollectionCount))
    .sum

  override def preStart(): Unit = {
    ticker = context.system.scheduler.scheduleAtFixedRate(
      60.seconds,
      60.seconds,
      self,
      Tick
    )(context.dispatcher, self)
    log.info(s"[$LogTag] ResourceHealthMonitor started — 60s observability pulse active")
  }

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = {
    case Tick                           => emitPulse()
    case UpdatePhaseContext(phase, ctx) => phaseCtx = Map("phase" -> phase) ++ ctx
  }

  private def emitPulse(): Unit = {
    val gcMsNow = GcPressureSampler.defaultGcCollectionTimeMs()
    val gcCntNow = ManagementFactory.getGarbageCollectorMXBeans.asScala
      .map(b => math.max(0L, b.getCollectionCount))
      .sum

    val gcPct = (gcMsNow - lastGcMs) / 600.0 // ms in 60 000 ms window → percent
    val gcCalls = gcCntNow - lastGcCount
    lastGcMs = gcMsNow
    lastGcCount = gcCntNow

    val heapUsed = (rt.totalMemory - rt.freeMemory) >> 20
    val heapMax = rt.maxMemory >> 20
    val heapPct = if (heapMax > 0) heapUsed * 100 / heapMax else 0L
    val offHeap = memMX.getNonHeapMemoryUsage.getUsed >> 20
    val sysFreeGB = f"${osMX.getFreePhysicalMemorySize / 1e9}%.1f"
    val swap = readSwapUsedMB()
    val load = f"${osMX.getSystemLoadAverage}%.1f"
    val rdbMem = rocksOpt.map(_.totalMemtableSizeMB).getOrElse(0L)

    val ctx = phaseCtx.map { case (k, v) => s"$k=$v" }.mkString(" ")
    log.info(
      s"[$LogTag] $ctx heap=$heapUsed/${heapMax}MB($heapPct%) " +
        s"gc-pressure=${f"$gcPct%.1f"}% gc-calls=$gcCalls off-heap=${offHeap}MB " +
        s"sys-free=${sysFreeGB}GB swap=${swap}MB load=$load rocksdb-mem=${rdbMem}MB"
    )
  }

  private def readSwapUsedMB(): Long =
    try {
      val path = Paths.get("/proc/meminfo")
      if (!Files.exists(path)) return -1L
      var total = 0L
      var free = 0L
      Files.readAllLines(path).asScala.foreach { line =>
        if (line.startsWith("SwapTotal:")) total = line.split("\\s+")(1).toLong
        else if (line.startsWith("SwapFree:")) free = line.split("\\s+")(1).toLong
      }
      (total - free) / 1024L // kB → MB
    } catch {
      case _: Exception => -1L
    }
}

object ResourceHealthMonitor {
  val ActorName: String = "resource-health-monitor"
  val LogTag: String = "RESOURCE-PULSE"

  private[metrics] case object Tick

  /** Sent by any sync coordinator to annotate the next pulse with its current phase and context.
    *
    * @param phase
    *   short label shown as `phase=<value>` (e.g. `"HEAL-BFS-L6"`, `"REGULAR-SYNC"`)
    * @param context
    *   additional key=value pairs appended to the pulse line (e.g. `Map("frontier" -> "42")`)
    */
  case class UpdatePhaseContext(phase: String, context: Map[String, String])

  def props(dataSource: DataSource): Props = Props(new ResourceHealthMonitor(dataSource))
}
