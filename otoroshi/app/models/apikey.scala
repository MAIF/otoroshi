package otoroshi.models

import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import otoroshi.env.Env
import otoroshi.events.{Alerts, ApiKeySecretHasRotated, ApiKeySecretWillRotate, RevokedApiKeyUsageAlert}
import otoroshi.gateway.Errors
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.{BadGateway, BadRequest, NotFound, TooManyRequests, Unauthorized}
import play.api.mvc.{RequestHeader, Result, Results}
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.storage.BasicStore
import otoroshi.utils.TypedMap
import otoroshi.ssl.DynamicSSLEngineProvider

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

case class ApiKeyRotation(
    enabled: Boolean = false,
    rotationEvery: Long = 31 * 24,
    gracePeriod: Long = 7 * 24,
    nextSecret: Option[String] = None
) {
  def json: JsValue = ApiKeyRotation.fmt.writes(this)
}

case class ApiKeyRotationInfo(rotationAt: DateTime, remaining: Long)

object ApiKeyRotation         {
  val fmt = new Format[ApiKeyRotation] {
    override def writes(o: ApiKeyRotation): JsValue             =
      Json.obj(
        "enabled"       -> o.enabled,
        "rotationEvery" -> o.rotationEvery,
        "gracePeriod"   -> o.gracePeriod,
        "nextSecret"    -> o.nextSecret.map(JsString.apply).getOrElse(JsNull).as[JsValue]
      )
    override def reads(json: JsValue): JsResult[ApiKeyRotation] =
      Try {
        ApiKeyRotation(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          rotationEvery = (json \ "rotationEvery").asOpt[Long].getOrElse(31 * 24),
          gracePeriod = (json \ "gracePeriod").asOpt[Long].getOrElse(7 * 24),
          nextSecret = (json \ "nextSecret").asOpt[String]
        )
      } match {
        case Failure(e)    => JsError(e.getMessage)
        case Success(apkr) => JsSuccess(apkr)
      }
  }
}
object EntityIdentifier       {
  def apply(prefixedId: String): Option[EntityIdentifier] = {
    prefixedId match {
      case id if id.startsWith("group_")   => Some(ServiceGroupIdentifier(id.replace("group_", "")))
      case id if id.startsWith("service_") => Some(ServiceDescriptorIdentifier(id.replace("service_", "")))
      case id                              => Some(ServiceGroupIdentifier(id)) // should be None but could be useful for backward compatibility
    }
  }
}
sealed trait EntityIdentifier {
  def prefix: String
  def id: String
  def str: String   = s"${prefix}_${id}"
  def json: JsValue = JsString(str)
}
case class ServiceGroupIdentifier(id: String) extends EntityIdentifier {
  def prefix: String = "group"
}
case class ServiceDescriptorIdentifier(id: String) extends EntityIdentifier {
  def prefix: String = "service"
}

