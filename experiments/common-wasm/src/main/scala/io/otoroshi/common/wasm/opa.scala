package io.otoroshi.common.wasm

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.common.utils.implicits._
import org.extism.sdk.wasmotoroshi._
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future};

trait AwaitCapable {
  def await[T](future: Future[T], atMost: FiniteDuration = 5.seconds): T = {
    Await.result(future, atMost)
  }
}

case class HostFunctionWithAuthorization(
  function: WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData],
  authorized: WasmConfiguration => Boolean
)

case class EnvUserData(
  ic: WasmIntegrationContext,
  executionContext: ExecutionContext,
  mat: Materializer,
  config: WasmConfiguration
) extends WasmOtoroshiHostUserData

case class StateUserData(
  ic: WasmIntegrationContext,
  executionContext: ExecutionContext,
  mat: Materializer,
  cache: TrieMap[String, TrieMap[String, ByteString]]
)                          extends WasmOtoroshiHostUserData

case class EmptyUserData() extends WasmOtoroshiHostUserData

object OPA extends AwaitCapable {

  def opaAbortFunction: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaAbortFunction");
    }

  def opaPrintlnFunction: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaPrintlnFunction");
    }

  def opaBuiltin0Function: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin0Function");
    }

  def opaBuiltin1Function: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin1Function");
    }

  def opaBuiltin2Function: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin2Function");
    }

  def opaBuiltin3Function: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin3Function");
    };

  def opaBuiltin4Function: WasmOtoroshiExtismFunction[EmptyUserData] =
    (
        plugin: WasmOtoroshiInternal,
        params: Array[WasmBridge.ExtismVal],
        returns: Array[WasmBridge.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin4Function");
    }

  def opaAbort() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_abort",
    Array(WasmBridge.ExtismValType.I32),
    Array(),
    opaAbortFunction,
    Optional.empty()
  )

  def opaPrintln() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_println",
    Array(WasmBridge.ExtismValType.I64),
    Array(WasmBridge.ExtismValType.I64),
    opaPrintlnFunction,
    Optional.empty()
  )

  def opaBuiltin0() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_builtin0",
    Array(WasmBridge.ExtismValType.I32, WasmBridge.ExtismValType.I32),
    Array(WasmBridge.ExtismValType.I32),
    opaBuiltin0Function,
    Optional.empty()
  )

  def opaBuiltin1() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_builtin1",
    Array(WasmBridge.ExtismValType.I32, WasmBridge.ExtismValType.I32, WasmBridge.ExtismValType.I32),
    Array(WasmBridge.ExtismValType.I32),
    opaBuiltin1Function,
    Optional.empty()
  )

  def opaBuiltin2() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_builtin2",
    Array(
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32
    ),
    Array(WasmBridge.ExtismValType.I32),
    opaBuiltin2Function,
    Optional.empty()
  )

  def opaBuiltin3() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_builtin3",
    Array(
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32
    ),
    Array(WasmBridge.ExtismValType.I32),
    opaBuiltin3Function,
    Optional.empty()
  )

  def opaBuiltin4() = new WasmOtoroshiHostFunction[EmptyUserData](
    "opa_builtin4",
    Array(
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32,
      WasmBridge.ExtismValType.I32
    ),
    Array(WasmBridge.ExtismValType.I32),
    opaBuiltin4Function,
    Optional.empty()
  )

  def getFunctions(config: WasmConfiguration): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(opaAbort(), _ => config.opa),
      HostFunctionWithAuthorization(opaPrintln(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin0(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin1(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin2(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin3(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin4(), _ => config.opa)
    )
  }

  def getLinearMemories(): Seq[WasmOtoroshiLinearMemory] = {
    Seq(
      new WasmOtoroshiLinearMemory("memory", "env", new WasmOtoroshiLinearMemoryOptions(5, Optional.empty()))
    )
  }

  def loadJSON(plugin: WasmOtoroshiInstance, value: Array[Byte]): Either[JsValue, Int] = {
    if (value.length == 0) {
      0.right
    } else {
      val value_buf_len = value.length
      var parameters    = new WasmOtoroshiParameters(1)
        .pushInt(value_buf_len)

      val raw_addr = plugin.call("opa_malloc", parameters, 1)

      if (
        plugin.writeBytes(
          value,
          value_buf_len,
          raw_addr.getValue(0).v.i32
        ) == -1
      ) {
        JsString("Cant' write in memory").left
      } else {
        parameters = new WasmOtoroshiParameters(2)
          .pushInts(raw_addr.getValue(0).v.i32, value_buf_len)
        val parsed_addr = plugin.call(
          "opa_json_parse",
          parameters,
          1
        )

        if (parsed_addr.getValue(0).v.i32 == 0) {
          JsString("failed to parse json value").left
        } else {
          parsed_addr.getValue(0).v.i32.right
        }
      }
    }
  }

  def initialize(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
    loadJSON(plugin, "{}".getBytes(StandardCharsets.UTF_8))
      .flatMap(dataAddr => {
        val base_heap_ptr = plugin.call(
          "opa_heap_ptr_get",
          new WasmOtoroshiParameters(0),
          1
        )

        val data_heap_ptr = base_heap_ptr.getValue(0).v.i32
        (
          Json.obj("dataAddr" -> dataAddr, "baseHeapPtr" -> data_heap_ptr).stringify,
          ResultsWrapper(new WasmOtoroshiResults(0))
        ).right
      })
  }

  def evaluate(
      plugin: WasmOtoroshiInstance,
      dataAddr: Int,
      baseHeapPtr: Int,
      input: String
  ): Either[JsValue, (String, ResultsWrapper)] = {
    val entrypoint = 0

    // TODO - read and load builtins functions by calling dumpJSON
    val input_len = input.getBytes(StandardCharsets.UTF_8).length
    plugin.writeBytes(
      input.getBytes(StandardCharsets.UTF_8),
      input_len,
      baseHeapPtr
    )

    val heap_ptr   = baseHeapPtr + input_len
    val input_addr = baseHeapPtr

    val ptr = new WasmOtoroshiParameters(7)
      .pushInts(0, entrypoint, dataAddr, input_addr, input_len, heap_ptr, 0)

    val ret = plugin.call("opa_eval", ptr, 1)

    val memory = plugin.getMemory("memory")

    val offset: Int    = ret.getValue(0).v.i32
    val arraySize: Int = 65356

    val mem: Array[Byte] = memory.getByteArray(offset, arraySize)
    val size: Int        = lastValidByte(mem)

    (
      new String(java.util.Arrays.copyOf(mem, size), StandardCharsets.UTF_8),
      ResultsWrapper(new WasmOtoroshiResults(0))
    ).right
  }

  def lastValidByte(arr: Array[Byte]): Int = {
    for (i <- arr.indices) {
      if (arr(i) == 0) {
        return i
      }
    }
    arr.length
  }
}

object LinearMemories {

  private val memories: AtomicReference[Seq[WasmOtoroshiLinearMemory]] =
    new AtomicReference[Seq[WasmOtoroshiLinearMemory]](Seq.empty[WasmOtoroshiLinearMemory])

  def getMemories(config: WasmConfiguration): Array[WasmOtoroshiLinearMemory] = {
    if (config.opa) {
      if (memories.get.isEmpty) {
        memories.set(
          OPA.getLinearMemories()
        )
      }
      memories.get().toArray
    } else {
      Array.empty
    }
  }
}

/*
    String dumpJSON() {
        Results addr = plugin.call("builtins",  new WasmOtoroshiParameters(0), 1);

        Parameters parameters = new WasmOtoroshiParameters(1);
        IntegerParameter builder = new IntegerParameter();
        builder.add(parameters, addr.getValue(0).v.i32, 0);

        Results rawAddr = plugin.call("opa_json_dump", parameters, 1);

        Pointer memory = WasmBridge.INSTANCE.extism_get_memory(plugin.getPointer(), plugin.getIndex(), "memory");
        byte[] mem = memory.getByteArray(rawAddr.getValue(0).v.i32, 65356);
        int size = lastValidByte(mem);

        return new String(Arrays.copyOf(mem, size), StandardCharsets.UTF_8);
    }
}*/
