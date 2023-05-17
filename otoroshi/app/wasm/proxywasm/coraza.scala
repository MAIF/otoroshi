package otoroshi.wasm.proxywasm

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.sksamuel.exts.concurrent.Futures.RichFuture
import org.extism.sdk.parameters._
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.ProxyWasmState
import play.api.libs.json._
import play.api.mvc
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

object CorazaPlugin {
  val defaultRules = Json.parse(
    """{
    "directives_map": {
      "default": []
    },
    "rules": [
      "SecDebugLogLevel 9",
      "SecRuleEngine On",
      "SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny\""
    ],
    "default_directive": "default"
  }""")
}

class CorazaPlugin(pluginRef: String, rules: JsValue, env: Env) {

  lazy val vmConfigurationSize = 0
  lazy val pluginConfigurationSize = rules.stringify.byteString.length
  lazy val rootData = VmData.withRules(rules)
  lazy val contextId = new AtomicInteger(0)
  lazy val state = new ProxyWasmState(100, contextId)
  lazy val functions = ProxyWasmFunctions.build(state)(env.otoroshiExecutionContext, env, env.otoroshiMaterializer)

  def callPluginWithoutResults(function: String, params: Parameters, data: VmData, attrs: TypedMap): Unit = {
    env.proxyState.wasmPlugin(pluginRef) match {
      case None => throw new RuntimeException("no plugin")
      case Some(plugin) => otoroshi.wasm.WasmUtils.rawExecute(
        config = plugin.config,
        defaultFunctionName = function,
        input = None,
        parameters = params.some,
        resultSize = None,
        attrs = attrs.some,
        ctx = Some(data),
        addHostFunctions = functions,
      )(env).await(5.seconds)
    }
  }

  def callPluginWithResults(function: String, params: Parameters, results: Int, data: VmData, attrs: TypedMap): Future[Results] = {
    env.proxyState.wasmPlugin(pluginRef) match {
      case None => throw new RuntimeException("no plugin")
      case Some(plugin) => otoroshi.wasm.WasmUtils.rawExecute(
        config = plugin.config,
        defaultFunctionName = function,
        input = None,
        parameters = params.some,
        resultSize = results.some,
        attrs = attrs.some,
        ctx = Some(data),
        addHostFunctions = functions,
      )(env).map {
        case Left(err) =>
          println("error", err)
          throw new RuntimeException(s"callPluginWithResults: ${err.stringify}")
        case Right((_, results)) => results
      }(env.otoroshiExecutionContext)
    }
  }

