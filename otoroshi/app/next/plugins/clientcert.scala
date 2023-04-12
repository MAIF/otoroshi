package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import otoroshi.cluster.ClusterAgent
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, RemainingQuotas, RouteIdentifier, ServiceDescriptorIdentifier}
import otoroshi.next.plugins.api.{NgPluginConfig, _}
import otoroshi.security.IdGenerator
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

case class NgCertificateAsApikeyConfig(
  readOnly: Boolean = false,
  allowClientIdOnly: Boolean = false,
  throttlingQuota: Long = 100,
  dailyQuota: Long = RemainingQuotas.MaxValue,
  monthlyQuota: Long = RemainingQuotas.MaxValue,
  constrainedServicesOnly: Boolean = false,
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "read_only" -> readOnly,
    "allow_client_id_only" -> allowClientIdOnly,
    "throttling_quota" -> throttlingQuota,
    "daily_quota" -> dailyQuota,
    "monthly_quota" -> monthlyQuota,
    "constrained_services_only" -> constrainedServicesOnly,
    "tags" -> tags,
    "metadata" -> metadata,
  )
}

object NgCertificateAsApikeyConfig {
  val format = new Format[NgCertificateAsApikeyConfig] {
    override def writes(o: NgCertificateAsApikeyConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgCertificateAsApikeyConfig] = Try {
      NgCertificateAsApikeyConfig(
        readOnly = json.select("read_only").asOpt[Boolean].getOrElse(false),
        allowClientIdOnly = json.select("allow_client_id_only").asOpt[Boolean].getOrElse(false),
        throttlingQuota = json.select("throttling_quota").asOpt[Long].getOrElse(100L),
        dailyQuota = json.select("daily_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
        monthlyQuota = json.select("monthly_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
        constrainedServicesOnly = json.select("constrained_services_only").asOpt[Boolean].getOrElse(false),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgCertificateAsApikey extends NgPreRouting {

  override def name: String = "Client certificate as apikey"
  override def description: Option[String] = "This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCertificateAsApikeyConfig().some

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.request.clientCertificateChain.flatMap(_.headOption) match {
      case None => Done.rightf
      case Some(cert) => {
        val config = ctx.cachedConfig(internalName)(NgCertificateAsApikeyConfig.format).getOrElse(NgCertificateAsApikeyConfig())
        val serialNumber = cert.getSerialNumber.toString
        val subjectDN = DN(cert.getSubjectDN.getName).stringify
        val clientId = Base64.encodeBase64String((subjectDN + "-" + serialNumber).getBytes)
        env.datastores.apiKeyDataStore
          .findById(clientId)
          .flatMap {
            case Some(apikey) => apikey.vfuture
            case None => {
              val apikey = ApiKey(
                clientId = clientId,
                clientSecret = IdGenerator.token(128),
                clientName = s"$subjectDN ($serialNumber)",
                authorizedEntities = Seq(RouteIdentifier(ctx.route.id)),
                validUntil = Some(new DateTime(cert.getNotAfter)),
                readOnly = config.readOnly,
                allowClientIdOnly = config.allowClientIdOnly,
                throttlingQuota = config.throttlingQuota,
                dailyQuota = config.dailyQuota,
                monthlyQuota = config.monthlyQuota,
                constrainedServicesOnly = config.constrainedServicesOnly,
                tags = config.tags,
                metadata = config.metadata,
              )
              if (env.clusterConfig.mode.isWorker) {
                ClusterAgent.clusterSaveApikey(env, apikey)(ec, env.otoroshiMaterializer)
              }
              apikey.save().map(_ => apikey)
            }
          }
          .map { apikey =>
            ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
            Done.right
          }
      }
    }
  }
}