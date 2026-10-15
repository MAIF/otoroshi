package plugins

import functional.{PluginsTestSpecBase, TargetService}
import org.apache.pekko.http.scaladsl.model.headers.{`Set-Cookie`, HttpCookie}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgPlugins, NgRoute, NgTarget}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.proxy.{NgExecutionReport, RequestFlowReport}
import otoroshi.plugins.log4j.Log4jExpressionParser
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.mvc.{DefaultSessionCookieBaker, Session}
import play.api.test.FakeRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

class DosAndLeaksTests(parent: PluginsTestSpecBase) {
  import parent.*

  // nested unclosed expressions used to make the parsing cost exponential
  def log4ShellParser(): Unit = {
    val start = System.currentTimeMillis()
    Log4jExpressionParser.parse(("${" * 40) + "x")
    withClue("parsing 40 nested unclosed expressions ") {
      (System.currentTimeMillis() - start) must be < 2000L
    }
    Log4jExpressionParser.parseAsExp("a ${jndi:${lower:l}dap://x} b").computed.contains("jndi") mustBe true
    Log4jExpressionParser.parse("ends with $") mustBe empty
  }

  // the filter of the metrics endpoints is a literal wildcard pattern, not a regex
  def metricsFilter(): Unit = {
    env.metrics.counterInc("dos-and-leaks.probe")
    val start = System.currentTimeMillis()
    env.metrics.jsonRawExport(Some("(" + ("[^!]+" * 20) + ")+!"))
    withClue("a backtracking pattern as filter ") {
      (System.currentTimeMillis() - start) must be < 2000L
    }
    env.metrics.jsonRawExport(Some("*dos-and-leaks*")).as[JsArray].value must not be empty
  }

  // the backoffice session cookie used to be forwarded to the url of the graphql proxy
  def graphqlProxyCookies(): Unit = {
    val received = new AtomicReference[Option[String]](None)
    val target   = TargetService
      .full(
        None,
        "/schema",
        "application/json",
        req => {
          received.set(req.headers.find(_.name().equalsIgnoreCase("Cookie")).map(_.value()))
          (200, "{}", List.empty)
        }
      )
      .await()
    val user     = BackOfficeUser(
      randomId = "graphql-proxy-user",
      name = "graphql-proxy-user",
      email = "graphql-proxy-user@otoroshi.io",
      profile = Json.obj(),
      authConfigId = "basic",
      simpleLogin = true,
      tags = Seq.empty,
      metadata = Map.empty,
      rights = UserRights.superAdmin,
      adminEntityValidators = Map()
    )
    user.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue
    val baker    = new DefaultSessionCookieBaker(
      env.httpConfiguration.session,
      env.httpConfiguration.secret,
      new CookieSignerProvider(env.httpConfiguration.secret).get
    )
    val cookie   = baker.encodeAsCookie(Session(Map("bousr" -> user.randomId)))
    val status   = ws
      .url(s"http://127.0.0.1:$port/bo/api/graphqlproxy?url=http://127.0.0.1:${target.port}/schema")
      .withHttpHeaders("Host" -> env.backOfficeHost, "Cookie" -> s"${cookie.name}=${cookie.value}")
      .get()
      .futureValue
      .status
    status mustBe 200
    withClue("cookie received by the proxied url ") {
      received.get() mustBe None
    }
    user.delete()(using env.otoroshiExecutionContext, env).futureValue
    target.stop()
  }

  def externalRoute(target: TargetService, plugins: NgPluginInstance*): NgRoute = createRouteWithExternalTarget(
    plugins,
    target = Some(NgTarget(id = "dos-and-leaks-backend", hostname = "127.0.0.1", port = target.port, tls = false))
  ).futureValue

  // the session cookies of otoroshi (backoffice, private apps) used to be forwarded to the backends
  def sessionCookies(): Unit = {
    val received = new AtomicReference[Option[String]](None)
    val target   = TargetService
      .full(
        None,
        "/",
        "application/json",
        req => {
          received.set(req.headers.find(_.name().equalsIgnoreCase("Cookie")).map(_.value()))
          (200, "{}", List.empty)
        }
      )
      .await()
    val route    = externalRoute(target)
    val cookies  = Seq(
      s"${env.httpConfiguration.session.cookieName}=backoffice",
      "oto-papps-module=privateapps",
      s"${env.privateAppsSessionManager.cookieName}=sso",
      "app=kept"
    ).mkString("; ")
    val status   = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Cookie" -> cookies)
      .get()
      .futureValue
      .status
    status mustBe 200
    withClue("cookie received by the backend ") {
      received.get() mustBe Some("app=kept")
    }
    deleteOtoroshiRoute(route).futureValue
    target.stop()
  }

