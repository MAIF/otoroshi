package otoroshi.wasm.proxywasm

import akka.stream.Materializer
import akka.util.ByteString
import com.sksamuel.exts.concurrent.Futures.RichFuture
import org.extism.sdk.parameters._
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api._
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic._
import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

object CorazaPlugin {
  val rootContextIds = new AtomicInteger(100)
  val testRules = Json.parse(
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

class CorazaPlugin(wasm: WasmConfig, configRef: String, env: Env) {

  private lazy val started = new AtomicBoolean(false)
  private lazy val logger = Logger("otoroshi-plugin-coraza")
  private lazy val vmConfigurationSize = 0
  lazy val config = env.adminExtensions.extension[CorazaWafAdminExtension].get.states.config(configRef).get
  private lazy val rules = config.config
  private lazy val pluginConfigurationSize = rules.stringify.byteString.length
  private val contextId = new AtomicInteger(0)
  private lazy val state = new ProxyWasmState(CorazaPlugin.rootContextIds.incrementAndGet(), contextId)
  private lazy val functions = ProxyWasmFunctions.build(state)(env.otoroshiExecutionContext, env, env.otoroshiMaterializer)

  def isStarted(): Boolean = started.get()

  def callPluginWithoutResults(function: String, params: Parameters, data: VmData, attrs: TypedMap): Unit = {
    otoroshi.wasm.WasmUtils.rawExecute(
      config = wasm,
      defaultFunctionName = function,
      input = None,
      parameters = params.some,
      resultSize = None,
      attrs = attrs.some,
      ctx = Some(data),
      addHostFunctions = functions,
    )(env).await(5.seconds)
  }

  def callPluginWithResults(function: String, params: Parameters, results: Int, data: VmData, attrs: TypedMap): Future[org.extism.sdk.parameters.Results] = {
    otoroshi.wasm.WasmUtils.rawExecute(
      config = wasm,
      defaultFunctionName = function,
      input = None,
      parameters = params.some,
      resultSize = results.some,
      attrs = attrs.some,
      ctx = Some(data),
      addHostFunctions = functions,
    )(env).flatMap {
      case Left(err) =>
        logger.error(s"error while calling plugin: ${err}")
        Future.failed(new RuntimeException(s"callPluginWithResults: ${err.stringify}"))
      case Right((_, results)) => results.vfuture
    }(env.otoroshiExecutionContext)
  }

  def proxyOnContexCreate(contextId: Int, rootContextId: Int, attrs: TypedMap, rootData: VmData): Unit = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, contextId, rootContextId)
    callPluginWithoutResults("proxy_on_context_create", prs, rootData, attrs)
  }

