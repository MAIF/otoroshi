package otoroshi.plugins.oidc

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import auth.GenericOauth2ModuleConfig
import cluster.ClusterAgent
import com.auth0.jwt.JWT
import env.Env
import gateway.Errors
import models._
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.Results.TooManyRequests
import play.api.mvc.{RequestHeader, Result, Results}
import security.IdGenerator
import utils.TypedMap

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}


class OIDCHeaders extends RequestTransformer {

  override def name: String = "OIDC headers"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "OIDCHeaders" -> Json.obj(
          "profile" -> Json.obj(
            "send"       -> true,
            "headerName" -> "X-OIDC-User"
          ),
          "idtoken" -> Json.obj(
            "send"       -> false,
            "name"       -> "id_token",
            "headerName" -> "X-OIDC-Id-Token",
            "jwt"        -> true
          ),
          "accesstoken" -> Json.obj(
            "send"       -> false,
            "name"       -> "access_token",
            "headerName" -> "X-OIDC-Access-Token",
            "jwt"        -> true
          )
        )
      )
    )

  override def description: Option[String] =
    Some("""This plugin injects headers containing tokens and profile from current OIDC provider.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "OIDCHeaders": {
      |    "profile": {
      |      "send": true,
      |      "headerName": "X-OIDC-User"
      |    },
      |    "idtoken": {
      |      "send": false,
      |      "name": "id_token",
      |      "headerName": "X-OIDC-Id-Token",
      |      "jwt": true
      |    },
      |    "accesstoken": {
      |      "send": false,
      |      "name": "access_token",
      |      "headerName": "X-OIDC-Access-Token",
      |      "jwt": true
      |    }
      |  }
      |}
      |```
    """.stripMargin)

  override def configSchema: Option[JsObject] =
    Some(
      Json
        .parse(
          """{"accesstoken.headerName":{"type":"string","props":{"label":"accesstoken.headerName"}},"idtoken.headerName":{"type":"string","props":{"label":"idtoken.headerName"}},"idtoken.name":{"type":"string","props":{"label":"idtoken.name"}},"accesstoken.name":{"type":"string","props":{"label":"accesstoken.name"}},"idtoken.send":{"type":"bool","props":{"label":"idtoken.send"}},"profile.headerName":{"type":"string","props":{"label":"profile.headerName"}},"accesstoken.send":{"type":"bool","props":{"label":"accesstoken.send"}},"idtoken.jwt":{"type":"bool","props":{"label":"idtoken.jwt"}},"profile.send":{"type":"bool","props":{"label":"profile.send"}},"accesstoken.jwt":{"type":"bool","props":{"label":"accesstoken.jwt"}}}""".stripMargin
        )
        .as[JsObject]
    )

  override def configFlow: Seq[String] = Seq(
    "profile.send",
    "profile.headerName",
    "idtoken.send",
    "idtoken.name",
    "idtoken.headerName",
    "idtoken.jwt",
    "accesstoken.send",
    "accesstoken.name",
    "accesstoken.headerName",
    "accesstoken.jwt"
  )

  private def extract(payload: JsValue, name: String, jwt: Boolean): String = {
    (payload \ name).asOpt[String] match {
      case None => "--"
      case Some(value) if jwt =>
        Try(new String(org.apache.commons.codec.binary.Base64.decodeBase64(value.split("\\.")(1)))).getOrElse("--")
      case Some(value) => value
    }
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    ctx.user match {
      case Some(user) if user.token.asOpt[JsObject].exists(_.value.nonEmpty) => {

        val config = ctx.configFor("OIDCHeaders")

        val sendProfile       = (config \ "profile" \ "send").asOpt[Boolean].getOrElse(true)
        val profileHeaderName = (config \ "profile" \ "headerName").asOpt[String].getOrElse("X-OIDC-User")

        val sendIdToken       = (config \ "idtoken" \ "send").asOpt[Boolean].getOrElse(false)
        val idTokenName       = (config \ "idtoken" \ "name").asOpt[String].getOrElse("id_token")
        val idTokenHeaderName = (config \ "idtoken" \ "headerName").asOpt[String].getOrElse("X-OIDC-Id-Token")
        val idTokenJwt        = (config \ "idtoken" \ "jwt").asOpt[Boolean].getOrElse(true)

        val sendAccessToken = (config \ "accesstoken" \ "send").asOpt[Boolean].getOrElse(false)
        val accessTokenName = (config \ "accesstoken" \ "name").asOpt[String].getOrElse("access_token")
        val accessTokenHeaderName =
          (config \ "accesstoken" \ "headerName").asOpt[String].getOrElse("X-OIDC-Access-Token")
        val accessTokenJwt = (config \ "accesstoken" \ "jwt").asOpt[Boolean].getOrElse(true)

        val profileMap = if (sendProfile) Map(profileHeaderName -> Json.stringify(user.profile)) else Map.empty
        val idTokenMap =
          if (sendIdToken) Map(idTokenHeaderName -> extract(user.token, idTokenName, idTokenJwt)) else Map.empty
        val accessTokenMap =
          if (sendAccessToken) Map(accessTokenHeaderName -> extract(user.token, accessTokenName, accessTokenJwt))
          else Map.empty

        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ profileMap ++ idTokenMap ++ accessTokenMap
          )
        ).future
      }
      case None => Right(ctx.otoroshiRequest).future
    }
  }
}

