package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, AnalyticEvent, ApiKeySecretWillRotate, GatewayEvent, GenericAlert}
import otoroshi.models.ApiKey
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import play.api.Configuration
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

// what the exporters and the audit store get from an audit or an alert event: by
// default none of the secrets it carries (the api key of an admin api call, the entities it is about, the
// credentials of a request), the rest untouched; everything as it is when app.events.maskSecrets is off.
// sbt "testOnly functional.AuditAlertEventSecretsSpec functional.AuditAlertEventSecretsUnmaskedSpec"
object AuditAlertEventSecretsSpec {

  val apikey: ApiKey = ApiKey(
    clientId = "audit-secrets-key",
    clientSecret = "audit-key-secret",
    clientName = "audit-secrets-key",
    authorizedEntities = Seq.empty
  )

  val secrets: Seq[String] = Seq("audit-key-secret", "cert-private-key", "alert-token", "alert-cookie")

  def events(env: Env): Seq[AnalyticEvent] = Seq(
    AdminApiEvent(
      "audit-1",
      "prod",
      Some(apikey),
      None,
      "CREATE_CERTIFICATE",
      "User created a certificate",
      "127.0.0.1",
      "curl",
      Json.obj("entity" -> Json.obj("id" -> "cert-1", "privateKey" -> "cert-private-key", "keep" -> "visible-value"))
    ),
    ApiKeySecretWillRotate("alert-1", "prod", apikey),
    GenericAlert("RequestAlert", env)(
      Json.obj(
        "request" -> Json.obj(
          "headers" -> Json.obj("Authorization" -> "Bearer alert-token", "X-Trace" -> "trace-1"),
          "cookies" -> Json.arr(Json.obj("name" -> "session", "value" -> "alert-cookie"))
        )
      )
    )
  )

  // the json every export reads
  def exported(env: Env): Seq[JsValue] = {
    given ExecutionContext = env.analyticsExecutionContext
    events(env).map(event => Await.result(event.toEnrichedJson(using env), 10.seconds))
  }
}

class AuditAlertEventSecretsSpec extends PluginsTestSpecBase {

  private def waitUntil(what: String)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 10000
    while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
    withClue(what)(cond mustBe true)
  }

  "An exported audit or alert event" should {

    "carry none of the secrets it embeds, and the rest as it is" in {
      val Seq(audit, alert, generic) = AuditAlertEventSecretsSpec.exported(env)
      val text                       = Json.stringify(Json.arr(audit, alert, generic))
      AuditAlertEventSecretsSpec.secrets.filter(text.contains) mustBe empty
      (audit \ "apiKey" \ "clientId").as[String] mustBe "audit-secrets-key"
      (audit \ "apiKey" \ "clientSecret").as[String] mustBe GatewayEvent.masked
      (audit \ "metadata" \ "entity" \ "privateKey").as[String] mustBe GatewayEvent.masked
      (audit \ "metadata" \ "entity" \ "keep").as[String] mustBe "visible-value"
      (alert \ "apikey" \ "clientId").as[String] mustBe "audit-secrets-key"
      (alert \ "apikey" \ "clientSecret").as[String] mustBe GatewayEvent.masked
      (generic \ "request" \ "headers" \ "Authorization").as[String] mustBe GatewayEvent.masked
      (generic \ "request" \ "headers" \ "X-Trace").as[String] mustBe "trace-1"
      (generic \ "request" \ "cookies" \ 0 \ "name").as[String] mustBe "session"
    }

    "be stored in the audit store without the secret of the admin api key of the call" in {
      val route = NgFakeRoute.route("audit-secrets-route")
      createOtoroshiRoute(route).futureValue
      try {
        def audits(): Seq[String] = env.datastores.auditDataStore.findAllRaw().futureValue.map(_.utf8String)
        // the creation of the route is audited with the admin api key of the call
        waitUntil("the creation of the route is audited")(audits().exists(_.contains("admin-api-apikey-id")))
        audits().filter(_.contains("admin-api-apikey-secret")) mustBe empty
        val (listed, status) = otoroshiApiCall("GET", "/api/audit/events?page=1&pageSize=50").futureValue
        status mustBe 200
        listed.stringify must include("admin-api-apikey-id")
        listed.stringify must not include "admin-api-apikey-secret"
      } finally {
        deleteOtoroshiRoute(route).futureValue
      }
    }
  }
}

class AuditAlertEventSecretsUnmaskedSpec extends PluginsTestSpecBase {

  override def configurationSpec: Configuration = Configuration(
    ConfigFactory.parseString("app.events.maskSecrets = false").resolve()
  )

  "An exported audit or alert event, with app.events.maskSecrets off" should {

    "carry what it embeds as it is" in {
      val text = Json.stringify(Json.toJson(AuditAlertEventSecretsSpec.exported(env)))
      AuditAlertEventSecretsSpec.secrets.filterNot(text.contains) mustBe empty
    }
  }
}
