package otoroshi.wasm.proxywasm

import com.sksamuel.exts.concurrent.Futures.RichFuture
import org.extism.sdk.parameters._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.ProxyWasmState
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object CorazaPlugin {
  val defaultRules = Json.parse(
    """{
    "directives_map": {
      "default": [
        "SecDebugLogLevel 9",
        "SecRuleEngine On",
        "SecRule REQUEST_URI \\"@streq /admin\\" \\"id:101,phase:1,t:lowercase,deny\\"",
        "SecRule REMOTE_ADDR \\"@rx .*\\" \\"id:1,phase:1,deny,status:403\\""
      ]
    },
    "rules": [
      "SecDebugLogLevel 9",
      "SecRuleEngine On",
      "SecRule REQUEST_URI \\"@streq /admin\\" \\"id:101,phase:1,t:lowercase,deny\\"",
      "SecRule REMOTE_ADDR \\"@rx .*\\" \\"id:1,phase:1,deny,status:403\\""
    ],
    "default_directive": "default"
  }""")
}

class CorazaPlugin(pluginRef: String, rules: JsValue, env: Env) {

  lazy val vmConfigurationSize = 0
  lazy val pluginConfigurationSize = rules.stringify.byteString.length
  lazy val rootData = VmData(rules.stringify, Map.empty, -1)
  lazy val state = new ProxyWasmState(100, 20)
  lazy val functions = ProxyWasmFunctions.build(state)(env.otoroshiExecutionContext, env, env.otoroshiMaterializer)

  def callPluginWithoutResults(function: String, params: Parameters, data: VmData): Unit = {
    env.proxyState.wasmPlugin(pluginRef) match {
      case None => throw new RuntimeException("no plugin")
      case Some(plugin) => otoroshi.wasm.WasmUtils.execute(
        config = plugin.config,
        defaultFunctionName = function,
        input = None,
        parameters = params.some,
        resultSize = None,
        attrs = None,
        ctx = Some(data),
        addHostFunctions = functions,
      )(env)
    }
  }

  def callPluginWithResults(function: String, params: Parameters, results: Int, data: VmData): Future[Results] = {
    env.proxyState.wasmPlugin(pluginRef) match {
      case None => throw new RuntimeException("no plugin")
      case Some(plugin) => otoroshi.wasm.WasmUtils.execute(
        config = plugin.config,
        defaultFunctionName = function,
        input = None,
        parameters = params.some,
        resultSize = results.some,
        attrs = None,
        ctx = Some(data),
        addHostFunctions = functions,
      )(env).map {
        case Left(err) =>
          println("error", err)
          throw new RuntimeException("callPluginWithResults")
        case Right((_, results)) => results
      }(env.otoroshiExecutionContext)
    }
  }

  def proxyOnContexCreate(contextId: Int, rootContextId: Int): Unit = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, contextId, rootContextId)
    callPluginWithoutResults("proxy_on_context_create", prs, rootData)
  }

  def proxyOnVmStart(): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, 0, vmConfigurationSize)
    val proxyOnVmStartAction = callPluginWithResults("proxy_on_vm_start", prs, 1, rootData).await(5.seconds)
    proxyOnVmStartAction.getValues()(0).v.i32 != 0;
  }

  def proxyOnConfigure(rootContextId: Int): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, rootContextId, pluginConfigurationSize)
    val proxyOnConfigureAction = callPluginWithResults("proxy_on_configure", prs, 1, rootData).await(5.seconds)
    proxyOnConfigureAction.getValues()(0).v.i32 != 0
  }

  def proxyStart(): Unit = {
    callPluginWithoutResults("_start", new Parameters(0), rootData)
  }

  def proxyCheckABIVersion(): Unit = {
    callPluginWithoutResults("proxy_abi_version_0_2_0", new Parameters(0), rootData)
  }

  def proxyOnRequestHeaders(contextId: Int, request: RequestHeader, attrs: TypedMap): Unit = {
    val endOfStream = 1
    val sizeHeaders = 0
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeHeaders, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_request_headers", prs, 1, rootData.withRequest(request, attrs)(env)).await(5.seconds)
    val result = Types.Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    println(s"result: ${result}")
  }

  def start(): Unit = {
    proxyStart()
    proxyCheckABIVersion()
    // according to ABI, we should create a root context id before any operations
    proxyOnContexCreate(state.rootContextId, 0)
    if (proxyOnVmStart()) {
      if (proxyOnConfigure(state.rootContextId)) {
        proxyOnContexCreate(state.contextId, state.rootContextId)
      } else {
        println("failed to configure")
      }
    } else {
      println("failed to start vm")
    }
  }

  def stop(): Unit = {

  }

  def run(request: RequestHeader, attrs: TypedMap): Unit = {
    proxyOnRequestHeaders(state.contextId, request, attrs)
  }
}
