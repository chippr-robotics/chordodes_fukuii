package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.SyncState
import com.chipprbots.ethereum.blockchain.sync.fast.StateStorageActor
import com.chipprbots.ethereum.blockchain.sync.fast.StateStorageActor.GetStorage
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.testing.Tags.*

class StateStorageActorSpec
    extends AnyFlatSpecLike
    with BeforeAndAfterAll
    with Matchers
    with Eventually
    with NormalPatience:

  private val testKit = ActorTestKit("FastSyncStateActorSpec_System")

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "FastSyncStateActor" should "eventually persist a newest state of a fast sync" taggedAs (UnitTest, SyncTest) in {
    val dataSource = EphemDataSource()
    val syncStateActor = testKit.spawn(StateStorageActor())
    val maxN = 10

    val targetBlockHeader = Fixtures.Blocks.ValidBlock.header
    syncStateActor ! StateStorageActor.Init(new FastSyncStateStorage(dataSource))
    (0 to maxN).foreach(n =>
      syncStateActor ! StateStorageActor.Persist(SyncState(targetBlockHeader).copy(downloadedNodesCount = n))
    )

    val probe = testKit.createTestProbe[Option[SyncState]]()
    eventually {
      syncStateActor ! GetStorage(probe.ref)
      val expected = SyncState(targetBlockHeader).copy(downloadedNodesCount = maxN)
      probe.expectMessage(Some(expected))
    }
  }
