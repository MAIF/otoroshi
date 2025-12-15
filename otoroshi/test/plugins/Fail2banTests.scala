package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import java.net.InetAddress
import scala.concurrent.duration._

class Fail2BanTests(parent: PluginsTestSpec) {

  import parent._

  def banClientAfterMaxFailedAttempts() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "${route.id}-${req.ip}",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401", "403"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ => (401, "", List.empty))
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    val call1 = call().futureValue
    call1.status mustBe Status.UNAUTHORIZED

    val call2 = call().futureValue
    call2.status mustBe Status.UNAUTHORIZED

    val call3 = call().futureValue
    call3.status mustBe Status.UNAUTHORIZED

    val bannedCall = call().futureValue
    bannedCall.status mustBe Status.FORBIDDEN

    val body = bannedCall.json
    (body \ "error").asOpt[String] mustBe Some("temporary_ban")
    (body \ "message").asOpt[String].isDefined mustBe true
    (body \ "retry_in_seconds").asOpt[Long].isDefined mustBe true

    val stillBanned = call().futureValue
    stillBanned.status mustBe Status.FORBIDDEN

    deleteOtoroshiRoute(route).futureValue
  }

  def notBanClientOnSuccessfulRequests() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "${route.id}-${req.ip}",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401", "403"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      result = _ => Json.obj("success" -> true)
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    for (_ <- 1 to 10) {
      val response = call().futureValue
      response.status mustBe Status.OK
    }

    val finalCall = call().futureValue
    finalCall.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def notBanIgnoredIdentifiers() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "foo",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr("foo"),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ => (401, "", List.empty)),
      id = "ignored"
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    for (_ <- 1 to 10) {
      val response = call().futureValue
      response.status mustBe Status.UNAUTHORIZED
    }

    deleteOtoroshiRoute(route).futureValue
  }

  def permanentlyBlockBlockedIdentifiers() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "foo",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr("foo")
              )
              .as[JsObject]
          )
        )
      ),
      result = _ => Json.obj("success" -> true)
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    val blockedCall = call().futureValue
    blockedCall.status mustBe Status.FORBIDDEN
    (blockedCall.json \ "error").asOpt[String] mustBe Some("blocked")

    deleteOtoroshiRoute(route).futureValue
  }

  def onlyApplyToURLsMatchingAllowRules() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "foob",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 2,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(
                  Json.obj("pattern" -> "/api/.*", "mode" -> "allow")
                ),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ => (401, "", List.empty))
    ).futureValue

    def callPath(path: String) = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    callPath("/api/test").futureValue.status mustBe Status.UNAUTHORIZED
    callPath("/api/test").futureValue.status mustBe Status.UNAUTHORIZED

    val banned = callPath("/api/test").futureValue
    banned.status mustBe Status.FORBIDDEN
    (banned.json \ "error").asOpt[String] mustBe Some("temporary_ban")

    deleteOtoroshiRoute(route).futureValue
  }

  def notApplyToURLsMatchingBlockRules() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "bar2",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 2,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(
                  Json.obj("pattern" -> "/public/.*", "mode" -> "block")
                ),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ => (401, "", List.empty))
    ).futureValue

    def callPath(path: String) = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    // Many failures on /public/test should not trigger ban (blocked rule)
    for (_ <- 1 to 10) {
      val response = callPath("/public/test").futureValue
      response.status mustBe Status.UNAUTHORIZED
    }

    deleteOtoroshiRoute(route).futureValue
  }

  def countFailuresFromMultipleStatusSodes() = {
    var statusToReturn = 401

    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "${route.id}",
                "detect_time"  -> "60s",
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401", "403", "500-502"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ =>
        (
          statusToReturn,
          Json.stringify(Json.obj("error" -> s"error_$statusToReturn")),
          List(RawHeader("Content-Type", "application/json"))
        )
      )
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    // Mix different error status codes
    statusToReturn = 401
    call().futureValue.status mustBe Status.UNAUTHORIZED

    statusToReturn = 403
    call().futureValue.status mustBe Status.FORBIDDEN

    statusToReturn = 500
    call().futureValue.status mustBe Status.INTERNAL_SERVER_ERROR

    // Fourth call should trigger ban
    statusToReturn = 502
    val banned = call().futureValue
    banned.status mustBe Status.FORBIDDEN
    (banned.json \ "error").asOpt[String] mustBe Some("temporary_ban")

    deleteOtoroshiRoute(route).futureValue
  }

  def unbanClientAfterBanTimeExpires() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "zar",
                "detect_time"  -> "60s",
                "ban_time"     -> "2s", // Short ban time for testing
                "max_retry"    -> 2,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(req => {
        if (req.uri.path.toString() == "/test") {
          (401, Json.obj("error" -> "unauthorized").stringify, List(RawHeader("Content-Type", "application/json")))
        } else {
          (200, Json.obj("success" -> true).stringify, List(RawHeader("Content-Type", "application/json")))
        }
      })
    ).futureValue

    def call(path: String = "/test") = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    // Trigger ban
    call().futureValue.status mustBe Status.UNAUTHORIZED
    call().futureValue.status mustBe Status.UNAUTHORIZED

    val banned = call().futureValue
    banned.status mustBe Status.FORBIDDEN
    (banned.json \ "error").asOpt[String] mustBe Some("temporary_ban")

    // Wait for ban to expire
    Thread.sleep(3000)

    // Should be able to make successful requests now
    val unbanned = call("/success").futureValue
    unbanned.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def resetCounterAfterDetectionWindowExpires() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[Fail2BanPlugin],
          config = NgPluginInstanceConfig(
            Json
              .obj(
                "identifier"   -> "${route.id}",
                "detect_time"  -> "2s", // Short detection window
                "ban_time"     -> "10s",
                "max_retry"    -> 3,
                "status_codes" -> Json.arr("401"),
                "url_regex"    -> Json.arr(),
                "ignored"      -> Json.arr(),
                "blocked"      -> Json.arr()
              )
              .as[JsObject]
          )
        )
      ),
      rawResult = Some(_ =>
        (401, Json.obj("error" -> "unauthorized").stringify, List(RawHeader("Content-Type", "application/json")))
      )
    ).futureValue

    def call() = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()

    // Make 2 failed attempts
    call().futureValue.status mustBe Status.UNAUTHORIZED
    call().futureValue.status mustBe Status.UNAUTHORIZED

    // Wait for detection window to expire
    Thread.sleep(3000)

    // Counter should have reset, so we can make 2 more failures without ban
    call().futureValue.status mustBe Status.UNAUTHORIZED
    call().futureValue.status mustBe Status.UNAUTHORIZED

    deleteOtoroshiRoute(route).futureValue
  }
}
