package functional

import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.WSAuthScheme

// the endpoints of the admin api that patch a whole configuration: a patch that cannot be applied, or a body that is
// not a json patch, must be refused and leave the configuration as it was
class JsonPatchEndpointsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def call(method: String, path: String, body: Option[JsValue] = None): (Int, String) = {
    val req  = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    val resp = body
      .map(b => req.addHttpHeaders("Content-Type" -> "application/json").withBody(b.stringify))
      .getOrElse(req)
      .execute()
      .futureValue
    (resp.status, resp.body)
  }

  private def current(path: String): JsValue = {
    val (status, body) = call("GET", path)
    status mustBe 200
    Json.parse(body)
  }

  private val notApplicable = Json.arr(Json.obj("op" -> "replace", "path" -> "/does/not/exist", "value" -> 1))
  private val notAPatch     = Json.obj("maxLogsSize" -> 1)

  // sets a value through a patch that applies, so that a reset to the defaults shows, then sends the bad ones
  private def checkPatches(path: String, field: String, value: JsValue): Unit = {
    val (setStatus, setBody) = call("PATCH", path, Some(Json.arr(Json.obj("op" -> "replace", "path" -> s"/$field", "value" -> value))))
    withClue(setBody)(setStatus mustBe 200)
    val before = current(path)
    (before \ field).as[JsValue] mustBe value
    Seq("a patch that cannot be applied" -> notApplicable, "a body that is not a json patch" -> notAPatch).foreach {
      case (what, patch) =>
        val (status, body) = call("PATCH", path, Some(patch))
        withClue(s"$what: $body") {
          status mustBe 400
          current(path) mustBe before
        }
    }
  }

  "The patch endpoints of the admin api" should {
    "warm up" in {
      startOtoroshi()
    }
    "refuse a bad patch of the snow monkey config and keep it" in {
      checkPatches("/api/snowmonkey/config", "timesPerDay", JsNumber(7))
    }
    "refuse a bad patch of the global config and keep it" in {
      checkPatches("/api/globalconfig", "maxLogsSize", JsNumber(4242))
    }
    "shutdown" in {
      stopAll()
    }
  }
}
