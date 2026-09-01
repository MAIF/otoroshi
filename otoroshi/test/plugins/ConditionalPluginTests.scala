package plugins

import functional.PluginsTestSpec
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.*
import otoroshi.next.plugins.{
  AllowHttpMethods,
  ApikeyCalls,
  ConditionalEvaluationMode,
  ConditionalPlugin,
  ConditionalPluginConfig,
  NgAllowedMethodsConfig,
  NgApikeyCallsConfig,
  OverrideHost,
  StaticBackend,
  StaticBackendConfig
}
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits.*
import play.api.http.Status
import play.api.libs.json.*
import play.api.libs.ws.WSBodyReadables.given
import play.api.libs.ws.WSBodyWritables.writeableOf_JsValue
import play.api.mvc.Result

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

// records the phases the wrapped plugin actually went through, keyed by the "tag" of the wrapper
// instance so several scenarios can share the class.
object ConditionalProbes {
  private val events = new ConcurrentLinkedQueue[String]()

  def bump(phase: String, tag: String): Unit = events.add(s"$phase:$tag")

  def phasesOf(tag: String): Seq[String] =
    events.asScala.toSeq.filter(_.endsWith(s":$tag")).map(_.split(':').head)

  def reset(): Unit = events.clear()

  def tagOf(config: JsValue): String = config.select("tag").asOpt[String].getOrElse("untagged")
}

// a transformer with callbacks on, so that the lazy beforeRequest and the mirrored afterRequest of
// the conditional plugin can be observed.
class ConditionalProbe extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync: Boolean            = false

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ConditionalProbes.bump("before", ConditionalProbes.tagOf(ctx.config))
    ().vfuture
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ConditionalProbes.bump("after", ConditionalProbes.tagOf(ctx.config))
    ().vfuture
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ConditionalProbes.bump("transform", ConditionalProbes.tagOf(ctx.config))
    Right(ctx.otoroshiRequest)
  }
}

class ConditionalPluginTests(parent: PluginsTestSpec) {
  import parent.*