class OIDCAccessTokenValidator extends AccessValidator {

  override def name: String = "OIDC access_token validator"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "OIDCAccessTokenValidator" -> Json.obj(
          "enabled" -> true,
          "config" -> basicConfig.toJson
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin will use the third party apikey configuration and apply it while keeping the apikey mecanism of otoroshi.
           |Use it to combine apikey validation and OIDC access_token validation.
           |
           |This plugin can accept the following configuration
           |
           |```json
           |{
           |  "OIDCAccessTokenValidator": {
           |    "enabled": true,
           |    "useDescriptorConfig": false,
           |    // config is optional and can be either an object config or an array of objects
           |    "config": ${basicConfig.toJson.prettify}
           |  }
           |}
           |```
         """.stripMargin
    )

  private val basicConfig = OIDCThirdPartyApiKeyConfig(
    enabled = true,
    oidcConfigRef = "some-oidc-auth-module-id".some,
  )

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val conf    = ctx.configFor("OIDCAccessTokenValidators")
    val enabled = (conf \ "enabled").asOpt[Boolean].getOrElse(false)
    // val useDescriptorConfig = (conf \ "useDescriptorConfig").asOpt[Boolean].getOrElse(false)
    if (enabled) {

      val configs: Seq[ThirdPartyApiKeyConfig] = {
        (conf \ "config").asOpt[JsValue] match {
          // case None if useDescriptorConfig => Seq(ctx.descriptor.thirdPartyApiKey)
          // case None if !useDescriptorConfig => Seq.empty
          case None => Seq.empty
          case Some(_) => {
            (conf \ "config").asOpt[JsObject].map(o => Seq(o))
              .orElse((conf \ "config").asOpt[JsArray].map(_.value))
              .map(seq => seq.map(v => ThirdPartyApiKeyConfig.format.reads(v)).collect {
                case JsSuccess(c, _) => c
              }).getOrElse(Seq.empty)
          }
        }
      }

      def checkOneConfig(config: ThirdPartyApiKeyConfig): Future[Boolean] = {
        config match {
          case a: OIDCThirdPartyApiKeyConfig =>
            val latestGlobalConfig = env.datastores.globalConfigDataStore.latest()
            val promise = Promise[Boolean]
            a.copy(enabled = true)
              .handleGen(ctx.request, ctx.descriptor, latestGlobalConfig, ctx.attrs) { _ =>
                promise.trySuccess(true)
                Results.Ok("--").right.future
              }
              .andThen {
                case _ if !promise.isCompleted => promise.trySuccess(false)
              }
            promise.future
          case _ => FastFuture.successful(true)
        }
      }

      Source(configs.toList)
        .mapAsync(1) { config =>
          checkOneConfig(config)
        }.runWith(Sink.seq)(env.otoroshiMaterializer).map { seq =>
        !seq.contains(false)
      }
    } else {
      FastFuture.successful(true)
    }
  }
}

class OIDCAccessTokenAsApikey extends PreRouting {

  override def name: String = "OIDC access_token as apikey"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "OIDCAccessTokenAsApikey" -> Json.obj(
          "enabled" -> true,
          "config" -> basicConfig.toJson
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin will use the third party apikey configuration to generate an apikey
         |
         |This plugin can accept the following configuration
         |
         |```json
         |{
         |  "OIDCAccessTokenValidator": {
         |    "enabled": true,
         |    "useDescriptorConfig": false,
         |    // config is optional and can be either an object config or an array of objects
         |    "config": ${basicConfig.toJson.prettify}
         |  }
         |}
         |```
         """.stripMargin
    )

