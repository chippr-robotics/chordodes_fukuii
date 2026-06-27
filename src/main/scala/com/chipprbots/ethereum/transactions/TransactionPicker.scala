package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.Logger

trait TransactionPicker extends Logger:

  protected def pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command]
  protected def getTransactionFromPoolTimeout: FiniteDuration
  protected def scheduler: Scheduler

  given timeout: Timeout = Timeout(getTransactionFromPoolTimeout)

  def getTransactionsFromPool: IO[PendingTransactionsResponse] =
    pendingTransactionsManager
      .askForTyped[PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactionsReq(_))(using
        timeout,
        scheduler
      )
      .handleError { ex =>
        log.error("Failed to get transactions, mining block with empty transactions list", ex)
        PendingTransactionsResponse(Nil)
      }
