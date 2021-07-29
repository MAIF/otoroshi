package otoroshi.models

import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.events.Exporters._
import otoroshi.events._
import org.joda.time.DateTime
import otoroshi.events.KafkaConfig
import otoroshi.plugins.geoloc.{IpStackGeolocationHelper, MaxMindGeolocationHelper}
import otoroshi.plugins.useragent.UserAgentHelper
import otoroshi.script.Script
import otoroshi.script.plugins.Plugins
import otoroshi.storage.BasicStore
import otoroshi.tcp.TcpService
import otoroshi.utils.RegexPool
import otoroshi.utils.letsencrypt.LetsEncryptSettings
import otoroshi.utils.clevercloud.CleverCloudClient
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientCertificateValidator}
import otoroshi.utils.clevercloud.CleverCloudClient.{CleverSettings, UserTokens}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.letsencrypt.LetsEncryptSettings
import otoroshi.utils.mailer.MailerSettings
import otoroshi.utils._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ElasticAnalyticsConfig(
    clusterUri: String,
    index: Option[String] = None,
    `type`: Option[String] = None,
    user: Option[String] = None,
    password: Option[String] = None,
    headers: Map[String, String] = Map.empty[String, String],
    mtlsConfig: MtlsConfig = MtlsConfig.default
) extends Exporter {
  def toJson: JsValue = ElasticAnalyticsConfig.format.writes(this)
}

