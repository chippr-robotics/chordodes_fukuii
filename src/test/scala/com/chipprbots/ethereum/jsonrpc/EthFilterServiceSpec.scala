package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.EthFilterService.*
import com.chipprbots.ethereum.jsonrpc.FilterManager as FM
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.FilterConfig

class EthFilterServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with TypeCheckedTripleEquals:

  implicit val runtime: IORuntime = IORuntime.global
  implicit val classicSystem: org.apache.pekko.actor.ActorSystem = testKit.system.classicSystem

  it should "handle newFilter request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val filter: Filter = Filter(None, None, None, Seq.empty)
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newFilter(NewFilterRequest(filter)).unsafeToFuture()
    val cmd: FM.NewLogFilter = filterManager.expectMessageType[FM.NewLogFilter]
    cmd.replyTo ! FM.NewFilterResponse(123)
    res.futureValue shouldEqual Right(NewFilterResponse(123))

  it should "handle newBlockFilter request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newBlockFilter(NewBlockFilterRequest()).unsafeToFuture()
    val cmd: FM.NewBlockFilter = filterManager.expectMessageType[FM.NewBlockFilter]
    cmd.replyTo ! FM.NewFilterResponse(123)
    res.futureValue shouldEqual Right(NewFilterResponse(123))

  it should "handle newPendingTransactionFilter request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newPendingTransactionFilter(NewPendingTransactionFilterRequest()).unsafeToFuture()
    val cmd: FM.NewPendingTransactionFilter = filterManager.expectMessageType[FM.NewPendingTransactionFilter]
    cmd.replyTo ! FM.NewFilterResponse(123)
    res.futureValue shouldEqual Right(NewFilterResponse(123))

  it should "handle uninstallFilter request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val res: Future[Either[JsonRpcError, UninstallFilterResponse]] =
      ethFilterService.uninstallFilter(UninstallFilterRequest(123)).unsafeToFuture()
    val cmd: FM.UninstallFilter = filterManager.expectMessageType[FM.UninstallFilter]
    cmd.replyTo ! FM.UninstallFilterResponse
    res.futureValue shouldEqual Right(UninstallFilterResponse(true))

  it should "handle getFilterChanges request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val res: Future[Either[JsonRpcError, GetFilterChangesResponse]] =
      ethFilterService.getFilterChanges(GetFilterChangesRequest(123)).unsafeToFuture()
    val cmd: FM.GetFilterChanges = filterManager.expectMessageType[FM.GetFilterChanges]
    val changes: FM.LogFilterChanges = FM.LogFilterChanges(Seq.empty)
    cmd.replyTo ! changes
    res.futureValue shouldEqual Right(GetFilterChangesResponse(changes))

  it should "handle getFilterLogs request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val res: Future[Either[JsonRpcError, GetFilterLogsResponse]] =
      ethFilterService.getFilterLogs(GetFilterLogsRequest(123)).unsafeToFuture()
    val cmd: FM.GetFilterLogs = filterManager.expectMessageType[FM.GetFilterLogs]
    val logs: FM.LogFilterLogs = FM.LogFilterLogs(Seq.empty)
    cmd.replyTo ! logs
    res.futureValue shouldEqual Right(GetFilterLogsResponse(logs))

  it should "handle getLogs request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    (() => mockBlockchainReader.getBestBlockNumber).when().returns(BigInt(100))
    val filter: Filter = Filter(None, None, None, Seq.empty)
    val res: Future[Either[JsonRpcError, GetLogsResponse]] =
      ethFilterService.getLogs(GetLogsRequest(filter)).unsafeToFuture()
    val cmd: FM.GetLogs = filterManager.expectMessageType[FM.GetLogs]
    val logs: FM.LogFilterLogs = FM.LogFilterLogs(Seq.empty)
    cmd.replyTo ! logs
    res.futureValue shouldEqual Right(GetLogsResponse(logs))

  class TestSetup:
    val filterManager: TestProbe[FM.Command] = testKit.createTestProbe[FM.Command]()
    val filterConfig: FilterConfig = new FilterConfig:
      override val filterTimeout: FiniteDuration = Timeouts.normalTimeout
      override val filterManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
    val mockBlockchainReader: BlockchainReader = stub[BlockchainReader]

    lazy val ethFilterService = new EthFilterService(
      filterManager.ref,
      filterConfig,
      mockBlockchainReader
    )
