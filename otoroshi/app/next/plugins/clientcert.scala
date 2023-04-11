package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.RegexPool
import otoroshi.utils.http.DN
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.security.cert.X509Certificate
import scala.concurrent.{ExecutionContext, Future}

// TODO: client side form
// TODO: translation in route
class NgHasClientCertValidator extends NgAccessValidator {

  override def name: String = "Client Certificate Only"
  override def description: Option[String] = "Check if a client certificate is present in the request".some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.request.clientCertificateChain match {
      case Some(_) => NgAccess.NgAllowed.vfuture
      case _ => Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}

class NgHasClientCertMatchingApikeyValidator extends NgAccessValidator {

  override def name: String = "Client Certificate + Api Key only"
  override def description: Option[String] =
      """Check if a client certificate is present in the request and that the apikey used matches the client certificate.
        |You can set the client cert. DN in an apikey metadata named `allowed-client-cert-dn`
        |""".stripMargin.some

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.request.clientCertificateChain match {
      case Some(_) =>
        ctx.apikey match {
          case Some(apikey) =>
            apikey.metadata.get("allowed-client-cert-dn") match {
              case Some(dn) =>
                ctx.request.clientCertificateChain match {
                  case Some(chain) =>
                    chain.headOption match {
                      case Some(cert) => RegexPool(dn).matches(DN(cert.getIssuerDN.getName).stringify) match {
                        case false => forbidden(ctx)
                        case true => NgAccess.NgAllowed.vfuture
                      }
                      case None => forbidden(ctx)
                    }
                  case None => forbidden(ctx)
                }
              case None => forbidden(ctx)
            }
          case None => forbidden(ctx)
        }
      case _ => forbidden(ctx)
    }
  }
}

case class SubIss(sn: String, subject: DN, issuer: DN)

class NgHasClientCertMatchingValidator extends NgAccessValidator {

  override def name: String = "Client certificate matching"
  override def description: Option[String] = "Check if client certificate matches the following configuration".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(context: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    context.request.clientCertificateChain
      .map(
        _.map(cert =>
          SubIss(cert.getSerialNumber.toString(16), DN(cert.getSubjectDN.getName), DN(cert.getIssuerDN.getName))
        )
      ) match {
      case Some(certs) => {
        val config                 = context.config
        val allowedSerialNumbers   =
          (config \ "serialNumbers").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedSubjectDNs      =
          (config \ "subjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedIssuerDNs       =
          (config \ "issuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedSubjectDNs =
          (config \ "regexSubjectDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val regexAllowedIssuerDNs  =
          (config \ "regexIssuerDNs").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        if (
          certs.exists(cert => allowedSerialNumbers.contains(cert.sn)) ||
            certs.exists(cert => allowedSubjectDNs.exists(s => RegexPool(s).matches(cert.subject.stringify))) ||
            certs.exists(cert => allowedIssuerDNs.exists(s => RegexPool(s).matches(cert.issuer.stringify))) ||
            certs
              .exists(cert => regexAllowedSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.subject.stringify))) ||
            certs.exists(cert => regexAllowedIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.issuer.stringify)))
        ) {
          NgAccess.NgAllowed.vfuture
        } else {
          forbidden(context)
        }
      }
      case _           => forbidden(context)
    }
  }
}

class NgClientCertChainHeader extends NgRequestTransformer {

  override def name: String = "Client certificate header"
  override def description: Option[String] = "This plugin pass client certificate informations to the target in headers".some

  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  private def jsonChain(chain: Seq[X509Certificate]): JsArray = {
    JsArray(
      chain.map(c =>
        Json.obj(
          "subjectDN" -> DN(c.getSubjectDN.getName).stringify,
          "issuerDN" -> DN(c.getIssuerDN.getName).stringify,
          "notAfter" -> c.getNotAfter.getTime,
          "notBefore" -> c.getNotBefore.getTime,
          "serialNumber" -> c.getSerialNumber.toString(16),
          "subjectCN" -> Option(DN(c.getSubjectDN.getName).stringify)
            .flatMap(_.split(",").toSeq.map(_.trim).find(_.toLowerCase().startsWith("cn=")))
            .map(_.replace("CN=", "").replace("cn=", ""))
            .getOrElse(DN(c.getSubjectDN.getName).stringify)
            .asInstanceOf[String],
          "issuerCN" -> Option(DN(c.getIssuerDN.getName).stringify)
            .flatMap(_.split(",").toSeq.map(_.trim).find(_.toLowerCase().startsWith("cn=")))
            .map(_.replace("CN=", "").replace("cn=", ""))
            .getOrElse(DN(c.getIssuerDN.getName).stringify)
            .asInstanceOf[String]
        )
      )
    )
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    ctx.request.clientCertificateChain match {
      case None        => Right(ctx.otoroshiRequest).future
      case Some(chain) => {

        val config = ctx.config

        val sendAsPem     = (config \ "pem" \ "send").asOpt[Boolean].getOrElse(false)
        val pemHeaderName =
          (config \ "pem" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain + "-pem")

        val sendDns       = (config \ "dns" \ "send").asOpt[Boolean].getOrElse(false)
        val dnsHeaderName =
          (config \ "dns" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain + "-dns")

        val sendChain       = (config \ "chain" \ "send").asOpt[Boolean].getOrElse(true)
        val chainHeaderName = (config \ "chain" \ "header").asOpt[String].getOrElse(env.Headers.OtoroshiClientCertChain)

        val pemMap   = if (sendAsPem) Map(pemHeaderName -> ctx.request.clientCertChainPemString) else Map.empty
        val dnsMap   =
          if (sendDns)
            Map(
              dnsHeaderName -> Json.stringify(JsArray(chain.map(c => JsString(DN(c.getSubjectDN.getName).stringify))))
            )
          else Map.empty
        val chainMap = if (sendChain) Map(chainHeaderName -> Json.stringify(jsonChain(chain))) else Map.empty

        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ pemMap ++ dnsMap ++ chainMap
          )
        ).future
      }
    }
  }
}