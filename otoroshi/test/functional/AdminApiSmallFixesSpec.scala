package functional

import otoroshi.env.Env
import otoroshi.ssl.{CertificateData, FakeKeyStore}
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import java.util.Base64
import scala.concurrent.duration.*

// small things of the admin api: the extensions of a certificate in hexadecimal
class AdminApiSmallFixesSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def admin(path: String): WSResponse = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders("Host" -> "otoroshi-api.oto.tools")
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
    .get()
    .futureValue

  "The admin api" should {
    "warm up" in {
      startOtoroshi()
    }
    "write the der values of the extensions of a certificate in hexadecimal" in {
      given env: Env = otoroshiComponents.env
      val cert       = FakeKeyStore.createSelfSignedCertificate("small-fixes.oto.tools", 1.day, None, None).cert
      val data       = CertificateData(Base64.getMimeEncoder.encodeToString(cert.getEncoded))
      val extensions = (data \ "cExtensions").as[Seq[JsObject]] ++ (data \ "ncExtensions").as[Seq[JsObject]]
      extensions must not be empty
      extensions.map(e => (e \ "value").as[String]).foreach { value =>
        value must fullyMatch regex "([0-9A-F]{2}:)*[0-9A-F]{2}"
      }
      // the subject alternative names hold the dns name
      val san        = extensions.find(e => (e \ "oid").as[String] == "2.5.29.17").map(e => (e \ "value").as[String])
      san.get must endWith("small-fixes.oto.tools".getBytes("UTF-8").map(b => f"$b%02X").mkString(":"))
    }
    "shutdown" in {
      stopAll()
    }
  }
}
