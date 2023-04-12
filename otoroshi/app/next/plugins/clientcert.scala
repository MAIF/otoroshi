package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgPluginConfig, _}
import otoroshi.utils.RegexPool
import otoroshi.utils.http.DN
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.security.cert.X509Certificate
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

case class NgHasClientCertMatchingValidatorConfig(
  serialNumbers: Seq[String] = Seq.empty,
  subjectDNs: Seq[String] = Seq.empty,
  issuerDNs: Seq[String] = Seq.empty,
  regexSubjectDNs: Seq[String] = Seq.empty,
  regexIssuerDNs: Seq[String] = Seq.empty,
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "serial_numbers" -> serialNumbers,
    "subject_dns" -> subjectDNs,
    "issuer_dns" -> issuerDNs,
    "regex_subject_dns" -> regexSubjectDNs,
    "regex_issuer_dns" -> regexIssuerDNs,
  )
}

object NgHasClientCertMatchingValidatorConfig {
  val format = new Format[NgHasClientCertMatchingValidatorConfig] {
    override def writes(o: NgHasClientCertMatchingValidatorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgHasClientCertMatchingValidatorConfig] = Try {
      NgHasClientCertMatchingValidatorConfig(
        serialNumbers = json.select("serial_numbers").asOpt[Seq[String]].getOrElse(Seq.empty),
        subjectDNs = json.select("subject_dns").asOpt[Seq[String]].getOrElse(Seq.empty),
        issuerDNs = json.select("issuer_dns").asOpt[Seq[String]].getOrElse(Seq.empty),
        regexSubjectDNs = json.select("regex_subject_dns").asOpt[Seq[String]].getOrElse(Seq.empty),
        regexIssuerDNs = json.select("regex_issuer_dns").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

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
        val config = context.cachedConfig(internalName)(NgHasClientCertMatchingValidatorConfig.format).getOrElse(NgHasClientCertMatchingValidatorConfig())
        if (
          certs.exists(cert => config.serialNumbers.contains(cert.sn)) ||
            certs.exists(cert => config.subjectDNs.exists(s => RegexPool(s).matches(cert.subject.stringify))) ||
            certs.exists(cert => config.issuerDNs.exists(s => RegexPool(s).matches(cert.issuer.stringify))) ||
            certs
              .exists(cert => config.regexSubjectDNs.exists(s => RegexPool.regex(s).matches(cert.subject.stringify))) ||
            certs.exists(cert => config.regexIssuerDNs.exists(s => RegexPool.regex(s).matches(cert.issuer.stringify)))
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

case class NgClientCertChainHeaderConfig(
    sendPem: Boolean = false,
    pemHeaderName: String = "X-Client-Cert-Pem",
    sendDns: Boolean = false,
    dnsHeaderName: String = "X-Client-Cert-DNs",
    sendChain: Boolean = false,
    chainHeaderName: String = "X-Client-Cert-Chain",
) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "send_pem" -> sendPem,
    "pem_header_name" -> pemHeaderName,
    "send_dns" -> sendDns,
    "dns_header_name" -> dnsHeaderName,
    "send_chain" -> sendChain,
    "chain_header_name" -> chainHeaderName,
  )
}

object NgClientCertChainHeaderConfig {
  val format = new Format[NgClientCertChainHeaderConfig] {
    override def writes(o: NgClientCertChainHeaderConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgClientCertChainHeaderConfig] = Try {
      NgClientCertChainHeaderConfig(
        sendPem = json.select("send_pem").asOpt[Boolean].getOrElse(false),
        pemHeaderName = json.select("pem_header_name").asOpt[String].getOrElse("X-Client-Cert-Pem"),
        sendDns = json.select("send_dns").asOpt[Boolean].getOrElse(false),
        dnsHeaderName = json.select("dns_header_name").asOpt[String].getOrElse("X-Client-Cert-DNs"),
        sendChain = json.select("send_chain").asOpt[Boolean].getOrElse(false),
        chainHeaderName = json.select("chain_header_name").asOpt[String].getOrElse("X-Client-Cert-Chain"),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
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
        val config = ctx.cachedConfig(internalName)(NgClientCertChainHeaderConfig.format).getOrElse(NgClientCertChainHeaderConfig())
        val pemMap = if (config.sendPem) Map(config.pemHeaderName -> ctx.request.clientCertChainPemString) else Map.empty
        val dnsMap =
          if (config.sendDns) Map(config.dnsHeaderName -> Json.stringify(JsArray(chain.map(c => JsString(DN(c.getSubjectDN.getName).stringify)))))
          else Map.empty
        val chainMap = if (config.sendChain) Map(config.chainHeaderName -> Json.stringify(jsonChain(chain))) else Map.empty
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ pemMap ++ dnsMap ++ chainMap
          )
        ).future
      }
    }
  }
}