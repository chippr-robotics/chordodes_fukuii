package com.chipprbots.ethereum.consensus.mining

import com.chipprbots.ethereum.nodebuilder.*
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.Logger

/** A [[MiningBuilder]] that builds a [[TestMining]]
  */
trait TestMiningBuilder:
  self: StdMiningBuilder =>
  protected def buildTestMining(): TestMining =
    buildMining() match
      case tm: TestMining => tm
      case other =>
        throw new RuntimeException(s"buildMining() returned ${other.getClass.getName}, expected TestMining")

/** A standard [[TestMiningBuilder]] cake. */
trait StdTestMiningBuilder
    extends com.chipprbots.ethereum.utils.InstanceConfigProvider
    with StdMiningBuilder
    with TestMiningBuilder
    with VmBuilder
    with VmConfigBuilder
    with ActorSystemBuilder
    with BlockchainBuilder
    with MESSBuilder
    with BlockQueueBuilder
    with ConsensusBuilder
    with StorageBuilder
    with BlockchainConfigBuilder
    with NodeKeyBuilder
    with SecureRandomBuilder
    with SyncConfigBuilder
    with MiningConfigBuilder
    with ShutdownHookBuilder
    with Logger
