package models

import env.Env
import events._
import play.api.Logger
import play.api.libs.json._
import storage.BasicStore
import security.{Auth0Config, IdGenerator}
import utils.CleverCloudClient
import utils.CleverCloudClient.{CleverSettings, UserTokens}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class ElasticAnalyticsConfig(clusterUri: String, index: Option[String], `type`: Option[String], user: Option[String], password: Option[String])
object ElasticAnalytics {
  val format = Json.format[ElasticAnalyticsConfig]
}

case class Webhook(url: String, headers: Map[String, String] = Map.empty[String, String]) {
  def toJson: JsObject = Webhook.format.writes(this)
}



object Webhook {
  implicit val format = Json.format[Webhook]
}

case class CleverCloudSettings(consumerKey: String,
                               consumerSecret: String,
                               token: String,
                               secret: String,
                               orgaId: String)

object CleverCloudSettings {
  implicit val format = Json.format[CleverCloudSettings]
}

case class MailgunSettings(apiKey: String, domain: String)

object MailgunSettings {
  implicit val format = Json.format[MailgunSettings]
}

case class GlobalConfig(
    lines: Seq[String] = Seq("dev", "sandbox", "experiments", "preprod", "prod"),
    streamEntityOnly: Boolean = true,
    autoLinkToDefaultGroup: Boolean = true,
    limitConcurrentRequests: Boolean = false, // TODO : true by default
    maxConcurrentRequests: Long = 1000,
    maxHttp10ResponseSize: Long = 4 * (1024 * 1024),
    useCircuitBreakers: Boolean = true,
    apiReadOnly: Boolean = false,
    u2fLoginOnly: Boolean = false,
    ipFiltering: IpFiltering = IpFiltering(),
    throttlingQuota: Long = BaseQuotas.MaxValue,
    perIpThrottlingQuota: Long = BaseQuotas.MaxValue,
    analyticsEventsUrl: Option[Webhook] = None,
    analyticsWebhooks: Seq[Webhook] = Seq.empty[Webhook],
    alertsWebhooks: Seq[Webhook] = Seq.empty[Webhook],
    alertsEmails: Seq[String] = Seq.empty[String],
    endlessIpAddresses: Seq[String] = Seq.empty[String],
    kafkaConfig: Option[KafkaConfig] = None,
    backOfficeAuthRef: Option[String] = None,
    cleverSettings: Option[CleverCloudSettings] = None,
    mailGunSettings: Option[MailgunSettings] = None,
    statsdConfig: Option[StatsdConfig] = None,
    maxWebhookSize: Int = 100,
    middleFingers: Boolean = false,
    maxLogsSize: Int = 10000,
    otoroshiId: String = IdGenerator.uuid,
    snowMonkeyConfig: SnowMonkeyConfig = SnowMonkeyConfig()
) {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.globalConfigDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.globalConfigDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.globalConfigDataStore.exists(this)
  def toJson                                            = GlobalConfig.toJson(this)
  def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.globalConfigDataStore.withinThrottlingQuota()
  def cleverClient(implicit env: Env): Option[CleverCloudClient] =
    cleverSettings match {
      case None => None
      case Some(settings) => {
        val cleverSetting = CleverSettings(
          apiConsumerKey = settings.consumerKey,
          apiConsumerSecret = settings.consumerSecret,
          apiAuthToken = UserTokens(
            token = settings.token,
            secret = settings.secret
          )
        )
        Some(CleverCloudClient(env, cleverSetting, settings.orgaId))
      }
    }
}

object GlobalConfig {

  lazy val logger = Logger("otoroshi-global-config")

