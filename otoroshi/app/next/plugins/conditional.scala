package otoroshi.next.plugins

import org.apache.pekko.Done
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.{JsonPathUtils, JsonPathValidator, TypedMap}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Result}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait ConditionalEvaluationMode {
  def name: String
  def json: JsValue = JsString(name)
}

object ConditionalEvaluationMode {

  // predicates are evaluated again on every phase the wrapped plugin takes part in
  case object PerPhase extends ConditionalEvaluationMode { def name: String = "per_phase" }
  // predicates are evaluated on the first phase the wrapper is called on, the decision is then reused
  case object Once     extends ConditionalEvaluationMode { def name: String = "once"      }
  // predicates are evaluated until they match once, the wrapped plugin then runs on every later phase
  case object Latch    extends ConditionalEvaluationMode { def name: String = "latch"     }

  val all: Seq[ConditionalEvaluationMode] = Seq(PerPhase, Once, Latch)

  def apply(value: String): Option[ConditionalEvaluationMode] = value.toLowerCase().trim match {
    case "per_phase" => PerPhase.some
    case "per-phase" => PerPhase.some
    case "once"      => Once.some
    case "latch"     => Latch.some
    case _           => None
  }
}

case class ConditionalPluginConfig(
    predicates: Seq[JsonPathValidator] = Seq.empty,
    invert: Boolean = false,
    evaluationMode: ConditionalEvaluationMode = ConditionalEvaluationMode.PerPhase,
    plugin: Option[String] = None,
    pluginConfig: JsObject = Json.obj()
) extends NgPluginConfig {

  def json: JsValue = ConditionalPluginConfig.format.writes(this)

  // identifies this wrapper instance for the duration of a request. it cannot be derived from the
  // plugin index: `idx` is never set on NgbBackendCallContext, and handleApikeyPluginsFlow rebuilds
  // the chain mid-request and reassigns every instanceId. the parsed config is stable across both,
  // and across the two config merge conventions of the engine (defaultConfig ++ raw everywhere but
  // in callBackend, where the raw config is passed as is).
  lazy val stateKey: String = json.stringify
}

object ConditionalPluginConfig {

