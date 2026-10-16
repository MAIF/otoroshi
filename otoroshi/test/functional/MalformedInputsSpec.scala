package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.ssl.Cert
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

// the /api controllers answer a malformed input with a client error, not with a server error
class MalformedInputsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.cluster.mode = "leader"
          |""".stripMargin)
        .resolve()
    ).withFallback(configuration)

  private def call(
      method: String,
      path: String,
      body: Option[String] = None,
      contentType: String = "application/json",
      headers: Seq[(String, String)] = Seq.empty
  ): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders(Seq("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json") ++ headers*)
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    body.map(b => req.addHttpHeaders("Content-Type" -> contentType).withBody(b)).getOrElse(req).execute().futureValue
  }

  private def mustBe400(resp: WSResponse): Unit = withClue(resp.body[String])(resp.status mustBe 400)

  "The /api controllers" should {
    "warm up" in {
      startOtoroshi()
    }
    "refuse the malformed calls of the cluster api" in {
      mustBe400(call("PUT", "/api/cluster/quotas", Some("{}")))
      // the last line of the body does not need a new line
      val quotas = call("PUT", "/api/cluster/quotas", Some("""{"typ":"nothing"}"""), headers = Seq("Otoroshi-Worker-Name" -> "a-worker"))
      withClue(quotas.body[String])(quotas.status mustBe 200)
      val relay  = call("POST", "/api/cluster/relay", Some("{}"))
      mustBe400(relay)
      relay.body[String] must include("Otoroshi-Relay-Routing-Id")
      mustBe400(call("POST", "/api/cluster/user-tokens", Some("{}")))
    }
    "answer null statistics for a member that sent none" in {
      // a worker that asked for the state is a member, before it sent any statistics
      val state = call("GET", "/api/cluster/state", headers = Seq("Otoroshi-Worker-Name" -> "a-worker-without-stats"))
      withClue(state.status)(state.status mustBe 200)
      val infos = call("GET", "/api/infos?full=true")
      withClue(infos.body[String])(infos.status mustBe 200)
    }
    "refuse the malformed calls of the pki" in {
      given env: Env = otoroshiComponents.env
      val deadline   = System.currentTimeMillis() + 60000L
      while (
        env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA).futureValue.isEmpty &&
        System.currentTimeMillis() < deadline
      ) Thread.sleep(500)
      call("POST", "/api/pki/cas/not-a-ca-nor-a-number/certs", Some("{}")).status mustBe 404
      mustBe400(call("POST", "/api/pki/certs", Some("not json"), contentType = "text/plain"))
      val sign = s"/api/pki/cas/${Cert.OtoroshiIntermediateCA}/certs/_sign"
      mustBe400(call("POST", s"$sign?duration=not-a-number", Some("not pem"), contentType = "text/plain"))
      mustBe400(call("POST", sign, Some("not pem"), contentType = "text/plain"))
      mustBe400(call("POST", "/api/pki/certs/_letencrypt", Some("not json"), contentType = "text/plain"))
    }
    "refuse the malformed calls of the analytics and the imports" in {
      mustBe400(call("POST", "/api/analytics/_test-connection", Some(Json.obj("uri" -> "not an uri").stringify)))
      // nothing is imported
      mustBe400(call("POST", "/api/otoroshi.json", Some("[]")))
      mustBe400(call("POST", "/api/import", Some("not json"), contentType = "text/plain"))
    }
    "refuse the malformed calls of the api keys and the openapi of an api" in {
      mustBe400(call("GET", "/apis/apis.otoroshi.io/v1/apis/an-api/apikeys?page=one"))
      mustBe400(call("GET", "/apis/apis.otoroshi.io/v1/apis/an-api/apikeys?enabled=maybe"))
      mustBe400(call("POST", "/apis/apis.otoroshi.io/v1/apis/an-api/apikeys", Some("[]")))
      val openapi = Json.obj(
        "domain"          -> "openapi.oto.tools",
        "openapi"         -> "not an url",
        "contextPath"     -> "/",
        "backendHostname" -> "localhost",
        "backendPath"     -> "/"
      )
      mustBe400(call("POST", "/apis/apis.otoroshi.io/v1/apis/_openapi", Some(openapi.stringify)))
    }
    "refuse the paging and ranges that are not numbers, and filter on any field" in {
      Seq(
        "/api/audit/events?page=one",
        "/api/alert/events?pageSize=ten",
        "/api/stats/global?from=yesterday",
        "/api/events?to=now",
        "/api/stats?page=one"
      ).foreach(path => withClue(path)(mustBe400(call("GET", path))))
      Seq("/api/audit/events?filter.not_a_field=1", "/api/audit/events?filter.@timestamp=not-a-number").foreach { path =>
        val resp = call("GET", path)
        withClue(s"$path ${resp.body[String]}")(resp.status mustBe 200)
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