  val _fmt: Format[GlobalConfig] = new Format[GlobalConfig] {
    override def writes(o: GlobalConfig): JsValue = {
      val mailGunSettings: JsValue = o.mailGunSettings match {
        case None => JsNull
        case Some(config) =>
          Json.obj(
            "apiKey" -> config.apiKey,
            "domain" -> config.domain
          )
      }
      val cleverSettings: JsValue = o.cleverSettings match {
        case None => JsNull
        case Some(config) =>
          Json.obj(
            "consumerKey"    -> config.consumerKey,
            "consumerSecret" -> config.consumerSecret,
            "token"          -> config.token,
            "secret"         -> config.secret,
            "orgaId"         -> config.orgaId
          )
      }
      val kafkaConfig: JsValue = o.kafkaConfig match {
        case None => JsNull
        case Some(config) =>
          Json.obj(
            "servers"        -> JsArray(config.servers.map(JsString.apply)),
            "keyPass"        -> config.keyPass.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "keystore"       -> config.keystore.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "truststore"     -> config.truststore.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "alertsTopic"    -> config.alertsTopic,
            "analyticsTopic" -> config.analyticsTopic,
            "auditTopic"     -> config.auditTopic
          )
      }
      val statsdConfig: JsValue = o.statsdConfig match {
        case None => JsNull
        case Some(config) =>
          Json.obj(
            "host"    -> config.host,
            "port"    -> config.port,
            "datadog" -> config.datadog
          )
      }
      Json.obj(
        "lines"                   -> JsArray(o.lines.map(JsString.apply)),
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
        "analyticsEventsUrl"      -> o.analyticsEventsUrl.map(_.toJson).getOrElse(JsNull).as[JsValue],
        "analyticsWebhooks"       -> JsArray(o.analyticsWebhooks.map(_.toJson)),
        "alertsWebhooks"          -> JsArray(o.alertsWebhooks.map(_.toJson)),
        "alertsEmails"            -> JsArray(o.alertsEmails.map(JsString.apply)),
        "endlessIpAddresses"      -> JsArray(o.endlessIpAddresses.map(JsString.apply)),
        "statsdConfig"            -> statsdConfig,
        "kafkaConfig"             -> kafkaConfig,
        "backOfficeAuthRef"       -> o.backOfficeAuthRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "mailGunSettings"         -> mailGunSettings,
        "cleverSettings"          -> cleverSettings,
        "maxWebhookSize"          -> o.maxWebhookSize,
        "middleFingers"           -> o.middleFingers,
        "maxLogsSize"             -> o.maxLogsSize,
        "otoroshiId"              -> o.otoroshiId,
        "snowMonkeyConfig"        -> o.snowMonkeyConfig.asJson
      )
    }
    override def reads(json: JsValue): JsResult[GlobalConfig] =
      Try {
        GlobalConfig(
          lines = (json \ "lines").asOpt[Seq[String]].getOrElse(Seq("dev", "sandbox", "experiments", "preprod", "prod")),
          streamEntityOnly = (json \ "streamEntityOnly").asOpt[Boolean].getOrElse(true),
          autoLinkToDefaultGroup = (json \ "autoLinkToDefaultGroup").asOpt[Boolean].getOrElse(true),
          limitConcurrentRequests = (json \ "limitConcurrentRequests").asOpt[Boolean].getOrElse(false), // TODO : true by default after prod monitoring
          maxConcurrentRequests = (json \ "maxConcurrentRequests").asOpt[Long].getOrElse(1000),
          maxHttp10ResponseSize = (json \ "maxHttp10ResponseSize").asOpt[Long].getOrElse(4 * (1024 * 1024)),
          useCircuitBreakers = (json \ "useCircuitBreakers").asOpt[Boolean].getOrElse(true),
          otoroshiId = (json \ "otoroshiId").asOpt[String].getOrElse(IdGenerator.uuid),
          apiReadOnly = (json \ "apiReadOnly").asOpt[Boolean].getOrElse(false),
          u2fLoginOnly = (json \ "u2fLoginOnly").asOpt[Boolean].getOrElse(false),
          ipFiltering = (json \ "ipFiltering").asOpt[IpFiltering](IpFiltering.format).getOrElse(IpFiltering()),
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(BaseQuotas.MaxValue),
          perIpThrottlingQuota = (json \ "perIpThrottlingQuota").asOpt[Long].getOrElse(BaseQuotas.MaxValue),
          analyticsEventsUrl = (json \ "analyticsEventsUrl")
            .asOpt[JsObject]
            .map(Webhook.format.reads)
            .filter(_.isSuccess)
            .map(r => Some(r.get))
            .getOrElse(None),
          analyticsWebhooks =
            (json \ "analyticsWebhooks").asOpt[Seq[Webhook]](Reads.seq(Webhook.format)).getOrElse(Seq.empty[Webhook]),
          alertsWebhooks =
            (json \ "alertsWebhooks").asOpt[Seq[Webhook]](Reads.seq(Webhook.format)).getOrElse(Seq.empty[Webhook]),
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
              case e => None
            }
          },
          kafkaConfig = (json \ "kafkaConfig").asOpt[JsValue].flatMap { config =>
            (
              (config \ "servers").asOpt[Seq[String]].filter(_.nonEmpty),
              (config \ "keyPass").asOpt[String],
              (config \ "keystore").asOpt[String],
              (config \ "truststore").asOpt[String],
              (config \ "alertsTopic").asOpt[String].filter(_.nonEmpty),
              (config \ "analyticsTopic").asOpt[String].filter(_.nonEmpty),
              (config \ "auditTopic").asOpt[String].filter(_.nonEmpty)
            ) match {
              case (Some(servers),
                    keyPass,
                    keystore,
                    truststore,
                    Some(alertsTopic),
                    Some(analyticsTopic),
                    Some(auditTopic)) =>
                Some(KafkaConfig(servers, keyPass, keystore, truststore, alertsTopic, analyticsTopic, auditTopic))
              case e => None
            }
          },
          backOfficeAuthRef = (json \ "backOfficeAuthRef").asOpt[String],
          mailGunSettings = (json \ "mailGunSettings").asOpt[JsValue].flatMap { config =>
            (
              (config \ "apiKey").asOpt[String].filter(_.nonEmpty),
              (config \ "domain").asOpt[String].filter(_.nonEmpty)
            ) match {
              case (Some(apiKey), Some(domain)) => Some(MailgunSettings(apiKey, domain))
              case _                            => None
            }
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
              case _ => None
            }
          },
          snowMonkeyConfig = (json \ "snowMonkeyConfig").asOpt(SnowMonkeyConfig._fmt).getOrElse(SnowMonkeyConfig())
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading GlobalConfig", t)
          JsError(t.getMessage)
      } get
  }

  def toJson(value: GlobalConfig): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): GlobalConfig =
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
  def updateQuotas(config: models.GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def singleton()(implicit ec: ExecutionContext, env: Env): Future[GlobalConfig]
  def latest()(implicit ec: ExecutionContext, env: Env): GlobalConfig
  def fullImport(export: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def fullExport()(implicit ec: ExecutionContext, env: Env): Future[JsValue]
  def allEnv()(implicit ec: ExecutionContext, env: Env): Future[Set[String]]
  def quotasValidationFor(from: String)(implicit ec: ExecutionContext, env: Env): Future[(Boolean, Long, Option[Long])]
  def migrate()(implicit ec: ExecutionContext, env: Env): Future[Unit]
}
