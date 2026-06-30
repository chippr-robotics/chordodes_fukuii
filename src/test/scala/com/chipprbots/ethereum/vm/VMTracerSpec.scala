package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

/** Tests that VM.call() and VM.create() emit balanced onCallEnter/onCallExit even on the latent abort paths (C2/C3/L2)
  * identified in the ETH/Sepolia assumption audit Thread 6.
  *
  * Background: §ETH-T6-A — three defensive guards inside the if/else branches can throw past the trailing onCallExit.
  * The try/finally refactor closes the structural hole so "every enter has an exit" holds by construction, matching
  * Besu's traceContextExit guarantee.
  *
  * C2 — VM.create(): require(recipientAddr.isEmpty) — fires when Some(addr) passed C3 — VM.create():
  * require(doTransfer) — fires when doTransfer=false (covered by C2 test) L2 — VM.call(): throw
  * IllegalArgumentException — fires when recipientAddr=None
  */
class VMTracerSpec extends AnyFreeSpec with Matchers:

  private class CountingTracer extends ExecutionTracer:
    var enterCount = 0
    var exitCount = 0

    override def onCallEnter(
        opCode: String,
        from: Address,
        to: Address,
        gas: BigInt,
        value: BigInt,
        input: ByteString
    ): Unit = enterCount += 1

    override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
      exitCount += 1

    override def getResult: org.json4s.JValue = org.json4s.JNull

  private val senderAddr = Address(0xcafebabeL)
  private val senderAcc = Account(nonce = 1, balance = 1000000)
  private val world = MockWorldState().saveAccount(senderAddr, senderAcc)

  private val blockHeader = BlockFixtures.ValidBlock.header.copy(
    difficulty = Difficulty(1000000),
    number = BlockNumber(1),
    gasLimit = GasAmount(10000000),
    gasUsed = GasAmount.Zero,
    unixTimestamp = Timestamp(0)
  )

  private val evmConfig = EvmConfig.forBlock(
    0,
    BlockchainConfigForEvm(
      frontierBlockNumber = Long.MaxValue,
      homesteadBlockNumber = 0,
      eip150BlockNumber = Long.MaxValue,
      eip160BlockNumber = Long.MaxValue,
      eip161BlockNumber = Long.MaxValue,
      byzantiumBlockNumber = Long.MaxValue,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      maxCodeSize = Some(16),
      accountStartNonce = 0,
      atlantisBlockNumber = Long.MaxValue,
      aghartaBlockNumber = Long.MaxValue,
      petersburgBlockNumber = Long.MaxValue,
      phoenixBlockNumber = Long.MaxValue,
      magnetoBlockNumber = Long.MaxValue,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = Long.MaxValue,
      spiralBlockNumber = Long.MaxValue,
      olympiaBlockNumber = Long.MaxValue,
      chainId = ChainId(0x3d)
    )
  )

  private def makeContext(
      recipientAddr: Option[Address],
      doTransfer: Boolean = true,
      callDepth: Int
  ): ProgramContext[MockWorldState, MockStorage] =
    ProgramContext(
      callerAddr = senderAddr,
      originAddr = senderAddr,
      recipientAddr = recipientAddr,
      gasPrice = 1,
      startGas = 1000000,
      inputData = ByteString.empty,
      value = 0,
      endowment = 0,
      doTransfer = doTransfer,
      blockHeader = blockHeader,
      callDepth = callDepth,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  "VM tracer enter/exit balance" - {

    // L2 latent path: call() with recipientAddr=None at sub-call depth triggers the throw
    // that previously skipped the trailing onCallExit.
    "call() with recipientAddr=None at sub-call depth emits onCallExit even when throw fires (L2)" taggedAs (
      UnitTest,
      VMTest
    ) in {
      val tracer = new CountingTracer
      val vm = VM[MockWorldState, MockStorage](Some(tracer))
      val ctx = makeContext(recipientAddr = None, callDepth = 1)

      intercept[IllegalArgumentException] {
        vm.call(ctx, senderAddr)
      }

      tracer.enterCount shouldBe 1
      tracer.exitCount shouldBe 1
    }

    // C2 latent path: create() with recipientAddr=Some(addr) at sub-call depth triggers
    // the require that previously skipped the trailing onCallExit.
    "create() with recipientAddr=Some at sub-call depth emits onCallExit even when require fires (C2)" taggedAs (
      UnitTest,
      VMTest
    ) in {
      val tracer = new CountingTracer
      val vm = VM[MockWorldState, MockStorage](Some(tracer))
      val ctx = makeContext(recipientAddr = Some(Address(0xdeadbeef)), callDepth = 1)

      intercept[IllegalArgumentException] {
        vm.create(ctx)
      }

      tracer.enterCount shouldBe 1
      tracer.exitCount shouldBe 1
    }

    // Confirm that the happy path (valid sub-call) still emits balanced enter/exit.
    "call() on the happy path still emits balanced enter/exit" taggedAs (UnitTest, VMTest) in {
      val tracer = new CountingTracer
      val vm = VM[MockWorldState, MockStorage](Some(tracer))
      val recipient = Address(0xdeadbeefL)
      val worldWithRecipient = world.saveAccount(recipient, Account(nonce = 1))
      val ctx = ProgramContext[MockWorldState, MockStorage](
        callerAddr = senderAddr,
        originAddr = senderAddr,
        recipientAddr = Some(recipient),
        gasPrice = 1,
        startGas = 1000000,
        inputData = ByteString.empty,
        value = 0,
        endowment = 0,
        doTransfer = false,
        blockHeader = blockHeader,
        callDepth = 1,
        world = worldWithRecipient,
        initialAddressesToDelete = Set(),
        evmConfig = evmConfig,
        originalWorld = worldWithRecipient,
        warmAddresses = Set.empty,
        warmStorage = Set.empty
      )

      vm.call(ctx, senderAddr)

      tracer.enterCount shouldBe 1
      tracer.exitCount shouldBe 1
    }
  }