  private val overrideHost = NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])

  private val apikeyCalls = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApikeyCalls],
    config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
  )

  private def conditional(
      predicates: Seq[JsonPathValidator],
      plugin: String,
      pluginConfig: JsObject,
      invert: Boolean = false,
      mode: ConditionalEvaluationMode = ConditionalEvaluationMode.PerPhase
  ): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ConditionalPlugin],
    config = NgPluginInstanceConfig(
      ConditionalPluginConfig(
        predicates = predicates,
        invert = invert,
        evaluationMode = mode,
        plugin = plugin.some,
        pluginConfig = pluginConfig
      ).json.as[JsObject]
    )
  )

  // the wrapped access validator forbids GET, so a 405 means it ran and a 200 means it was skipped
  private val forbidGet = NgAllowedMethodsConfig(allowed = Seq("POST"), forbidden = Seq("GET")).json.as[JsObject]

  private val condHeaderMatches = Seq(JsonPathValidator("$.request.headers.cond", JsString("on")))

  private def probe(
      tag: String,
      mode: ConditionalEvaluationMode = ConditionalEvaluationMode.PerPhase,
      tier: String = "gold"
  ): NgPluginInstance = conditional(
    // no apikey at all in the json context of beforeRequest, so this predicate can only become true
    // once the access phase has extracted the apikey
    predicates = Seq(JsonPathValidator("$.apikey.metadata.tier", JsString(tier))),
    plugin = NgPluginHelper.pluginId[ConditionalProbe],
    pluginConfig = Json.obj("tag" -> tag),
    mode = mode
  )

  private def get(domain: String, headers: (String, String)*) = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(Seq("Host" -> domain) ++ headers*)
    .get()
    .futureValue

  // the router picks a freshly created route up asynchronously, leave it the time to
  private def routeWith(plugins: NgPluginInstance*): NgRoute = {
    val route = createRouteWithExternalTarget(plugins.toSeq).futureValue
    await(2.seconds)
    route
  }

  // -----------------------------------------------------------------------------------------------
  // the predicates gate the wrapped plugin
  // -----------------------------------------------------------------------------------------------

  val gated = routeWith(
    overrideHost,
    conditional(condHeaderMatches, NgPluginHelper.pluginId[AllowHttpMethods], forbidGet)
  )

  get(gated.frontend.domains.head.domain).status mustBe Status.OK
  get(gated.frontend.domains.head.domain, "cond" -> "on").status mustBe Status.METHOD_NOT_ALLOWED
  get(gated.frontend.domains.head.domain, "cond" -> "off").status mustBe Status.OK

  deleteOtoroshiRoute(gated).futureValue

  // -----------------------------------------------------------------------------------------------
  // invert flips the decision
  // -----------------------------------------------------------------------------------------------

  val inverted = routeWith(
    overrideHost,
    conditional(condHeaderMatches, NgPluginHelper.pluginId[AllowHttpMethods], forbidGet, invert = true)
  )

  get(inverted.frontend.domains.head.domain).status mustBe Status.METHOD_NOT_ALLOWED
  get(inverted.frontend.domains.head.domain, "cond" -> "on").status mustBe Status.OK

  deleteOtoroshiRoute(inverted).futureValue

  // -----------------------------------------------------------------------------------------------
  // beforeRequest runs late, afterRequest mirrors it
  // -----------------------------------------------------------------------------------------------

  val apikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "conditional-apikey",
    authorizedEntities = Seq.empty,
    metadata = Map("tier" -> "gold")
  )

  createOtoroshiApiKey(apikey).futureValue

  private def callWithApikey(domain: String) = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  // the predicate is false when beforeRequest is called and true from the request transformation on,
  // so beforeRequest has to be replayed just in time, exactly once, before the transformation
  ConditionalProbes.reset()

  val lazyBefore = routeWith(overrideHost, apikeyCalls, probe("lazy"))

  callWithApikey(lazyBefore.frontend.domains.head.domain).status mustBe Status.OK
  await(2.seconds)

  ConditionalProbes.phasesOf("lazy") mustBe Seq("before", "transform", "after")

  deleteOtoroshiRoute(lazyBefore).futureValue

  // a wrapped plugin that never runs never gets a beforeRequest, and therefore never an afterRequest
  ConditionalProbes.reset()

  val neverRuns = routeWith(overrideHost, apikeyCalls, probe("never", tier = "platinum"))

  callWithApikey(neverRuns.frontend.domains.head.domain).status mustBe Status.OK
  await(2.seconds)

  ConditionalProbes.phasesOf("never") mustBe Seq.empty

  deleteOtoroshiRoute(neverRuns).futureValue

  // -----------------------------------------------------------------------------------------------
  // evaluation modes
  // -----------------------------------------------------------------------------------------------

  // once: the decision is taken on the first phase the wrapper is called on, which is beforeRequest,
  // where no apikey is known yet. the wrapped plugin therefore never runs, unlike in per_phase.
  ConditionalProbes.reset()

  val onceRoute = routeWith(overrideHost, apikeyCalls, probe("once", mode = ConditionalEvaluationMode.Once))

  callWithApikey(onceRoute.frontend.domains.head.domain).status mustBe Status.OK
  await(2.seconds)

  ConditionalProbes.phasesOf("once") mustBe Seq.empty

  deleteOtoroshiRoute(onceRoute).futureValue

  // latch: false on beforeRequest, true from the transformation on, and it stays true afterwards
  ConditionalProbes.reset()

  val latchRoute = routeWith(overrideHost, apikeyCalls, probe("latch", mode = ConditionalEvaluationMode.Latch))

  callWithApikey(latchRoute.frontend.domains.head.domain).status mustBe Status.OK
  await(2.seconds)

  ConditionalProbes.phasesOf("latch") mustBe Seq("before", "transform", "after")

  deleteOtoroshiRoute(latchRoute).futureValue

  deleteOtoroshiApiKey(apikey).futureValue

  // -----------------------------------------------------------------------------------------------
  // the backend call of the route is not shadowed by a conditional plugin sitting in front of it
  // -----------------------------------------------------------------------------------------------

  val tempRoot: Path = Files.createTempDirectory("conditionalRoot")
  Files.write(tempRoot.resolve("index.html"), "<div>Hello from file system</div>".getBytes())

  private val staticBackend = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[StaticBackend],
    config = NgPluginInstanceConfig(StaticBackendConfig(tempRoot.toAbsolutePath.toString).json.as[JsObject])
  )

  // the conditional plugin wraps a transformer, so it is not a backend caller, but it still lands
  // first in the backend call plugins of the route. it has to hand over to the static backend and
  // not to the default http call.
  val notShadowed = routeWith(
    overrideHost,
    conditional(condHeaderMatches, NgPluginHelper.pluginId[ConditionalProbe], Json.obj("tag" -> "shadow")),
    staticBackend
  )

  val shadowResp = ws
    .url(s"http://127.0.0.1:$port/index.html")
    .withHttpHeaders("Host" -> notShadowed.frontend.domains.head.domain)
    .get()
    .futureValue

  shadowResp.status mustBe Status.OK
  shadowResp.body[String].contains("Hello from file system") mustBe true

  deleteOtoroshiRoute(notShadowed).futureValue

  // and a wrapped backend caller only serves the file when the predicates match. the very same
  // predicate as the access validation scenarios above is used on purpose: the backend call context
  // natively exposes `raw_request` and no `request`, and the normalisation is what makes
  // `$.request.headers.cond` keep working there
  val conditionalBackend = routeWith(
    overrideHost,
    conditional(
      condHeaderMatches,
      NgPluginHelper.pluginId[StaticBackend],
      StaticBackendConfig(tempRoot.toAbsolutePath.toString).json.as[JsObject]
    )
  )

  val served = ws
    .url(s"http://127.0.0.1:$port/index.html")
    .withHttpHeaders("Host" -> conditionalBackend.frontend.domains.head.domain, "cond" -> "on")
    .get()
    .futureValue

  served.status mustBe Status.OK
  served.body[String].contains("Hello from file system") mustBe true

  // without the header the static backend is skipped and the call goes to the real backend
  val proxied = ws
    .url(s"http://127.0.0.1:$port/index.html")
    .withHttpHeaders("Host" -> conditionalBackend.frontend.domains.head.domain)
    .get()
    .futureValue

  proxied.body[String].contains("Hello from file system") mustBe false

  deleteOtoroshiRoute(conditionalBackend).futureValue

  Files
    .walk(tempRoot)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)
}
