package models

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.Env
import events.{Alerts, ApiKeySecretHasRotated, ApiKeySecretWillRotate, RevokedApiKeyUsageAlert}
import gateway.Errors
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.{BadGateway, TooManyRequests, Unauthorized}
import play.api.mvc.{RequestHeader, Result}
import security.{IdGenerator, OtoroshiClaim}
import storage.BasicStore
import utils.TypedMap

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

object ApiKeyRotation {
  val fmt = new Format[ApiKeyRotation] {
    override def writes(o: ApiKeyRotation): JsValue = Json.obj(
      "enabled"       -> o.enabled,
      "rotationEvery" -> o.rotationEvery,
      "gracePeriod"   -> o.gracePeriod,
      "nextSecret"    -> o.nextSecret.map(JsString.apply).getOrElse(JsNull).as[JsValue],
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

case class ApiKey(clientId: String = IdGenerator.token(16),
                  clientSecret: String = IdGenerator.token(64),
                  clientName: String,
                  authorizedGroup: String,
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
                  metadata: Map[String, String] = Map.empty[String, String]) {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.apiKeyDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.apiKeyDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.apiKeyDataStore.exists(this)
  def toJson                                            = ApiKey.toJson(this)
  def isValid(value: String): Boolean =
    enabled && ((value == clientSecret) || (rotation.enabled && rotation.nextSecret.contains(value)))
  def isInvalid(value: String): Boolean = !isValid(value)
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
  def withinQuotas()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.apiKeyDataStore.withingQuotas(this)
  def withinQuotasAndRotation()(implicit ec: ExecutionContext,
                                env: Env): Future[(Boolean, Option[ApiKeyRotationInfo])] = {
    for {
      within   <- env.datastores.apiKeyDataStore.withingQuotas(this)
      rotation <- env.datastores.apiKeyDataStore.keyRotation(this)
    } yield (within, rotation)
  }
  def metadataJson: JsValue = JsObject(metadata.mapValues(JsString.apply))
  def lightJson: JsObject = Json.obj(
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
      val matchOnRole: Boolean = Option(sr.apiKeyConstraints.routing.oneTagIn)
        .filter(_.nonEmpty)
        .map(tags => this.tags.findOne(tags))
        .getOrElse(true)
      val matchAllRoles: Boolean = Option(sr.apiKeyConstraints.routing.allTagsIn)
        .filter(_.nonEmpty)
        .map(tags => this.tags.findAll(tags))
        .getOrElse(true)

      val matchOneMeta: Boolean = Option(sr.apiKeyConstraints.routing.oneMetaIn.toSeq)
        .filter(_.nonEmpty)
        .map(metas => this.metadata.toSeq.findOne(metas))
        .getOrElse(true)
      val matchAllMeta: Boolean = Option(sr.apiKeyConstraints.routing.allMetaIn.toSeq)
        .filter(_.nonEmpty)
        .map(metas => this.metadata.toSeq.findAll(metas))
        .getOrElse(true)

      val matchNoneRole: Boolean = !Option(sr.apiKeyConstraints.routing.noneTagIn)
        .filter(_.nonEmpty)
        .map(tags => this.tags.findOne(tags))
        .getOrElse(false)
      val matchNoneMeta: Boolean = !Option(sr.apiKeyConstraints.routing.noneMetaIn.toSeq)
        .filter(_.nonEmpty)
        .map(metas => this.metadata.toSeq.findOne(metas))
        .getOrElse(false)

      matchOnRole && matchAllRoles && matchOneMeta && matchAllMeta && matchNoneRole && matchNoneMeta
    }
  }
}

class ServiceNotFoundException(serviceId: String)
    extends RuntimeException(s"Service descriptor with id: '$serviceId' does not exist")
class GroupNotFoundException(groupId: String)
    extends RuntimeException(s"Service group with id: '$groupId' does not exist")

object ApiKey {

  lazy val logger = Logger("otoroshi-apkikey")

  val _fmt: Format[ApiKey] = new Format[ApiKey] {
    override def writes(apk: ApiKey): JsValue = {
      val enabled = apk.validUntil match {
        case Some(date) if date.isBeforeNow => false
        case _                              => apk.enabled
      }
      Json.obj(
        "clientId"                -> apk.clientId,
        "clientSecret"            -> apk.clientSecret,
        "clientName"              -> apk.clientName,
        "authorizedGroup"         -> apk.authorizedGroup,
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
        val enabled = rawValidUntil match {
          case Some(date) if date.isBeforeNow => false
          case _                              => rawEnabled
        }
        ApiKey(
          clientId = (json \ "clientId").as[String],
          clientSecret = (json \ "clientSecret").as[String],
          clientName = (json \ "clientName").as[String],
          authorizedGroup = (json \ "authorizedGroup").as[String],
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
      authorizedGroup = groupId,
      validUntil = None
    )
  def remainingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def resetQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas]
  def updateQuotas(apiKey: ApiKey, increment: Long = 1L)(implicit ec: ExecutionContext,
                                                         env: Env): Future[RemainingQuotas]
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

  // def willBeRotatedAt(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Option[(DateTime, Long)]] = {
  //   if (apiKey.rotation.enabled) {
  //     val key = s"${env.storageRoot}:apikeys-rotation:${apiKey.clientId}"
  //     env.datastores.rawDataStore.get(key).map {
  //       case None => None
  //       case Some(body) =>
  //         val json  = Json.parse(body.utf8String)
  //         val start = new DateTime((json \ "start").as[Long])
  //         val end   = start.plusHours(apiKey.rotation.rotationEvery.toInt)
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
        case None =>
          val newApk = apiKey.copy(rotation = apiKey.rotation.copy(nextSecret = None))
          val start  = DateTime.now()
          val end    = start.plusHours(apiKey.rotation.rotationEvery.toInt)
          val res: Option[ApiKeyRotationInfo] = Some(
            ApiKeyRotationInfo(end, new org.joda.time.Period(DateTime.now(), end).toStandardSeconds.getSeconds * 1000)
          )
          env.datastores.rawDataStore
            .set(key,
                 ByteString(
                   Json.stringify(
                     Json.obj(
                       "start" -> start.toDate.getTime
                     )
                   )
                 ),
                 Some((newApk.rotation.rotationEvery * 60 * 60 * 1000) + (5 * 60 * 1000)))
            .flatMap { _ =>
              newApk.save().map(_ => res)
            }
        case Some(body) => {
          val json              = Json.parse(body.utf8String)
          val start             = new DateTime((json \ "start").as[Long])
          val end               = start.plusHours(apiKey.rotation.rotationEvery.toInt)
          val startGrace        = end.minusHours(apiKey.rotation.gracePeriod.toInt)
          val now               = DateTime.now()
          val beforeGracePeriod = now.isAfter(start) && now.isBefore(startGrace) && now.isBefore(end)
          val inGracePeriod     = now.isAfter(start) && now.isAfter(startGrace) && now.isBefore(end)
          val afterGracePeriod  = now.isAfter(start) && now.isAfter(startGrace) && now.isAfter(end)
          val res: Option[ApiKeyRotationInfo] = Some(
            ApiKeyRotationInfo(end, new org.joda.time.Period(DateTime.now(), end).toStandardSeconds.getSeconds * 1000)
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
            val newApk = apiKey.copy(clientSecret = apiKey.rotation.nextSecret.getOrElse(IdGenerator.token(64)),
                                     rotation = apiKey.rotation.copy(nextSecret = None))
            Alerts.send(
              ApiKeySecretHasRotated(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@env` = env.env,
                oldApikey = apiKey,
                apikey = newApk
              )
            )
            env.datastores.rawDataStore
              .set(key,
                   ByteString(
                     Json.stringify(
                       Json.obj(
                         "start" -> System.currentTimeMillis()
                       )
                     )
                   ),
                   Some((newApk.rotation.rotationEvery * 60 * 60 * 1000) + (5 * 60 * 1000)))
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

  import utils.RequestImplicits._

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def extractApiKey(req: RequestHeader, descriptor: ServiceDescriptor, attrs: TypedMap)(implicit ec: ExecutionContext,
                                                                       env: Env): Future[Option[ApiKey]] = {

    val authByJwtToken = req.headers
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
    val authBasic = req.headers
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
    val authByCustomHeaders = req.headers
      .get(
        descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
          .getOrElse(env.Headers.OtoroshiClientId)
      )
      .flatMap(
        id =>
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
        Option(jwt.getClaim("iss"))
          .filterNot(_.isNull)
          .map(_.asString())
          .orElse(
            Option(jwt.getClaim("clientId")).filterNot(_.isNull).map(_.asString())
          ) match {
          case Some(clientId) =>
            env.datastores.apiKeyDataStore
              .findAuthorizeKeyFor(clientId, descriptor.id)
              .flatMap {
                case Some(apiKey) => {
                  val algorithm = Option(jwt.getAlgorithm).map {
                    case "HS256" => Algorithm.HMAC256(apiKey.clientSecret)
                    case "HS512" => Algorithm.HMAC512(apiKey.clientSecret)
                  } getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                  val exp =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                  val iat =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                  val httpPath = Option(jwt.getClaim("httpPath"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpVerb = Option(jwt.getClaim("httpVerb"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val httpHost = Option(jwt.getClaim("httpHost"))
                    .filterNot(_.isNull)
                    .map(_.asString())
                  val verifier =
                    JWT.require(algorithm).withIssuer(clientId).acceptLeeway(10).build
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
                case None => FastFuture.successful(None)
              }
          case None => FastFuture.successful(None)
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
        case _ => FastFuture.successful(None)
      }
    } else {
      FastFuture.successful(None)
    }
  }

  def detectApiKey(req: RequestHeader, descriptor: ServiceDescriptor, attrs: TypedMap)(implicit env: Env): Boolean = {

    val authByJwtToken = req.headers
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
    val authBasic = req.headers
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
    val authByCustomHeaders = req.headers
      .get(
        descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
          .getOrElse(env.Headers.OtoroshiClientId)
      )
      .flatMap(
        id =>
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
}