case class ApiKey(
    clientId: String = IdGenerator.token(16),
    clientSecret: String = IdGenerator.token(64),
    clientName: String,
    description: String = "",
    authorizedEntities: Seq[EntityIdentifier],
    enabled: Boolean = true,
    readOnly: Boolean = false,
    allowClientIdOnly: Boolean = false,
    throttlingQuota: Long = RemainingQuotas.MaxValue,
    dailyQuota: Long = RemainingQuotas.MaxValue,
    monthlyQuota: Long = RemainingQuotas.MaxValue,
    constrainedServicesOnly: Boolean = false,
    restrictions: Restrictions = Restrictions(),
    validUntil: Option[DateTime] = None,
    rotation: ApiKeyRotation = ApiKeyRotation(),
    tags: Seq[String] = Seq.empty[String],
    metadata: Map[String, String] = Map.empty[String, String],
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {

  def json: JsValue      = toJson
  def internalId: String = clientId
  def theDescription: String = description
  def theMetadata: Map[String,String] = metadata
  def theName: String = clientName
  def theTags: Seq[String] = tags

  def save()(implicit ec: ExecutionContext, env: Env)     = env.datastores.apiKeyDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env)   = env.datastores.apiKeyDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env)   = env.datastores.apiKeyDataStore.exists(this)
  def toJson                                              = ApiKey.toJson(this)
  def isActive(): Boolean                                 = enabled && validUntil.map(date => date.isBeforeNow).getOrElse(true)
  def isInactive(): Boolean                               = !isActive()
  def isValid(value: String): Boolean                     =
    enabled && ((value == clientSecret) || (rotation.enabled && rotation.nextSecret.contains(value)))
  def isInvalid(value: String): Boolean                   = !isValid(value)
  def authorizedOn(identifier: EntityIdentifier): Boolean = authorizedEntities.contains(identifier)
  def authorizedOnService(id: String): Boolean            = authorizedEntities.contains(ServiceDescriptorIdentifier(id))
  def authorizedOnGroup(id: String): Boolean              = authorizedEntities.contains(ServiceGroupIdentifier(id))
  def authorizedOnOneGroupFrom(ids: Seq[String]): Boolean = {
    val identifiers = ids.map(ServiceGroupIdentifier.apply)
    authorizedEntities.exists(e => identifiers.contains(e))
  }
  def authorizedOnServiceOrGroups(service: String, groups: Seq[String]): Boolean = {
    authorizedOnService(service) || {
      val identifiers = groups.map(ServiceGroupIdentifier.apply)
      authorizedEntities.exists(e => identifiers.contains(e))
    }
  }
  def services(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    FastFuture
      .sequence(authorizedEntities.map {
        case ServiceDescriptorIdentifier(id) => env.datastores.serviceDescriptorDataStore.findById(id).map(_.toSeq)
        case ServiceGroupIdentifier(id)      =>
          env.datastores.serviceGroupDataStore.findById(id).flatMap {
            case Some(group) => group.services
            case None        => FastFuture.successful(Seq.empty[ServiceDescriptor])
          }
      })
      .map(_.flatten)
  }

  def updateQuotas()(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]    =
    env.datastores.apiKeyDataStore.updateQuotas(this)
  def remainingQuotas()(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas] =
    env.datastores.apiKeyDataStore.remainingQuotas(this)
  def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean]   =
    env.datastores.apiKeyDataStore.withinThrottlingQuota(this)
  def withinDailyQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean]        =
    env.datastores.apiKeyDataStore.withinDailyQuota(this)
  def withinMonthlyQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean]      =
    env.datastores.apiKeyDataStore.withinMonthlyQuota(this)
  def withinQuotas()(implicit ec: ExecutionContext, env: Env): Future[Boolean]            =
    env.datastores.apiKeyDataStore.withingQuotas(this)
  def withinQuotasAndRotation()(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[(Boolean, Option[ApiKeyRotationInfo])] = {
    for {
      within   <- env.datastores.apiKeyDataStore.withingQuotas(this)
      rotation <- env.datastores.apiKeyDataStore.keyRotation(this)
    } yield (within, rotation)
  }
  def metadataJson: JsValue                                                               = JsObject(metadata.mapValues(JsString.apply))
  def lightJson: JsObject                                                                 =
    Json.obj(
      "clientId"   -> clientId,
      "clientName" -> clientName,
      "metadata"   -> metadata,
      "tags"       -> tags
    )

  def matchRouting(sr: ServiceDescriptor): Boolean = {

    import SeqImplicits._

    val shouldNotSearchForAnApiKey = sr.hasNoRoutingConstraints

    if (shouldNotSearchForAnApiKey && constrainedServicesOnly) {
      false
    } else if (shouldNotSearchForAnApiKey) {
      true
    } else {
      val matchOnRole: Boolean   = Option(sr.apiKeyConstraints.routing.oneTagIn)
        .filter(_.nonEmpty)
        .forall(tags => this.tags.findOne(tags))
      val matchAllRoles: Boolean = Option(sr.apiKeyConstraints.routing.allTagsIn)
        .filter(_.nonEmpty)
        .forall(tags => this.tags.findAll(tags))
      val matchNoneRole: Boolean = !Option(sr.apiKeyConstraints.routing.noneTagIn)
        .filter(_.nonEmpty)
        .exists(tags => this.tags.findOne(tags))

      val matchOneMeta: Boolean  = Option(sr.apiKeyConstraints.routing.oneMetaIn.toSeq)
        .filter(_.nonEmpty)
        .forall(metas => this.metadata.toSeq.findOne(metas))
      val matchAllMeta: Boolean  = Option(sr.apiKeyConstraints.routing.allMetaIn.toSeq)
        .filter(_.nonEmpty)
        .forall(metas => this.metadata.toSeq.findAll(metas))
      val matchNoneMeta: Boolean = !Option(sr.apiKeyConstraints.routing.noneMetaIn.toSeq)
        .filter(_.nonEmpty)
        .exists(metas => this.metadata.toSeq.findOne(metas))

      val matchOneMetakeys: Boolean  = Option(sr.apiKeyConstraints.routing.oneMetaKeyIn)
        .filter(_.nonEmpty)
        .forall(keys => this.metadata.toSeq.map(_._1).findOne(keys))
      val matchAllMetaKeys: Boolean  = Option(sr.apiKeyConstraints.routing.allMetaKeysIn)
        .filter(_.nonEmpty)
        .forall(keys => this.metadata.toSeq.map(_._1).findAll(keys))
      val matchNoneMetaKeys: Boolean = !Option(sr.apiKeyConstraints.routing.noneMetaKeysIn)
        .filter(_.nonEmpty)
        .exists(keys => this.metadata.toSeq.map(_._1).findOne(keys))

      val result = Seq(
        matchOnRole,
        matchAllRoles,
        matchNoneRole,
        matchOneMeta,
        matchAllMeta,
        matchNoneMeta,
        matchOneMetakeys,
        matchAllMetaKeys,
        matchNoneMetaKeys
      )
        .forall(bool => bool)
      result
    }
  }
}

class ServiceNotFoundException(serviceId: String)
    extends RuntimeException(s"Service descriptor with id: '$serviceId' does not exist")
class GroupNotFoundException(groupId: String)
    extends RuntimeException(s"Service group with id: '$groupId' does not exist")

object ApiKey {

  lazy val logger = Logger("otoroshi-apkikey")

