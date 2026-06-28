package com.chipprbots.ethereum.db.dataSource

import java.util.concurrent.atomic.AtomicBoolean

import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.utils.Logger

/** Prometheus poll gauges for the RocksDB block-cache hit/miss tickers (spec 002 US2 / FR-005).
  *
  * These sample [[RocksDbDataSource.cacheStats]] at scrape time, so the operator can confirm a slow heal walk is
  * cache-miss-bound before raising `max-open-files` / `block-cache-size`. The data is populated only when
  * `db.rocksdb.enable-statistics = true`; with statistics off, `cacheStats == None` and every gauge reads `0.0`.
  *
  * Registered once at node startup against the live state [[RocksDbDataSource]] (see `BaseNode.startMetricsClient`).
  * The closures NEVER throw at scrape time: a `None` (flag off) or a zero denominator both read `0.0`.
  *
  * Exported series (the `app_` prefix + dot→underscore mapping is applied by the Prometheus exporter):
  *   - `app_db_rocksdb_block_cache_hit`
  *   - `app_db_rocksdb_block_cache_miss`
  *   - `app_db_rocksdb_block_cache_hit_rate` — `hit / (hit + miss)`, `0.0` when `None`/denominator 0
  *   - `app_db_rocksdb_index_filter_hit`
  *   - `app_db_rocksdb_index_filter_miss`
  *
  * Mirrors the closure-poll-gauge pattern of `EngineApiMetrics` / `PoWMiningMetrics`.
  */
object RocksDbCacheMetrics extends MetricsContainer with Logger:

  private val registered = new AtomicBoolean(false)

  /** Register the block-cache poll gauges against the given DataSource.
    *
    * Idempotent: a second call is a no-op (avoids duplicate Micrometer registration). When the DataSource is not a
    * concrete [[RocksDbDataSource]] (e.g. an in-memory `EphemDataSource` in tests), registration is skipped.
    *
    * @param dataSource
    *   the production state DataSource; only `RocksDbDataSource` exposes `cacheStats`.
    */
  def register(dataSource: DataSource): Unit =
    dataSource match
      case rdb: RocksDbDataSource =>
        if registered.compareAndSet(false, true) then
          // hit
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.hit",
            () => rdb.cacheStats.map(_._1.toDouble).getOrElse(0.0)
          )
          // miss
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.miss",
            () => rdb.cacheStats.map(_._2.toDouble).getOrElse(0.0)
          )
          // hit_rate = hit / (hit + miss); 0.0 when None or denominator 0
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.hit_rate",
            () =>
              rdb.cacheStats match
                case Some((hit, miss, _, _)) =>
                  val total = hit + miss
                  if total <= 0L then 0.0 else hit.toDouble / total.toDouble
                case None => 0.0
          )
          // index_filter hit / miss
          val _ = metrics.gauge(
            "db.rocksdb.index_filter.hit",
            () => rdb.cacheStats.map(_._3.toDouble).getOrElse(0.0)
          )
          val _ = metrics.gauge(
            "db.rocksdb.index_filter.miss",
            () => rdb.cacheStats.map(_._4.toDouble).getOrElse(0.0)
          )
          log.info(
            "RocksDB block-cache metrics registered (app_db_rocksdb_block_cache_* / index_filter_*); " +
              "values populated only when db.rocksdb.enable-statistics = true"
          )
      case _ =>
        // Not a RocksDbDataSource (e.g. EphemDataSource) — no cacheStats to poll; skip silently.
        ()