  // the response cache used to serve the response to a caller with credentials to every other caller, its
  // set-cookie included
  def responseCacheCredentials(): Unit = {
    val calls  = new AtomicInteger(0)
    val target = TargetService
      .full(
        None,
        "/",
        "application/json",
        _ => {
          val call = calls.incrementAndGet()
          (200, s"""{"call":$call}""", List(`Set-Cookie`(HttpCookie("caller", call.toString))))
        }
      )
      .await()
    val route  = externalRoute(
      target,
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgResponseCache],
        config = NgPluginInstanceConfig(NgResponseCacheConfig().json.as[JsObject])
      )
    )
    def get(path: String, headers: (String, String)*) = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders((Seq("Host" -> route.frontend.domains.head.domain) ++ headers)*)
      .get()
      .futureValue
    get("/anonymous").status mustBe 200
    await(500.millis)
    val cached = get("/anonymous")
    withClue("anonymous calls reaching the backend ") { calls.get() mustBe 1 }
    withClue("set-cookie served from the cache ") { cached.header("Set-Cookie") mustBe None }
    get("/anonymous", "Authorization" -> "Bearer caller")
    withClue("a caller with credentials served from the cache ") { calls.get() mustBe 2 }
    get("/private", "Cookie" -> "session=first")
    await(500.millis)
    get("/private", "Cookie" -> "session=second")
    withClue("a response to a caller with credentials stored in the cache ") { calls.get() mustBe 4 }
    deleteOtoroshiRoute(route).futureValue
    target.stop()
  }

  // the alerts and the flow reports used to export the route and the request as they are, secrets included
  def exportedAlertsMasking(): Unit = {
    val route  = NgRoute.empty.copy(
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(
            plugin = "cp:otoroshi.next.plugins.SomePlugin",
            config = NgPluginInstanceConfig(Json.obj("secret" -> "plugin-secret", "kept" -> "visible-value"))
          )
        )
      )
    )
    val report = new NgExecutionReport("masked-report", DateTime.now(), true)
    report
      .setContext(
        Json.obj(
          "request" -> Json.obj(
            "headers" -> Json.obj(
              "Authorization" -> "Bearer the-token",
              "Cookie"        -> "session=the-cookie",
              "Accept"        -> "text/visible"
            ),
            "cookies" -> Json.arr(Json.obj("name" -> "session", "value" -> "the-cookie-value"))
          )
        )
      )
      .markFailure("masked")
    val json   = Json.stringify(RequestFlowReport(report, route).toJson(using env))
    Seq("plugin-secret", "the-token", "the-cookie").foreach { secret =>
      withClue(s"$secret in the exported report ") { json must not include secret }
    }
    json must include("visible-value")
    json must include("text/visible")
  }

  // the plugins reading the whole body in memory used to read it without any bound
  def bodyLimits(): Unit = {
    val target        = TargetService.full(None, "/", "application/json", _ => (200, "{}", List.empty)).await()
    val log4shell     = externalRoute(
      target,
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgLog4ShellFilter],
        config = NgPluginInstanceConfig(NgLog4ShellFilterConfig(parseBody = true, maxBodySize = 1024).json.as[JsObject])
      )
    )
    val react2shell   = externalRoute(
      target,
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[React2SShellDetector],
        config = NgPluginInstanceConfig(React2SShellDetectorConfig(maxBodySize = 1024).json.as[JsObject])
      )
    )
    val tokenEndpoint = externalRoute(
      target,
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgClientCredentialTokenEndpoint],
        config = NgPluginInstanceConfig(
          NgClientCredentialTokenEndpointConfig.default.copy(maxBodySize = 1024).json.as[JsObject]
        )
      )
    )
    def post(route: NgRoute, size: Int, contentType: String = "text/plain"): Int = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Content-Type" -> contentType)
      .post("a" * size)
      .futureValue
      .status
    withClue("log4shell, small body ") { post(log4shell, 100) mustBe 200 }
    withClue("log4shell, big body ") { post(log4shell, 2048) mustBe 413 }
    withClue("react2shell, small body ") { post(react2shell, 100) mustBe 200 }
    withClue("react2shell, big body ") { post(react2shell, 2048) mustBe 413 }
    withClue("token endpoint, small body ") {
      post(tokenEndpoint, 100, "application/x-www-form-urlencoded") must not be 413
    }
    withClue("token endpoint, big body ") { post(tokenEndpoint, 2048, "application/x-www-form-urlencoded") mustBe 413 }
    val ocsp          = env.ocspResponder
      .respond(FakeRequest(), Source.single(ByteString(Array.fill[Byte](5 * 1024 * 1024)(1))), Seq.empty)
      .futureValue
    withClue("ocsp responder, big body ") { ocsp.header.status mustBe 413 }
    Seq(log4shell, react2shell, tokenEndpoint).foreach(r => deleteOtoroshiRoute(r).futureValue)
    target.stop()
  }
}