  val _fmt: Format[ApiKey]                           = new Format[ApiKey] {
    override def writes(apk: ApiKey): JsValue = {
      val enabled            = apk.validUntil match {
        case Some(date) if date.isBeforeNow => false
        case _                              => apk.enabled
      }
      val authGroup: JsValue = apk.authorizedEntities
        .find {
          case ServiceGroupIdentifier(_)      => true
          case ServiceDescriptorIdentifier(_) => false
        }
        .map(_.id)
        .map(JsString.apply)
        .getOrElse(JsNull) // simulate old behavior
      apk.location.jsonWithKey ++ Json.obj(
        "clientId"                -> apk.clientId,
        "clientSecret"            -> apk.clientSecret,
        "clientName"              -> apk.clientName,
        "description"             -> apk.description,
        "authorizedGroup"         -> authGroup,
        "authorizedEntities"      -> JsArray(apk.authorizedEntities.map(_.json)),
        "enabled"                 -> enabled, //apk.enabled,
        "readOnly"                -> apk.readOnly,
        "allowClientIdOnly"       -> apk.allowClientIdOnly,
        "throttlingQuota"         -> apk.throttlingQuota,
        "dailyQuota"              -> apk.dailyQuota,
        "monthlyQuota"            -> apk.monthlyQuota,
        "constrainedServicesOnly" -> apk.constrainedServicesOnly,
        "restrictions"            -> apk.restrictions.json,
        "rotation"                -> apk.rotation.json,
        "validUntil"              -> apk.validUntil.map(v => JsNumber(v.toDate.getTime)).getOrElse(JsNull).as[JsValue],
        "tags"                    -> JsArray(apk.tags.map(JsString.apply)),
        "metadata"                -> JsObject(apk.metadata.filter(_._1.nonEmpty).mapValues(JsString.apply))
      )
    }
    override def reads(json: JsValue): JsResult[ApiKey] =
      Try {
        val rawEnabled    = (json \ "enabled").asOpt[Boolean].getOrElse(true)
        val rawValidUntil = (json \ "validUntil").asOpt[Long].map(l => new DateTime(l))
        val enabled       = rawValidUntil match {
          case Some(date) if date.isBeforeNow => false
          case _                              => rawEnabled
        }
        ApiKey(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          clientId = (json \ "clientId").as[String],
          clientSecret = (json \ "clientSecret").as[String],
          clientName = (json \ "clientName").as[String],
          description = (json \ "description").asOpt[String].getOrElse(""),
          authorizedEntities = {
            val authorizedGroup: Seq[EntityIdentifier]    =
              (json \ "authorizedGroup").asOpt[String].map(ServiceGroupIdentifier.apply).toSeq
            val authorizedEntities: Seq[EntityIdentifier] =
              (json \ "authorizedEntities")
                .asOpt[Seq[String]]
                .map { identifiers =>
                  identifiers.map(EntityIdentifier.apply).collect { case Some(id) =>
                    id
                  }
                }
                .getOrElse(Seq.empty[EntityIdentifier])
            (authorizedGroup ++ authorizedEntities).distinct
          },
          enabled = enabled,
          readOnly = (json \ "readOnly").asOpt[Boolean].getOrElse(false),
          allowClientIdOnly = (json \ "allowClientIdOnly").asOpt[Boolean].getOrElse(false),
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          dailyQuota = (json \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          monthlyQuota = (json \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          constrainedServicesOnly = (json \ "constrainedServicesOnly").asOpt[Boolean].getOrElse(false),
          restrictions = Restrictions.format
            .reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(Restrictions()),
          rotation = ApiKeyRotation.fmt
            .reads((json \ "rotation").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(ApiKeyRotation()),
          validUntil = rawValidUntil,
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          metadata = (json \ "metadata")
            .asOpt[Map[String, String]]
            .map(m => m.filter(_._1.nonEmpty))
            .getOrElse(Map.empty[String, String])
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading ApiKey", t)
        JsError(t.getMessage)
      } get
  }
  def toJson(value: ApiKey): JsValue                 = _fmt.writes(value)
  def fromJsons(value: JsValue): ApiKey              =
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
      authorizedEntities = Seq(ServiceGroupIdentifier(groupId))
    )
  def template(env: Env): ApiKey =
    ApiKey(
      clientId = IdGenerator.token(16),
      clientSecret = IdGenerator.token(64),
      clientName = "client-name-apikey",
      authorizedEntities = Seq.empty
    )
  def remainingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def resetQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def updateQuotas(apiKey: ApiKey, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[RemainingQuotas]
  def withingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinThrottlingQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinDailyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withinMonthlyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def findByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]]
  def findByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]]
  def findAuthorizeKeyFor(clientId: String, serviceId: String)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ApiKey]]
  def deleteFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def deleteFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Long]
  def addFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def addFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def clearFastLookupByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def clearFastLookupByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Long]

  // def willBeRotatedAt(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Option[(DateTime, Long)]] = {
  //   if (apiKey.rotation.enabled) {
  //     val key = s"${env.storageRoot}:apikeys-rotation:${apiKey.clientId}"
  //     env.datastores.rawDataStore.get(key).map {
  //       case None => None
  //       case Some(body) =>
  //         val json  = Json.parse(body.utf8String)
  //         val start = new DateTime((json \ "start").as[Long])
  //         val end   = start.plus(apiKey.rotation.rotationEvery * 3600000L)
  //         Some((end, new org.joda.time.Period(DateTime.now(), end).toStandardSeconds.getSeconds * 1000))
  //     }
  //   } else {
  //     FastFuture.successful(None)
  //   }
  // }

  def keyRotation(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Option[ApiKeyRotationInfo]] = {
    if (apiKey.rotation.enabled) {
      val key = s"${env.storageRoot}:apikeys-rotation:${apiKey.clientId}"
      env.datastores.rawDataStore.get(key).flatMap {
        case None       =>
          val newApk                          = apiKey.copy(rotation = apiKey.rotation.copy(nextSecret = None))
          val start                           = DateTime.now()
          val end                             = start.plus(apiKey.rotation.rotationEvery * 3600000L)
          val remaining                       = end.getMillis - DateTime.now().getMillis
          val res: Option[ApiKeyRotationInfo] = Some(
            ApiKeyRotationInfo(
              end,
              remaining
            ) // new org.joda.time.Period(DateTime.now(), end).toStandardSeconds.getSeconds * 1000)
          )
          env.datastores.rawDataStore
            .set(
              key,
              ByteString(
                Json.stringify(
                  Json.obj(
                    "start" -> start.toDate.getTime
                  )
                )
              ),
              Some((newApk.rotation.rotationEvery * 60 * 60 * 1000) + (5 * 60 * 1000))
            )
            .flatMap { _ =>
              newApk.save().map(_ => res)
            }
        case Some(body) => {
          val json                            = Json.parse(body.utf8String)
          val start                           = new DateTime((json \ "start").as[Long])
          val end                             = start.plus(apiKey.rotation.rotationEvery * 3600000L)
          val startGrace                      = end.minus(apiKey.rotation.gracePeriod * 3600000L)
          val now                             = DateTime.now()
          val beforeGracePeriod               = now.isAfter(start) && now.isBefore(startGrace) && now.isBefore(end)
          val inGracePeriod                   = now.isAfter(start) && now.isAfter(startGrace) && now.isBefore(end)
          val afterGracePeriod                = now.isAfter(start) && now.isAfter(startGrace) && now.isAfter(end)
          val remaining                       = end.getMillis - DateTime.now().getMillis
          val res: Option[ApiKeyRotationInfo] = Some(
            ApiKeyRotationInfo(
              end,
              remaining
            ) //new org.joda.time.Period(DateTime.now(), end).toStandardSeconds.getSeconds * 1000)
          )
          if (beforeGracePeriod) {
            FastFuture.successful(res)
          } else if (inGracePeriod) {
            val newApk = apiKey.copy(
              rotation =
                apiKey.rotation.copy(nextSecret = apiKey.rotation.nextSecret.orElse(Some(IdGenerator.token(64))))
            )
            Alerts.send(
              ApiKeySecretWillRotate(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@env` = env.env,
                apikey = newApk
              )
            )
            newApk.save().map(_ => res)
          } else if (afterGracePeriod) {
            val newApk = apiKey.copy(
              clientSecret = apiKey.rotation.nextSecret.getOrElse(IdGenerator.token(64)),
              rotation = apiKey.rotation.copy(nextSecret = None)
            )
            Alerts.send(
              ApiKeySecretHasRotated(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@env` = env.env,
                oldApikey = apiKey,
                apikey = newApk
              )
            )
            env.datastores.rawDataStore
              .set(
                key,
                ByteString(
                  Json.stringify(
                    Json.obj(
                      "start" -> System.currentTimeMillis()
                    )
                  )
                ),
                Some((newApk.rotation.rotationEvery * 60 * 60 * 1000) + (5 * 60 * 1000))
              )
              .flatMap { _ =>
                newApk.save().map(_ => res)
              }
          } else {
            FastFuture.successful(res)
          }
        }
      }
    } else {
      FastFuture.successful(None)
    }
  }
}

