package otoroshi.wasm.proxywasm

import akka.stream.Materializer
import akka.util.ByteString
import com.sksamuel.exts.concurrent.Futures.RichFuture
import io.otoroshi.wasm4s.scaladsl._
import org.extism.sdk.wasmotoroshi._
import org.joda.time.DateTime
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic._
import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

object CorazaPlugin {
  val rootContextIds     = new AtomicInteger(100)
  val testRules          = Json.parse("""{
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
  val corazaDefaultRules = """{
    |  "directives_map": {
    |      "default": [
    |        "Include @recommended-conf",
    |        "Include @crs-setup-conf",
    |        "Include @owasp_crs/*.conf",
    |        "SecRuleEngine On"
    |      ]
    |  },
    |  "default_directives": "default",
    |  "metric_labels": {},
    |  "per_authority_directives": {}
    |}""".stripMargin.parseJson
}

object CorazaPluginKeys {
  val CorazaContextIdKey = TypedKey[Int]("otoroshi.next.plugins.CorazaContextId")
  val CorazaWasmVmKey    = TypedKey[WasmVm]("otoroshi.next.plugins.CorazaWasmVm")
}

class CorazaPlugin(wasm: WasmConfig, val config: CorazaWafConfig, key: String, env: Env) {

  // WasmVmPool.logger.debug("new CorazaPlugin")

  private implicit val ev = env
  private implicit val ec = env.otoroshiExecutionContext
  private implicit val ma = env.otoroshiMaterializer

  private lazy val timeout                 = 10.seconds
  private lazy val started                 = new AtomicBoolean(false)
  private lazy val logger                  = Logger("otoroshi-plugin-coraza")
  private lazy val vmConfigurationSize     = 0
  private lazy val rules                   = config.config
  private lazy val pluginConfigurationSize = rules.stringify.byteString.length
  private lazy val contextId               = new AtomicInteger(0)
  private lazy val state                   =
    new ProxyWasmState(CorazaPlugin.rootContextIds.incrementAndGet(), contextId, Some((l, m) => logCallback(l, m)), env)
  private lazy val pool: WasmVmPool        = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  def logCallback(level: org.slf4j.event.Level, msg: String): Unit = {
    CorazaTrailEvent(level, msg).toAnalytics()
  }

  def isStarted(): Boolean = started.get()

  def createFunctions(ref: AtomicReference[WasmVmData]): Seq[WasmOtoroshiHostFunction[EnvUserData]] = {
    ProxyWasmFunctions.build(state, ref)
  }

  def callPluginWithoutResults(
      function: String,
      params: WasmOtoroshiParameters,
      data: VmData,
      attrs: TypedMap,
      shouldBeCallOnce: Boolean = false
  ): Future[Either[JsValue, ResultsWrapper]] = {
    attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey) match {
      case None     =>
        logger.error("no vm found in attrs")
        Left(Json.obj("error" -> "no vm found in attrs")).vfuture
      case Some(vm) => {
        WasmUtils.traceHostVm(function + s" - vm: ${vm.index}")
        vm.call(WasmFunctionParameters.NoResult(function, params), Some(data))
          .map { opt =>
            opt.map { res =>
              res._2.free()
              res._2
            }
          }
          .andThen { case _ =>
            vm.release()
          }
      }
    }
  }

  def callPluginWithResults(
      function: String,
      params: WasmOtoroshiParameters,
      results: Int,
      data: VmData,
      attrs: TypedMap,
      shouldBeCallOnce: Boolean = false
  ): Future[ResultsWrapper] = {
    attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey) match {
      case None     =>
        logger.error("no vm found in attrs")
        Future.failed(new RuntimeException("no vm found in attrs"))
      case Some(vm) => {
        WasmUtils.traceHostVm(function + s" - vm: ${vm.index}")
        vm.call(WasmFunctionParameters.BothParamsResults(function, params, results), Some(data))
          .flatMap {
            case Left(err)           =>
              logger.error(s"error while calling plugin: ${err}")
              Future.failed(new RuntimeException(s"callPluginWithResults: ${err.stringify}"))
            case Right((_, results)) => results.vfuture
          }
          .andThen { case _ =>
            vm.release()
          }
      }
    }
  }

  def proxyOnContexCreate(contextId: Int, rootContextId: Int, attrs: TypedMap, rootData: VmData): Future[Unit] = {
    val prs = new WasmOtoroshiParameters(2)
      .pushInts(contextId, rootContextId)
    callPluginWithoutResults("proxy_on_context_create", prs, rootData, attrs).map(_ => ())
    // TODO - just try to reset context for each request without call proxyOnConfigure
  }

  def proxyOnVmStart(attrs: TypedMap, rootData: VmData): Future[Boolean] = {
    val prs = new WasmOtoroshiParameters(2)
      .pushInts(0, vmConfigurationSize)
    callPluginWithResults("proxy_on_vm_start", prs, 1, rootData, attrs, shouldBeCallOnce = true).map {
      proxyOnVmStartAction =>
        val res = proxyOnVmStartAction.results.getValues()(0).v.i32 != 0
        proxyOnVmStartAction.free()
        res
    }
  }

  def proxyOnConfigure(rootContextId: Int, attrs: TypedMap, rootData: VmData): Future[Boolean] = {
    val prs = new WasmOtoroshiParameters(2)
      .pushInts(rootContextId, pluginConfigurationSize)
    callPluginWithResults("proxy_on_configure", prs, 1, rootData, attrs, shouldBeCallOnce = true).map {
      proxyOnConfigureAction =>
        val res = proxyOnConfigureAction.results.getValues()(0).v.i32 != 0
        proxyOnConfigureAction.free()
        res
    }
  }

  def proxyOnDone(rootContextId: Int, attrs: TypedMap): Future[Boolean] = {
    val prs      = new WasmOtoroshiParameters(1).pushInt(rootContextId)
    val rootData = VmData.empty()
    callPluginWithResults("proxy_on_done", prs, 1, rootData, attrs).map { proxyOnConfigureAction =>
      val res = proxyOnConfigureAction.results.getValues()(0).v.i32 != 0
      proxyOnConfigureAction.free()
      res
    }
  }

  def proxyOnDelete(rootContextId: Int, attrs: TypedMap): Future[Unit] = {
    val prs      = new WasmOtoroshiParameters(1).pushInt(rootContextId)
    val rootData = VmData.empty()
    callPluginWithoutResults("proxy_on_delete", prs, rootData, attrs).map(_ => ())
  }

  def proxyStart(attrs: TypedMap, rootData: VmData): Future[ResultsWrapper] = {
    callPluginWithoutResults("_start", new WasmOtoroshiParameters(0), rootData, attrs, shouldBeCallOnce = true).map {
      res =>
        res.right.get
    }
  }

  def proxyCheckABIVersion(attrs: TypedMap, rootData: VmData): Future[Unit] = {
    callPluginWithoutResults(
      "proxy_abi_version_0_2_0",
      new WasmOtoroshiParameters(0),
      rootData,
      attrs,
      shouldBeCallOnce = true
    ).map(_ => ())
  }

  def reportError(result: Result, vm: WasmVm, from: String): Unit = {
    logger.error(s"[${vm.index}] from: $from - error: ${result.value} - ${vm.calls} / ${vm.current}")
  }

  def proxyOnRequestHeaders(
      contextId: Int,
      request: RequestHeader,
      attrs: TypedMap
  ): Future[Either[play.api.mvc.Result, Unit]] = {
    val vm          = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val data        = VmData.empty().withRequest(request, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs         = new WasmOtoroshiParameters(3).pushInts(contextId, sizeHeaders, endOfStream)
    callPluginWithResults("proxy_on_request_headers", prs, 1, data, attrs).map { requestHeadersAction =>
      val result = Result.valueToType(requestHeadersAction.results.getValues()(0).v.i32)
      requestHeadersAction.free()
      if (result != Result.ResultOk || data.httpResponse.isDefined) {
        data.httpResponse match {
          case None           =>
            reportError(result, vm, "proxyOnRequestHeaders")
            Left(
              play.api.mvc.Results
                .InternalServerError(Json.obj("error" -> s"no http response in context 1: ${result.value}"))
            ) // TODO: not sure if okay
          case Some(response) => Left(response)
        }
      } else {
        Right(())
      }
    }
  }

  def proxyOnRequestBody(
      contextId: Int,
      request: RequestHeader,
      req: NgPluginHttpRequest,
      body_bytes: ByteString,
      attrs: TypedMap
  ): Future[Either[play.api.mvc.Result, Unit]] = {
    val vm          = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val data        = VmData.empty().withRequest(request, attrs)(env)
    data.bodyInRef.set(body_bytes)
    val endOfStream = 1
    val sizeBody    = body_bytes.size.bytes.length
    val prs         = new WasmOtoroshiParameters(3).pushInts(contextId, sizeBody, endOfStream)
    callPluginWithResults("proxy_on_request_body", prs, 1, data, attrs).map { requestHeadersAction =>
      val result = Result.valueToType(requestHeadersAction.results.getValues()(0).v.i32)
      requestHeadersAction.free()
      if (result != Result.ResultOk || data.httpResponse.isDefined) {
        data.httpResponse match {
          case None           =>
            reportError(result, vm, "proxyOnRequestBody")
            Left(
              play.api.mvc.Results
                .InternalServerError(Json.obj("error" -> s"no http response in context 2: ${result.value}"))
            ) // TODO: not sure if okay
          case Some(response) => Left(response)
        }
      } else {
        Right(())
      }
    }
  }

  def proxyOnResponseHeaders(
      contextId: Int,
      response: NgPluginHttpResponse,
      attrs: TypedMap
  ): Future[Either[play.api.mvc.Result, Unit]] = {
    val vm          = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val data        = VmData.empty().withResponse(response, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs         = new WasmOtoroshiParameters(3).pushInts(contextId, sizeHeaders, endOfStream)
    callPluginWithResults("proxy_on_response_headers", prs, 1, data, attrs).map { requestHeadersAction =>
      val result = Result.valueToType(requestHeadersAction.results.getValues()(0).v.i32)
      requestHeadersAction.free()
      if (result != Result.ResultOk || data.httpResponse.isDefined) {
        data.httpResponse match {
          case None           =>
            reportError(result, vm, "proxyOnResponseHeaders")
            Left(
              play.api.mvc.Results
                .InternalServerError(Json.obj("error" -> s"no http response in context 3: ${result.value}"))
            ) // TODO: not sure if okay
          case Some(response) => Left(response)
        }
      } else {
        Right(())
      }
    }
  }

  def proxyOnResponseBody(
      contextId: Int,
      response: NgPluginHttpResponse,
      body_bytes: ByteString,
      attrs: TypedMap
  ): Future[Either[play.api.mvc.Result, Unit]] = {
    val vm          = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val data        = VmData.empty().withResponse(response, attrs)(env)
    data.bodyInRef.set(body_bytes)
    val endOfStream = 1
    val sizeBody    = body_bytes.size.bytes.length
    val prs         = new WasmOtoroshiParameters(3).pushInts(contextId, sizeBody, endOfStream)
    callPluginWithResults("proxy_on_response_body", prs, 1, data, attrs).map { requestHeadersAction =>
      val result = Result.valueToType(requestHeadersAction.results.getValues()(0).v.i32)
      requestHeadersAction.free()
      if (result != Result.ResultOk || data.httpResponse.isDefined) {
        data.httpResponse match {
          case None           =>
            reportError(result, vm, "proxyOnResponseBody")
            Left(
              play.api.mvc.Results
                .InternalServerError(Json.obj("error" -> s"no http response in context 4: ${result.value}"))
            ) // TODO: not sure if okay
          case Some(response) => Left(response)
        }
      } else {
        Right(())
      }
    }
  }

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(false, true, createFunctions)).flatMap { vm =>
      val data = VmData.withRules(rules)
      attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey -> vm)
      vm.finitialize {
        proxyStart(attrs, data).flatMap { _ =>
          proxyCheckABIVersion(attrs, data).flatMap { _ =>
            // according to ABI, we should create a root context id before any operations
            proxyOnContexCreate(state.rootContextId, 0, attrs, data).flatMap { _ =>
              proxyOnVmStart(attrs, data).flatMap {
                case true =>
                  proxyOnConfigure(state.rootContextId, attrs, data).map {
                    case true => started.set(true)
                    case _    => logger.error("failed to configure coraza")
                  }
                case _    => logger.error("failed to start coraza vm").vfuture
              }
            }
          }
        }
      }
    }
  }

  def stop(attrs: TypedMap): Future[Unit] = {
    ().vfuture
  }
  // TODO - need to save VmData in attrs to get it from the start function and reuse the same slotId
  def runRequestPath(request: RequestHeader, attrs: TypedMap): Future[NgAccess] = {
    val contId = contextId.incrementAndGet()
    attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaContextIdKey -> contId)
    val instance = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val data     = VmData.withRules(rules)
    proxyOnContexCreate(contId, state.rootContextId, attrs, data).flatMap { _ =>
      proxyOnRequestHeaders(contId, request, attrs).map {
        case Left(errRes) =>
          proxyOnDone(contId, attrs)
          proxyOnDelete(contId, attrs)
          NgAccess.NgDenied(errRes)
        case Right(_)     => NgAccess.NgAllowed
      }
    }
  }

  def runRequestBodyPath(
      request: RequestHeader,
      req: NgPluginHttpRequest,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]] = {
    val contId = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaContextIdKey).get
    val f      =
      if (body_bytes.isDefined) proxyOnRequestBody(contId, request, req, body_bytes.get, attrs) else Right(()).vfuture
    // proxy_on_http_request_trailers
    // proxy_on_http_request_metadata : H2 only
    f.map {
      case Left(errRes) =>
        proxyOnDone(contId, attrs)
        proxyOnDelete(contId, attrs)
        Left(errRes)
      case Right(_)     => Right(())
    }
  }

  def runResponsePath(
      response: NgPluginHttpResponse,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]] = {
    val contId = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaContextIdKey).get
    proxyOnResponseHeaders(contId, response, attrs).flatMap {
      case Left(e)  => Left(e).vfuture
      case Right(_) => {
        val res =
          if (body_bytes.isDefined) proxyOnResponseBody(contId, response, body_bytes.get, attrs) else Right(()).vfuture
        // proxy_on_http_response_trailers
        // proxy_on_http_response_metadata : H2 only
        proxyOnDone(contId, attrs)
        proxyOnDelete(contId, attrs)
        res
      }
    }
  }
}

case class NgCorazaWAFConfig(ref: String) extends NgPluginConfig {
  override def json: JsValue = NgCorazaWAFConfig.format.writes(this)
}

object NgCorazaWAFConfig {
  val format = new Format[NgCorazaWAFConfig] {
    override def writes(o: NgCorazaWAFConfig): JsValue             = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[NgCorazaWAFConfig] = Try {
      NgCorazaWAFConfig(
        ref = json.select("ref").asString
      )
    } match {
      case Success(e) => JsSuccess(e)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

class NgCorazaWAF extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Coraza WAF"
  override def description: Option[String]                 = "Coraza WAF plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorazaWAFConfig("none").some

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  private val plugins = new UnboundedTrieMap[String, CorazaPlugin]()

  private def getPlugin(ref: String, attrs: TypedMap)(implicit env: Env): CorazaPlugin = plugins.synchronized {
    val config     = env.adminExtensions.extension[CorazaWafAdminExtension].get.states.config(ref).get
    val configHash = config.json.stringify.sha512
    val key        = s"ref=${ref}&hash=${configHash}"

    val plugin          = if (plugins.contains(key)) {
      plugins(key)
    } else {
      val url = s"http://127.0.0.1:${env.httpPort}/__otoroshi_assets/wasm/coraza-proxy-wasm-v0.1.2.wasm?$key"
      val p   = new CorazaPlugin(
        WasmConfig(
          source = WasmSource(
            kind = WasmSourceKind.Http,
            path = url
          ),
          memoryPages = 1000,
          functionName = None,
          wasi = true,
          // lifetime = WasmVmLifetime.Forever,
          instances = config.poolCapacity,
          killOptions = WasmVmKillOptions(
            maxCalls = 2000,
            maxMemoryUsage = 0.9,
            maxAvgCallDuration = 1.day,
            maxUnusedDuration = 5.minutes
          )
        ),
        config,
        url,
        env
      )
      plugins.put(key, p)
      p
    }
    val oldVersionsKeys = plugins.keySet.filter(_.startsWith(s"ref=${ref}&hash=")).filterNot(_ == key)
    val oldVersions     = oldVersionsKeys.flatMap(plugins.get)
    if (oldVersions.nonEmpty) {
      oldVersions.foreach(_.stop(attrs))
      oldVersionsKeys.foreach(plugins.remove)
    }
    plugin
  }

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref, ctx.attrs)
    plugin.start(ctx.attrs)
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).foreach(_.release())
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref, ctx.attrs)
    plugin.runRequestPath(ctx.request, ctx.attrs)
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val config                             = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin                             = getPlugin(config.ref, ctx.attrs)
    val hasBody                            = ctx.request.theHasBody
    val bytesf: Future[Option[ByteString]] =
      if (!plugin.config.inspectBody) None.vfuture
      else if (!hasBody) None.vfuture
      else {
        ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
      }
    bytesf.flatMap { bytes =>
      val req =
        if (plugin.config.inspectBody && hasBody) ctx.otoroshiRequest.copy(body = bytes.get.chunks(16 * 1024))
        else ctx.otoroshiRequest
      plugin
        .runRequestBodyPath(
          ctx.request,
          req,
          bytes,
          ctx.attrs
        )
        .map {
          case Left(result) => Left(result)
          case Right(_)     => Right(req)
        }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val config                             = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin                             = getPlugin(config.ref, ctx.attrs)
    val bytesf: Future[Option[ByteString]] =
      if (!plugin.config.inspectBody) None.vfuture
      else ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
    bytesf.flatMap { bytes =>
      val res =
        if (plugin.config.inspectBody) ctx.otoroshiResponse.copy(body = bytes.get.chunks(16 * 1024))
        else ctx.otoroshiResponse
      plugin
        .runResponsePath(
          res,
          bytes,
          ctx.attrs
        )
        .map {
          case Left(result) => Left(result)
          case Right(_)     => Right(res)
        }
    }
  }
}

case class CorazaWafConfig(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    inspectBody: Boolean,
    config: JsObject,
    poolCapacity: Int
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = CorazaWafConfig.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object CorazaWafConfig {
  def template(): CorazaWafConfig = CorazaWafConfig(
    location = EntityLocation.default,
    id = s"coraza-waf-config_${IdGenerator.uuid}",
    name = "New WAF",
    description = "New WAF",
    metadata = Map.empty,
    tags = Seq.empty,
    config = CorazaPlugin.corazaDefaultRules.asObject,
    inspectBody = true,
    poolCapacity = 2
  )
  val format                      = new Format[CorazaWafConfig] {
    override def writes(o: CorazaWafConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"            -> o.id,
      "name"          -> o.name,
      "description"   -> o.description,
      "metadata"      -> o.metadata,
      "tags"          -> JsArray(o.tags.map(JsString.apply)),
      "config"        -> o.config,
      "inspect_body"  -> o.inspectBody,
      "pool_capacity" -> o.poolCapacity
    )
    override def reads(json: JsValue): JsResult[CorazaWafConfig] = Try {
      CorazaWafConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
        inspectBody = (json \ "inspect_body").asOpt[Boolean].getOrElse(true),
        poolCapacity = (json \ "pool_capacity").asOpt[Int].getOrElse(2)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait CorazaWafConfigDataStore extends BasicStore[CorazaWafConfig]

class KvCorazaWafConfigDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends CorazaWafConfigDataStore
    with RedisLikeStore[CorazaWafConfig] {
  override def fmt: Format[CorazaWafConfig]              = CorazaWafConfig.format
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:configs:$id"
  override def extractId(value: CorazaWafConfig): String = value.id
}

class CorazaWafConfigAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val corazaConfigsDatastore: CorazaWafConfigDataStore =
    new KvCorazaWafConfigDataStore(extensionId, env.datastores.redis, env)
}

class CorazaWafConfigAdminExtensionState(env: Env) {

  private val configs = new UnboundedTrieMap[String, CorazaWafConfig]()

  def config(id: String): Option[CorazaWafConfig] = configs.get(id)
  def allConfigs(): Seq[CorazaWafConfig]          = configs.values.toSeq

  private[proxywasm] def updateConfigs(values: Seq[CorazaWafConfig]): Unit = {
    configs.addAll(values.map(v => (v.id, v))).remAll(configs.keySet.toSeq.diff(values.map(_.id)))
  }
}

class CorazaWafAdminExtension(val env: Env) extends AdminExtension {

  private[proxywasm] lazy val datastores = new CorazaWafConfigAdminExtensionDatastores(env, id)
  private[proxywasm] lazy val states     = new CorazaWafConfigAdminExtensionState(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.CorazaWAF")

  override def name: String = "Coraza WAF extension"

  override def description: Option[String] = "Coraza WAF extension".some

  override def enabled: Boolean = true

  override def start(): Unit = ()

  override def stop(): Unit = ()

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      configs <- datastores.corazaConfigsDatastore.findAll()
    } yield {
      states.updateConfigs(configs)
      ()
    }
  }

  // override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = {
  //   Seq(
  //     AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/coraza-extension.js")
  //   )
  // }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "CorazaConfig",
          "coraza-configs",
          "coraza-config",
          "coraza-waf.extensions.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[CorazaWafConfig](
            CorazaWafConfig.format,
            classOf[CorazaWafConfig],
            id => datastores.corazaConfigsDatastore.key(id),
            c => datastores.corazaConfigsDatastore.extractId(c),
            (json) => json.select("id").asString,
            () => "id",
            tmpl = (v, p) => CorazaWafConfig.template().json,
            stateAll = () => states.allConfigs(),
            stateOne = id => states.config(id),
            stateUpdate = values => states.updateConfigs(values)
          )
        )
      )
    )
  }
}

case class CorazaTrailEvent(level: org.slf4j.event.Level, msg: String) extends AnalyticEvent {

  override def `@service`: String            = "--"
  override def `@serviceId`: String          = "--"
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CorazaTrailEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(implicit env: Env): JsValue = {
    var fields = Map.empty[String, String]
    val txt    = Try(
      ReplaceAllWith("\\[([^\\]]*)\\]")
        .replaceOn(msg, 1) { token =>
          val parts = token.split(" ")
          val key   = parts.head
          val value = parts.tail
            .mkString(" ")
            .applyOnWithPredicate(_.startsWith("\""))(_.substring(1))
            .applyOnWithPredicate(_.endsWith("\""))(_.init)
          fields = fields.put((key, value))
          ""
        }
        .trim
    ).getOrElse("--")
    Json.obj(
      "@id" -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CorazaTrailEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "level"      -> level.name(),
      "raw"        -> msg,
      "msg"        -> txt,
      "fields"     -> JsObject(fields.mapValues(JsString.apply))
    )
  }
}
