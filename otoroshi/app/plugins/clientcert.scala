package otoroshi.plugins.clientcert

import java.security.cert.X509Certificate

import akka.stream.Materializer
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.libs.json._
import play.api.mvc.Result
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class ClientCertChain extends RequestTransformer {

  private def jsonChain(chain: Seq[X509Certificate]): JsArray = {
    JsArray(chain.map(c =>
      Json.obj(
        "subjectDN"    -> c.getSubjectDN.getName,
        "issuerDN"     -> c.getIssuerDN.getName,
        "notAfter"     -> c.getNotAfter.getTime,
        "notBefore"    -> c.getNotBefore.getTime,
        "serialNumber" -> c.getSerialNumber.toString(16),
        "subjectCN" -> Option(c.getSubjectDN.getName)
          .flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN=")))
          .map(_.replace("CN=", ""))
          .getOrElse(c.getSubjectDN.getName)
          .asInstanceOf[String],
        "issuerCN" -> Option(c.getIssuerDN.getName)
          .flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN=")))
          .map(_.replace("CN=", ""))
          .getOrElse(c.getIssuerDN.getName)
          .asInstanceOf[String]
      ))
    )
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    ctx.request.clientCertificateChain match {
      case None => Right(ctx.otoroshiRequest).future
      case Some(chain) => {

        val config = (ctx.config \ "ClientCertChain").asOpt[JsObject].getOrElse(Json.obj())

        val sendAsPem = (config \ "pem" \ "send").asOpt[Boolean].getOrElse(false)
        val pemHeaderName = (config \ "pem" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain + "-pem")

        val sendDns = (config \ "dns" \ "send").asOpt[Boolean].getOrElse(false)
        val dnsHeaderName = (config \ "dns" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain + "-dns")

        val sendChain = (config \ "chain" \ "send").asOpt[Boolean].getOrElse(true)
        val chainHeaderName = (config \ "chain" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain)

        val sendClaims = (config \ "claims" \ "send").asOpt[Boolean].getOrElse(false)
        val claimsHeaderName = (config \ "claims" \ "header").asOpt[String].getOrElse("clientCertChain")

        val pemMap = if (sendAsPem) Map(pemHeaderName -> ctx.request.clientCertChainPemString) else Map.empty
        val dnsMap = if (sendDns) Map(dnsHeaderName -> Json.stringify(JsArray(chain.map(c => JsString(c.getSubjectDN.getName))))) else Map.empty
        val chainMap = if (sendChain) Map(chainHeaderName -> Json.stringify(jsonChain(chain))) else Map.empty

        Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers ++ pemMap ++ dnsMap ++ chainMap,
          claims = if (sendClaims) ctx.otoroshiRequest.claims.withJsArrayClaim(claimsHeaderName, Some(jsonChain(chain))) else ctx.otoroshiRequest.claims
        )).future
      }
    }
  }
}


/*

val clientCertChain = requestHeader
      .flatMap(_.clientCertificateChain)
      .map(
        chain =>

      )
 */