object ApiKeyHelper {

  import otoroshi.utils.http.RequestImplicits._
  import otoroshi.utils.syntax.implicits._

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def extractApiKey(req: RequestHeader, descriptor: ServiceDescriptor, attrs: TypedMap)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ApiKey]] = {

    val authByJwtToken             = req.headers
      .get(
        descriptor.apiKeyConstraints.jwtAuth.headerName
          .getOrElse(env.Headers.OtoroshiBearer)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Bearer "))
      )
      .map(_.replace("Bearer ", ""))
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.jwtAuth.queryName
              .getOrElse(env.Headers.OtoroshiBearerAuthorization)
          )
          .flatMap(_.lastOption)
      )
      .orElse(
        req.cookies
          .get(
            descriptor.apiKeyConstraints.jwtAuth.cookieName
              .getOrElse(env.Headers.OtoroshiJWTAuthorization)
          )
          .map(_.value)
      )
      .filter(_.split("\\.").length == 3)
    val authBasic                  = req.headers
      .get(
        descriptor.apiKeyConstraints.basicAuth.headerName
          .getOrElse(env.Headers.OtoroshiAuthorization)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Basic "))
      )
      .map(_.replace("Basic ", ""))
      .flatMap(e => Try(decodeBase64(e)).toOption)
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.basicAuth.queryName
              .getOrElse(env.Headers.OtoroshiBasicAuthorization)
          )
          .flatMap(_.lastOption)
          .flatMap(e => Try(decodeBase64(e)).toOption)
      )
    val authByCustomHeaders        = req.headers
      .get(
        descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
          .getOrElse(env.Headers.OtoroshiClientId)
      )
      .flatMap(id =>
        req.headers
          .get(
            descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName
              .getOrElse(env.Headers.OtoroshiClientSecret)
          )
          .map(s => (id, s))
      )
    val authBySimpleApiKeyClientId = req.headers
      .get(
        descriptor.apiKeyConstraints.clientIdAuth.headerName
          .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
      )
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.clientIdAuth.queryName
              .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
          )
          .flatMap(_.lastOption)
      )

    val preExtractedApiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    if (preExtractedApiKey.isDefined) {
      FastFuture.successful(preExtractedApiKey)
    } else if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
      val clientId = authBySimpleApiKeyClientId.get
      env.datastores.apiKeyDataStore
        .findAuthorizeKeyFor(clientId, descriptor.id)
        .flatMap {
          case None                                => FastFuture.successful(None)
          case Some(key) if !key.allowClientIdOnly => FastFuture.successful(None)
          case Some(key) if key.allowClientIdOnly  => FastFuture.successful(Some(key))
        }
    } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
      val (clientId, clientSecret) = authByCustomHeaders.get
      env.datastores.apiKeyDataStore
        .findAuthorizeKeyFor(clientId, descriptor.id)
        .flatMap {
          case None                                     => FastFuture.successful(None)
          case Some(key) if key.isInvalid(clientSecret) => FastFuture.successful(None)
          case Some(key) if key.isValid(clientSecret)   => FastFuture.successful(Some(key))
        }
    } else if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
      val jwtTokenValue = authByJwtToken.get
      Try {
        JWT.decode(jwtTokenValue)
      } map { jwt =>
        jwt
          .claimStr("clientId")
          .orElse(jwt.claimStr("client_id"))
          .orElse(jwt.claimStr("cid"))
          .orElse(jwt.claimStr("iss")) match {
          case Some(clientId) =>
            env.datastores.apiKeyDataStore
              .findAuthorizeKeyFor(clientId, descriptor.id)
              .flatMap {
                case Some(apiKey) => {
                  val possibleKeyPairId               = apiKey.metadata.get("jwt-sign-keypair")
                  val kid                             = Option(jwt.getKeyId)
                    .orElse(possibleKeyPairId)
                    .filter(_ => descriptor.apiKeyConstraints.jwtAuth.keyPairSigned)
                    .filter(id => if (possibleKeyPairId.isDefined) possibleKeyPairId.get == id else true)
                    .flatMap(id => DynamicSSLEngineProvider.certificates.get(id))
                  val kp                              = kid.map(_.cryptoKeyPair)
                  val algorithmOpt: Option[Algorithm] = Option(jwt.getAlgorithm).collect {
                    case "HS256" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC256(apiKey.clientSecret)
                    case "HS384" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC384(apiKey.clientSecret)
                    case "HS512" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC512(apiKey.clientSecret)
                    case "ES256" if kid.isDefined                                     =>
                      Algorithm.ECDSA256(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "ES384" if kid.isDefined                                     =>
                      Algorithm.ECDSA384(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "ES512" if kid.isDefined                                     =>
                      Algorithm.ECDSA512(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "RS256" if kid.isDefined                                     =>
                      Algorithm.RSA256(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                    case "RS384" if kid.isDefined                                     =>
                      Algorithm.RSA384(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                    case "RS512" if kid.isDefined                                     =>
                      Algorithm.RSA512(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                  } // getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                  val exp                             =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                  val iat                             =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                  val httpPath                        = Option(jwt.getClaim("httpPath"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpVerb                        = Option(jwt.getClaim("httpVerb"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpHost                        = Option(jwt.getClaim("httpHost"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  algorithmOpt match {
                    case Some(algorithm) => {
                      val verifier =
                        JWT
                          .require(algorithm)
                          //.withIssuer(clientId)
                          .acceptLeeway(10)
                          .build
                      Try(verifier.verify(jwtTokenValue))
                        .filter { token =>
                          val xsrfToken       = token.getClaim("xsrfToken")
                          val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                          if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                            xsrfToken.asString() == xsrfTokenHeader.get
                          } else if (!xsrfToken.isNull && xsrfTokenHeader.isEmpty) {
                            false
                          } else {
                            true
                          }
                        }
                        .filter { _ =>
                          descriptor.apiKeyConstraints.jwtAuth.maxJwtLifespanSecs.map { maxJwtLifespanSecs =>
                            if (exp.isEmpty || iat.isEmpty) {
                              false
                            } else {
                              if ((exp.get - iat.get) <= maxJwtLifespanSecs) {
                                true
                              } else {
                                false
                              }
                            }
                          } getOrElse {
                            true
                          }
                        }
                        .filter { _ =>
                          if (descriptor.apiKeyConstraints.jwtAuth.includeRequestAttributes) {
                            val matchPath = httpPath.exists(_ == req.relativeUri)
                            val matchVerb =
                              httpVerb.exists(_.toLowerCase == req.method.toLowerCase)
                            val matchHost = httpHost.exists(_.toLowerCase == req.theHost)
                            matchPath && matchVerb && matchHost
                          } else {
                            true
                          }
                        } match {
                        case Success(_) => FastFuture.successful(Some(apiKey))
                        case Failure(e) => FastFuture.successful(None)
                      }
                    }
                    case None            => FastFuture.successful(None)
                  }
                }
                case None         => FastFuture.successful(None)
              }
          case None           => FastFuture.successful(None)
        }
      } getOrElse FastFuture.successful(None)
    } else if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
      val auth   = authBasic.get
      val id     = auth.split(":").headOption.map(_.trim)
      val secret = auth.split(":").lastOption.map(_.trim)
      (id, secret) match {
        case (Some(apiKeyClientId), Some(apiKeySecret)) => {
          env.datastores.apiKeyDataStore
            .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
            .flatMap {
              case None                                     => FastFuture.successful(None)
              case Some(key) if key.isInvalid(apiKeySecret) => FastFuture.successful(None)
              case Some(key) if key.isValid(apiKeySecret)   => FastFuture.successful(Some(key))
            }
        }
        case _                                          => FastFuture.successful(None)
      }
    } else {
      FastFuture.successful(None)
    }
  }

  def detectApiKey(req: RequestHeader, descriptor: ServiceDescriptor, attrs: TypedMap)(implicit env: Env): Boolean = {

    val authByJwtToken             = req.headers
      .get(
        descriptor.apiKeyConstraints.jwtAuth.headerName
          .getOrElse(env.Headers.OtoroshiBearer)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Bearer "))
      )
      .map(_.replace("Bearer ", ""))
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.jwtAuth.queryName
              .getOrElse(env.Headers.OtoroshiBearerAuthorization)
          )
          .flatMap(_.lastOption)
      )
      .orElse(
        req.cookies
          .get(
            descriptor.apiKeyConstraints.jwtAuth.cookieName
              .getOrElse(env.Headers.OtoroshiJWTAuthorization)
          )
          .map(_.value)
      )
      .filter(_.split("\\.").length == 3)
    val authBasic                  = req.headers
      .get(
        descriptor.apiKeyConstraints.basicAuth.headerName
          .getOrElse(env.Headers.OtoroshiAuthorization)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Basic "))
      )
      .map(_.replace("Basic ", ""))
      .flatMap(e => Try(decodeBase64(e)).toOption)
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.basicAuth.queryName
              .getOrElse(env.Headers.OtoroshiBasicAuthorization)
          )
          .flatMap(_.lastOption)
          .flatMap(e => Try(decodeBase64(e)).toOption)
      )
    val authByCustomHeaders        = req.headers
      .get(
        descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
          .getOrElse(env.Headers.OtoroshiClientId)
      )
      .flatMap(id =>
        req.headers
          .get(
            descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName
              .getOrElse(env.Headers.OtoroshiClientSecret)
          )
          .map(s => (id, s))
      )
    val authBySimpleApiKeyClientId = req.headers
      .get(
        descriptor.apiKeyConstraints.clientIdAuth.headerName
          .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
      )
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.clientIdAuth.queryName
              .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
          )
          .flatMap(_.lastOption)
      )
    val preExtractedApiKey         = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    if (preExtractedApiKey.isDefined) {
      true
    } else if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
      true
    } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
      true
    } else if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
      true
    } else if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
      true
    } else {
      false
    }
  }

  case class PassWithApiKeyContext(
      req: RequestHeader,
      descriptor: ServiceDescriptor,
      attrs: TypedMap,
      config: GlobalConfig
  )

  def passWithApiKey[T](
      ctx: PassWithApiKeyContext,
      callDownstream: (GlobalConfig, Option[ApiKey], Option[PrivateAppsUser]) => Future[Either[Result, T]],
      errorResult: (Results.Status, String, String) => Future[Either[Result, T]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, T]] = {

    val PassWithApiKeyContext(req, descriptor, attrs, config) = ctx

    def sendRevokedApiKeyAlert(key: ApiKey) = {
      Alerts.send(
        RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(), DateTime.now(), env.env, req, key, descriptor, env)
      )
    }

    // if (req.headers.get("Otoroshi-Client-Id").isEmpty) {
    //   println("no apikey", req.method, req.path)
    // } else {
    //   println("with apikey", req.method, req.path)
    // }

    // if (descriptor.thirdPartyApiKey.enabled) {
    //   descriptor.thirdPartyApiKey.handleGen[T](req, descriptor, config, attrs) { key =>
    //     callDownstream(config, key, None)
    //   }
    // } else {
    val authByJwtToken             = req.headers
      .get(
        descriptor.apiKeyConstraints.jwtAuth.headerName
          .getOrElse(env.Headers.OtoroshiBearer)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Bearer "))
      )
      .map(_.replace("Bearer ", ""))
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.jwtAuth.queryName
              .getOrElse(env.Headers.OtoroshiBearerAuthorization)
          )
          .flatMap(_.lastOption)
      )
      .orElse(
        req.cookies
          .get(
            descriptor.apiKeyConstraints.jwtAuth.cookieName
              .getOrElse(env.Headers.OtoroshiJWTAuthorization)
          )
          .map(_.value)
      )
      .filter(_.split("\\.").length == 3)
    val authBasic                  = req.headers
      .get(
        descriptor.apiKeyConstraints.basicAuth.headerName
          .getOrElse(env.Headers.OtoroshiAuthorization)
      )
      .orElse(
        req.headers.get("Authorization").filter(_.startsWith("Basic "))
      )
      .map(_.replace("Basic ", ""))
      .flatMap(e => Try(decodeBase64(e)).toOption)
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.basicAuth.queryName
              .getOrElse(env.Headers.OtoroshiBasicAuthorization)
          )
          .flatMap(_.lastOption)
          .flatMap(e => Try(decodeBase64(e)).toOption)
      )
    val authByCustomHeaders        = req.headers
      .get(
        descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
          .getOrElse(env.Headers.OtoroshiClientId)
      )
      .flatMap(id =>
        req.headers
          .get(
            descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName
              .getOrElse(env.Headers.OtoroshiClientSecret)
          )
          .map(s => (id, s))
      )
    val authBySimpleApiKeyClientId = req.headers
      .get(
        descriptor.apiKeyConstraints.clientIdAuth.headerName
          .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
      )
      .orElse(
        req.queryString
          .get(
            descriptor.apiKeyConstraints.clientIdAuth.queryName
              .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
          )
          .flatMap(_.lastOption)
      )

    val preExtractedApiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    if (preExtractedApiKey.isDefined) {
      preExtractedApiKey match {
        case None                                         => errorResult(Unauthorized, "Invalid API key", "errors.invalid.api.key")
        case Some(key) if key.isInvalid(key.clientSecret) => {
          sendRevokedApiKeyAlert(key)
          errorResult(Unauthorized, "Bad API key", "errors.bad.api.key")
        }
        case Some(key) if !key.matchRouting(descriptor)   => {
          errorResult(Unauthorized, "Invalid API key", "errors.bad.api.key")
        }
        case Some(key)
            if key.restrictions
              .handleRestrictions(descriptor, Some(key), req, attrs)
              ._1 => {
          key.restrictions
            .handleRestrictions(descriptor, Some(key), req, attrs)
            ._2
            .map(v => Left(v))
        }
        case Some(key) if key.isValid(key.clientSecret)   =>
          key.withinQuotasAndRotation().flatMap {
            case (true, rotationInfos) =>
              rotationInfos.foreach { i =>
                attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
              }
              callDownstream(config, Some(key), None)
            case (false, _)            =>
              errorResult(TooManyRequests, "You performed too much requests", "errors.too.much.requests")
          }
      }
    } else if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
      val clientId = authBySimpleApiKeyClientId.get
      env.datastores.apiKeyDataStore
        .findAuthorizeKeyFor(clientId, descriptor.id)
        .flatMap {
          case None                                       =>
            errorResult(Unauthorized, "Invalid API key", "errors.invalid.api.key")
          case Some(key) if !key.allowClientIdOnly        =>
            errorResult(BadRequest, "Bad API key", "errors.bad.api.key")
          case Some(key) if !key.matchRouting(descriptor) =>
            errorResult(Unauthorized, "Invalid API key", "errors.bad.api.key")
          case Some(key)
              if key.restrictions.enabled && key.restrictions
                .isNotFound(req.method, req.theDomain, req.relativeUri) => {
            errorResult(NotFound, "Not Found", "errors.not.found")
          }
          case Some(key)
              if key.restrictions
                .handleRestrictions(descriptor, Some(key), req, attrs)
                ._1 => {
            key.restrictions
              .handleRestrictions(descriptor, Some(key), req, attrs)
              ._2
              .map(v => Left(v))
          }
          case Some(key) if key.allowClientIdOnly         =>
            key.withinQuotasAndRotation().flatMap {
              case (true, rotationInfos) =>
                rotationInfos.foreach { i =>
                  attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
                }
                callDownstream(config, Some(key), None)
              case (false, _)            =>
                errorResult(TooManyRequests, "You performed too much requests", "errors.too.much.requests")
            }
        }
    } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
      val (clientId, clientSecret) = authByCustomHeaders.get
      env.datastores.apiKeyDataStore
        .findAuthorizeKeyFor(clientId, descriptor.id)
        .flatMap {
          case None                                       =>
            errorResult(Unauthorized, "Invalid API key", "errors.invalid.api.key")
          case Some(key) if key.isInvalid(clientSecret)   => {
            sendRevokedApiKeyAlert(key)
            errorResult(Unauthorized, "Bad API key", "errors.bad.api.key")
          }
          case Some(key) if !key.matchRouting(descriptor) =>
            errorResult(Unauthorized, "Bad API key", "errors.bad.api.key")
          case Some(key)
              if key.restrictions
                .handleRestrictions(descriptor, Some(key), req, attrs)
                ._1 => {
            key.restrictions
              .handleRestrictions(descriptor, Some(key), req, attrs)
              ._2
              .map(v => Left(v))
          }
          case Some(key) if key.isValid(clientSecret)     =>
            key.withinQuotasAndRotation().flatMap {
              case (true, rotationInfos) =>
                rotationInfos.foreach { i =>
                  attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
                }
                callDownstream(config, Some(key), None)
              case (false, _)            =>
                errorResult(TooManyRequests, "You performed too much requests", "errors.too.much.requests")
            }
        }
    } else if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
      val jwtTokenValue = authByJwtToken.get
      Try {
        JWT.decode(jwtTokenValue)
      } map { jwt =>
        jwt
          .claimStr("clientId")
          .orElse(jwt.claimStr("client_id"))
          .orElse(jwt.claimStr("cid"))
          .orElse(jwt.claimStr("iss")) match {
          case Some(clientId) =>
            env.datastores.apiKeyDataStore
              .findAuthorizeKeyFor(clientId, descriptor.id)
              .flatMap {
                case Some(apiKey) => {
                  val possibleKeyPairId               = apiKey.metadata.get("jwt-sign-keypair")
                  val kid                             = Option(jwt.getKeyId)
                    .orElse(possibleKeyPairId)
                    .filter(_ => descriptor.apiKeyConstraints.jwtAuth.keyPairSigned)
                    .filter(id => if (possibleKeyPairId.isDefined) possibleKeyPairId.get == id else true)
                    .flatMap(id => DynamicSSLEngineProvider.certificates.get(id))
                  val kp                              = kid.map(_.cryptoKeyPair)
                  val algorithmOpt: Option[Algorithm] = Option(jwt.getAlgorithm).collect {
                    case "HS256" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC256(apiKey.clientSecret)
                    case "HS384" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC384(apiKey.clientSecret)
                    case "HS512" if descriptor.apiKeyConstraints.jwtAuth.secretSigned =>
                      Algorithm.HMAC512(apiKey.clientSecret)
                    case "ES256" if kid.isDefined                                     =>
                      Algorithm.ECDSA256(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "ES384" if kid.isDefined                                     =>
                      Algorithm.ECDSA384(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "ES512" if kid.isDefined                                     =>
                      Algorithm.ECDSA512(
                        kp.get.getPublic.asInstanceOf[ECPublicKey],
                        kp.get.getPrivate.asInstanceOf[ECPrivateKey]
                      )
                    case "RS256" if kid.isDefined                                     =>
                      Algorithm.RSA256(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                    case "RS384" if kid.isDefined                                     =>
                      Algorithm.RSA384(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                    case "RS512" if kid.isDefined                                     =>
                      Algorithm.RSA512(
                        kp.get.getPublic.asInstanceOf[RSAPublicKey],
                        kp.get.getPrivate.asInstanceOf[RSAPrivateKey]
                      )
                  } //getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                  val exp                             =
                    Option(jwt.getClaim("exp"))
                      .filterNot(_.isNull)
                      .map(_.asLong())
                  val iat                             =
                    Option(jwt.getClaim("iat"))
                      .filterNot(_.isNull)
                      .map(_.asLong())
                  val httpPath                        = Option(jwt.getClaim("httpPath"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpVerb                        = Option(jwt.getClaim("httpVerb"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpHost                        = Option(jwt.getClaim("httpHost"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  algorithmOpt match {
                    case Some(algorithm) => {
                      val verifier =
                        JWT
                          .require(algorithm)
                          // .withIssuer(clientId)
                          .acceptLeeway(10) // TODO: customize ???
                          .build
                      Try(verifier.verify(jwtTokenValue))
                        .filter { token =>
                          val xsrfToken       = token.getClaim("xsrfToken")
                          val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                          if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                            xsrfToken.asString() == xsrfTokenHeader.get
                          } else !(!xsrfToken.isNull && xsrfTokenHeader.isEmpty)
                        }
                        .filter { _ =>
                          descriptor.apiKeyConstraints.jwtAuth.maxJwtLifespanSecs.map { maxJwtLifespanSecs =>
                            if (exp.isEmpty || iat.isEmpty) {
                              false
                            } else {
                              (exp.get - iat.get) <= maxJwtLifespanSecs
                            }
                          } getOrElse {
                            true
                          }
                        }
                        .filter { _ =>
                          if (descriptor.apiKeyConstraints.jwtAuth.includeRequestAttributes) {
                            val matchPath = httpPath.exists(_ == req.relativeUri)
                            val matchVerb =
                              httpVerb
                                .exists(_.toLowerCase == req.method.toLowerCase)
                            val matchHost =
                              httpHost.exists(_.toLowerCase == req.theHost)
                            matchPath && matchVerb && matchHost
                          } else {
                            true
                          }
                        } match {
                        case Success(_) if !apiKey.matchRouting(descriptor) =>
                          errorResult(Unauthorized, "Invalid API key", "errors.bad.api.key")
                        case Success(_)
                            if apiKey.restrictions
                              .handleRestrictions(descriptor, Some(apiKey), req, attrs)
                              ._1 => {
                          apiKey.restrictions
                            .handleRestrictions(descriptor, Some(apiKey), req, attrs)
                            ._2
                            .map(v => Left(v))
                        }
                        case Success(_)                                     =>
                          apiKey.withinQuotasAndRotation().flatMap {
                            case (true, rotationInfos) =>
                              rotationInfos.foreach { i =>
                                attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
                              }
                              callDownstream(config, Some(apiKey), None)
                            case (false, _)            =>
                              errorResult(
                                TooManyRequests,
                                "You performed too much requests",
                                "errors.too.much.requests"
                              )
                          }
                        case Failure(e)                                     => {
                          e.printStackTrace()
                          sendRevokedApiKeyAlert(apiKey)
                          errorResult(BadRequest, s"Bad API key 1", "errors.bad.api.key")
                        }
                      }
                    }
                    case None            =>
                      errorResult(Unauthorized, "Invalid ApiKey provided", "errors.invalid.api.key")
                  }
                }
                case None         =>
                  errorResult(Unauthorized, "Invalid ApiKey provided", "errors.invalid.api.key")
              }
          case None           =>
            errorResult(Unauthorized, "Invalid ApiKey provided", "errors.invalid.api.key")
        }
      } getOrElse errorResult(Unauthorized, s"Invalid ApiKey provided", "errors.invalid.api.key")
    } else if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
      val auth   = authBasic.get
      val id     = auth.split(":").headOption.map(_.trim)
      val secret = auth.split(":").lastOption.map(_.trim)
      (id, secret) match {
        case (Some(apiKeyClientId), Some(apiKeySecret)) => {
          env.datastores.apiKeyDataStore
            .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
            .flatMap {
              case None                                       =>
                errorResult(Unauthorized, "Invalid API key", "errors.invalid.api.key")
              case Some(key) if key.isInvalid(apiKeySecret)   => {
                sendRevokedApiKeyAlert(key)
                errorResult(Unauthorized, "Bad API key", "errors.bad.api.key")
              }
              case Some(key) if !key.matchRouting(descriptor) =>
                errorResult(Unauthorized, "Invalid API key", "errors.bad.api.key")
              case Some(key)
                  if key.restrictions
                    .handleRestrictions(descriptor, Some(key), req, attrs)
                    ._1 => {
                key.restrictions
                  .handleRestrictions(descriptor, Some(key), req, attrs)
                  ._2
                  .map(v => Left(v))
              }
              case Some(key) if key.isValid(apiKeySecret)     =>
                key.withinQuotasAndRotation().flatMap {
                  case (true, rotationInfos) =>
                    rotationInfos.foreach { i =>
                      attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
                    }
                    callDownstream(config, Some(key), None)
                  case (false, _)            =>
                    errorResult(TooManyRequests, "You performed too much requests", "errors.too.much.requests")
                }
            }
        }
        case _                                          =>
          errorResult(BadRequest, "No ApiKey provided", "errors.no.api.key")
      }
    } else {
      errorResult(BadRequest, "No ApiKey provided", "errors.no.api.key")
    }
    //}
  }
}
