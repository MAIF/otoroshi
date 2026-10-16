package functional

import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

// the answers of the generic admin api (and of the admin api route in front of it) to the calls it refuses, and to
// the calls the routes of conf/routes used to shadow
class GenericApiErrorsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def call(
      method: String,
      path: String,
      body: Option[String] = None,
      contentType: String = "application/json",
      credentials: Option[(String, String)] = Some(("admin-api-apikey-id", "admin-api-apikey-secret"))
  ): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withMethod(method)
      .applyOnWithOpt(credentials) { case (r, (id, secret)) => r.withAuth(id, secret, WSAuthScheme.BASIC) }
    body.map(b => req.addHttpHeaders("Content-Type" -> contentType).withBody(b)).getOrElse(req).execute().futureValue
  }

  private def lines(resp: WSResponse): Seq[JsValue] =
    resp.body[String].split("\n").toSeq.filter(_.trim.nonEmpty).map(Json.parse)

  private val routes = "/apis/proxy.otoroshi.io/v1/routes"

  "The generic admin api" should {
    "warm up" in {
      startOtoroshi()
    }
    "answer its refusals of credentials as json" in {
      Seq(None, Some(("admin-api-apikey-id", "not-the-secret"))).foreach { credentials =>
        val resp = call("GET", routes, credentials = credentials)
        withClue(s"$credentials: ${resp.status} ${resp.headers}") {
          resp.status must (be >= 400 and be < 500)
          resp.contentType mustBe "application/json"
          (resp.json \ "Otoroshi-Error").asOpt[String] must not be empty
        }
      }
    }
    "refuse an entity that is not a json object" in {
      Seq(("POST", routes), ("POST", s"$routes/route_not_an_object"), ("PUT", s"$routes/route_not_an_object")).foreach {
        case (method, path) =>
          val resp = call(method, path, Some("[]"))
          withClue(s"$method $path: ${resp.body[String]}") {
            resp.status mustBe 400
            (resp.json \ "error").asOpt[String] mustBe Some("bad_request")
          }
      }
    }
    "reach the generic operations of the drafts, and list the drafts of a kind" in {
      val drafts = "/apis/proxy.otoroshi.io/v1/drafts"
      Seq("_count", "_schema", "_template").foreach { op =>
        withClue(op)(call("GET", s"$drafts/$op").status mustBe 200)
      }
      call("GET", s"$drafts/draft_that_does_not_exist").status mustBe 404
      val byKind = call("GET", s"$drafts/_kind/draft_that_does_not_exist")
      byKind.status mustBe 200
      byKind.json mustBe Json.arr()
    }
    "serve its documentation through the admin api host" in {
      val resp = call("GET", "/apis/openapi.json")
      withClue(resp.body[String].take(500))(resp.status mustBe 200)
      (resp.json \ "openapi").asOpt[String] mustBe Some("3.0.3")
    }
    "shutdown" in {
      stopAll()
    }
  }
}
