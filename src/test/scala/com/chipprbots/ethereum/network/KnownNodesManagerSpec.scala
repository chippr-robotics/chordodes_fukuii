package com.chipprbots.ethereum.network

import java.net.URI

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.KnownNodesStorage
import com.chipprbots.ethereum.network.KnownNodesManager.KnownNodes
import com.chipprbots.ethereum.network.KnownNodesManager.KnownNodesManagerConfig
import com.chipprbots.ethereum.testing.Tags.*

class KnownNodesManagerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  "KnownNodesManager" should "keep a list of nodes and persist changes" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessage(KnownNodes(Set.empty))

    knownNodesManager ! KnownNodesManager.AddKnownNode(uri(1))
    knownNodesManager ! KnownNodesManager.AddKnownNode(uri(2))
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessage(KnownNodes(Set(uri(1), uri(2))))
    knownNodesStorage.getKnownNodes shouldBe Set.empty

    knownNodesManager ! KnownNodesManager.PersistChanges
    // After PersistChanges is processed, the GetKnownNodesReq round-trip guarantees ordering.
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessage(KnownNodes(Set(uri(1), uri(2))))
    knownNodesStorage.getKnownNodes shouldBe Set(uri(1), uri(2))

    knownNodesManager ! KnownNodesManager.AddKnownNode(uri(3))
    knownNodesManager ! KnownNodesManager.AddKnownNode(uri(4))
    knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri(1))
    knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri(4))

    knownNodesManager ! KnownNodesManager.PersistChanges
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessage(KnownNodes(Set(uri(2), uri(3))))

    knownNodesStorage.getKnownNodes shouldBe Set(uri(2), uri(3))

  it should "respect max nodes limit" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessage(KnownNodes(Set.empty))

    (1 to 10).foreach { n =>
      knownNodesManager ! KnownNodesManager.AddKnownNode(uri(n))
    }
    knownNodesManager ! KnownNodesManager.PersistChanges

    // Round-trip to ensure PersistChanges has been processed before reading storage.
    knownNodesManager ! KnownNodesManager.GetKnownNodesReq(client.ref)
    client.expectMessageType[KnownNodes]

    knownNodesStorage.getKnownNodes.size shouldBe 5

  trait TestSetup:
    private val setup: EphemBlockchainTestSetup = new EphemBlockchainTestSetup {}
    val knownNodesStorage: KnownNodesStorage = setup.storagesInstance.storages.knownNodesStorage

    val config: KnownNodesManagerConfig = KnownNodesManagerConfig(persistInterval = 5.seconds, maxPersistedNodes = 5)

    val client: TestProbe[KnownNodes] = testKit.createTestProbe[KnownNodes]()

    def uri(n: Int): URI = new URI(s"enode://test$n@test$n.com:9000")

    val knownNodesManager: ActorRef[KnownNodesManager.Command] =
      testKit.spawn(KnownNodesManager(config, knownNodesStorage))
