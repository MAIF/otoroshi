package plugins

import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.models.*
import otoroshi.next.models.{NgPluginInstance, NgRoute, NgTarget}
import otoroshi.plugins.log4j.Log4jExpressionParser
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import java.util.concurrent.atomic.AtomicReference
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
}
