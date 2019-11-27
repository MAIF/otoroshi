package otoroshi.plugins.clientcert

import java.security.cert.X509Certificate

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import env.Env
import otoroshi.script._
import play.api.libs.json._
import play.api.mvc.Result
import utils.RegexPool
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}


class HasClientCertValidator extends AccessValidator {

  override def name: String = "Client Certificate Only"

  override def description: Option[String] = Some("Check if a client certificate is present in the request")

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(_) => FastFuture.successful(true)
      case _       => FastFuture.successful(false)
    }
  }
}

class HasClientCertMatchingValidator extends AccessValidator {

  override def name: String = "Client certificate matching"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "HasClientCertMatchingValidator" -> Json.obj(
      "serialNumbers" -> Json.arr(),
      "subjectDNs" -> Json.arr(),
      "issuerDNs" -> Json.arr(),
      "regexSubjectDNs" -> Json.arr(),
      "regexIssuerDNs" -> Json.arr(),
    )
  ))

  override def description: Option[String] = Some(
    """Check if client certificate matches the following configuration
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "HasClientCertMatchingValidator": {
      |    "serialNumbers": [],   // allowed certificated serial numbers
      |    "subjectDNs": [],      // allowed certificated DNs
      |    "issuerDNs": [],       // allowed certificated issuer DNs
      |    "regexSubjectDNs": [], // allowed certificated DNs matching regex
      |    "regexIssuerDNs": [],  // allowed certificated issuer DNs matching regex
      |  }
      |}
    """.stripMargin)

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(certs) => {
        val config = (context.config \ "HasClientCertMatchingValidator")
          .asOpt[JsValue]
          .orElse((context.globalConfig \ "GlobalHasClientCertMatchingValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val allowedSerialNumbers =
          (config \ "serialNumbers").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedSubjectDNs =
          (config \ "subjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedIssuerDNs =
          (config \ "issuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedSubjectDNs =
          (config \ "regexSubjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedIssuerDNs =
          (config \ "regexIssuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        if (certs.exists(cert => allowedSerialNumbers.exists(s => s == cert.getSerialNumber.toString(16))) ||
          certs.exists(cert => allowedSubjectDNs.exists(s => RegexPool(s).matches(cert.getSubjectDN.getName))) ||
          certs.exists(cert => allowedIssuerDNs.exists(s => RegexPool(s).matches(cert.getIssuerDN.getName))) ||
          certs.exists(
            cert => regexAllowedSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.getSubjectDN.getName))
          ) ||
          certs.exists(
            cert => regexAllowedIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.getIssuerDN.getName))
          )) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
        // val subjectDnMatching = (config \ "subjectDN").asOpt[String]
        // val issuerDnMatching = (config \ "issuerDN").asOpt[String]
        // (subjectDnMatching, issuerDnMatching) match {
        //   case (None, None)                  => FastFuture.successful(true)
        //   case (Some(subject), None)         => FastFuture.successful(certs.exists(_.getSubjectDN.getName.matches(subject)))
        //   case (None, Some(issuer))          => FastFuture.successful(certs.exists(_.getIssuerDN.getName.matches(issuer)))
        //   case (Some(subject), Some(issuer)) => FastFuture.successful(
        //     certs.exists(_.getSubjectDN.getName.matches(subject)) && certs.exists(_.getIssuerDN.getName.matches(issuer))
        //   )
        // }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

/*
 * # HasClientCertMatchingHttpValidator
 *
 * Like HasClientCertMatchingValidator but with the config. returned by an http call
 *
 * {
 *   "url"          // url for the call
 *   "headers": {}  // http header for the call
 *   "ttl": 600000  // cache ttl
 * }
 *
 */
class HasClientCertMatchingHttpValidator extends AccessValidator {

  override def name: String = "Client certificate matching (over http)"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "HasClientCertMatchingHttpValidator" -> Json.obj(
      "url" -> "http://foo.bar",
      "ttl" -> 600000,
      "headers" -> Json.obj()
    )
  ))

  override def description: Option[String] = Some(
    """Check if client certificate matches the following configuration
      |
      |expected response from http service is
      |
      |```json
      |{
      |  "serialNumbers": [],   // allowed certificated serial numbers
      |  "subjectDNs": [],      // allowed certificated DNs
      |  "issuerDNs": [],       // allowed certificated issuer DNs
      |  "regexSubjectDNs": [], // allowed certificated DNs matching regex
      |  "regexIssuerDNs": [],  // allowed certificated issuer DNs matching regex
      |}
      |```
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "HasClientCertMatchingValidator": {
      |    "url"          // url for the call
      |    "headers": {}  // http header for the call
      |    "ttl": 600000  // cache ttl
      |  }
      |}
    """.stripMargin)

  private val cache = new TrieMap[String, (Long, JsValue)]

  private def validate(certs: Seq[X509Certificate], values: JsValue): Boolean = {
    val allowedSerialNumbers =
      (values \ "serialNumbers").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val allowedSubjectDNs =
      (values \ "subjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val allowedIssuerDNs =
      (values \ "issuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val regexAllowedSubjectDNs =
      (values \ "regexSubjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    val regexAllowedIssuerDNs =
      (values \ "regexIssuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
    if (certs.exists(cert => allowedSerialNumbers.exists(s => s == cert.getSerialNumber.toString(16))) ||
      certs.exists(cert => allowedSubjectDNs.exists(s => RegexPool(s).matches(cert.getSubjectDN.getName))) ||
      certs.exists(cert => allowedIssuerDNs.exists(s => RegexPool(s).matches(cert.getIssuerDN.getName))) ||
      certs.exists(cert => regexAllowedSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.getSubjectDN.getName))) ||
      certs.exists(cert => regexAllowedIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.getIssuerDN.getName)))) {
      true
    } else {
      false
    }
  }

  private def fetch(url: String, headers: Map[String, String], ttl: Long)(implicit env: Env,
                                                                          ec: ExecutionContext): Future[JsValue] = {
    env.Ws
      .url(url)
      .withHttpHeaders(headers.toSeq: _*)
      .get()
      .map {
        case r if r.status == 200 =>
          cache.put(url, (System.currentTimeMillis(), r.json))
          r.json
        case _ =>
          cache.put(url, (System.currentTimeMillis(), Json.obj()))
          Json.obj()
      }
      .recover {
        case e =>
          e.printStackTrace()
          cache.put(url, (System.currentTimeMillis(), Json.obj()))
          Json.obj()
      }
  }

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.request.clientCertificateChain match {
      case Some(certs) => {
        val config = (context.config \ "HasClientCertMatchingHttpValidator")
          .asOpt[JsValue]
          .orElse((context.globalConfig \ "GlobalHasClientCertMatchingHttpValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val url     = (config \ "url").as[String]
        val headers = (config \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
        val ttl     = (config \ "ttl").asOpt[Long].getOrElse(10 * 60000L)
        val start   = System.currentTimeMillis()
        cache.get(url) match {
          case None =>
            fetch(url, headers, ttl).map(b => validate(certs, b))
          case Some((time, values)) if start - time <= ttl =>
            FastFuture.successful(validate(certs, values))
          case Some((time, values)) if start - time > ttl =>
            fetch(url, headers, ttl)
            FastFuture.successful(validate(certs, values))
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}

class ClientCertChainHeader extends RequestTransformer {

  override def name: String = "Client certificate header"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "ClientCertChain" -> Json.obj(
      "pem" -> Json.obj("send" -> false, "header" -> "X-Client-Cert-Pem"),
      "dns" -> Json.obj("send" -> false, "header" -> "X-Client-Cert-DNs"),
      "chain" -> Json.obj("send" -> true, "header" -> "X-Client-Cert-Chain"),
      "claims" -> Json.obj("send" -> false, "header" -> "clientCertChain"),
    )
  ))

  override def description: Option[String] = Some(
    """This plugin pass client certificate informations to the target in headers.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "ClientCertChain": {
      |    "enabled": true, // enabled cache
      |    "ttl": 300000,  // store it for some times (5 minutes by default)
      |    "maxSize": 5242880, // max body size (body will be cut after that)
      |    "filter": { // cacge only for some status, method and paths
      |      "statuses": [],
      |      "methods": [],
      |      "paths": [],
      |      "not": {
      |        "statuses": [],
      |        "methods": [],
      |        "paths": []
      |      }
      |    }
      |  }
      |}
      |```
    """.stripMargin)

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