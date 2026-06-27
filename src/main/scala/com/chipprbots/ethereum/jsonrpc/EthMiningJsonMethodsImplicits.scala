package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JBool
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue

import com.chipprbots.ethereum.jsonrpc.EthMiningService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object EthMiningJsonMethodsImplicits extends JsonMethodsImplicits:
  given eth_mining: (NoParamsMethodDecoder[GetMiningRequest] & JsonEncoder[GetMiningResponse]) =
    new NoParamsMethodDecoder(GetMiningRequest()) with JsonEncoder[GetMiningResponse]:
      override def encodeJson(t: GetMiningResponse): JValue = JBool(t.isMining)

  given eth_getWork: (NoParamsMethodDecoder[GetWorkRequest] & JsonEncoder[GetWorkResponse]) =
    new NoParamsMethodDecoder(GetWorkRequest()) with JsonEncoder[GetWorkResponse]:
      override def encodeJson(t: GetWorkResponse): JsonAST.JValue =
        val powHeaderHash = encodeAsHex(t.powHeaderHash)
        val dagSeed = encodeAsHex(t.dagSeed)
        val target = encodeAsHex(t.target)
        val blockNumber = encodeAsHex(t.blockNumber)
        JArray(List(powHeaderHash, dagSeed, target, blockNumber))

  given eth_submitHashrate: (JsonMethodDecoder[SubmitHashRateRequest] & JsonEncoder[SubmitHashRateResponse]) =
    new JsonMethodDecoder[SubmitHashRateRequest] with JsonEncoder[SubmitHashRateResponse]:
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, SubmitHashRateRequest] =
        params match
          case Some(JArray(hashRate :: JString(id) :: Nil)) =>
            val result: Either[JsonRpcError, SubmitHashRateRequest] = for
              rate <- extractQuantity(hashRate)
              miner <- extractHash(id)
            yield SubmitHashRateRequest(rate, miner)
            result
          case _ =>
            Left(InvalidParams())

      override def encodeJson(t: SubmitHashRateResponse): JValue = JBool(t.success)

  given eth_hashrate: (NoParamsMethodDecoder[GetHashRateRequest] & JsonEncoder[GetHashRateResponse]) =
    new NoParamsMethodDecoder(GetHashRateRequest()) with JsonEncoder[GetHashRateResponse]:
      override def encodeJson(t: GetHashRateResponse): JsonAST.JValue = encodeAsHex(t.hashRate)

  given eth_coinbase: (NoParamsMethodDecoder[GetCoinbaseRequest] & JsonEncoder[GetCoinbaseResponse]) =
    new NoParamsMethodDecoder(GetCoinbaseRequest()) with JsonEncoder[GetCoinbaseResponse]:
      override def encodeJson(t: GetCoinbaseResponse): JsonAST.JValue =
        encodeAsHex(t.address.bytes)

  given eth_submitWork: (JsonMethodDecoder[SubmitWorkRequest] & JsonEncoder[SubmitWorkResponse]) =
    new JsonMethodDecoder[SubmitWorkRequest] with JsonEncoder[SubmitWorkResponse]:
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, SubmitWorkRequest] = params match
        case Some(JArray(JString(nonce) :: JString(powHeaderHash) :: JString(mixHash) :: Nil)) =>
          for
            n <- extractBytes(nonce)
            p <- extractBytes(powHeaderHash)
            m <- extractBytes(mixHash)
          yield SubmitWorkRequest(n, p, m)
        case _ =>
          Left(InvalidParams())

      override def encodeJson(t: SubmitWorkResponse): JValue = JBool(t.success)

  given miner_start: (NoParamsMethodDecoder[StartMinerRequest] & JsonEncoder[StartMinerResponse]) =
    new NoParamsMethodDecoder(StartMinerRequest()) with JsonEncoder[StartMinerResponse]:
      override def encodeJson(t: StartMinerResponse): JValue = JBool(t.success)

  given miner_stop: (NoParamsMethodDecoder[StopMinerRequest] & JsonEncoder[StopMinerResponse]) =
    new NoParamsMethodDecoder(StopMinerRequest()) with JsonEncoder[StopMinerResponse]:
      override def encodeJson(t: StopMinerResponse): JValue = JBool(t.success)

  given miner_getStatus: (NoParamsMethodDecoder[GetMinerStatusRequest] & JsonEncoder[GetMinerStatusResponse]) =
    new NoParamsMethodDecoder(GetMinerStatusRequest()) with JsonEncoder[GetMinerStatusResponse]:
      override def encodeJson(t: GetMinerStatusResponse): JValue =
        import org.json4s.JsonDSL.*
        ("isMining" -> t.isMining) ~
          ("coinbase" -> encodeAsHex(t.coinbase.bytes)) ~
          ("hashRate" -> encodeAsHex(t.hashRate)) ~
          ("blocksMinedCount" -> t.blocksMinedCount.map(_.toString))

  given eth_setEtherbase
      : (JsonMethodDecoder[EthMiningService.SetEtherbaseRequest] & JsonEncoder[EthMiningService.SetEtherbaseResponse]) =
    new JsonMethodDecoder[EthMiningService.SetEtherbaseRequest] with JsonEncoder[EthMiningService.SetEtherbaseResponse]:
      override def decodeJson(
          params: Option[JsonAST.JArray]
      ): Either[JsonRpcError, EthMiningService.SetEtherbaseRequest] =
        params match
          case Some(JArray(JString(addressStr) :: Nil)) =>
            extractAddress(addressStr).map(address => EthMiningService.SetEtherbaseRequest(address))
          case _ =>
            Left(InvalidParams())

      override def encodeJson(t: EthMiningService.SetEtherbaseResponse): JValue = JBool(t.success)
