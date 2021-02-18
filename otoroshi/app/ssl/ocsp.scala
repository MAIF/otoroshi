package otoroshi.ssl

import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import play.api.mvc.{RequestHeader, Result, Results}
import otoroshi.utils.syntax.implicits._
import utils.RequestImplicits._
import play.api.Logger
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

object OcspResponder {
  def apply(env: Env): OcspResponder = new OcspResponder(env)
}

// check for inspiration: https://github.com/wdawson/revoker/blob/master/src/main/java/wdawson/samples/revoker/resources/OCSPResponderResource.java
// for testing: https://akshayranganath.github.io/OCSP-Validation-With-Openssl/
// test command: openssl ocsp -issuer chain.pem -cert certificate.pem -text -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
// test command: openssl ocsp -issuer "ca.cer" -cert "*.oto.tools.cer" -text -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
class OcspResponder(env: Env) {

  private val logger = Logger("otoroshi-ocsp-responder")
  private implicit val mat = env.otoroshiMaterializer

  def respond(req: RequestHeader, body: Source[ByteString, _])(implicit ec: ExecutionContext): Future[Result] = {
    body.runFold(ByteString.empty)(_ ++ _).map { bs =>
      val body = bs.utf8String
      logger.info(s"method: ${req.method}, path: ${req.thePath}, content-type: ${req.contentType.getOrElse("--")}, accept: ${req.acceptedTypes}, body: $body")
      // TODO: compare query cert to revoked certs from db
      Results.NotImplemented(Json.obj("error" -> "not implemented yet !"))
    }
  }
}