  private val basicConfig = OIDCThirdPartyApiKeyConfig(
    enabled = true,
    oidcConfigRef = "some-oidc-auth-module-id".some,
  )

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf    = ctx.configFor("OIDCAccessTokenAsApikey")
    val enabled = (conf \ "enabled").asOpt[Boolean].getOrElse(false)
    if (enabled) {

      val configs: Seq[ThirdPartyApiKeyConfig] = {
        (conf \ "config").asOpt[JsValue] match {
          case None => Seq.empty
          case Some(_) => {
            (conf \ "config").asOpt[JsObject].map(o => Seq(o))
              .orElse((conf \ "config").asOpt[JsArray].map(_.value))
              .map(seq => seq.map(v => ThirdPartyApiKeyConfig.format.reads(v)).collect {
                case JsSuccess(c, _) => c
              }).getOrElse(Seq.empty)
          }
        }
      }

      def checkOneConfig(config: ThirdPartyApiKeyConfig, ref: AtomicReference[ApiKey]): Future[Unit] = {
        config match {
          case a: OIDCThirdPartyApiKeyConfig =>
            val latestGlobalConfig = env.datastores.globalConfigDataStore.latest()
            a.copy(enabled = true)
              .handleGen(ctx.request, ctx.descriptor, latestGlobalConfig, ctx.attrs) { apk =>
                apk.foreach(a => ref.set(a))
                Results.Ok("--").right.future
              }.map(_ => ())
          case _ => ().future
        }
      }

      val ref = new AtomicReference[ApiKey]()
      Source(configs.toList)
        .mapAsync(1) { config =>
          checkOneConfig(config, ref)
        }.runWith(Sink.seq)(env.otoroshiMaterializer).map { seq =>
        Option(ref.get()).foreach(apk => ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apk))
        ()
      }
    } else {
      ().future
    }
  }
}

sealed trait ThirdPartyApiKeyConfigType {
  def name: String
}

object ThirdPartyApiKeyConfigType {
  case object OIDC extends ThirdPartyApiKeyConfigType {
    def name: String = "OIDC"
  }
}

sealed trait ThirdPartyApiKeyConfig {
  def enabled: Boolean
  def typ: ThirdPartyApiKeyConfigType
  def toJson: JsValue
  def handleGen[A](req: RequestHeader, descriptor: ServiceDescriptor, config: GlobalConfig, attrs: TypedMap)(
    f: Option[ApiKey] => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]]
}

sealed trait OIDCThirdPartyApiKeyConfigMode {
  def name: String
}

object OIDCThirdPartyApiKeyConfigMode {
  case object Tmp extends OIDCThirdPartyApiKeyConfigMode {
    def name: String = "Tmp"
  }
  case object Hybrid extends OIDCThirdPartyApiKeyConfigMode {
    def name: String = "Hybrid"
  }
  case object Persistent extends OIDCThirdPartyApiKeyConfigMode {
    def name: String = "Persistent"
  }
  def apply(str: String): Option[OIDCThirdPartyApiKeyConfigMode] = {
    str match {
      case "Tmp"        => Some(Tmp)
      case "tmp"        => Some(Tmp)
      case "Hybrid"     => Some(Hybrid)
      case "hybrid"     => Some(Hybrid)
      case "Persistent" => Some(Persistent)
      case "persistent" => Some(Persistent)
      case _            => None
    }
  }
}

