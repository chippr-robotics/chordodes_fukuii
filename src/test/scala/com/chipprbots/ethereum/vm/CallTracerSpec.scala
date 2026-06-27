package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.MonadicJValue.jvalueToMonadic
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address

class CallTracerSpec extends AnyFreeSpec with Matchers:

  private val from = Address(0x1234)
  private val to = Address(0x5678)
  private val input = ByteString(0x12, 0x34)
  private val output = ByteString(0xab, 0xcd)

  "CallTracer" - {
    "should produce a root CALL frame for a simple transaction" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 21000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 21000, output = output, error = None)

      val result = tracer.getResult
      (result \ "type") shouldBe JString("CALL")
      (result \ "from") shouldBe a[JString]
      (result \ "to") shouldBe a[JString]
      (result \ "gas") shouldBe JString("0x5208")
      (result \ "gasUsed") shouldBe JString("0x5208")
      (result \ "input") shouldBe a[JString]
      (result \ "output") shouldBe a[JString]
    }

    "should produce a CREATE frame when to is None" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, to = None, gas = 100000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 50000, output = output, error = None)

      val result = tracer.getResult
      (result \ "type") shouldBe JString("CREATE")
    }

    "should build a nested call tree" in {
      val tracer = new CallTracer()
      val inner = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val result = tracer.getResult
      val calls = result \ "calls"
      calls shouldBe a[JArray]
      val callArray = calls.asInstanceOf[JArray].arr
      callArray should have size 1
      (callArray.head \ "type") shouldBe JString("STATICCALL")
      (callArray.head \ "gasUsed") shouldBe JString("0x2710")
    }

    "should skip sub-calls when onlyTopCall is true" in {
      val tracer = new CallTracer(onlyTopCall = true)
      val inner = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val result = tracer.getResult
      (result \ "calls") shouldBe JNothing
    }

    "should include error on failure" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 100000, output = ByteString.empty, error = Some("out of gas"))

      val result = tracer.getResult
      (result \ "error") shouldBe JString("out of gas")
    }

    "should encode gas and gasUsed as hex strings matching core-geth callFrameMarshaling" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 1000000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 500000, output = output, error = None)

      val result = tracer.getResult
      (result \ "gas") shouldBe JString("0xf4240")
      (result \ "gasUsed") shouldBe JString("0x7a120")
    }

    "should omit value for STATICCALL and DELEGATECALL" in {
      val tracer = new CallTracer()
      val inner = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val calls = (tracer.getResult \ "calls").asInstanceOf[JArray].arr
      (calls.head \ "value") shouldBe JNothing
    }

    "should return JNull when no transaction was traced" in {
      val tracer = new CallTracer()
      tracer.getResult shouldBe JNull
    }

    // §8l-I — EIP-3860 tracer balance fix.
    // VM.create() fires onCallEnter unconditionally for a sub-call CREATE, then
    // evaluates the EIP-3860 initcode-size limit. Previously the abort arm did an
    // early `return` that skipped the trailing onCallExit, leaving an orphaned
    // frame on the call stack (the failed CREATE never attached to the parent and
    // every subsequent sibling nested one level too deep). The fix converts the
    // abort arm to an expression that flows through onCallExit. This test asserts
    // the post-fix emission sequence the VM now produces.
    "should balance enter/exit for a sub-call CREATE aborted by EIP-3860 initcode limit" in {
      val tracer = new CallTracer()
      val newContract = Address(0)

      // Top-level CALL frame (the parent).
      tracer.onTxStart(from, Some(to), gas = 1000000, value = 0, input = input)
      // Sub-call CREATE with oversized initcode: onCallEnter fires unconditionally,
      // then the EIP-3860 abort path fires onCallExit (full gas consumed, no output,
      // InitCodeSizeLimit error) — exactly what VM.create() emits after the fix.
      tracer.onCallEnter("CREATE", to, newContract, gas = 500000, value = 0, input = input)
      tracer.onCallExit(gasUsed = 500000, output = ByteString.empty, error = Some("InitCodeSizeLimit"))
      tracer.onTxEnd(gasUsed = 600000, output = output, error = None)

      val result = tracer.getResult

      // The failed CREATE appears in the parent's calls list with the InitCodeSizeLimit error.
      val calls = (result \ "calls").asInstanceOf[JArray].arr
      calls should have size 1
      (calls.head \ "type") shouldBe JString("CREATE")
      (calls.head \ "error") shouldBe JString("InitCodeSizeLimit")
      (calls.head \ "gasUsed") shouldBe JString("0x7a120") // 500000 — full consumption

      // No orphaned frame: the root frame's gasUsed reflects onTxEnd (proving the
      // CREATE frame was popped by onCallExit, not left on the stack to swallow it).
      (result \ "type") shouldBe JString("CALL")
      (result \ "gasUsed") shouldBe JString("0x927c0") // 600000
    }

    "should keep siblings flat after an EIP-3860 abort (no stack corruption)" in {
      val tracer = new CallTracer()
      val newContract = Address(0)
      val sibling = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 1000000, value = 0, input = input)
      // Aborted CREATE — balanced enter/exit per the §8l-I fix.
      tracer.onCallEnter("CREATE", to, newContract, gas = 500000, value = 0, input = input)
      tracer.onCallExit(gasUsed = 500000, output = ByteString.empty, error = Some("InitCodeSizeLimit"))
      // A subsequent sibling call must nest directly under the root, not under the
      // (correctly popped) CREATE frame.
      tracer.onCallEnter("STATICCALL", to, sibling, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 600000, output = output, error = None)

      val calls = (tracer.getResult \ "calls").asInstanceOf[JArray].arr
      // Both frames are direct children of the root — proof the stack was balanced.
      calls should have size 2
      (calls.head \ "type") shouldBe JString("CREATE")
      (calls.head \ "error") shouldBe JString("InitCodeSizeLimit")
      (calls(1) \ "type") shouldBe JString("STATICCALL")
      // The sibling carries no nested calls (it was not wrongly parented to CREATE).
      (calls(1) \ "calls") shouldBe JNothing
    }
  }