object ElasticAnalyticsConfig {
  val format = new Format[ElasticAnalyticsConfig] {
    override def writes(o: ElasticAnalyticsConfig) =
      Json.obj(
        "clusterUri" -> o.clusterUri,
        "index"      -> o.index.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "type"       -> o.`type`.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "user"       -> o.user.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "password"   -> o.password.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "headers"    -> JsObject(o.headers.mapValues(JsString.apply)),
        "mtlsConfig" -> o.mtlsConfig.json
      )
    override def reads(json: JsValue)              =
      Try {
        JsSuccess(
          ElasticAnalyticsConfig(
            clusterUri = (json \ "clusterUri").asOpt[String].map(_.trim).filter(_.nonEmpty).get,
            index = (json \ "index").asOpt[String].map(_.trim).filter(_.nonEmpty),
            `type` = (json \ "type").asOpt[String].map(_.trim).filter(_.nonEmpty),
            user = (json \ "user").asOpt[String].map(_.trim).filter(_.nonEmpty),
            password = (json \ "password").asOpt[String].map(_.trim).filter(_.nonEmpty),
            headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
            mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class Webhook(
    url: String,
    headers: Map[String, String] = Map.empty[String, String],
    mtlsConfig: MtlsConfig = MtlsConfig.default
) extends Exporter {
  def toJson: JsValue = Webhook.format.writes(this)
}

object Webhook {
  implicit val format = new Format[Webhook] {
    override def reads(json: JsValue): JsResult[Webhook] =
      Try {
        Webhook(
          url = (json \ "url").as[String],
          headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
          mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue])
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }

    override def writes(o: Webhook): JsValue =
      Json.obj(
        "url"        -> o.url,
        "headers"    -> o.headers,
        "mtlsConfig" -> o.mtlsConfig.json
      )
  }
}

case class CleverCloudSettings(
    consumerKey: String,
    consumerSecret: String,
    token: String,
    secret: String,
    orgaId: String
)

object CleverCloudSettings {
  implicit val format = Json.format[CleverCloudSettings]
}

case class Proxies(
    alertEmails: Option[WSProxyServer] = None,
    eventsWebhooks: Option[WSProxyServer] = None,
    clevercloud: Option[WSProxyServer] = None,
    services: Option[WSProxyServer] = None,
    auth: Option[WSProxyServer] = None,
    authority: Option[WSProxyServer] = None,
    jwk: Option[WSProxyServer] = None,
    elastic: Option[WSProxyServer] = None
) {
  def toJson: JsValue = Proxies.format.writes(this)
}

object Proxies {

  val format = new Format[Proxies] {
    override def writes(o: Proxies)   =
      Json.obj(
        "alertEmails"    -> WSProxyServerJson.maybeProxyToJson(o.alertEmails),
        "eventsWebhooks" -> WSProxyServerJson.maybeProxyToJson(o.eventsWebhooks),
        "clevercloud"    -> WSProxyServerJson.maybeProxyToJson(o.clevercloud),
        "services"       -> WSProxyServerJson.maybeProxyToJson(o.services),
        "auth"           -> WSProxyServerJson.maybeProxyToJson(o.auth),
        "authority"      -> WSProxyServerJson.maybeProxyToJson(o.authority),
        "jwk"            -> WSProxyServerJson.maybeProxyToJson(o.jwk),
        "elastic"        -> WSProxyServerJson.maybeProxyToJson(o.elastic)
      )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          Proxies(
            alertEmails = (json \ "alertEmails").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            eventsWebhooks = (json \ "eventsWebhooks").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            clevercloud = (json \ "clevercloud").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            services = (json \ "services").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            auth = (json \ "auth").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            authority = (json \ "authority").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            jwk = (json \ "jwk").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            elastic = (json \ "elastic").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p))
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class GlobalScripts(
    enabled: Boolean = false,
    transformersRefs: Seq[String] = Seq.empty,
    transformersConfig: JsValue = Json.obj(),
    validatorRefs: Seq[String] = Seq.empty,
    validatorConfig: JsValue = Json.obj(),
    preRouteRefs: Seq[String] = Seq.empty,
    preRouteConfig: JsValue = Json.obj(),
    sinkRefs: Seq[String] = Seq.empty,
    sinkConfig: JsValue = Json.obj(),
    jobRefs: Seq[String] = Seq.empty,
    jobConfig: JsValue = Json.obj()
) {
  def json: JsValue = GlobalScripts.format.writes(this)
}

object GlobalScripts {
  val format = new Format[GlobalScripts] {
    override def writes(o: GlobalScripts): JsValue             =
      Json.obj(
        "enabled"            -> o.enabled,
        "transformersRefs"   -> JsArray(o.transformersRefs.map(JsString.apply)),
        "transformersConfig" -> o.transformersConfig,
        "validatorRefs"      -> JsArray(o.validatorRefs.map(JsString.apply)),
        "validatorConfig"    -> o.validatorConfig,
        "preRouteRefs"       -> JsArray(o.preRouteRefs.map(JsString.apply)),
        "preRouteConfig"     -> o.preRouteConfig,
        "sinkRefs"           -> JsArray(o.sinkRefs.map(JsString.apply)),
        "sinkConfig"         -> o.sinkConfig,
        "jobRefs"            -> JsArray(o.jobRefs.map(JsString.apply)),
        "jobConfig"          -> o.jobConfig
      )
    override def reads(json: JsValue): JsResult[GlobalScripts] =
      Try {
        JsSuccess(
          GlobalScripts(
            transformersRefs = (json \ "transformersRefs").asOpt[Seq[String]].getOrElse(Seq.empty),
            validatorRefs = (json \ "validatorRefs").asOpt[Seq[String]].getOrElse(Seq.empty),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            transformersConfig = (json \ "transformersConfig").asOpt[JsValue].getOrElse(Json.obj()),
            validatorConfig = (json \ "validatorConfig").asOpt[JsValue].getOrElse(Json.obj()),
            preRouteConfig = (json \ "preRouteConfig").asOpt[JsValue].getOrElse(Json.obj()),
            preRouteRefs = (json \ "preRouteRefs").asOpt[Seq[String]].getOrElse(Seq.empty),
            sinkConfig = (json \ "sinkConfig").asOpt[JsValue].getOrElse(Json.obj()),
            sinkRefs = (json \ "sinkRefs").asOpt[Seq[String]].getOrElse(Seq.empty),
            jobConfig = (json \ "jobConfig").asOpt[JsValue].getOrElse(Json.obj()),
            jobRefs = (json \ "jobRefs").asOpt[Seq[String]].getOrElse(Seq.empty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

object GeolocationSettings {
  val format = new Format[GeolocationSettings] {
    override def writes(o: GeolocationSettings): JsValue             = o.json
    override def reads(json: JsValue): JsResult[GeolocationSettings] =
      Try {
        JsSuccess(
          (json \ "type").as[String] match {
            case "none"    => NoneGeolocationSettings
            case "maxmind" =>
              MaxmindGeolocationSettings(
                enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
                path = (json \ "path").asOpt[String].filter(_.trim.nonEmpty).get
              )
            case "ipstack" =>
              IpStackGeolocationSettings(
                enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
                apikey = (json \ "apikey").asOpt[String].filter(_.trim.nonEmpty).get,
                timeout = (json \ "timeout").asOpt[Long].getOrElse(2000L)
              )
            case _         => NoneGeolocationSettings
          }
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

sealed trait GeolocationSettings {
  def enabled: Boolean
  def find(ip: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]]
  def json: JsValue
}

case object NoneGeolocationSettings extends GeolocationSettings {
  def enabled: Boolean                                                                   = false
  def find(ip: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = FastFuture.successful(None)
  def json: JsValue                                                                      = Json.obj("type" -> "none")
}

case class MaxmindGeolocationSettings(enabled: Boolean, path: String) extends GeolocationSettings {
  def json: JsValue = Json.obj("type" -> "maxmind", "path" -> path, "enabled" -> enabled)
  def find(ip: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    enabled match {
      case false => FastFuture.successful(None)
      case true  => MaxMindGeolocationHelper.find(ip, path)
    }
  }
}

case class IpStackGeolocationSettings(enabled: Boolean, apikey: String, timeout: Long) extends GeolocationSettings {
  def json: JsValue = Json.obj("type" -> "ipstack", "apikey" -> apikey, "timeout" -> timeout, "enabled" -> enabled)
  def find(ip: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    enabled match {
      case false => FastFuture.successful(None)
      case true  => IpStackGeolocationHelper.find(ip, apikey, timeout)
    }
  }
}

object UserAgentSettings {
  val format = new Format[UserAgentSettings] {
    override def writes(o: UserAgentSettings): JsValue             = o.json
    override def reads(json: JsValue): JsResult[UserAgentSettings] =
      Try {
        JsSuccess(
          UserAgentSettings(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class UserAgentSettings(enabled: Boolean) {
  def json: JsValue = Json.obj("enabled" -> enabled)
  def find(ua: String)(implicit env: Env): Option[JsValue] = {
    enabled match {
      case false => None
      case true  => UserAgentHelper.userAgentDetails(ua)
    }
  }
}

case class AutoCert(
    enabled: Boolean = false,
    caRef: Option[String] = None,
    allowed: Seq[String] = Seq.empty,
    notAllowed: Seq[String] = Seq.empty,
    replyNicely: Boolean = false
) {
  def json: JsValue = AutoCert.format.writes(this)
  def matches(domain: String): Boolean = {
    !notAllowed.exists(p => otoroshi.utils.RegexPool.apply(p).matches(domain)) &&
    allowed.exists(p => otoroshi.utils.RegexPool.apply(p).matches(domain))
  }
}

object AutoCert {
  val format = new Format[AutoCert] {
    override def writes(o: AutoCert): JsValue =
      Json.obj(
        "enabled"     -> o.enabled,
        "replyNicely" -> o.replyNicely,
        "caRef"       -> o.caRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "allowed"     -> JsArray(o.allowed.map(JsString.apply)),
        "notAllowed"  -> JsArray(o.notAllowed.map(JsString.apply))
      )

    override def reads(json: JsValue): JsResult[AutoCert] =
      Try {
        AutoCert(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          replyNicely = (json \ "replyNicely").asOpt[Boolean].getOrElse(false),
          caRef = (json \ "caRef").asOpt[String],
          allowed = (json \ "allowed").asOpt[Seq[String]].getOrElse(Seq("*")),
          notAllowed = (json \ "notAllowed").asOpt[Seq[String]].getOrElse(Seq.empty)
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ac) => JsSuccess(ac)
      }
  }
}

case class TlsSettings(
  defaultDomain: Option[String] = None,
  randomIfNotFound: Boolean = false,
  includeJdkCaServer: Boolean = true,
  includeJdkCaClient: Boolean = true,
  trustedCAsServer: Seq[String] = Seq.empty
) {
  def json: JsValue = TlsSettings.format.writes(this)
}
object TlsSettings {
  val format = new Format[TlsSettings] {
    override def writes(o: TlsSettings): JsValue =
      Json.obj(
        "defaultDomain"      -> o.defaultDomain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "randomIfNotFound"   -> o.randomIfNotFound,
        "includeJdkCaServer" -> o.includeJdkCaServer,
        "includeJdkCaClient" -> o.includeJdkCaClient,
        "trustedCAsServer"   -> JsArray(o.trustedCAsServer.map(JsString.apply))
      )

    override def reads(json: JsValue): JsResult[TlsSettings] =
      Try {
        TlsSettings(
          defaultDomain = (json \ "defaultDomain").asOpt[String].map(_.trim).filter(_.nonEmpty),
          randomIfNotFound = (json \ "randomIfNotFound").asOpt[Boolean].getOrElse(false),
          includeJdkCaServer = (json \ "includeJdkCaServer").asOpt[Boolean].getOrElse(true),
          includeJdkCaClient = (json \ "includeJdkCaClient").asOpt[Boolean].getOrElse(true),
          trustedCAsServer = (json \ "trustedCAsServer").asOpt[Seq[String]].getOrElse(Seq.empty)
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ac) => JsSuccess(ac)
      }
  }
}

case class GlobalConfig(
    letsEncryptSettings: LetsEncryptSettings = LetsEncryptSettings(),
    lines: Seq[String] = Seq("prod"),
    enableEmbeddedMetrics: Boolean = true,
    streamEntityOnly: Boolean = true,
    autoLinkToDefaultGroup: Boolean = true,
    limitConcurrentRequests: Boolean = false, // TODO : true by default
    maxConcurrentRequests: Long = 1000,
    maxHttp10ResponseSize: Long = 4 * (1024 * 1024),
    useCircuitBreakers: Boolean = true,
    apiReadOnly: Boolean = false,
    u2fLoginOnly: Boolean = false,
    maintenanceMode: Boolean = false,
    ipFiltering: IpFiltering = IpFiltering(),
    throttlingQuota: Long = BaseQuotas.MaxValue,
    perIpThrottlingQuota: Long = BaseQuotas.MaxValue,
    elasticReadsConfig: Option[ElasticAnalyticsConfig] = None,
    elasticWritesConfigs: Seq[ElasticAnalyticsConfig] = Seq.empty[ElasticAnalyticsConfig],
    analyticsWebhooks: Seq[Webhook] = Seq.empty[Webhook],
    logAnalyticsOnServer: Boolean = false,
    useAkkaHttpClient: Boolean = false,
    // TODO: logBodies: Boolean,
    alertsWebhooks: Seq[Webhook] = Seq.empty[Webhook],
    alertsEmails: Seq[String] = Seq.empty[String],
    endlessIpAddresses: Seq[String] = Seq.empty[String],
    kafkaConfig: Option[KafkaConfig] = None,
    backOfficeAuthRef: Option[String] = None,
    cleverSettings: Option[CleverCloudSettings] = None,
    mailerSettings: Option[MailerSettings] = None,
    statsdConfig: Option[StatsdConfig] = None,
    maxWebhookSize: Int = 100,
    middleFingers: Boolean = false,
    maxLogsSize: Int = 10000,
    otoroshiId: String = IdGenerator.uuid,
    snowMonkeyConfig: SnowMonkeyConfig = SnowMonkeyConfig(),
    proxies: Proxies = Proxies(),
    scripts: GlobalScripts = GlobalScripts(),
    geolocationSettings: GeolocationSettings = NoneGeolocationSettings,
    userAgentSettings: UserAgentSettings = UserAgentSettings(false),
    autoCert: AutoCert = AutoCert(),
    tlsSettings: TlsSettings = TlsSettings(),
    plugins: Plugins = Plugins(),
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty
) extends Entity {

  def internalId: String               = "global"
  def json: play.api.libs.json.JsValue = toJson
  def theMetadata: Map[String, String] = metadata
  def theTags: Seq[String]             = tags
  def theDescription: String           = "The global config for otoroshi"
  def theName: String                  = "otoroshi-global-config"

  def save()(implicit ec: ExecutionContext, env: Env)                                   = env.datastores.globalConfigDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env)                                 = env.datastores.globalConfigDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env)                                 = env.datastores.globalConfigDataStore.exists(this)
  def toJson                                                                            = GlobalConfig.toJson(this)
  def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.globalConfigDataStore.withinThrottlingQuota()
  def cleverClient(implicit env: Env): Option[CleverCloudClient]                        =
    cleverSettings match {
      case None           => None
      case Some(settings) => {
        val cleverSetting = CleverSettings(
          apiConsumerKey = settings.consumerKey,
          apiConsumerSecret = settings.consumerSecret,
          apiAuthToken = UserTokens(
            token = settings.token,
            secret = settings.secret
          )
        )
        Some(CleverCloudClient(env, this, cleverSetting, settings.orgaId))
      }
    }
  def matchesEndlessIpAddresses(ipAddress: String): Boolean = {
    if (endlessIpAddresses.nonEmpty) {
      endlessIpAddresses.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(ipAddress)
        } else {
          RegexPool(ip).matches(ipAddress)
        }
      }
    } else {
      false
    }
  }
}

object GlobalConfig {

  lazy val logger = Logger("otoroshi-global-config")

  val _fmt: Format[GlobalConfig] = new Format[GlobalConfig] {
    override def writes(o: GlobalConfig): JsValue = {
      val mailerSettings: JsValue = o.mailerSettings match {
        case None         => JsNull
        case Some(config) => config.json
      }
      val cleverSettings: JsValue = o.cleverSettings match {
        case None         => JsNull
        case Some(config) =>
          Json.obj(
            "consumerKey"    -> config.consumerKey,
            "consumerSecret" -> config.consumerSecret,
            "token"          -> config.token,
            "secret"         -> config.secret,
            "orgaId"         -> config.orgaId
          )
      }
      val kafkaConfig: JsValue    = o.kafkaConfig match {
        case None         => JsNull
        case Some(config) => config.json
        // Json.obj(
        //   "servers"        -> JsArray(config.servers.map(JsString.apply)),
        //   "keyPass"        -> config.keyPass.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        //   "keystore"       -> config.keystore.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        //   "truststore"     -> config.truststore.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        //   "alertsTopic"    -> config.alertsTopic,
        //   "analyticsTopic" -> config.analyticsTopic,
        //   "auditTopic"     -> config.auditTopic,
        //   "sendEvents"     -> config.sendEvents
        // )
      }
      val statsdConfig: JsValue   = o.statsdConfig match {
        case None         => JsNull
        case Some(config) =>
          Json.obj(
            "host"    -> config.host,
            "port"    -> config.port,
            "datadog" -> config.datadog
          )
      }
      Json.obj(
        "tags" -> JsArray(o.tags.map(JsString.apply)),
        "letsEncryptSettings"     -> o.letsEncryptSettings.json,
        "lines"                   -> JsArray(o.lines.map(JsString.apply)),
        "maintenanceMode"         -> o.maintenanceMode,
        "enableEmbeddedMetrics"   -> o.enableEmbeddedMetrics,
        "streamEntityOnly"        -> o.streamEntityOnly,
        "autoLinkToDefaultGroup"  -> o.autoLinkToDefaultGroup,
        "limitConcurrentRequests" -> o.limitConcurrentRequests,
        "maxConcurrentRequests"   -> o.maxConcurrentRequests,
        "maxHttp10ResponseSize"   -> o.maxHttp10ResponseSize,
        "useCircuitBreakers"      -> o.useCircuitBreakers,
        "apiReadOnly"             -> o.apiReadOnly,
        "u2fLoginOnly"            -> o.u2fLoginOnly,
        "ipFiltering"             -> o.ipFiltering.toJson,
        "throttlingQuota"         -> o.throttlingQuota,
        "perIpThrottlingQuota"    -> o.perIpThrottlingQuota,
        "analyticsWebhooks"       -> JsArray(o.analyticsWebhooks.map(_.toJson)),
        "alertsWebhooks"          -> JsArray(o.alertsWebhooks.map(_.toJson)),
        "elasticWritesConfigs"    -> JsArray(o.elasticWritesConfigs.map(_.toJson)),
        "elasticReadsConfig"      -> o.elasticReadsConfig.map(_.toJson).getOrElse(JsNull).as[JsValue],
        "alertsEmails"            -> JsArray(o.alertsEmails.map(JsString.apply)),
        "logAnalyticsOnServer"    -> o.logAnalyticsOnServer,
        "useAkkaHttpClient"       -> o.useAkkaHttpClient,
        "endlessIpAddresses"      -> JsArray(o.endlessIpAddresses.map(JsString.apply)),
        "statsdConfig"            -> statsdConfig,
        "kafkaConfig"             -> kafkaConfig,
        "backOfficeAuthRef"       -> o.backOfficeAuthRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "mailerSettings"          -> mailerSettings,
        "cleverSettings"          -> cleverSettings,
        "maxWebhookSize"          -> o.maxWebhookSize,
        "middleFingers"           -> o.middleFingers,
        "maxLogsSize"             -> o.maxLogsSize,
        "otoroshiId"              -> o.otoroshiId,
        "snowMonkeyConfig"        -> o.snowMonkeyConfig.asJson,
        "scripts"                 -> o.scripts.json,
        "geolocationSettings"     -> o.geolocationSettings.json,
        "userAgentSettings"       -> o.userAgentSettings.json,
        "autoCert"                -> o.autoCert.json,
        "tlsSettings"             -> o.tlsSettings.json,
        "plugins"                 -> o.plugins.json,
        "metadata"                -> o.metadata
      )
    }
    override def reads(json: JsValue): JsResult[GlobalConfig] =
      Try {
        GlobalConfig(
          lines = (json \ "lines").asOpt[Seq[String]].getOrElse(Seq("prod")),
          enableEmbeddedMetrics = (json \ "enableEmbeddedMetrics").asOpt[Boolean].getOrElse(true),
          streamEntityOnly = (json \ "streamEntityOnly").asOpt[Boolean].getOrElse(true),
          maintenanceMode = (json \ "maintenanceMode").asOpt[Boolean].getOrElse(false),
          autoLinkToDefaultGroup = (json \ "autoLinkToDefaultGroup").asOpt[Boolean].getOrElse(true),
          limitConcurrentRequests = (json \ "limitConcurrentRequests")
            .asOpt[Boolean]
            .getOrElse(false), // TODO : true by default after prod monitoring
          maxConcurrentRequests = (json \ "maxConcurrentRequests").asOpt[Long].getOrElse(1000),
          maxHttp10ResponseSize = (json \ "maxHttp10ResponseSize").asOpt[Long].getOrElse(4 * (1024 * 1024)),
          useCircuitBreakers = (json \ "useCircuitBreakers").asOpt[Boolean].getOrElse(true),
          otoroshiId = (json \ "otoroshiId").asOpt[String].getOrElse(IdGenerator.uuid),
          apiReadOnly = (json \ "apiReadOnly").asOpt[Boolean].getOrElse(false),
          u2fLoginOnly = (json \ "u2fLoginOnly").asOpt[Boolean].getOrElse(false),
          logAnalyticsOnServer = (json \ "logAnalyticsOnServer").asOpt[Boolean].getOrElse(false),
          useAkkaHttpClient = (json \ "useAkkaHttpClient").asOpt[Boolean].getOrElse(false),
          ipFiltering = (json \ "ipFiltering").asOpt[IpFiltering](IpFiltering.format).getOrElse(IpFiltering()),
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(BaseQuotas.MaxValue),
          perIpThrottlingQuota = (json \ "perIpThrottlingQuota").asOpt[Long].getOrElse(BaseQuotas.MaxValue),
          elasticReadsConfig = (json \ "elasticReadsConfig").asOpt[JsObject].flatMap { config =>
            ElasticAnalyticsConfig.format.reads(config).asOpt
          /*(
              (config \ "clusterUri").asOpt[String],
              (config \ "index").asOpt[String],
              (config \ "type").asOpt[String],
              (config \ "user").asOpt[String],
              (config \ "password").asOpt[String]
            ) match {
              case (Some(clusterUri), index, typ, user, password) if clusterUri.nonEmpty =>
                Some(ElasticAnalyticsConfig(clusterUri, index, typ, user, password))
              case e => None
            }*/
          },
          analyticsWebhooks =
            (json \ "analyticsWebhooks").asOpt[Seq[Webhook]](Reads.seq(Webhook.format)).getOrElse(Seq.empty[Webhook]),
          alertsWebhooks =
            (json \ "alertsWebhooks").asOpt[Seq[Webhook]](Reads.seq(Webhook.format)).getOrElse(Seq.empty[Webhook]),
          elasticWritesConfigs = (json \ "elasticWritesConfigs")
            .asOpt[Seq[ElasticAnalyticsConfig]](Reads.seq(ElasticAnalyticsConfig.format))
            .getOrElse(Seq.empty[ElasticAnalyticsConfig]),
          alertsEmails = (json \ "alertsEmails").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          endlessIpAddresses = (json \ "endlessIpAddresses").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          maxWebhookSize = (json \ "maxWebhookSize").asOpt[Int].getOrElse(100),
          middleFingers = (json \ "middleFingers").asOpt[Boolean].getOrElse(false),
          maxLogsSize = (json \ "maxLogsSize").asOpt[Int].getOrElse(10000),
          statsdConfig = (json \ "statsdConfig").asOpt[JsValue].flatMap { config =>
            (
              (config \ "host").asOpt[String].filter(_.nonEmpty),
              (config \ "port").asOpt[Int],
              (config \ "datadog").asOpt[Boolean].getOrElse(false)
            ) match {
              case (Some(host), Some(port), datadog) =>
                Some(StatsdConfig(datadog, host, port))
              case e                                 => None
            }
          },
          kafkaConfig = (json \ "kafkaConfig").asOpt[JsValue].flatMap { config =>
            KafkaConfig.format.reads(config).asOpt
          // (
          //   (config \ "servers").asOpt[Seq[String]].filter(_.nonEmpty),
          //   (config \ "keyPass").asOpt[String],
          //   (config \ "keystore").asOpt[String],
          //   (config \ "truststore").asOpt[String],
          //   (config \ "sendEvents").asOpt[Boolean].getOrElse(true),
          //   (config \ "alertsTopic").asOpt[String].filter(_.nonEmpty),
          //   (config \ "analyticsTopic").asOpt[String].filter(_.nonEmpty),
          //   (config \ "auditTopic").asOpt[String].filter(_.nonEmpty)
          // ) match {
          //   case (Some(servers),
          //         keyPass,
          //         keystore,
          //         truststore,
          //         sendEvents,
          //         Some(alertsTopic),
          //         Some(analyticsTopic),
          //         Some(auditTopic)) =>
          //     Some(KafkaConfig(servers, keyPass, keystore, truststore, sendEvents, alertsTopic, analyticsTopic, auditTopic))
          //   case e => None
          // }
          },
          backOfficeAuthRef = (json \ "backOfficeAuthRef").asOpt[String],
          mailerSettings = (json \ "mailerSettings").asOpt[JsValue].flatMap { config =>
            MailerSettings.format.reads(config).asOpt
          },
          cleverSettings = (json \ "cleverSettings").asOpt[JsValue].flatMap { config =>
            (
              (config \ "consumerKey").asOpt[String].filter(_.nonEmpty),
              (config \ "consumerSecret").asOpt[String].filter(_.nonEmpty),
              (config \ "token").asOpt[String].filter(_.nonEmpty),
              (config \ "secret").asOpt[String].filter(_.nonEmpty),
              (config \ "orgaId").asOpt[String].filter(_.nonEmpty)
            ) match {
              case (Some(consumerKey), Some(consumerSecret), Some(token), Some(secret), Some(orgaId)) =>
                Some(CleverCloudSettings(consumerKey, consumerSecret, token, secret, orgaId))
              case _                                                                                  => None
            }
          },
          snowMonkeyConfig = (json \ "snowMonkeyConfig").asOpt(SnowMonkeyConfig._fmt).getOrElse(SnowMonkeyConfig()),
          scripts = GlobalScripts.format
            .reads((json \ "scripts").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(GlobalScripts()),
          geolocationSettings = GeolocationSettings.format
            .reads((json \ "geolocationSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(NoneGeolocationSettings),
          userAgentSettings = UserAgentSettings.format
            .reads((json \ "userAgentSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(UserAgentSettings(false)),
          letsEncryptSettings = LetsEncryptSettings.format
            .reads((json \ "letsEncryptSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(LetsEncryptSettings()),
          autoCert = AutoCert.format
            .reads((json \ "autoCert").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(AutoCert()),
          tlsSettings = TlsSettings.format
            .reads((json \ "tlsSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(TlsSettings()),
          plugins = Plugins.format
            .reads((json \ "plugins").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(Plugins()),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading GlobalConfig", t)
        JsError(t.getMessage)
      } get
  }

  def toJson(value: GlobalConfig): JsValue                 = _fmt.writes(value)
  def fromJsons(value: JsValue): GlobalConfig              =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[GlobalConfig] = _fmt.reads(value)
}

trait GlobalConfigDataStore extends BasicStore[GlobalConfig] {
  def incrementCallsForIpAddressWithTTL(ip: String, ttl: Int = 10)(implicit ec: ExecutionContext): Future[Long]
  def quotaForIpAddress(ip: String)(implicit ec: ExecutionContext): Future[Option[Long]]
  def isOtoroshiEmpty()(implicit ec: ExecutionContext): Future[Boolean]
  def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def updateQuotas(config: otoroshi.models.GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def singleton()(implicit ec: ExecutionContext, env: Env): Future[GlobalConfig]
  def latest()(implicit ec: ExecutionContext, env: Env): GlobalConfig
  def latestSafe: Option[GlobalConfig]
  def fullImport(export: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def fullExport()(implicit ec: ExecutionContext, env: Env): Future[JsValue]
  def allEnv()(implicit ec: ExecutionContext, env: Env): Future[Set[String]]
  def quotasValidationFor(from: String)(implicit ec: ExecutionContext, env: Env): Future[(Boolean, Long, Option[Long])]
  def migrate()(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def template: GlobalConfig = GlobalConfig()
}

case class OtoroshiExport(
    config: GlobalConfig,
    descs: Seq[ServiceDescriptor] = Seq.empty,
    apikeys: Seq[ApiKey] = Seq.empty,
    groups: Seq[ServiceGroup] = Seq.empty,
    tmplts: Seq[ErrorTemplate] = Seq.empty,
    calls: Long = 0,
    dataIn: Long = 0,
    dataOut: Long = 0,
    admins: Seq[WebAuthnOtoroshiAdmin] = Seq.empty,
    simpleAdmins: Seq[SimpleOtoroshiAdmin] = Seq.empty,
    jwtVerifiers: Seq[GlobalJwtVerifier] = Seq.empty,
    authConfigs: Seq[AuthModuleConfig] = Seq.empty,
    certificates: Seq[Cert] = Seq.empty,
    clientValidators: Seq[ClientCertificateValidator] = Seq.empty,
    scripts: Seq[Script] = Seq.empty,
    tcpServices: Seq[TcpService] = Seq.empty,
    dataExporters: Seq[DataExporterConfig] = Seq.empty,
    tenants: Seq[Tenant] = Seq.empty,
    teams: Seq[Team] = Seq.empty
) {

  import otoroshi.utils.syntax.implicits._
  import otoroshi.utils.json.JsonImplicits._

  private def customizeAndMergeArray[A](
      entities: Seq[A],
      arr: JsArray,
      fmt: Format[A],
      extractIdFromJs: JsValue => String,
      extractIdFromEntity: A => String
  ): Seq[A] = {
    val arrWithId              = arr.value.map { item =>
      val id = extractIdFromJs(item)
      (id, item)
    }
    val (existing, additional) = arrWithId.partition { case (id, item) =>
      entities.exists(v => extractIdFromEntity(v) == id)
    }
    val ex                     = existing
      .map { case (id, item) =>
        val entity    = entities.find(v => extractIdFromEntity(v) == id).get
        val newEntity = fmt.writes(entity).asObject.deepMerge(item.asObject)
        fmt.reads(newEntity)
      }
      .collect { case JsSuccess(s, _) =>
        s
      }
    val add                    = additional.map(t => fmt.reads(t._2)).collect { case JsSuccess(s, _) =>
      s
    }
    val eid                    = existing.map(_._1)
    val already                = entities.filterNot(e => eid.contains(extractIdFromEntity(e)))
    already ++ ex ++ add
  }

  def customizeWith(customization: JsObject): OtoroshiExport = {
    val cconfig     = customization.select("config").asOpt[JsObject].getOrElse(Json.obj())
    val finalConfig = GlobalConfig.fromJsons(config.toJson.asObject.deepMerge(cconfig))
    copy(
      config = finalConfig,
      descs = customizeAndMergeArray[ServiceDescriptor](
        descs,
        customization.select("descs").asOpt[JsArray].getOrElse(Json.arr()),
        ServiceDescriptor._fmt,
        _.select("id").asString,
        _.id
      ),
      apikeys = customizeAndMergeArray[ApiKey](
        apikeys,
        customization.select("apikeys").asOpt[JsArray].getOrElse(Json.arr()),
        ApiKey._fmt,
        _.select("clientId").asString,
        _.clientId
      ),
      groups = customizeAndMergeArray[ServiceGroup](
        groups,
        customization.select("groups").asOpt[JsArray].getOrElse(Json.arr()),
        ServiceGroup._fmt,
        _.select("id").asString,
        _.id
      ),
      tmplts = customizeAndMergeArray[ErrorTemplate](
        tmplts,
        customization.select("tmplts").asOpt[JsArray].getOrElse(Json.arr()),
        ErrorTemplate.format,
        _.select("id").asString,
        _.serviceId
      ),
      jwtVerifiers = customizeAndMergeArray[GlobalJwtVerifier](
        jwtVerifiers,
        customization.select("jwtVerifiers").asOpt[JsArray].getOrElse(Json.arr()),
        GlobalJwtVerifier._fmt,
        _.select("id").asString,
        _.id
      ),
      authConfigs = customizeAndMergeArray[AuthModuleConfig](
        authConfigs,
        customization.select("authConfigs").asOpt[JsArray].getOrElse(Json.arr()),
        AuthModuleConfig._fmt,
        _.select("id").asString,
        _.id
      ),
      certificates = customizeAndMergeArray[Cert](
        certificates,
        customization.select("certificates").asOpt[JsArray].getOrElse(Json.arr()),
        Cert._fmt,
        _.select("id").asString,
        _.id
      ),
      scripts = customizeAndMergeArray[Script](
        scripts,
        customization.select("scripts").asOpt[JsArray].getOrElse(Json.arr()),
        Script._fmt,
        _.select("id").asString,
        _.id
      ),
      tcpServices = customizeAndMergeArray[TcpService](
        tcpServices,
        customization.select("tcpServices").asOpt[JsArray].getOrElse(Json.arr()),
        TcpService.fmt,
        _.select("id").asString,
        _.id
      ),
      dataExporters = customizeAndMergeArray[DataExporterConfig](
        dataExporters,
        customization.select("dataExporters").asOpt[JsArray].getOrElse(Json.arr()),
        DataExporterConfig.format,
        _.select("id").asString,
        _.id
      ),
      tenants = customizeAndMergeArray[Tenant](
        tenants,
        customization.select("tenants").asOpt[JsArray].getOrElse(Json.arr()),
        Tenant.format,
        _.select("id").asString,
        _.id.value
      ),
      teams = customizeAndMergeArray[Team](
        teams,
        customization.select("teams").asOpt[JsArray].getOrElse(Json.arr()),
        Team.format,
        _.select("id").asString,
        _.id.value
      ),
      admins = customizeAndMergeArray[WebAuthnOtoroshiAdmin](
        admins,
        customization.select("admins").asOpt[JsArray].getOrElse(Json.arr()),
        WebAuthnOtoroshiAdmin.fmt,
        _.select("username").asString,
        _.username
      ),
      simpleAdmins = customizeAndMergeArray[SimpleOtoroshiAdmin](
        simpleAdmins,
        customization.select("simpleAdmins").asOpt[JsArray].getOrElse(Json.arr()),
        SimpleOtoroshiAdmin.fmt,
        _.select("username").asString,
        _.username
      )
    )
  }

  def json: JsObject = {
    Json.obj(
      "label"              -> "Otoroshi export",
      "dateRaw"            -> DateTime.now(),
      "date"               -> DateTime.now().toString("yyyy-MM-dd hh:mm:ss"),
      "stats"              -> Json.obj(
        "calls"   -> calls,
        "dataIn"  -> dataIn,
        "dataOut" -> dataOut
      ),
      "config"             -> config.toJson,
      "admins"             -> JsArray(admins.map(_.json)),
      "simpleAdmins"       -> JsArray(simpleAdmins.map(_.json)),
      "serviceGroups"      -> JsArray(groups.map(_.toJson)),
      "apiKeys"            -> JsArray(apikeys.map(_.toJson)),
      "serviceDescriptors" -> JsArray(descs.map(_.toJson)),
      "errorTemplates"     -> JsArray(tmplts.map(_.toJson)),
      "jwtVerifiers"       -> JsArray(jwtVerifiers.map(_.asJson)),
      "authConfigs"        -> JsArray(authConfigs.map(_.asJson)),
      "certificates"       -> JsArray(certificates.map(_.toJson)),
      "clientValidators"   -> JsArray(clientValidators.map(_.asJson)),
      "scripts"            -> JsArray(scripts.map(_.toJson)),
      "tcpServices"        -> JsArray(tcpServices.map(_.json)),
      "dataExporters"      -> JsArray(dataExporters.map(_.json)),
      "tenants"            -> JsArray(tenants.map(_.json)),
      "teams"              -> JsArray(teams.map(_.json))
    )
  }
}
