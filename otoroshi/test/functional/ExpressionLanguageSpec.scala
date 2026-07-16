package functional

import com.typesafe.config.ConfigFactory
import next.models.{Api, ApiDocumentationPlan}
import otoroshi.el.{
  GlobalExpressionLanguage,
  HeadersExpressionLanguage,
  JwtExpressionLanguage,
  RedirectionExpressionLanguage,
  TargetExpressionLanguage
}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation, PrivateAppsUser, ServiceGroupIdentifier}
import otoroshi.next.models.{NgDomainAndPath, NgMatchedRoute, NgRoute}
import otoroshi.utils.TypedMap
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc.{Cookie, RequestHeader}
import play.api.test.FakeRequest

import scala.collection.mutable
import scala.util.Failure

/**
 * Direct (non-request-lifecycle) validation of the Otoroshi Expression Language.
 *
 * The EL was rewritten for Scala 3: the macro-based `kaleidoscope` `r"..."` interpolator is now
 * backed by [[otoroshi.utils.KaleidoscopeShim]], and the `${...}` scanning by
 * [[otoroshi.utils.ReplaceAllWith]]. This spec exercises the whole `GlobalExpressionLanguage.apply`
 * pattern surface against hand-built model objects, to make sure the rewrite behaves exactly like
 * the original.
 *
 * A truly unit (env-less) test is not possible: `apply` wraps everything in
 * `env.metrics.withTimer(...)` and several branches read `env` directly. So we boot a minimal
 * in-memory Otoroshi once (just to obtain an `Env`), then call the EL directly with objects we
 * craft ourselves — never through an actual proxied request.
 */
class ExpressionLanguageSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString("""
          |otoroshi.test.elMarker = "el-works"
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  private lazy val env: Env = otoroshiComponents.env

  // ---------------------------------------------------------------------------------------------
  // sample objects, generously populated so we can reach as many EL branches as possible
  // ---------------------------------------------------------------------------------------------

  private lazy val sampleRoute: NgRoute = NgRoute.empty.copy(
    id = "route_test",
    name = "my-route",
    metadata = Map("owner" -> "team-a", "tier" -> "gold"),
    frontend = NgRoute.empty.frontend.copy(domains = Seq(NgDomainAndPath("api.example.com/api")))
  )

  private lazy val sampleApiKey: ApiKey = ApiKey(
    clientId = "ak_client_id",
    clientSecret = "ak_secret",
    clientName = "my-apikey",
    authorizedEntities = Seq(ServiceGroupIdentifier("default")),
    metadata = Map("team" -> "core", "tier" -> "gold"),
    tags = Seq("alpha", "beta", "gamma")
  )

  private lazy val sampleUser: PrivateAppsUser = PrivateAppsUser(
    randomId = "usr_test",
    name = "John Doe",
    email = "john@example.com",
    profile = Json.obj(
      "sub"        -> "john",
      "given_name" -> "John",
      "address"    -> Json.obj("city" -> "Poitiers")
    ),
    token = Json.obj(
      "access_token"  -> "at-123",
      "id_token"      -> "it-456",
      "refresh_token" -> "rt-789",
      "token_type"    -> "Bearer",
      "expires_in"    -> "3600",
      "custom"        -> "cval"
    ),
    realm = "test-realm",
    authConfigId = "auth_test",
    otoroshiData = Some(Json.obj("role" -> "admin", "level" -> 7, "nested" -> Json.obj("k" -> "v"))),
    tags = Seq("t1"),
    metadata = Map("m1" -> "v1"),
    location = EntityLocation()
  )

  private lazy val samplePlan: ApiDocumentationPlan =
    ApiDocumentationPlan(Json.obj("id" -> "plan_free", "name" -> "Free plan"))

  private lazy val sampleApi: Api = env.datastores.apiDataStore.template(env).copy(id = "api_test", name = "my-api")

  private lazy val sampleRequest: RequestHeader = FakeRequest("GET", "/api/foo?q=1&name=otoroshi")
    .withHeaders("Host" -> "api.example.com", "X-Custom" -> "custom-value")
    .withCookies(Cookie("session", "sess-123"))

  private lazy val sampleContext: Map[String, String] = Map(
    "foo"         -> "bar",
    "greeting"    -> "hello-world",
    "num"         -> "42",
    "pi"          -> "3.14",
    "item.color"  -> "red",
    "params.size" -> "L",
    "thedate"     -> "2020-01-01T00:00:00.000Z"
  )

  private lazy val sampleAttrs: TypedMap = TypedMap.empty
    // extra context read only from attrs
    .put(otoroshi.plugins.Keys.CurrentListenerKey    -> "standard")
    .put(otoroshi.plugins.Keys.UserAgentInfoKey      -> Json.obj("device" -> "iPhone", "os" -> "iOS"))
    .put(otoroshi.plugins.Keys.GeolocationInfoKey    -> Json.obj("country" -> "FR", "city" -> "Poitiers"))
    .put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> Json.obj("sub" -> "in-sub", "nested" -> Json.obj("k" -> "v")))
    .put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> Json.obj("iss" -> "otoroshi"))
    .put(otoroshi.plugins.Keys.MatchedRawInputTokenKey  -> "raw-input-jwt")
    .put(otoroshi.plugins.Keys.MatchedRawOutputTokenKey -> "raw-output-jwt")
    .put(otoroshi.plugins.Keys.ApiKeyJwtKey          -> Json.obj("scope" -> "admin", "data" -> Json.obj("id" -> "x")))
    .put(otoroshi.next.plugins.Keys.MatchedRouteKey  -> NgMatchedRoute(sampleRoute, "/api/foo", mutable.HashMap("id" -> "123")))
    // entities, so the attrs-based `apply(value, attrs, env)` overload can resolve them too
    .put(otoroshi.plugins.Keys.RequestKey        -> sampleRequest)
    .put(otoroshi.next.plugins.Keys.RouteKey     -> sampleRoute)
    .put(otoroshi.plugins.Keys.ApiKeyKey         -> sampleApiKey)
    .put(otoroshi.plugins.Keys.UserKey           -> sampleUser)
    .put(otoroshi.plugins.Keys.ElCtxKey          -> sampleContext)
    .put(otoroshi.plugins.Keys.PlanKey           -> samplePlan)
    .put(otoroshi.plugins.Keys.ApiKey            -> sampleApi)

  /** call the EL directly with our sample objects; each argument is overridable per-case */
  private def el(
      value: String,
      req: Option[RequestHeader] = Some(sampleRequest),
      route: Option[NgRoute] = Some(sampleRoute),
      apiKey: Option[ApiKey] = Some(sampleApiKey),
      user: Option[PrivateAppsUser] = Some(sampleUser),
      context: Map[String, String] = sampleContext,
      attrs: TypedMap = sampleAttrs,
      plan: Option[ApiDocumentationPlan] = Some(samplePlan),
      api: Option[Api] = Some(sampleApi)
  ): String =
    GlobalExpressionLanguage.apply(value, req, None, route, apiKey, user, context, attrs, env, plan, api)

  "GlobalExpressionLanguage" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(e) => e.printStackTrace() }.futureValue // WARM UP
    }

    "leave strings without any ${...} untouched" in {
      el("hello world") mustBe "hello world"
      el("") mustBe ""
      el("100% done, price is $5") mustBe "100% done, price is $5"
    }

    "resolve ctx / token / item / params expressions" in {
      el("${ctx.foo}") mustBe "bar"
      el("${ctx.missing}") mustBe "no-ctx-missing"
      el("${ctx.foo:default}") mustBe "bar"
      el("${ctx.missing:default}") mustBe "default"
      el("${ctx.absent|ctx.foo}") mustBe "bar"
      el("${ctx.absent|ctx.stillabsent:dv}") mustBe "dv"
      el("${ctx.greeting.replace('-', '_')}") mustBe "hello_world"
      el("${ctx.greeting.replaceAll('-','#')}") mustBe "hello#world"
      el("${ctx.useragent.device}") mustBe "iPhone"
      el("${ctx.geolocation.country}") mustBe "FR"

      el("${token.foo}") mustBe "bar"
      el("${token.missing}") mustBe "no-token-missing"
      el("${token.missing:dv}") mustBe "dv"
      el("${token.absent|token.foo}") mustBe "bar"

      el("${item.color}") mustBe "red"
      el("${item.unknown}") mustBe "no-item-unknown"
      el("${params.size}") mustBe "L"
      el("${params.unknown}") mustBe "no-params-unknown"
    }

    "resolve || fallback chains (with :: default)" in {
      el("${ctx.absent || ctx.foo}") mustBe "bar"
      el("${ctx.foo || ctx.absent}") mustBe "bar"
      el("${ctx.absent || ctx.stillabsent :: mydefault}") mustBe "mydefault"
    }

    "resolve request expressions" in {
      el("${req.method}") mustBe "GET"
      el("${req.path}") mustBe "/api/foo"
      el("${req.uri}") mustBe "/api/foo?q=1&name=otoroshi"
      el("${req.host}") mustBe "api.example.com"
      el("${req.domain}") mustBe "api.example.com"
      el("${req.protocol}") mustBe "http"
      el("${req.secured}") mustBe "false"
      el("${req.fullUrl}") mustBe "http://api.example.com/api/foo?q=1&name=otoroshi"
      el("${req.ip_address}") mustBe "127.0.0.1"
      el("${req.listener}") mustBe "standard"

      el("${req.headers.X-Custom}") mustBe "custom-value"
      el("${req.headers.Missing}") mustBe "no-header-Missing"
      el("${req.headers.Missing:dv}") mustBe "dv"

      el("${req.query.name}") mustBe "otoroshi"
      el("${req.query.missing}") mustBe "no-query-missing"
      el("${req.query.missing:dv}") mustBe "dv"

      el("${req.cookies.session}") mustBe "sess-123"

      el("${req.pathparams.id}") mustBe "123"
      el("${req.pathparams.missing:dv}") mustBe "dv"
    }

    "resolve route expressions" in {
      el("${route.id}") mustBe "route_test"
      el("${route.name}") mustBe "my-route"
      el("${route.metadata.owner}") mustBe "team-a"
      el("${route.metadata.missing}") mustBe "no-meta-missing"
      el("${route.metadata.missing:dv}") mustBe "dv"
      el("${route.domains['0']}") mustBe "api.example.com/api"
      el("${route.domains['5':'nodomain']}") mustBe "nodomain"
      el("${route.json}") must include("route_test")
    }

    "resolve apikey expressions" in {
      el("${apikey.name}") mustBe "my-apikey"
      el("${apikey.id}") mustBe "ak_client_id"
      el("${apikey.clientId}") mustBe "ak_client_id"
      el("${apikey.metadata.team}") mustBe "core"
      el("${apikey.metadata.missing}") mustBe "no-meta-missing"
      el("${apikey.metadata.missing:dv}") mustBe "dv"
      el("${apikey.tags['0']}") mustBe "alpha"
      el("${apikey.tags['9':'notag']}") mustBe "notag"
      el("${apikey.json}") must include("ak_client_id")
    }

    "resolve user expressions" in {
      el("${user.name}") mustBe "John Doe"
      el("${user.email}") mustBe "john@example.com"
      el("${user.tokens.access_token}") mustBe "at-123"
      el("${user.tokens.id_token}") mustBe "it-456"
      el("${user.tokens.refresh_token}") mustBe "rt-789"
      el("${user.tokens.token_type}") mustBe "Bearer"
      el("${user.tokens.expires_in}") mustBe "3600"
      el("${user.tokens.custom}") mustBe "cval"
      el("${user.tokens.missing:dv}") mustBe "dv"
      el("${user.metadata.role}") mustBe "admin"
      el("${user.metadata.level}") mustBe "7"
      el("${user.metadata.nested.k}") mustBe "v"
      el("${user.metadata.missing:dv}") mustBe "dv"
      el("${user.profile.given_name}") mustBe "John"
      el("${user.profile.address.city}") mustBe "Poitiers"
      el("${user.profile.missing:dv}") mustBe "dv"
    }

    "resolve consumer expressions" in {
      // user takes precedence over apikey
      el("${consumer.id}") mustBe "john@example.com"
      el("${consumer.long_id}") mustBe "test-realm-john@example.com"
      el("${consumer.name}") mustBe "John Doe"
      el("${consumer.kind}") mustBe "user"
      el("${consumer.metadata.role}") mustBe "admin"
      // apikey only
      el("${consumer.id}", user = None) mustBe "ak_client_id"
      el("${consumer.name}", user = None) mustBe "my-apikey"
      el("${consumer.kind}", user = None) mustBe "apikey"
      // public
      el("${consumer.kind}", user = None, apiKey = None) mustBe "public"
    }

    "resolve jwt / apikeyjwt expressions" in {
      el("${in_jwt.sub}") mustBe "in-sub"
      el("${in_jwt.nested.k}") mustBe "v"
      el("${in_jwt.missing:dv}") mustBe "dv"
      el("${out_jwt.iss}") mustBe "otoroshi"
      el("${in_raw_jwt}") mustBe "raw-input-jwt"
      el("${out_raw_jwt}") mustBe "raw-output-jwt"
      el("${apikeyjwt.scope}") mustBe "admin"
      el("${apikeyjwt.data.id}") mustBe "x"
    }

    "resolve plan / api expressions" in {
      el("${plan.id}") mustBe "plan_free"
      el("${plan.name}") mustBe "Free plan"
      el("${api.id}") mustBe "api_test"
      el("${api.name}") mustBe "my-api"
    }

    "resolve deterministic date expressions" in {
      el("${date(2020-01-01T00:00:00.000Z).epoch_ms}") mustBe "1577836800000"
      el("${date(2020-01-01T00:00:00.000Z).epoch_sec}") mustBe "1577836800"
      el("${date(2020-01-01T00:00:00.000Z).plus_ms(1000).epoch_ms}") mustBe "1577836801000"
      el("${date(2020-01-01T00:00:00.000Z).minus_ms(500).epoch_ms}") mustBe "1577836799500"
      el("${date(2020-06-15T12:00:00.000).format('yyyy')}") mustBe "2020"
      // date_el: the inner expression is itself an EL that yields the date string
      el("${date_el(ctx.thedate).epoch_ms}") mustBe "1577836800000"
    }

    "resolve relative-now date expressions (shape only)" in {
      el("${now.epoch_ms}").toLong must be > 0L
      el("${now.epoch_sec}").toLong must be > 0L
      el("${now.format('yyyy')}").matches("[0-9]{4}") mustBe true
      el("${now.plus_ms(1000).epoch_ms}").toLong must be > 0L
    }

    "resolve env / config expressions" in {
      el("${env.THIS_ENV_VAR_DOES_NOT_EXIST_XYZ}") mustBe "no-env-var-THIS_ENV_VAR_DOES_NOT_EXIST_XYZ"
      el("${env.THIS_ENV_VAR_DOES_NOT_EXIST_XYZ:fallback}") mustBe "fallback"
      // an actual env var, if any well-named one exists
      sys.env.find { case (k, _) => k.matches("[A-Za-z_][A-Za-z0-9_]*") }.foreach { case (k, v) =>
        el("${env." + k + "}") mustBe v
      }
      el("${config.otoroshi.test.elMarker}") mustBe "el-works"
      el("${config.some.missing.key:cfgdefault}") mustBe "cfgdefault"
    }

    "resolve misc expressions" in {
      el("${nbf}") mustBe "{nbf}"
      el("${iat}") mustBe "{iat}"
      el("${exp}") mustBe "{exp}"
      el("${rand}").length mustBe 64
      el("${totally.unknown.thing}") mustBe "bad-expr"
    }

    "resolve multiple expressions in a single string" in {
      el("${ctx.foo} and ${item.color}") mustBe "bar and red"
      el("[${route.name}] ${req.method} ${req.path} for ${consumer.name}") mustBe "[my-route] GET /api/foo for John Doe"
    }

    "support the attrs-based apply(value, attrs, env) overload" in {
      GlobalExpressionLanguage.apply("${route.name}/${ctx.foo}/${user.email}", sampleAttrs, env) mustBe
        "my-route/bar/john@example.com"
    }

    "expose the same behaviour through the wrapper objects" in {
      HeadersExpressionLanguage(
        "${route.name}",
        Some(sampleRequest), None, Some(sampleRoute), Some(sampleApiKey), Some(sampleUser),
        sampleContext, sampleAttrs, env, Some(samplePlan), Some(sampleApi)
      ) mustBe "my-route"
      TargetExpressionLanguage(
        "${apikey.id}",
        Some(sampleRequest), None, Some(sampleRoute), Some(sampleApiKey), Some(sampleUser),
        sampleContext, sampleAttrs, env, Some(samplePlan), Some(sampleApi)
      ) mustBe "ak_client_id"
      RedirectionExpressionLanguage(
        "${ctx.foo}",
        Some(sampleRequest), None, Some(sampleRoute), Some(sampleApiKey), Some(sampleUser),
        sampleContext, sampleAttrs, env, Some(samplePlan), Some(sampleApi)
      ) mustBe "bar"
      JwtExpressionLanguage(
        "${user.email}",
        Some(sampleRequest), None, Some(sampleRoute), Some(sampleApiKey), Some(sampleUser),
        sampleContext, sampleAttrs, env, Some(samplePlan), Some(sampleApi)
      ) mustBe "john@example.com"
    }

    "coerce types through JwtExpressionLanguage.fromJson" in {
      def fromJson(v: JsValue): JsValue = JwtExpressionLanguage.fromJson(
        v,
        Some(sampleRequest), None, Some(sampleRoute), Some(sampleApiKey), Some(sampleUser),
        sampleContext, sampleAttrs, env, Some(samplePlan), Some(sampleApi)
      )

      // type coercion only applies when the *root* value is a JsString.
      // `.as[Double]`/`.as[Boolean]` also prove the type (they throw on a JsString).
      fromJson(JsString("${ctx.num}")).as[Double] mustBe 42.0
      fromJson(JsString("${ctx.pi}")).as[Double] mustBe 3.14
      fromJson(JsString("true")).as[Boolean] mustBe true
      fromJson(JsString("false")).as[Boolean] mustBe false
      fromJson(JsString("${ctx.missing:null}")) mustBe JsNull
      fromJson(JsString("${ctx.foo}")) mustBe JsString("bar")

      // inside objects/arrays, EL is applied but values stay JsString; non-strings are preserved
      val out = fromJson(
        Json.obj(
          "s"      -> "${ctx.foo}",
          "n"      -> "${ctx.num}",
          "keep"   -> 5,
          "nested" -> Json.obj("x" -> "${ctx.foo}"),
          "arr"    -> Json.arr("${ctx.num}", "plain")
        )
      )
      (out \ "s").as[String] mustBe "bar"
      (out \ "n").as[String] mustBe "42"
      (out \ "keep").as[Int] mustBe 5
      (out \ "nested" \ "x").as[String] mustBe "bar"
      (out \ "arr").as[List[JsValue]].map(_.as[String]) mustBe List("42", "plain")
    }

    "shutdown" in {
      stopAll()
    }
  }
}
