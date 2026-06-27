package com.chipprbots.ethereum.db.cache

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.utils.NodeCacheConfig

// Thread-safe for `get` and `update` (called from multiple threads); `getValues`, `clear`, and
// `shouldPersist` are actor-context-only. The backing TrieMap provides lock-free concurrent reads
// and writes; multi-key updates are individually atomic (non-transactional cache misses are
// acceptable — the storage layer is the source of truth).
class MapCache[K, V](val cache: mutable.Map[K, V], config: NodeCacheConfig) extends Cache[K, V]:

  private val lastClear = new AtomicLong(System.nanoTime())

  override def update(toRemove: Seq[K], toUpsert: Seq[(K, V)]): Cache[K, V] =
    toRemove.foreach(key => cache -= key)
    toUpsert.foreach(element => cache += element._1 -> element._2)
    this

  override def getValues: Seq[(K, V)] =
    cache.toSeq

  override def get(key: K): Option[V] =
    cache.get(key)

  override def clear(): Unit =
    lastClear.getAndSet(System.nanoTime())
    cache.clear()

  override def shouldPersist: Boolean =
    cache.size > config.maxSize || isTimeToClear

  private def isTimeToClear: Boolean =
    FiniteDuration(System.nanoTime(), TimeUnit.NANOSECONDS) - FiniteDuration(
      lastClear.get(),
      TimeUnit.NANOSECONDS
    ) >= config.maxHoldTime

object MapCache:

  def getMap[K, V]: mutable.Map[K, V] = TrieMap.empty[K, V]

  def createCache[K, V](config: NodeCacheConfig): MapCache[K, V] =
    new MapCache[K, V](getMap[K, V], config)

  private case class TestCacheConfig(override val maxSize: Long, override val maxHoldTime: FiniteDuration)
      extends NodeCacheConfig

  def createTestCache[K, V](
      maxSize: Long,
      maxHoldTime: FiniteDuration = FiniteDuration(5, TimeUnit.MINUTES)
  ): Cache[K, V] =
    createCache[K, V](TestCacheConfig(maxSize, maxHoldTime))
