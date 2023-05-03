package otoroshi.wasm;

import akka.stream.Materializer
import org.extism.sdk.parameters.{IntegerParameter, Parameters}
import org.extism.sdk._
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgCachedConfigContext

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext;

object OPA extends AwaitCapable {

  def opaAbortFunction: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaAbortFunction");
    }

  def opaPrintlnFunction: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaPrintlnFunction");
    }

  def opaBuiltin0Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin0Function");
    }

  def opaBuiltin1Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin1Function");
    }

  def opaBuiltin2Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin2Function");
    }

  def opaBuiltin3Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin3Function");
    };

  def opaBuiltin4Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin4Function");
    }

  def opaAbort() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_abort",
    Array(LibExtism.ExtismValType.I32),
    Array(),
    opaAbortFunction,
    Optional.empty()
  )

  def opaPrintln() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_println",
    Array(LibExtism.ExtismValType.I64),
    Array(LibExtism.ExtismValType.I64),
    opaPrintlnFunction,
    Optional.empty()
  )

  def opaBuiltin0() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_builtin0",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin0Function,
    Optional.empty()
  )

  def opaBuiltin1() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_builtin1",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin1Function,
    Optional.empty()
  )

  def opaBuiltin2() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_builtin2",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin2Function,
    Optional.empty()
  )

  def opaBuiltin3() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_builtin3",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin3Function,
    Optional.empty()
  )

  def opaBuiltin4() = new org.extism.sdk.HostFunction[EmptyUserData](
    "opa_builtin4",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin4Function,
    Optional.empty()
  )

  def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
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

  def getLinearMemories(config: WasmConfig, ctx: Option[NgCachedConfigContext])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[LinearMemory] = {
    Seq(
      new LinearMemory("memory", "env", new LinearMemoryOptions(5, Optional.empty()))
    )
  }

  def loadJSON(plugin: Plugin, value: Array[Byte]): Int = {
    if (value.length == 0) {
      return 0
    } else {
      val value_buf_len = value.length
      var parameters    = new Parameters(1)
      val parameter     = new IntegerParameter()
      parameter.add(parameters, value_buf_len, 0)

      val raw_addr = plugin.call("opa_malloc", parameters, 1, "".getBytes())

      if (
        LibExtism.INSTANCE.extism_memory_write_bytes(
          plugin.getPointer(),
          plugin.getIndex(),
          value,
          value_buf_len,
          raw_addr.getValue(0).v.i32
        ) == -1
      ) {
        throw new ExtismException("Cant' write in memory")
      }

      parameters = new Parameters(2)
      parameter.add(parameters, raw_addr.getValue(0).v.i32, 0)
      parameter.add(parameters, value_buf_len, 1)
      val parsed_addr = plugin.call(
        "opa_json_parse",
        parameters,
        1
      )

      if (parsed_addr.getValue(0).v.i32 == 0) {
        throw new ExtismException("failed to parse json value")
      }

      parsed_addr.getValue(0).v.i32
    }
  }

  def evaluate(plugin: Plugin, input: String): String = {
    val entrypoint = 0

    // TODO - read and load builtins functions by calling dumpJSON

    val data_addr = loadJSON(plugin, "{}".getBytes(StandardCharsets.UTF_8))

    val parameter = new IntegerParameter()

    val base_heap_ptr = plugin.call(
      "opa_heap_ptr_get",
      new Parameters(0),
      1
    )

    val data_heap_ptr = base_heap_ptr.getValue(0).v.i32

    val input_len = input.getBytes(StandardCharsets.UTF_8).length
    LibExtism.INSTANCE.extism_memory_write_bytes(
      plugin.getPointer(),
      plugin.getIndex(),
      input.getBytes(StandardCharsets.UTF_8),
      input_len,
      data_heap_ptr
    )

    val heap_ptr   = data_heap_ptr + input_len
    val input_addr = data_heap_ptr

    val ptr = new Parameters(7)
    parameter.add(ptr, 0, 0)
    parameter.add(ptr, entrypoint, 1)
    parameter.add(ptr, data_addr, 2)
    parameter.add(ptr, input_addr, 3)
    parameter.add(ptr, input_len, 4)
    parameter.add(ptr, heap_ptr, 5)
    parameter.add(ptr, 0, 6)

    val ret = plugin.call("opa_eval", ptr, 1)

    val memory = LibExtism.INSTANCE.extism_get_memory(plugin.getPointer(), plugin.getIndex(), "memory")

    val mem: Array[Byte] = memory.getByteArray(ret.getValue(0).v.i32, 65356)
    val size: Int        = lastValidByte(mem)

    new String(java.util.Arrays.copyOf(mem, size), StandardCharsets.UTF_8)
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

  private val memories: AtomicReference[Seq[LinearMemory]] =
    new AtomicReference[Seq[LinearMemory]](Seq.empty[LinearMemory])

  def getMemories(config: WasmConfig, ctx: Option[NgCachedConfigContext], pluginId: String)(implicit
      env: Env,
      executionContext: ExecutionContext
  ): Array[LinearMemory] = {
    if (config.opa) {
      implicit val mat = env.otoroshiMaterializer
      if (memories.get.isEmpty) {
        memories.set(
          OPA.getLinearMemories(config, ctx)
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
        Results addr = plugin.call("builtins",  new Parameters(0), 1);

        Parameters parameters = new Parameters(1);
        IntegerParameter builder = new IntegerParameter();
        builder.add(parameters, addr.getValue(0).v.i32, 0);

        Results rawAddr = plugin.call("opa_json_dump", parameters, 1);

        Pointer memory = LibExtism.INSTANCE.extism_get_memory(plugin.getPointer(), plugin.getIndex(), "memory");
        byte[] mem = memory.getByteArray(rawAddr.getValue(0).v.i32, 65356);
        int size = lastValidByte(mem);

        return new String(Arrays.copyOf(mem, size), StandardCharsets.UTF_8);
    }
}*/