  val format = new Format[ConditionalPluginConfig] {

    override def writes(o: ConditionalPluginConfig): JsValue = Json.obj(
      "predicates"      -> JsArray(o.predicates.map(_.json)),
      "invert"          -> o.invert,
      "evaluation_mode" -> o.evaluationMode.json,
      "plugin"          -> o.plugin.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "plugin_config"   -> o.pluginConfig
    )

    override def reads(json: JsValue): JsResult[ConditionalPluginConfig] = Try {
      ConditionalPluginConfig(
        predicates = json
          .select("predicates")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty)
          .toSeq,
        invert = json.select("invert").asOpt[Boolean].getOrElse(false),
        evaluationMode = json
          .select("evaluation_mode")
          .asOpt[String]
          .flatMap(ConditionalEvaluationMode.apply)
          .getOrElse(ConditionalEvaluationMode.PerPhase),
        plugin = json.select("plugin").asOpt[String].filter(_.trim.nonEmpty),
        pluginConfig = json.select("plugin_config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }

  val configFlow: Seq[String] = Seq(
    "predicates",
    "invert",
    "evaluation_mode",
    "plugin",
    "plugin_config"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "predicates"      -> Json.obj(
        "type"   -> "object",
        "array"  -> true,
        "format" -> "form",
        "label"  -> "Predicates",
        "help"   -> "All the predicates must match for the wrapped plugin to run",
        "schema" -> Json.obj(
          "path"  -> Json.obj(
            "type"  -> "string",
            "label" -> "Path",
            "props" -> Json.obj("subTitle" -> "Example: $.apikey.metadata.foo")
          ),
          "value" -> Json.obj(
            "type"  -> "code",
            "help"  -> "Example: Contains(bar)",
            "props" -> Json.obj("label" -> "Value", "type" -> "json", "editorOnly" -> true)
          )
        ),
        "flow"   -> Json.arr("path", "value")
      ),
      "invert"          -> Json.obj(
        "type"  -> "boolean",
        "label" -> "Invert",
        "help"  -> "Run the wrapped plugin when the predicates do NOT match"
      ),
      "evaluation_mode" -> Json.obj(
        "type"  -> "select",
        "label" -> "Evaluation mode",
        "props" -> Json.obj(
          "options" -> JsArray(
            Seq(
              Json.obj("value" -> "per_phase", "label" -> "On every phase"),
              Json.obj("value" -> "once", "label"      -> "Once per request"),
              Json.obj("value" -> "latch", "label"     -> "Once matched, always run")
            )
          )
        )
      ),
      "plugin"          -> Json.obj(
        "type"  -> "select",
        "label" -> "Plugin",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/api/plugins/all",
          "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
        )
      ),
      "plugin_config"   -> Json.obj(
        "type"  -> "code",
        "label" -> "Plugin configuration",
        "props" -> Json.obj("type" -> "json", "editorOnly" -> true)
      )
    )
  )
}

// per request and per wrapper instance state
class ConditionalPluginState {
  val decision: AtomicReference[Option[Boolean]] = new AtomicReference[Option[Boolean]](None)
  val beforeRequestRan: AtomicBoolean            = new AtomicBoolean(false)
}

class ConditionalPlugin
    extends NgPreRouting
    with NgAccessValidator
    with NgRequestTransformer
    with NgBackendCall {

  override def steps: Seq[NgStep]                =
    Seq(
      NgStep.PreRoute,
      NgStep.ValidateAccess,
      NgStep.TransformRequest,
      NgStep.TransformResponse,
      NgStep.CallBackend
    )
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = true
  override def core: Boolean          = true
  override def name: String           = "Conditional plugin"

  // the plugin instance is a singleton per class, shared by every route, so none of these flags can
  // depend on the wrapped plugin. they are all true and each phase no-ops when it does not apply.
  override def useDelegates: Boolean             = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = true
  override def isPreRouteAsync: Boolean          = true
  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true

  override def defaultConfigObject: Option[NgPluginConfig] = ConditionalPluginConfig().some
  override def configFlow: Seq[String]                     = ConditionalPluginConfig.configFlow
  override def configSchema: Option[JsObject]              = ConditionalPluginConfig.configSchema

  override def description: Option[String] =
    """This plugin runs another plugin conditionally.
      |
      |It holds a list of JSONPath predicates, the id of a plugin and the configuration of that
      |plugin. When every predicate matches, the call is delegated to the wrapped plugin. When they
      |do not, the wrapper behaves as a no-op for that phase and the request goes on untouched.
      |
      |Only the plugins of the core phases can be wrapped: pre-routing, access validation, request
      |transformation, response transformation, error transformation and backend call. Route
      |matchers, request sinks, tunnel handlers, websocket plugins and incoming request validators
      |are not supported.
      |
      |The predicates receive the native context of the phase being run, completed so that
      |`$.request`, `$.apikey` and `$.user` are always present whatever the phase, `null` when they
      |are not known yet. Every native key of the phase stays reachable too, such as
      |`$.raw_request`, `$.otoroshi_request` or `$.otoroshi_response`. The whole route is available
      |under `$.attrs['otoroshi.next.core.Route']`.
      |
      |Beware that `$.apikey` and `$.user` are only filled in from the access validation phase
      |onwards. A predicate on the apikey is always false during pre-routing.
      |
      |### Evaluation modes
      |
      |A plugin can take part in several phases, and a predicate can become true or false in between
      |two of them. `evaluation_mode` decides what happens then:
      |
      |* `per_phase` (default): the predicates are evaluated again on every phase. The most reactive,
      |  but a plugin can run on some phases and not on others.
      |* `once`: the predicates are evaluated on the first phase the wrapper is called on and the
      |  decision is reused for the whole request. Always consistent, at the price of a poorer
      |  context: if the wrapped plugin starts at pre-routing, no apikey and no user are known yet.
      |* `latch`: the predicates are evaluated on every phase until they match once, after which the
      |  wrapped plugin runs on every remaining phase.
      |
      |`beforeRequest` and `afterRequest` are handled whatever the mode. If the predicates blocked
      |`beforeRequest` but a later phase is delegated, `beforeRequest` is run first, and
      |`afterRequest` then always runs at the end of the request. This matters for plugins that
      |acquire a resource in `beforeRequest` and release it in `afterRequest`.
      |
      |### Known limitations
      |
      |With `per_phase` and `latch`, a phase that was skipped cannot be replayed afterwards. A plugin
      |whose request transformation was skipped but whose response transformation runs will see an
      |unmodified request. Prefer `once` for multi-phase plugins, or write predicates over data that
      |does not change during a request, such as route metadata, request headers or the client IP.
      |
      |The flow report shows this plugin, not the wrapped one, because the report is built per slot
      |of the plugin chain and this plugin occupies the slot.
      |""".stripMargin.some

  private val logger = play.api.Logger("otoroshi-plugins-conditional")

  private val maxDepth = 5

  ///////////////////////////////////////////////////////////////////////////////////////////////
  // helpers
  ///////////////////////////////////////////////////////////////////////////////////////////////

  // not cachedConfig: its key is route + plugin name + idx, and neither part is unique enough here.
  // `idx` is never set on NgbBackendCallContext, and a conditional plugin wrapping another one runs
  // the very same class at the very same index, so both would read the config of the outer wrapper.
  // parsing costs far less than the json serialisation every predicate already pays.
  private def configOf(ctx: NgCachedConfigContext): ConditionalPluginConfig =
    ctx.rawConfig(ConditionalPluginConfig.format).getOrElse(ConditionalPluginConfig())

  private def resolve(
      config: ConditionalPluginConfig
  )(using env: Env, ec: ExecutionContext): Option[NgNamedPlugin] = {
    config.plugin.flatMap(id => env.scriptManager.getAnyScript[NgNamedPlugin](id).toOption)
  }

  // mirrors what the engine does before calling a plugin
  private def subConfig(wrapped: NgNamedPlugin, config: ConditionalPluginConfig): JsValue = {
    wrapped.defaultConfig.map(dc => dc ++ config.pluginConfig).getOrElse(config.pluginConfig)
  }

  private def stateFor(
      config: ConditionalPluginConfig,
      route: NgRoute,
      attrs: TypedMap
  ): ConditionalPluginState = {
    val states = attrs.get(Keys.ConditionalPluginsStateKey) match {
      case Some(map) => map
      case None      =>
        val map = new UnboundedTrieMap[String, ConditionalPluginState]()
        attrs.put(Keys.ConditionalPluginsStateKey -> map)
        map
    }
    states.getOrElseUpdate(s"${route.cacheableId}::${config.stateKey}", new ConditionalPluginState())
  }

  // the native context of each phase, completed so that `request`, `apikey` and `user` are always
  // there whatever the phase. every native key stays reachable ($.raw_request, $.otoroshi_request,
  // $.otoroshi_response, ...). nothing heavy is added here: the full route is already part of the
  // payload, under attrs["otoroshi.next.core.Route"] (see the NgRoute branch of TypedMap.json).
  private def normalized(json: JsValue): JsValue = {
    val obj     = json.asObject
    val keys    = obj.keys
    val missing = Json
      .obj()
      .applyOnIf(!keys.contains("request")) { o =>
        o ++ Json.obj("request" -> obj.select("raw_request").asOpt[JsValue].getOrElse(JsNull))
      }
      .applyOnIf(!keys.contains("apikey"))(_ ++ Json.obj("apikey" -> JsNull))
      .applyOnIf(!keys.contains("user"))(_ ++ Json.obj("user" -> JsNull))
    if (missing.fields.isEmpty) obj else obj ++ missing
  }

  private def evaluate(
      config: ConditionalPluginConfig,
      attrs: TypedMap,
      jsonContext: => JsValue
  )(using env: Env): Boolean = {
    val matched =
      if (config.predicates.isEmpty) true
      else {
        // parsed once, every predicate then reads its own path off it. going through
        // JsonPathValidator.validate(JsValue) would serialise and re-parse the whole context, route
        // and attrs included, once per predicate.
        val document = JsonPathUtils.document(normalized(jsonContext))
        config.predicates.forall { validator =>
          validator
            .copy(
              path = validator.path.evaluateEl(attrs),
              value = validator.value match {
                case JsString(value) => JsString(value.evaluateEl(attrs))
                case value           => value
              }
            )
            .validate(document)
        }
      }
    if (config.invert) !matched else matched
  }

  private def shouldRun(
      config: ConditionalPluginConfig,
      route: NgRoute,
      attrs: TypedMap,
      jsonContext: => JsValue
  )(using env: Env): Boolean = {
    config.evaluationMode match {
      case ConditionalEvaluationMode.PerPhase => evaluate(config, attrs, jsonContext)
      case ConditionalEvaluationMode.Once     =>
        val state = stateFor(config, route, attrs)
        state.decision.get() match {
          case Some(decision) => decision
          case None           =>
            val decision = evaluate(config, attrs, jsonContext)
            state.decision.set(Some(decision))
            decision
        }
      case ConditionalEvaluationMode.Latch    =>
        val state = stateFor(config, route, attrs)
        state.decision.get() match {
          case Some(true) => true
          case _          =>
            val decision = evaluate(config, attrs, jsonContext)
            if (decision) state.decision.set(Some(true))
            decision
        }
    }
  }

  // a conditional plugin can wrap another conditional plugin, which is how an OR of predicate groups
  // is expressed. a misconfiguration can then build a cycle, so nested delegations are capped.
  private def delegating[A](wrapped: NgNamedPlugin, attrs: TypedMap)(
      run: => Future[A]
  )(skip: => Future[A])(using ec: ExecutionContext): Future[A] = {
    if (!wrapped.isInstanceOf[ConditionalPlugin]) {
      run
    } else {
      val depth = attrs.get(Keys.ConditionalPluginDepthKey).getOrElse(0)
      if (depth >= maxDepth) {
        logger.error(s"nested conditional plugins deeper than $maxDepth, skipping. check your configuration")
        skip
      } else {
        attrs.put(Keys.ConditionalPluginDepthKey -> (depth + 1))
        run.andThen { case _ => attrs.put(Keys.ConditionalPluginDepthKey -> depth) }
      }
    }
  }

  // beforeRequest and afterRequest are a resource acquire/release pair. the predicates may have
  // blocked beforeRequest while letting a later phase through, so it is run just in time, at most
  // once per request, right before the first phase that actually delegates.
  private def ensureBeforeRequest(
      config: ConditionalPluginConfig,
      wrapped: NgNamedPlugin,
      route: NgRoute,
      snowflake: String,
      request: RequestHeader,
      globalConfig: JsValue,
      attrs: TypedMap,
      idx: Int
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    wrapped match {
      case plugin: NgRequestTransformer if plugin.usesCallbacks =>
        val state = stateFor(config, route, attrs)
        if (state.beforeRequestRan.compareAndSet(false, true)) {
          plugin.beforeRequest(
            NgBeforeRequestContext(
              snowflake = snowflake,
              route = route,
              request = request,
              config = subConfig(wrapped, config),
              attrs = attrs,
              globalConfig = globalConfig,
              idx = idx
            )
          )
        } else {
          ().vfuture
        }
      case _                                                    => ().vfuture
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  // phases
  ///////////////////////////////////////////////////////////////////////////////////////////////

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgRequestTransformer)
          if plugin.usesCallbacks && shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        ensureBeforeRequest(
          config,
          plugin,
          ctx.route,
          ctx.snowflake,
          ctx.request,
          ctx.globalConfig,
          ctx.attrs,
          ctx.idx
        )
      case _ => ().vfuture
    }
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgRequestTransformer) if plugin.usesCallbacks =>
        // no predicate here on purpose: whatever they say now, a beforeRequest that ran has to be
        // paired with an afterRequest
        if (stateFor(config, ctx.route, ctx.attrs).beforeRequestRan.get()) {
          plugin.afterRequest(ctx.copy(config = subConfig(plugin, config)))
        } else {
          ().vfuture
        }
      case _                                                          => ().vfuture
    }
  }

  override def preRoute(
      ctx: NgPreRoutingContext
  )(using env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    given Materializer = env.otoroshiMaterializer
    val config         = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgPreRouting) if shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.request,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.preRoute(ctx.copy(config = subConfig(plugin, config))))
        }(NgPreRouting.futureDone)
      case _                                                                              => NgPreRouting.futureDone
    }
  }

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    given Materializer = env.otoroshiMaterializer
    val config         = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgAccessValidator) if shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.request,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.access(ctx.copy(config = subConfig(plugin, config))))
        }(NgAccess.NgAllowed.vfuture)
      case _                                                                                   => NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val pass: Future[Either[Result, NgPluginHttpRequest]] = Right(ctx.otoroshiRequest).vfuture
    val config                                            = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgRequestTransformer)
          if plugin.transformsRequest && shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.request,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.transformRequest(ctx.copy(config = subConfig(plugin, config))))
        }(pass)
      case _ => pass
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val pass: Future[Either[Result, NgPluginHttpResponse]] = Right(ctx.otoroshiResponse).vfuture
    val config                                             = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgRequestTransformer)
          if plugin.transformsResponse && shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.request,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.transformResponse(ctx.copy(config = subConfig(plugin, config))))
        }(pass)
      case _ => pass
    }
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val config = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgRequestTransformer)
          if plugin.transformsError && shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.request,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.transformError(ctx.copy(config = subConfig(plugin, config))))
        }(ctx.otoroshiResponse.vfuture)
      case _ => ctx.otoroshiResponse.vfuture
    }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(using
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = configOf(ctx)
    resolve(config) match {
      case Some(plugin: NgBackendCall) if shouldRun(config, ctx.route, ctx.attrs, ctx.json) =>
        delegating(plugin, ctx.attrs) {
          ensureBeforeRequest(
            config,
            plugin,
            ctx.route,
            ctx.snowflake,
            ctx.rawRequest,
            ctx.globalConfig,
            ctx.attrs,
            ctx.idx
          ).flatMap(_ => plugin.callBackend(ctx.copy(config = subConfig(plugin, config)), delegates))
        }(nextBackendCall(ctx, delegates))
      case _                                                                                => nextBackendCall(ctx, delegates)
    }
  }

  // the engine only ever calls backendCallPlugins.head. a conditional plugin that does not wrap a
  // backend call, or whose predicates do not match, must therefore hand over to the next backend
  // call plugin of the route rather than to delegates(), or it would silently shadow it. the
  // position is tracked in the attrs so that a chain of conditional plugins cannot loop.
  private def nextBackendCall(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(using
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val plugins  = ctx.attrs.get(Keys.ContextualPluginsKey).map(_.backendCallPlugins).getOrElse(Seq.empty)
    val position = ctx.attrs.get(Keys.ConditionalBackendCallPositionKey).getOrElse(0)
    plugins.drop(position + 1).headOption match {
      case None          => delegates()
      case Some(wrapper) =>
        ctx.attrs.put(Keys.ConditionalBackendCallPositionKey -> (position + 1))
        val pluginConfig = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        wrapper.plugin.callBackend(ctx.copy(config = pluginConfig), delegates)
    }
  }
}