case class OIDCThirdPartyApiKeyConfig(
  enabled: Boolean = false,
  oidcConfigRef: Option[String],
  localVerificationOnly: Boolean = false,
  ttl: Long = 0,
  headerName: String = "Authorization",
  quotasEnabled: Boolean = true,
  uniqueApiKey: Boolean = false,
  throttlingQuota: Long = 100L,
  dailyQuota: Long = RemainingQuotas.MaxValue,
  monthlyQuota: Long = RemainingQuotas.MaxValue,
  excludedPatterns: Seq[String] = Seq.empty,
  mode: OIDCThirdPartyApiKeyConfigMode = OIDCThirdPartyApiKeyConfigMode.Tmp,
  scopes: Seq[String] = Seq.empty,
  roles: Seq[String] = Seq.empty,
  rolesPath: Seq[String] = Seq.empty,
) extends ThirdPartyApiKeyConfig {

  import utils.http.Implicits._

  import org.apache.commons.codec.binary.{Base64 => ApacheBase64}

  def typ: ThirdPartyApiKeyConfigType = ThirdPartyApiKeyConfigType.OIDC

  def toJson: JsValue = OIDCThirdPartyApiKeyConfig.format.writes(this)

  private def findAt(json: JsValue, path: String): Option[JsValue] = {
    val parts                                = path.split("\\.").toSeq
    def tail(rest: Seq[String]): Seq[String] = if (rest.isEmpty) Seq.empty[String] else rest.tail
    def navTo(value: JsValue, field: Option[String], rest: Seq[String]): Option[JsValue] = {
      field match {
        case None => Some(value)
        case Some(f) => {
          (value \ f).asOpt[JsValue] match {
            case None      => None
            case Some(doc) => navTo(doc, rest.headOption, tail(rest))
          }
        }
      }
    }
    navTo(json, parts.headOption, tail(parts))
  }

  def handleGen[A](req: RequestHeader, descriptor: ServiceDescriptor, config: GlobalConfig, attrs: TypedMap)(
    f: Option[ApiKey] => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    handleInternal(req, descriptor, config, attrs)(f).map {
      case Left(badResult)   => Left[Result, A](badResult)
      case Right(goodResult) => goodResult
    }
  }

  private def shouldBeVerified(path: String): Boolean =
    !excludedPatterns.exists(p => utils.RegexPool.regex(p).matches(path))

  private def handleInternal[A](req: RequestHeader,
                                descriptor: ServiceDescriptor,
                                config: GlobalConfig,
                                attrs: TypedMap)(
                                 f: Option[ApiKey] => Future[A]
                               )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    shouldBeVerified(req.path) match {
      case false => f(None).fright[Result]
      case true => {
        oidcConfigRef match {
          case None =>
            Errors
              .craftResponseResult(
                message = "No OIDC configuration ref found",
                status = Results.InternalServerError,
                req = req,
                maybeDescriptor = Some(descriptor),
                maybeCauseId = Some("oidc.no.config.ref.found"),
                attrs = attrs
              ).fleft[A]
          case Some(ref) =>
            env.datastores.authConfigsDataStore.findById(ref).flatMap {
              case None =>
                Errors
                  .craftResponseResult(
                    message = "No OIDC configuration found",
                    status = Results.InternalServerError,
                    req = req,
                    maybeDescriptor = Some(descriptor),
                    maybeCauseId = Some("oidc.no.config.found"),
                    attrs = attrs
                  ).fleft[A]
              case Some(auth) => {
                val oidcAuth = auth.asInstanceOf[GenericOauth2ModuleConfig]
                oidcAuth.jwtVerifier match {
                  case None =>
                    Errors
                      .craftResponseResult(
                        message = "No JWT verifier found",
                        status = Results.BadRequest,
                        req = req,
                        maybeDescriptor = Some(descriptor),
                        maybeCauseId = Some("oidc.no.jwt.verifier.found"),
                        attrs = attrs
                      ).fleft[A]
                  case Some(jwtVerifier) =>
                    req.headers.get(headerName) match {
                      case None =>
                        Errors
                          .craftResponseResult(
                            message = "No bearer header found",
                            status = Results.BadRequest,
                            req = req,
                            maybeDescriptor = Some(descriptor),
                            maybeCauseId = Some("oidc.no.bearer.found"),
                            attrs = attrs
                          ).fleft[A]
                      case Some(rawHeader) => {
                        val header = rawHeader.replace("Bearer ", "").replace("bearer ", "").trim()
                        val tokenHeader =
                          Try(Json.parse(ApacheBase64.decodeBase64(header.split("\\.")(0)))).getOrElse(Json.obj())
                        val tokenBody =
                          Try(Json.parse(ApacheBase64.decodeBase64(header.split("\\.")(1)))).getOrElse(Json.obj())
                        val kid = (tokenHeader \ "kid").asOpt[String].filterNot(_.trim.isEmpty)
                        val alg = (tokenHeader \ "alg").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("RS256")
                        jwtVerifier.asAlgorithmF(InputMode(alg, kid)) flatMap {
                          case None =>
                            Errors
                              .craftResponseResult(
                                "Bad input algorithm",
                                Results.BadRequest,
                                req,
                                Some(descriptor),
                                maybeCauseId = Some("oidc.bad.input.algorithm.name"),
                                attrs = attrs
                              ).fleft[A]
                          case Some(algorithm) => {
                            val verifier = JWT.require(algorithm).acceptLeeway(10).build()
                            Try(verifier.verify(header)) match {
                              case Failure(e) =>
                                Errors
                                  .craftResponseResult(
                                    "Bad token",
                                    Results.Unauthorized,
                                    req,
                                    Some(descriptor),
                                    maybeCauseId = Some("oidc.bad.token"),
                                    attrs = attrs
                                  ).fleft[A]
                              case Success(_) => {
                                val iss     = (tokenBody \ "iss").as[String]
                                val subject = (tokenBody \ "sub").as[String]
                                val possibleMoreMeta: Seq[(String, String)] = (tokenBody \ oidcAuth.apiKeyMetaField)
                                  .asOpt[JsObject]
                                  .getOrElse(Json.obj())
                                  .value
                                  .toSeq
                                  .collect {
                                    case (key, JsString(str))     => (key, str)
                                    case (key, JsNumber(nbr))     => (key, nbr.toString())
                                    case (key, JsBoolean(b))      => (key, b.toString())
                                    case (key, arr @ JsArray(_))  => (key, Json.stringify(arr))
                                    case (key, obj @ JsObject(_)) => (key, Json.stringify(obj))
                                    case (key, JsNull)            => (key, "null")
                                  }
                                val possibleMoreTags: Seq[String] = (tokenBody \ oidcAuth.apiKeyTagsField)
                                  .asOpt[JsArray]
                                  .getOrElse(JsArray())
                                  .value
                                  .collect {
                                    case JsString(str)     => str
                                    case JsNumber(nbr)     => nbr.toString()
                                    case JsBoolean(b)      => b.toString()
                                    case arr @ JsArray(_)  => Json.stringify(arr)
                                    case obj @ JsObject(_) => Json.stringify(obj)
                                    case JsNull            => "null"
                                  }
                                val _apiKey = ApiKey(
                                  clientId = uniqueApiKey match {
                                    case true  => s"${descriptor.groupId}-${descriptor.id}-${oidcAuth.id}"
                                    case false => s"${descriptor.groupId}-${descriptor.id}-${oidcAuth.id}-${subject}"
                                  },
                                  clientSecret = IdGenerator.token(128),
                                  clientName =
                                    s"Temporary apikey from ${oidcAuth.name} for $subject on ${descriptor.name}",
                                  authorizedGroup = descriptor.groupId,
                                  enabled = false,
                                  readOnly = false,
                                  allowClientIdOnly = true,
                                  throttlingQuota = throttlingQuota,
                                  dailyQuota = dailyQuota,
                                  monthlyQuota = monthlyQuota,
                                  validUntil = None,
                                  tags = possibleMoreTags,
                                  metadata = Map(
                                    "type"     -> "Auto generated apikey corresponding to an OIDC JWT token. Please do not enable it !",
                                    "iss"      -> iss,
                                    "sub"      -> subject,
                                    "desc"     -> descriptor.id,
                                    "descName" -> descriptor.name,
                                    "auth"     -> oidcAuth.id,
                                    "authName" -> oidcAuth.name
                                  ) ++ possibleMoreMeta
                                )
                                val tokenScopes = (tokenBody \ "scope")
                                  .asOpt[String]
                                  .filterNot(_.trim.isEmpty)
                                  .map(_.split(" ").toSeq)
                                  .getOrElse(Seq.empty[String])
                                val tokenRoles: Seq[String] = rolesPath
                                  .flatMap(p => findAt(tokenBody, p))
                                  .collect {
                                    case JsString(str) => Seq(str)
                                    case JsArray(v)    => v.flatMap(_.asOpt[String].filterNot(_.trim.isEmpty))
                                  }
                                  .flatten
                                if (tokenScopes.intersect(scopes) == scopes && tokenRoles.intersect(roles) == roles) {
                                  (mode match {
                                    case OIDCThirdPartyApiKeyConfigMode.Tmp => FastFuture.successful(_apiKey)
                                    case OIDCThirdPartyApiKeyConfigMode.Hybrid =>
                                      env.datastores.apiKeyDataStore.findById(_apiKey.clientId).map {
                                        case Some(apk) => apk
                                        case None      => _apiKey
                                      }
                                    case OIDCThirdPartyApiKeyConfigMode.Persistent =>
                                      env.datastores.apiKeyDataStore.findById(_apiKey.clientId).flatMap {
                                        case Some(apk) => FastFuture.successful(apk)
                                        case None =>
                                          if (env.clusterConfig.mode.isWorker) {
                                            ClusterAgent.clusterSaveApikey(env, _apiKey)
                                          }
                                          _apiKey.save().map { _ =>
                                            _apiKey
                                          }
                                      }
                                  }) flatMap { apiKey =>
                                    (quotasEnabled match {
                                      case true  => apiKey.withinQuotasAndRotation()
                                      case false => FastFuture.successful(true)
                                    }).flatMap {
                                      case true => {
                                        if (localVerificationOnly) {
                                          f(Some(apiKey)).fright[Result]
                                        } else {
                                          OIDCThirdPartyApiKeyConfig.cache.get(apiKey.clientId) match {
                                            case Some((stop, active)) if stop > System.currentTimeMillis() => {
                                              if (active) {
                                                f(Some(apiKey)).fright[Result]
                                              } else {
                                                Errors
                                                  .craftResponseResult(
                                                    "Invalid api key",
                                                    Results.Unauthorized,
                                                    req,
                                                    Some(descriptor),
                                                    maybeCauseId = Some("oidc.invalid.token"),
                                                    attrs = attrs
                                                  ).fleft[A]
                                              }
                                            }
                                            case _ => {
                                              val clientSecret = Option(oidcAuth.clientSecret).filterNot(_.trim.isEmpty)
                                              val builder =
                                                env.MtlsWs.url(oidcAuth.introspectionUrl, oidcAuth.mtlsConfig)
                                              val future1 = if (oidcAuth.useJson) {
                                                builder.post(
                                                  Json.obj(
                                                    "token"     -> header,
                                                    "client_id" -> oidcAuth.clientId
                                                  ) ++ clientSecret
                                                    .map(s => Json.obj("client_secret" -> s))
                                                    .getOrElse(Json.obj())
                                                )
                                              } else {
                                                builder.post(
                                                  Map(
                                                    "token"     -> header,
                                                    "client_id" -> oidcAuth.clientId
                                                  ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
                                                )(writeableOf_urlEncodedSimpleForm)
                                              }
                                              future1
                                                .flatMap { resp =>
                                                  val active = (resp.json \ "active").asOpt[Boolean].getOrElse(false)
                                                  OIDCThirdPartyApiKeyConfig.cache
                                                    .put(apiKey.clientId, (System.currentTimeMillis() + ttl, active))
                                                  if (active) {
                                                    f(Some(apiKey)).fright[Result]
                                                  } else {
                                                    Errors
                                                      .craftResponseResult(
                                                        "Invalid api key",
                                                        Results.Unauthorized,
                                                        req,
                                                        Some(descriptor),
                                                        maybeCauseId = Some("oidc.invalid.token"),
                                                        attrs = attrs
                                                      ).fleft[A]
                                                  }
                                                }
                                            }
                                          }
                                        }
                                      }
                                      case false =>
                                        Errors
                                          .craftResponseResult(
                                            "You performed too much requests",
                                            TooManyRequests,
                                            req,
                                            Some(descriptor),
                                            Some("errors.too.much.requests"),
                                            attrs = attrs
                                          ).fleft[A]
                                    }
                                  }
                                } else {
                                  Errors
                                    .craftResponseResult(
                                      "Invalid token",
                                      Results.Unauthorized,
                                      req,
                                      Some(descriptor),
                                      maybeCauseId = Some("oidc.invalid.token"),
                                      attrs = attrs
                                    ).fleft[A]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                }
              }
            }
        }
      }
    }
  }
}

object OIDCThirdPartyApiKeyConfig {

  lazy val logger = Logger("otoroshi-oidc-apikey-config")

  val cache: TrieMap[String, (Long, Boolean)] = new TrieMap[String, (Long, Boolean)]()

  implicit val format = new Format[OIDCThirdPartyApiKeyConfig] {

    override def reads(json: JsValue): JsResult[OIDCThirdPartyApiKeyConfig] =
      Try {
        OIDCThirdPartyApiKeyConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          quotasEnabled = (json \ "quotasEnabled").asOpt[Boolean].getOrElse(true),
          uniqueApiKey = (json \ "uniqueApiKey").asOpt[Boolean].getOrElse(false),
          oidcConfigRef = (json \ "oidcConfigRef").asOpt[String].filterNot(_.isEmpty),
          localVerificationOnly = (json \ "localVerificationOnly").asOpt[Boolean].getOrElse(false),
          mode = (json \ "mode")
            .asOpt[String]
            .flatMap(v => OIDCThirdPartyApiKeyConfigMode(v))
            .getOrElse(OIDCThirdPartyApiKeyConfigMode.Tmp),
          ttl = (json \ "ttl").asOpt[Long].getOrElse(0L),
          headerName = (json \ "headerName").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("Authorization"),
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(100L),
          dailyQuota = (json \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          monthlyQuota = (json \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          scopes = (json \ "scopes").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          rolesPath = (json \ "rolesPath").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          roles = (json \ "roles").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading OIDCThirdPartyApiKeyConfig", t)
          JsError(t.getMessage)
      } get

    override def writes(o: OIDCThirdPartyApiKeyConfig): JsValue = Json.obj(
      "enabled"               -> o.enabled,
      "quotasEnabled"         -> o.quotasEnabled,
      "uniqueApiKey"          -> o.uniqueApiKey,
      "type"                  -> o.typ.name,
      "oidcConfigRef"         -> o.oidcConfigRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "localVerificationOnly" -> o.localVerificationOnly,
      "mode"                  -> o.mode.name,
      "ttl"                   -> o.ttl,
      "headerName"            -> o.headerName,
      "throttlingQuota"       -> o.throttlingQuota,
      "dailyQuota"            -> o.dailyQuota,
      "monthlyQuota"          -> o.monthlyQuota,
      "excludedPatterns"      -> JsArray(o.excludedPatterns.map(JsString.apply)),
      "scopes"                -> JsArray(o.scopes.map(JsString.apply)),
      "rolesPath"             -> JsArray(o.rolesPath.map(JsString.apply)),
      "roles"                 -> JsArray(o.roles.map(JsString.apply))
    )
  }
}

object ThirdPartyApiKeyConfig {

  implicit val format = new Format[ThirdPartyApiKeyConfig] {

    override def reads(json: JsValue): JsResult[ThirdPartyApiKeyConfig] =
      Try {
        (json \ "type").as[String] match {
          case "OIDC" => OIDCThirdPartyApiKeyConfig.format.reads(json)
        }
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: ThirdPartyApiKeyConfig): JsValue = o.toJson
  }
}