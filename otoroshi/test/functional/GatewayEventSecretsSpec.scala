package functional

import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{DataInOut, GatewayEvent, Header, Location}
import otoroshi.models.{GlobalJwtVerifier, HSAlgoSettings, RemainingQuotas}
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}

// what a data exporter receives for a proxied request: by default neither the credentials the request carried nor
// the secrets of the configurations embedded in the event, the rest untouched; everything as it is when
// app.events.maskSecrets is off. sbt "testOnly functional.GatewayEventSecretsSpec functional.GatewayEventSecretsUnmaskedSpec"
object GatewayEventSecretsSpec {

  val secrets: Seq[String] =
    Seq("request-token", "apikey-secret", "response-session", "request-session", "plugin-secret", "verifier-secret")

  def exported(env: Env): JsValue = {
    val route    = NgFakeRoute
      .route("event-secrets")
      .copy(plugins =
        NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = "cp:otoroshi.next.plugins.OverrideHost",
              config = NgPluginInstanceConfig(Json.obj("secret" -> "plugin-secret", "keep" -> "visible-value"))
            )
          )
        )
      )
    val verifier = GlobalJwtVerifier(
      id = "event-secrets-verifier",
      name = "verifier",
      desc = "",
      algoSettings = HSAlgoSettings(512, "verifier-secret")
    )
    GatewayEvent(
      `@id` = "event-1",
      `@timestamp` = DateTime.now(),
      `@calledAt` = DateTime.now(),
      reqId = "1",
      parentReqId = None,
      protocol = "HTTP/1.1",
      to = Location("event-secrets.oto.tools", "http", "/"),
      target = Location("backend.oto.tools", "http", "/"),
      url = "http://event-secrets.oto.tools/",
      method = "GET",
      from = "127.0.0.1",
      env = "prod",
      backendDuration = 1,
      duration = 2,
      requestStreamingDuration = 0,
      responseStreamingDuration = 0,
      backendResponseStreamingDuration = 0,
      overhead = 1,
      cbDuration = 0,
      overheadWoCb = 1,
      callAttempts = 1,
      data = DataInOut(0, 0),
      status = 200,
      headers = Seq(
        Header("Authorization", "Bearer request-token"),
        Header(env.Headers.OtoroshiClientSecret, "apikey-secret"),
        Header("X-Trace", "trace-1")
      ),
      headersOut = Seq(Header("Set-Cookie", "session=response-session")),
      otoroshiHeadersIn = Seq(Header("Cookie", "session=request-session")),
      otoroshiHeadersOut = Seq.empty,
      extraInfos = None,
      responseChunked = false,
      err = false,
      `@serviceId` = route.id,
      `@service` = route.name,
      descriptor = Some(route.legacy),
      route = Some(route),
      matchedJwtVerifier = Some(verifier),
      remainingQuotas = RemainingQuotas(),
      viz = None,
      userAgentInfo = None,
      geolocationInfo = None,
      extraAnalyticsData = None
    ).toJson(using env)
  }

  def headerValue(json: JsValue, key: String): Option[String] =
    (json \ "headers").as[Seq[JsObject]].find(h => (h \ "key").as[String] == key).map(h => (h \ "value").as[String])
}

class GatewayEventSecretsSpec extends PluginsTestSpecBase {

  "An exported gateway event" should {

    "carry neither the credentials of the request nor the secrets of the configurations it embeds" in {
      val json = GatewayEventSecretsSpec.exported(env)
      val text = Json.stringify(json)
      GatewayEventSecretsSpec.secrets.filter(text.contains) mustBe empty
      GatewayEventSecretsSpec.headerValue(json, "Authorization") mustBe Some(GatewayEvent.masked)
      GatewayEventSecretsSpec.headerValue(json, "X-Trace") mustBe Some("trace-1")
      text must include("visible-value")
      (json \ "route" \ "id").as[String] mustBe "event-secrets"
      (json \ "matcheJwtVerifier" \ "id").as[String] mustBe "event-secrets-verifier"
    }
  }
}

class GatewayEventSecretsUnmaskedSpec extends PluginsTestSpecBase {

  override def configurationSpec: Configuration = Configuration(
    ConfigFactory.parseString("app.events.maskSecrets = false").resolve()
  )

  "An exported gateway event, with app.events.maskSecrets off" should {

    "carry the headers and the configurations as they are" in {
      val json = GatewayEventSecretsSpec.exported(env)
      val text = Json.stringify(json)
      GatewayEventSecretsSpec.secrets.filterNot(text.contains) mustBe empty
      GatewayEventSecretsSpec.headerValue(json, "Authorization") mustBe Some("Bearer request-token")
    }
  }
}
