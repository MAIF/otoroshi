package models

import akka.http.scaladsl.util.FastFuture
import env.Env
import play.api.Logger
import play.api.libs.json._
import security.IdGenerator
import storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class RemainingQuotas(
    // secCalls: Long = RemainingQuotas.MaxValue,
    // secCallsRemaining: Long = RemainingQuotas.MaxValue,
    // dailyCalls: Long = RemainingQuotas.MaxValue,
    // dailyCallsRemaining: Long = RemainingQuotas.MaxValue,
    // monthlyCalls: Long = RemainingQuotas.MaxValue,
    // monthlyCallsRemaining: Long = RemainingQuotas.MaxValue
    authorizedCallsPerSec: Long = RemainingQuotas.MaxValue,
    currentCallsPerSec: Long = RemainingQuotas.MaxValue,
    remainingCallsPerSec: Long = RemainingQuotas.MaxValue,
    authorizedCallsPerDay: Long = RemainingQuotas.MaxValue,
    currentCallsPerDay: Long = RemainingQuotas.MaxValue,
    remainingCallsPerDay: Long = RemainingQuotas.MaxValue,
    authorizedCallsPerMonth: Long = RemainingQuotas.MaxValue,
    currentCallsPerMonth: Long = RemainingQuotas.MaxValue,
    remainingCallsPerMonth: Long = RemainingQuotas.MaxValue
) {
  def toJson: JsObject = RemainingQuotas.fmt.writes(this)
}

object RemainingQuotas {
  val MaxValue: Long = 10000000L
  implicit val fmt   = Json.format[RemainingQuotas]
}

case class ApiKey(clientId: String = IdGenerator.token(16),
                  clientSecret: String = IdGenerator.token(64),
                  clientName: String,
                  authorizedGroup: String,
                  enabled: Boolean = true,
                  readOnly: Boolean = false,
                  throttlingQuota: Long = RemainingQuotas.MaxValue,
                  dailyQuota: Long = RemainingQuotas.MaxValue,
                  monthlyQuota: Long = RemainingQuotas.MaxValue,
                  metadata: Map[String, String] = Map.empty[String, String]) {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.apiKeyDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.apiKeyDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.apiKeyDataStore.exists(this)
  def toJson                                            = ApiKey.toJson(this)
  def isValid(value: String): Boolean                   = enabled && value == clientSecret
  def isInvalid(value: String): Boolean                 = !isValid(value)
  def group(implicit ec: ExecutionContext, env: Env): Future[Option[ServiceGroup]] =
    env.datastores.serviceGroupDataStore.findById(authorizedGroup)
  def services(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] =
    env.datastores.serviceGroupDataStore.findById(authorizedGroup).flatMap {
      case Some(group) => group.services
      case None        => FastFuture.successful(Seq.empty[ServiceDescriptor])
    }
  def updateQuotas()(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas] =
    env.datastores.apiKeyDataStore.updateQuotas(this)
  def remainingQuotas()(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas] =
    env.datastores.apiKeyDataStore.remainingQuotas(this)
  def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.apiKeyDataStore.withinThrottlingQuota(this)
  def withinDailyQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.apiKeyDataStore.withinDailyQuota(this)
  def withinMonthlyQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.apiKeyDataStore.withinMonthlyQuota(this)
  def withingQuotas()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.apiKeyDataStore.withingQuotas(this)
}

class ServiceNotFoundException(serviceId: String)
    extends RuntimeException(s"Service descriptor with id: '$serviceId' does not exist")
class GroupNotFoundException(groupId: String)
    extends RuntimeException(s"Service group with id: '$groupId' does not exist")

object ApiKey {

  lazy val logger = Logger("otoroshi-apkikey")

  val _fmt: Format[ApiKey] = new Format[ApiKey] {
    override def writes(apk: ApiKey): JsValue = Json.obj(
      "clientId"        -> apk.clientId,
      "clientSecret"    -> apk.clientSecret,
      "clientName"      -> apk.clientName,
      "authorizedGroup" -> apk.authorizedGroup,
      "enabled"         -> apk.enabled,
      "readOnly"        -> apk.readOnly,
      "throttlingQuota" -> apk.throttlingQuota,
      "dailyQuota"      -> apk.dailyQuota,
      "monthlyQuota"    -> apk.monthlyQuota,
      "metadata"        -> JsObject(apk.metadata.mapValues(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[ApiKey] =
      Try {
        ApiKey(
          clientId = (json \ "clientId").as[String],
          clientSecret = (json \ "clientSecret").as[String],
          clientName = (json \ "clientName").as[String],
          authorizedGroup = (json \ "authorizedGroup").as[String],
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          readOnly = (json \ "readOnly").asOpt[Boolean].getOrElse(false),
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          dailyQuota = (json \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          monthlyQuota = (json \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading ApiKey", t)
          JsError(t.getMessage)
      } get
  }
  def toJson(value: ApiKey): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): ApiKey =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[ApiKey] = _fmt.reads(value)
}

trait ApiKeyDataStore extends BasicStore[ApiKey] {
  def initiateNewApiKey(groupId: String): ApiKey =
    ApiKey(
      clientId = IdGenerator.token(16),
      clientSecret = IdGenerator.token(64),
      clientName = "client-name-apikey",
      authorizedGroup = groupId
    )
  def remainingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def resetQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def updateQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def withingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinThrottlingQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinDailyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinMonthlyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def findByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]]
  def findByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]]
  def findAuthorizeKeyFor(clientId: String, serviceId: String)(implicit ec: ExecutionContext,
                                                               env: Env): Future[Option[ApiKey]]
  def deleteFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def deleteFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit ec: ExecutionContext,
                                                                   env: Env): Future[Long]
  def addFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def addFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def clearFastLookupByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def clearFastLookupByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
