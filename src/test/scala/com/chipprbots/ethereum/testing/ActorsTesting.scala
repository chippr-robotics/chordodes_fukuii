package com.chipprbots.ethereum.testing
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.testkit.TestActor.AutoPilot

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse

object ActorsTesting:
  def simpleAutoPilot(makeResponse: PartialFunction[Any, Any]): AutoPilot =
    new AutoPilot:
      def run(sender: ActorRef, msg: Any): AutoPilot =
        val response = makeResponse.lift(msg)
        response match
          case Some(value) => sender ! value
          case _           => ()
        this

  /** AutoPilot for SyncController stubs that replies via the typed replyTo embedded in GetStatus. Classic TestProbe
    * sender() is not the reply target for typed asks — the replyTo field is.
    */
  def syncStatusAutoPilot(status: SyncProtocol.Status): AutoPilot = new AutoPilot:
    def run(sender: ActorRef, msg: Any): AutoPilot =
      msg match
        case SyncController.WrappedSyncProtocol(gs: SyncProtocol.GetStatus) => gs.replyTo ! status
        case _                                                              => ()
      this

  /** AutoPilot for PendingTransactionsManager stubs. Responds to the Typed ask pattern (GetPendingTransactionsReq with
    * replyTo) and forwards PTM Commands via fire-and-forget.
    *
    * Use this when a TestProbe is set as a PTM stub. The Typed ask sends GetPendingTransactionsReq to the probe; reply
    * must go to replyTo, not sender().
    */
  def ptmAutoPilot(response: PendingTransactionsResponse): AutoPilot = new AutoPilot:
    def run(sender: ActorRef, msg: Any): AutoPilot =
      msg match
        case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
          replyTo ! response
          this
        case _: PendingTransactionsManager.Command => this
        case _                                     => this