  def proxyOnVmStart(attrs: TypedMap, rootData: VmData): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, 0, vmConfigurationSize)
    val proxyOnVmStartAction = callPluginWithResults("proxy_on_vm_start", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnVmStartAction.getValues()(0).v.i32 != 0;
  }

  def proxyOnConfigure(rootContextId: Int, attrs: TypedMap, rootData: VmData): Boolean = {
    val prs = new Parameters(2)
    new IntegerParameter().addAll(prs, rootContextId, pluginConfigurationSize)
    val proxyOnConfigureAction = callPluginWithResults("proxy_on_configure", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnConfigureAction.getValues()(0).v.i32 != 0
  }

  def proxyOnDone(rootContextId: Int, attrs: TypedMap): Boolean = {
    val prs = new Parameters(1)
    new IntegerParameter().addAll(prs, rootContextId)
    val rootData = VmData.empty()
    val proxyOnConfigureAction = callPluginWithResults("proxy_on_done", prs, 1, rootData, attrs).await(5.seconds)
    proxyOnConfigureAction.getValues()(0).v.i32 != 0
  }

  def proxyOnDelete(rootContextId: Int, attrs: TypedMap): Unit = {
    val prs = new Parameters(1)
    new IntegerParameter().addAll(prs, rootContextId)
    val rootData = VmData.empty()
    callPluginWithoutResults("proxy_on_done", prs, rootData, attrs)
  }

  def proxyStart(attrs: TypedMap, rootData: VmData): Unit = {
    callPluginWithoutResults("_start", new Parameters(0), rootData, attrs)
  }

  def proxyCheckABIVersion(attrs: TypedMap, rootData: VmData): Unit = {
    callPluginWithoutResults("proxy_abi_version_0_2_0", new Parameters(0), rootData, attrs)
  }

  def proxyOnRequestHeaders(contextId: Int, request: RequestHeader, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = VmData.empty().withRequest(request, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeHeaders, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_request_headers", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    if (result != Result.ResultOk || data.httpResponse.isDefined) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnRequestBody(contextId: Int, request: RequestHeader, req: NgPluginHttpRequest, body_bytes: ByteString, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = VmData.empty().withRequest(request, attrs)(env)
    data.bodyInRef.set(body_bytes)
    val endOfStream = 1
    val sizeBody = body_bytes.size.bytes.length
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeBody, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_request_body", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    if (result != Result.ResultOk || data.httpResponse.isDefined) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnResponseHeaders(contextId: Int, response: NgPluginHttpResponse, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = VmData.empty().withResponse(response, attrs)(env)
    val endOfStream = 1
    val sizeHeaders = 0
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeHeaders, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_response_headers", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    if (result != Result.ResultOk || data.httpResponse.isDefined) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def proxyOnResponseBody(contextId: Int, response: NgPluginHttpResponse, body_bytes: ByteString, attrs: TypedMap): Either[play.api.mvc.Result, Unit] = {
    val data = VmData.empty().withResponse(response, attrs)(env)
    data.bodyInRef.set(body_bytes)
    val endOfStream = 1
    val sizeBody = body_bytes.size.bytes.length
    val prs = new Parameters(3)
    new IntegerParameter().addAll(prs, contextId, sizeBody, endOfStream)
    val requestHeadersAction = callPluginWithResults("proxy_on_response_body", prs, 1, data, attrs).await(5.seconds)
    val result = Result.valueToType(requestHeadersAction.getValues()(0).v.i32)
    if (result != Result.ResultOk || data.httpResponse.isDefined) {
      data.httpResponse match {
        case None => Left(play.api.mvc.Results.InternalServerError(Json.obj("error" -> "no http response in context"))) // TODO: not sure if okay
        case Some(response) => Left(response)
      }
    } else {
      Right(())
    }
  }

  def start(attrs: TypedMap): Unit = {
    val data = VmData.withRules(rules)
    proxyStart(attrs, data)
    proxyCheckABIVersion(attrs, data)
    // according to ABI, we should create a root context id before any operations
    proxyOnContexCreate(state.rootContextId, 0, attrs, data)
    if (proxyOnVmStart(attrs, data)) {
      if (proxyOnConfigure(state.rootContextId, attrs, data)) {
        started.set(true)
        //proxyOnContexCreate(state.contextId.get(), state.rootContextId, attrs)
      } else {
        logger.error("failed to configure coraza")
      }
    } else {
      logger.error("failed to start coraza vm")
    }
  }

  def stop(attrs: TypedMap): Unit = {}

  def runRequestPath(request: RequestHeader, attrs: TypedMap): NgAccess = {
    contextId.incrementAndGet()
    val data = VmData.withRules(rules)
    proxyOnContexCreate(state.contextId.get(), state.rootContextId, attrs, data)
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

  def runRequestBodyPath(request: RequestHeader, req: NgPluginHttpRequest, body_bytes: Option[ByteString], attrs: TypedMap): Either[mvc.Result, Unit] = {
    val res = for {
      _ <- if (body_bytes.isDefined) proxyOnRequestBody(state.contextId.get(), request, req, body_bytes.get, attrs) else Right(())
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

  def runResponsePath(response: NgPluginHttpResponse, body_bytes: Option[ByteString], attrs: TypedMap): Either[mvc.Result, Unit] = {
    val res = for {
      _ <- proxyOnResponseHeaders(state.contextId.get(), response, attrs)
      _ <- if (body_bytes.isDefined) proxyOnResponseBody(state.contextId.get(), response, body_bytes.get, attrs) else Right(())
      // proxy_on_http_response_trailers
      // proxy_on_http_response_metadata : H2 only
    } yield ()
    proxyOnDone(state.contextId.get(), attrs)
    proxyOnDelete(state.contextId.get(), attrs)
    res
  }
}

case class NgCorazaWAFConfig(ref: String) extends NgPluginConfig {
  override def json: JsValue = NgCorazaWAFConfig.format.writes(this)
}

object NgCorazaWAFConfig {
  val format = new Format[NgCorazaWAFConfig] {
    override def writes(o: NgCorazaWAFConfig): JsValue = Json.obj("ref" -> o.ref)
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

  // TODO: update plugin if config changed !!!
  // TODO: avoid blocking calls for wasm calls
  // TODO: send event on coraza error log
  // TODO: fix the context_id situation
  // TODO: add job to preinstantiate plugin
  // TODO: fix [error] otoroshi-proxy-wasm - ProcessResponseHeaders has already been called tx_id="fUMQtGalZkeffptNgxh"
  // TODO: root [warn] otoroshi-proxy-wasm - ProcessResponseBody should have already been called tx_id="fUMQtGalZkeffptNgxh"
  // TODO: add coraza.wasm build in the release process

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Coraza WAF"
  override def description: Option[String] = "Coraza WAF plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorazaWAFConfig("none").some

  override def isAccessAsync: Boolean = true
  override def isTransformRequestAsync: Boolean = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false

  private val plugins = new LegitTrieMap[String, CorazaPlugin]()

  private def getPlugin(ref: String)(implicit env: Env): CorazaPlugin = {
    // println(s"get plugin: ${ref}")
    plugins.getOrUpdate(ref) {
      // println(s"create plugin: ${ref}")
      new CorazaPlugin(WasmConfig(
        source = WasmSource(
          kind = WasmSourceKind.Http,
          path = s"http://127.0.0.1:${env.httpPort}/__otoroshi_assets/wasm/coraza.wasm"
        ),
        memoryPages = 1000,
        functionName = None,
        wasi = true
      ), ref, env)
    }
  }

  override def beforeRequest(ctx: NgBeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref)
    if (!plugin.isStarted()) {
      plugin.start(ctx.attrs)
    }
    ().vfuture
  }

  override def afterRequest(ctx: NgAfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref)
    plugin.runRequestPath(ctx.request, ctx.attrs).vfuture
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref)
    val hasBody = ctx.request.theHasBody
    val bytesf: Future[Option[ByteString]] = if (!plugin.config.inspectBody) None.vfuture else if (!hasBody) None.vfuture else {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
    }
    bytesf.map { bytes =>
      val req = if (plugin.config.inspectBody && hasBody) ctx.otoroshiRequest.copy(body = bytes.get.chunks(16 * 1024)) else ctx.otoroshiRequest
      plugin.runRequestBodyPath(
        ctx.request,
        req,
        bytes,
        ctx.attrs,
      ).map(_ => req)
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = getPlugin(config.ref)
    val bytesf: Future[Option[ByteString]] = if (!plugin.config.inspectBody) None.vfuture else ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
    bytesf.map { bytes =>
      val res = if (plugin.config.inspectBody) ctx.otoroshiResponse.copy(body = bytes.get.chunks(16 * 1024)) else ctx.otoroshiResponse
      plugin.runResponsePath(
        res,
        bytes,
        ctx.attrs,
      ).map(_ => res)
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
  )
  val format = new Format[CorazaWafConfig] {
    override def writes(o: CorazaWafConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "config"      -> o.config,
      "inspect_body" -> o.inspectBody,
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
  override def fmt: Format[CorazaWafConfig]                        = CorazaWafConfig.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:configs:$id"
  override def extractId(value: CorazaWafConfig): String           = value.id
}

class CorazaWafConfigAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val corazaConfigsDatastore: CorazaWafConfigDataStore = new KvCorazaWafConfigDataStore(extensionId, env.datastores.redis, env)
}

class CorazaWafConfigAdminExtensionState(env: Env) {

  private val configs = new LegitTrieMap[String, CorazaWafConfig]()

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

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = {
    Seq(
      AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/coraza-extension.js")
    )
  }

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
            id => datastores.corazaConfigsDatastore.key(id),
            c => datastores.corazaConfigsDatastore.extractId(c),
            tmpl = () => CorazaWafConfig.template().json,
            stateAll = () => states.allConfigs(),
            stateOne = id => states.config(id),
            stateUpdate = values => states.updateConfigs(values)
          )
        )
      )
    )
  }
}

