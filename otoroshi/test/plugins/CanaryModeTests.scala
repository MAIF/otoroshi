package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.atomic.AtomicInteger

class CanaryModeTests(parent: PluginsTestSpec) {

  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[CanaryMode],
        config = NgPluginInstanceConfig(
          NgCanarySettings(
            traffic = 0.5,
            targets = Seq(
              NgTarget(
                id = IdGenerator.uuid,
                hostname = "request.otoroshi.io",
                port = 443,
                tls = true
              )
            ),
            root = "/foo"
          ).json.as[JsObject]
        )
      )
    ),
    result = _ => Json.obj("foo" -> "bar")
  ).futureValue

  def call() = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()

  val user       = call.futureValue
  val userCookie = user.cookie("otoroshi-canary")

  val user1       = call.futureValue
  val user1Cookie = user1.cookie("otoroshi-canary")

  user.status mustBe Status.OK
  userCookie.isDefined mustBe true

  user1.status mustBe Status.OK
  user1Cookie.isDefined mustBe true

  userCookie.get == user1Cookie.get mustBe false

  val counterDefaultPath = new AtomicInteger(0)
  val counterFooPath     = new AtomicInteger(0)

  for (_ <- 3 to 100) {
    val res = call().futureValue
    if (res.json.selectAsOptString("path").isDefined)
      counterDefaultPath.incrementAndGet()
    else
      counterFooPath.incrementAndGet()
  }

  counterDefaultPath.get() > 0 mustBe true
  counterFooPath.get() > 0 mustBe true

  deleteOtoroshiRoute(route).futureValue
}
