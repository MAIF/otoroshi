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
    "shutdown" in {
      stopAll()
    }
  }
}