  def proxyOnContexCreate(contextId: Int, rootContextId: Int, attrs: TypedMap): Unit = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, contextId, rootContextId)
    callPluginWithoutResults("proxy_on_context_create", prs, rootData, attrs)
  }

  def proxyOnVmStart(attrs: TypedMap): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, 0, vmConfigurationSize)
    val proxyOnVmStartAction = callPluginWithResults("proxy_on_vm_start", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnVmStartAction.getValues()(0).v.i32 != 0;
  }

  def proxyOnConfigure(rootContextId: Int, attrs: TypedMap): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, rootContextId, pluginConfigurationSize)
    val proxyOnConfigureAction = callPluginWithResults("proxy_on_configure", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnConfigureAction.getValues()(0).v.i32 != 0
  }

  def proxyOnDone(rootContextId: Int, attrs: TypedMap): Boolean = {
    val prs = new Parameters(1)
    new IntegerParameter().addAll(prs, rootContextId)
    val proxyOnConfigureAction = callPluginWithResults("proxy_on_done", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnConfigureAction.getValues()(0).v.i32 != 0
  }

  def proxyOnDelete(rootContextId: Int, attrs: TypedMap): Unit = {
    val prs = new Parameters(1)
    new IntegerParameter().addAll(prs, rootContextId)
    callPluginWithoutResults("proxy_on_done", prs, rootData, attrs)
  }

  def proxyStart(attrs: TypedMap): Unit = {
    callPluginWithoutResults("_start", new Parameters(0), rootData, attrs)
  }

  def proxyCheckABIVersion(attrs: TypedMap): Unit = {
    callPluginWithoutResults("proxy_abi_version_0_2_0", new Parameters(0), rootData, attrs)
  }

  def proxyOnRequestHeaders(contextId: Int, request: RequestHeader, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = rootData.withRequest(request, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeHeaders, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_request_headers", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    // println(s"result: ${result}")
    if (result != Result.ResultOk) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnRequestBody(contextId: Int, request: RequestHeader, req: NgPluginHttpRequest, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = rootData.withRequest(request, attrs)(env)
    val endOfStream = 1
    val body_bytes = req.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).await(5.seconds)
    val sizeBody = body_bytes.size.bytes.length
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeBody, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_http_request_body", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    // println(s"result: ${result}")
    if (result != Result.ResultOk) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnResponseHeaders(contextId: Int, response: NgPluginHttpResponse, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = rootData.withResponse(response, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeHeaders, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_response_headers", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    // println(s"result: ${result}")
    if (result != Result.ResultOk) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnResponseBody(contextId: Int, response: NgPluginHttpResponse, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    // val data = rootData.withResponse(response, attrs)(env)
    // val endOfStream = 1
    // val body_bytes = response.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).await(5.seconds)
    // val sizeBody = body_bytes.size.bytes.length
    // val prs = new Parameters(3)
    // new IntegerParameter().addAll(prs, contextId, sizeBody, endOfStream)
    // val requestHeadersAction = callPluginWithResults("proxy_on_http_response_body", prs, 1, data, attrs).await(5.seconds)
    // val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    // // println(s"result: ${result}")
    // if (result != Result.ResultOk) {
    //   data.httpResponse match {
    //     case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
    //     case Some(response) => Left(response)
    //   }
    // } else {
    //   Right(())
    // }
    Right(()) // because Function not found: proxy_on_http_response_body
  }

  def start(attrs: TypedMap): Unit = {
    proxyStart(attrs)
    proxyCheckABIVersion(attrs)
    // according to ABI, we should create a root context id before any operations
    proxyOnContexCreate(state.rootContextId, 0, attrs)
    if (proxyOnVmStart(attrs)) {
      if (proxyOnConfigure(state.rootContextId, attrs)) {
        //proxyOnContexCreate(state.contextId.get(), state.rootContextId, attrs)
      } else {
        println("failed to configure")
      }
    } else {
      println("failed to start vm")
    }
  }

  def stop(attrs: TypedMap): Unit = {}

  // TODO: avoid blocking calls for wasm calls
  def runRequestPath(request: RequestHeader, attrs: TypedMap): NgAccess = {
    contextId.incrementAndGet()
    proxyOnContexCreate(state.contextId.get(), state.rootContextId, attrs)
    val res = for {
      _ <- proxyOnRequestHeaders(state.contextId.get(), request, attrs)
    } yield ()
    res match {
      case Left(errRes) =>
        proxyOnDone(state.contextId.get(), attrs)
        proxyOnDelete(state.contextId.get(), attrs)
        NgAccess.NgDenied(errRes)
      case Right(_) => NgAccess.NgAllowed
    }
  }

  def runRequestBodyPath(request: RequestHeader, req: NgPluginHttpRequest, attrs: TypedMap): Either[mvc.Result, Unit] = {
    val hasBody = request.theHasBody
    val res = for {
      _ <- if (hasBody) proxyOnRequestBody(state.contextId.get(), request, req, attrs) else Right(())
      // proxy_on_http_request_trailers
      // proxy_on_http_request_metadata : H2 only
    } yield ()
    res match {
      case Left(errRes) =>
        proxyOnDone(state.contextId.get(), attrs)
        proxyOnDelete(state.contextId.get(), attrs)
        Left(errRes)
      case Right(_) => Right(())
    }
  }

  def runResponsePath(response: NgPluginHttpResponse, attrs: TypedMap): Either[mvc.Result, Unit] = {
    val res = for {
      _ <- proxyOnResponseHeaders(state.contextId.get(), response, attrs)
      _ <- proxyOnResponseBody(state.contextId.get(), response, attrs)
      // proxy_on_http_response_trailers
      // proxy_on_http_response_metadata : H2 only
    } yield ()
    proxyOnDone(state.contextId.get(), attrs)
    proxyOnDelete(state.contextId.get(), attrs)
    res
  }
}

class CorazaValidator extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Coraza"
  override def description: Option[String] ="Coraza".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def isAccessAsync: Boolean = true
  override def isTransformRequestAsync: Boolean = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false

  private val started = new AtomicBoolean(false)
  private val ref = new AtomicReference[CorazaPlugin](null)

  override def beforeRequest(ctx: NgBeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    if (ref.get() == null) {
      // TODO: reference a wasm file on github
      ref.set(new CorazaPlugin("wasm-plugin_dev_8c854ff2-a571-45bd-93ef-39663c5ab343", CorazaPlugin.defaultRules, env))
    }
    val plugin = ref.get()
    if (started.compareAndSet(false, true)) {
      plugin.start(ctx.attrs)
    }
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val plugin = ref.get()
    plugin.runRequestPath(ctx.request, ctx.attrs).vfuture
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val plugin = ref.get()
    plugin.runRequestBodyPath(ctx.request, ctx.otoroshiRequest, ctx.attrs).map(_ => ctx.otoroshiRequest).vfuture
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val plugin = ref.get()
    plugin.runResponsePath(ctx.otoroshiResponse, ctx.attrs).map(_ => ctx.otoroshiResponse).vfuture
  }
}
