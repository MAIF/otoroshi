package functional

import otoroshi.events.{GatewayEvent, Header}
import otoroshi.models.HSAlgoSettings
import play.api.libs.json.Json

// the masking applied to exported gateway events: credential header values, and the secret fields of the
// configurations an event embeds. the wiring in GatewayEvent.writes is checked by GatewayEventSecretsSpec
class EventMaskingSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  "GatewayEvent.maskHeaders" should {

    "mask the values of the credential headers, whatever their case, and keep the others" in {
      val headers = Seq(Header("Authorization", "Bearer abc"), Header("Cookie", "session=x"), Header("X-Trace", "t1"))
      GatewayEvent.maskHeaders(headers, Set("authorization", "cookie")) mustBe Seq(
        Header("Authorization", GatewayEvent.masked),
        Header("Cookie", GatewayEvent.masked),
        Header("X-Trace", "t1")
      )
    }
  }

  "GatewayEvent.maskSecrets" should {

    "mask secret fields at any depth, in objects and arrays" in {
      val json = Json.obj(
        "id"      -> "route-1",
        "plugins" -> Json.arr(
          Json.obj("config" -> Json.obj("secret" -> "s3cr3t", "ttl" -> 10)),
          Json.obj("config" -> Json.obj("client_secret" -> "cs", "clientSecret" -> "cs2", "client_id" -> "id"))
        ),
        "backend" -> Json.obj("client" -> Json.obj("proxy" -> Json.obj("host" -> "p", "password" -> "pw")))
      )
      GatewayEvent.maskSecrets(json) mustBe Json.obj(
        "id"      -> "route-1",
        "plugins" -> Json.arr(
          Json.obj("config" -> Json.obj("secret" -> GatewayEvent.masked, "ttl" -> 10)),
          Json.obj(
            "config" -> Json.obj(
              "client_secret" -> GatewayEvent.masked,
              "clientSecret"  -> GatewayEvent.masked,
              "client_id"     -> "id"
            )
          )
        ),
        "backend" -> Json.obj("client" -> Json.obj("proxy" -> Json.obj("host" -> "p", "password" -> GatewayEvent.masked)))
      )
    }

    "mask the secret of a jwt verifier" in {
      (GatewayEvent.maskSecrets(HSAlgoSettings(512, "verifier-secret").asJson) \ "secret").as[String] mustBe
      GatewayEvent.masked
    }

    "keep empty values, and values that are not strings, as they are" in {
      GatewayEvent.maskSecrets(Json.obj("secret" -> "", "token" -> 42, "password" -> Json.obj("x" -> 1))) mustBe
      Json.obj("secret" -> "", "token" -> 42, "password" -> Json.obj("x" -> 1))
    }
  }
}
