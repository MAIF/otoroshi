package otoroshi.next.plugins

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.auth.{AuthModuleConfig, ErrorReason, GenericOauth2Module, GenericOauth2ModuleConfig, OAuth2ModuleConfig}
import otoroshi.cluster.{Cluster, ClusterMode}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models._
import otoroshi.next.plugins.api._
import otoroshi.plugins.oidc.{OIDCThirdPartyApiKeyConfig, ThirdPartyApiKeyConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class OIDCProfileHeader(send: Boolean = false, headerName: String = "X-OIDC-User")
case class OIDCIDTokenHeader(
    send: Boolean = false,
    name: String = "id_token",
    headerName: String = "X-OIDC-Id-Token",
    jwt: Boolean = true
)
case class OIDCAccessTokenHeader(
    send: Boolean = false,
    name: String = "access_token",
    headerName: String = "X-OIDC-Access-Token",
    jwt: Boolean = true
)

case class OIDCHeadersConfig(
    profile: OIDCProfileHeader = OIDCProfileHeader(),
    idtoken: OIDCIDTokenHeader = OIDCIDTokenHeader(),
    accessToken: OIDCAccessTokenHeader = OIDCAccessTokenHeader()
) extends NgPluginConfig {
  override def json: JsValue = OIDCHeadersConfig.format.writes(this)
}

object OIDCHeadersConfig {
  val format = new Format[OIDCHeadersConfig] {
    override def writes(o: OIDCHeadersConfig): JsValue = Json.obj(
      "profile"     -> Json.obj(
        "send"       -> o.profile.send,
        "headerName" -> o.profile.headerName
      ),
      "idToken"     -> Json.obj(
        "send"       -> o.idtoken.send,
        "name"       -> o.idtoken.name,
        "headerName" -> o.idtoken.headerName,
        "jwt"        -> o.idtoken.jwt
      ),
      "accessToken" -> Json.obj(
        "send"       -> o.accessToken.send,
        "name"       -> o.accessToken.name,
        "headerName" -> o.accessToken.headerName,
        "jwt"        -> o.accessToken.jwt
      )
    )

    override def reads(json: JsValue): JsResult[OIDCHeadersConfig] = Try {
      OIDCHeadersConfig(
        profile = OIDCProfileHeader(
          send = json.at("profile.send").asOpt[Boolean].getOrElse(true),
          headerName = json.at("profile.headerName").asOpt[String].getOrElse("X-OIDC-User")
        ),
        idtoken = OIDCIDTokenHeader(
          send = json.at("idToken.send").asOpt[Boolean].getOrElse(false),
          name = json.at("idToken.name").asOpt[String].getOrElse("id_token"),
          headerName = json.at("idToken.headerName").asOpt[String].getOrElse("X-OIDC-Id-Token"),
          jwt = json.at("idToken.jwt").asOpt[Boolean].getOrElse(true)
        ),
        accessToken = OIDCAccessTokenHeader(
          send = json.at("accessToken.send").asOpt[Boolean].getOrElse(false),
          name = json.at("accessToken.name").asOpt[String].getOrElse("access_token"),
          headerName = json.at("accessToken.headerName").asOpt[String].getOrElse("X-OIDC-Access-Token"),
          jwt = json.at("accessToken.jwt").asOpt[Boolean].getOrElse(true)
        )
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

class OIDCHeaders extends NgRequestTransformer {

  override def name: String                      = "OIDC headers"
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = false
  override def transformsError: Boolean          = false

  override def defaultConfigObject: Option[NgPluginConfig] = OIDCHeadersConfig().some

  override def description: Option[String] =
    "This plugin injects headers containing tokens and profile from current OIDC provider.".some

  private def extract(payload: JsValue, name: String, jwt: Boolean): String = {
    (payload \ name).asOpt[String] match {
      case None               => "--"
      case Some(value) if jwt =>
        Try(new String(org.apache.commons.codec.binary.Base64.decodeBase64(value.split("\\.")(1)))).getOrElse("--")
      case Some(value)        => value
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.user match {
      case Some(user) if user.token.asOpt[JsObject].exists(_.value.nonEmpty) =>
        val config = ctx.cachedConfig(internalName)(OIDCHeadersConfig.format).getOrElse(OIDCHeadersConfig())

        val profileMap     =
          if (config.profile.send) Map(config.profile.headerName -> Json.stringify(user.profile)) else Map.empty
        val idTokenMap     =
          if (config.idtoken.send)
            Map(config.idtoken.headerName -> extract(user.token, config.idtoken.name, config.idtoken.jwt))
          else Map.empty
        val accessTokenMap =
          if (config.accessToken.send)
            Map(config.accessToken.headerName -> extract(user.token, config.accessToken.name, config.accessToken.jwt))
          else Map.empty

        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ profileMap ++ idTokenMap ++ accessTokenMap
          )
        )
      case None                                                              => Right(ctx.otoroshiRequest)
    }
  }
}

case class OIDCAccessTokenConfig(enabled: Boolean = true, atLeastOne: Boolean = false, config: Option[JsValue] = None)
    extends NgPluginConfig {
  override def json: JsValue = OIDCAccessTokenConfig.format.writes(this)
}

object OIDCAccessTokenConfig {
  val format = new Format[OIDCAccessTokenConfig] {
    override def writes(o: OIDCAccessTokenConfig): JsValue = Json.obj(
      "enabled"    -> o.enabled,
      "atLeastOne" -> o.atLeastOne,
      "config"     -> o.config
    )

    override def reads(json: JsValue): JsResult[OIDCAccessTokenConfig] = Try {
      OIDCAccessTokenConfig(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        atLeastOne = json.select("atLeastOne").asOpt[Boolean].getOrElse(false),
        config = json.select("config").asOpt[JsValue]
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

class OIDCAccessTokenValidator extends NgAccessValidator {

  override def multiInstance: Boolean                      = true
  override def name: String                                = "OIDC access_token validator"
  override def defaultConfigObject: Option[NgPluginConfig] = OIDCAccessTokenConfig(
    config = OIDCThirdPartyApiKeyConfig(
      enabled = true,
      oidcConfigRef = "some-oidc-auth-module-id".some
    ).toJson.some
  ).some

  override def description: Option[String] =
    s"""This plugin will use the third party apikey configuration and apply it while keeping the apikey mecanism of otoroshi.
           |Use it to combine apikey validation and OIDC access_token validation. """.stripMargin.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val pluginConfiguration = ctx
      .cachedConfig(internalName)(OIDCAccessTokenConfig.format)
      .getOrElse(OIDCAccessTokenConfig())

    if (pluginConfiguration.enabled) {
      val configs: Seq[ThirdPartyApiKeyConfig] = {
        (pluginConfiguration.config match {
          case Some(r: JsObject)  => Seq(r)
          case Some(arr: JsArray) => arr.value
          case _                  => Seq.empty
        })
          .map(v => ThirdPartyApiKeyConfig.format.reads(v))
          .collect { case JsSuccess(c, _) =>
            c
          }
      }

      def checkOneConfig(config: ThirdPartyApiKeyConfig): Future[Boolean] = {
        config match {
          case a: OIDCThirdPartyApiKeyConfig =>
            val latestGlobalConfig = env.datastores.globalConfigDataStore.latest()
            val promise            = Promise[Boolean]
            a.copy(enabled = true)
              .handleGen(ctx.request, ctx.route.serviceDescriptor, latestGlobalConfig, ctx.attrs) { _ =>
                promise.trySuccess(true)
                Results.Ok("--").right.future
              }
              .andThen {
                case _ if !promise.isCompleted => promise.trySuccess(false)
              }
            promise.future
          case _                             => FastFuture.successful(true)
        }
      }

      Source(configs.toList)
        .mapAsync(1) { config =>
          checkOneConfig(config)
        }
        .runWith(Sink.seq)(env.otoroshiMaterializer)
        .map { seq =>
          if (pluginConfiguration.atLeastOne) {
            seq.contains(true)
          } else {
            !seq.contains(false)
          }
        }
        .flatMap(result => {
          if (result) {
            NgAccess.NgAllowed.vfuture
          } else {
            Errors
              .craftResponseResult(
                "bad request",
                Results.BadRequest,
                ctx.request,
                None,
                None,
                attrs = ctx.attrs
              )
              .map(NgAccess.NgDenied)
          }
        })
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}

class OIDCAccessTokenAsApikey extends NgPreRouting {

  override def name: String = "OIDC access_token as apikey"

  override def multiInstance: Boolean = true

  override def defaultConfigObject: Option[NgPluginConfig] = OIDCAccessTokenConfig(
    config = OIDCThirdPartyApiKeyConfig(
      enabled = true,
      oidcConfigRef = "some-oidc-auth-module-id".some
    ).toJson.some
  ).some

  override def description: Option[String] =
    "This plugin will use the third party apikey configuration to generate an apikey".some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val pluginConfiguration = ctx
      .cachedConfig(internalName)(OIDCAccessTokenConfig.format)
      .getOrElse(OIDCAccessTokenConfig())

    if (pluginConfiguration.enabled) {
      val configs: Seq[ThirdPartyApiKeyConfig] = {
        (pluginConfiguration.config match {
          case Some(r: JsObject)  => Seq(r)
          case Some(arr: JsArray) => arr.value
          case _                  => Seq.empty
        })
          .map(v => ThirdPartyApiKeyConfig.format.reads(v))
          .collect { case JsSuccess(c, _) =>
            c
          }
      }

      def checkOneConfig(config: ThirdPartyApiKeyConfig, ref: AtomicReference[ApiKey]): Future[Unit] = {
        config match {
          case a: OIDCThirdPartyApiKeyConfig =>
            val latestGlobalConfig = env.datastores.globalConfigDataStore.latest()
            a.copy(enabled = true)
              .handleGen(ctx.request, ctx.route.serviceDescriptor, latestGlobalConfig, ctx.attrs) { apk =>
                apk.foreach { key =>
                  if (pluginConfiguration.atLeastOne) {
                    ref.compareAndSet(null, key)
                  } else {
                    ref.set(key)
                  }
                }
                Results.Ok("--").right.future
              }
              .map(_ => ())
          case _                             => ().future
        }
      }

      val ref = new AtomicReference[ApiKey]()
      Source(configs.toList)
        .mapAsync(1) { config =>
          checkOneConfig(config, ref)
        }
        .runWith(Sink.seq)(env.otoroshiMaterializer)
        .map { _ =>
          Option(ref.get()).foreach(apk => ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apk))
          Done.right
        }
    } else {
      Done.right.vfuture
    }
  }

}

case class OIDCAuthTokenConfig(
    ref: String,
    opaque: Boolean,
    fetchUserProfile: Boolean,
    validateAudience: Boolean,
    headerName: String
) extends NgPluginConfig {
  def json: JsValue = OIDCAuthTokenConfig.format.writes(this)
}

object OIDCAuthTokenConfig {
  val default                        = OIDCAuthTokenConfig("", true, true, false, "Authorization")
  val configFlow: Seq[String]        = Seq("ref", "header_name", "opaque", "fetch_user_profile", "validate_audience")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "header_name"        -> Json.obj(
        "type"  -> "string",
        "label" -> "access_token header name"
      ),
      "opaque"             -> Json.obj(
        "type"  -> "bool",
        "label" -> "Opaque access_token"
      ),
      "fetch_user_profile" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Fetch user profile"
      ),
      "validate_audience"  -> Json.obj(
        "type"  -> "bool",
        "label" -> "Validate audience"
      ),
      "ref"                -> Json.obj(
        "type"  -> "select",
        "label" -> s"Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/api/auths",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[OIDCAuthTokenConfig] {
    override def reads(json: JsValue): JsResult[OIDCAuthTokenConfig] = Try {
      OIDCAuthTokenConfig(
        ref = json.select("ref").asString,
        opaque = json.select("opaque").asOpt[Boolean].getOrElse(false),
        fetchUserProfile = json.select("fetch_user_profile").asOpt[Boolean].getOrElse(false),
        validateAudience = json.select("validate_audience").asOpt[Boolean].getOrElse(false),
        headerName = json.select("header_name").asOpt[String].getOrElse("Authorization")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: OIDCAuthTokenConfig): JsValue             = Json.obj(
      "ref"                -> o.ref,
      "opaque"             -> o.opaque,
      "fetch_user_profile" -> o.fetchUserProfile,
      "validate_audience"  -> o.validateAudience,
      "header_name"        -> o.headerName
    )
  }
}

class OIDCAuthToken extends NgAccessValidator {

  override def multiInstance: Boolean                      = true
  override def name: String                                = "OIDC access_token authentication"
  override def defaultConfigObject: Option[NgPluginConfig] = OIDCAuthTokenConfig.default.some

  override def description: Option[String] =
    s"""This plugin will authenticate a user based on it's OIDC access_token""".stripMargin.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = OIDCAuthTokenConfig.configFlow
  override def configSchema: Option[JsObject] = OIDCAuthTokenConfig.configSchema

  private def getSession(ctx: NgAccessContext, oauth2Config: OAuth2ModuleConfig, config: OIDCAuthTokenConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ) = {

    val authModule =
      oauth2Config.authModule(env.datastores.globalConfigDataStore.latest()).asInstanceOf[GenericOauth2Module]
    val token      = ctx.request.headers.get(config.headerName).flatMap(v => v.split(" ").lastOption).getOrElse("")
    val tokenHash  = token.sha256

    def createSession(): Future[Either[Result, NgAccess]] = {
      if (config.fetchUserProfile) {
        authModule.getUserInfoSafe(token, env.datastores.globalConfigDataStore.latest()).flatMap {
          case Left(err)      =>
            Errors
              .craftResponseResult(
                "unauthorized",
                Results.Unauthorized,
                ctx.request,
                None,
                None,
                attrs = ctx.attrs
              )
              .map(v => Left(v))
          case Right(profile) =>
            val meta: Option[JsObject] = PrivateAppsUser
              .select(profile, oauth2Config.otoroshiDataField)
              .asOpt[String]
              .map(s => Json.parse(s))
              .orElse(
                Option(PrivateAppsUser.select(profile, oauth2Config.otoroshiDataField))
              )
              .map(_.asOpt[JsObject].getOrElse(Json.obj()))
            val email                  = (profile \ oauth2Config.emailField)
              .asOpt[String]
              .orElse((profile \ "sub").asOpt[String])
              .getOrElse("No email")
            PrivateAppsUser(
              randomId = tokenHash,
              name = (profile \ oauth2Config.nameField)
                .asOpt[String]
                .orElse((profile \ "sub").asOpt[String])
                .getOrElse("No Name"),
              email = email,
              profile = profile,
              token = Json.obj("access_token" -> token),
              authConfigId = oauth2Config.id,
              realm = oauth2Config.cookieSuffix(ctx.route.legacy),
              otoroshiData = oauth2Config.dataOverride
                .get(email)
                .map(v => oauth2Config.extraMetadata.deepMerge(v))
                .orElse(Some(oauth2Config.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))),
              tags = oauth2Config.theTags,
              metadata = oauth2Config.metadata,
              location = oauth2Config.location
            ).validate(
              ctx.route.legacy,
              isRoute = true,
              oauth2Config
            ).flatMap {
              case Left(err)   =>
                Errors
                  .craftResponseResult(
                    err.display,
                    Results.InternalServerError,
                    ctx.request,
                    None,
                    None,
                    attrs = ctx.attrs
                  )
                  .map(v => Left(v))
              case Right(user) =>
                user.save(
                  Duration(oauth2Config.sessionMaxAge, TimeUnit.MILLISECONDS)
                )
                if (env.clusterConfig.mode == ClusterMode.Worker) {
                  env.clusterAgent.createSession(user)
                }
                ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                Right(NgAccess.NgAllowed).vfuture
            }
        }
      } else {
        val profile                = token.split("\\.")(1).decodeBase64.parseJson
        val meta: Option[JsObject] = PrivateAppsUser
          .select(profile, oauth2Config.otoroshiDataField)
          .asOpt[String]
          .map(s => Json.parse(s))
          .orElse(
            Option(PrivateAppsUser.select(profile, oauth2Config.otoroshiDataField))
          )
          .map(_.asOpt[JsObject].getOrElse(Json.obj()))
        val email                  = (profile \ oauth2Config.emailField)
          .asOpt[String]
          .orElse((profile \ "sub").asOpt[String])
          .getOrElse("No email")
        PrivateAppsUser(
          randomId = tokenHash,
          name = (profile \ oauth2Config.nameField)
            .asOpt[String]
            .orElse((profile \ "sub").asOpt[String])
            .getOrElse("No Name"),
          email = email,
          profile = profile,
          token = Json.obj("access_token" -> token),
          authConfigId = oauth2Config.id,
          realm = oauth2Config.cookieSuffix(ctx.route.legacy),
          otoroshiData = oauth2Config.dataOverride
            .get(email)
            .map(v => oauth2Config.extraMetadata.deepMerge(v))
            .orElse(Some(oauth2Config.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))),
          tags = oauth2Config.theTags,
          metadata = oauth2Config.metadata,
          location = oauth2Config.location
        ).validate(
          ctx.route.legacy,
          isRoute = true,
          oauth2Config
        ).flatMap {
          case Left(err)   =>
            Errors
              .craftResponseResult(
                err.display,
                Results.InternalServerError,
                ctx.request,
                None,
                None,
                attrs = ctx.attrs
              )
              .map(v => Left(v))
          case Right(user) =>
            user.save(
              Duration(oauth2Config.sessionMaxAge, TimeUnit.MILLISECONDS)
            )
            if (env.clusterConfig.mode == ClusterMode.Worker) {
              env.clusterAgent.createSession(user)
            }
            ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
            Right(NgAccess.NgAllowed).vfuture
        }
      }
    }

    env.datastores.privateAppsUserDataStore
      .findById(tokenHash)
      .flatMap {
        case Some(user)                                           =>
          ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
          if (env.clusterConfig.mode == ClusterMode.Worker) {
            env.clusterAgent.createSession(user)
          }
          Right(NgAccess.NgAllowed).vfuture
        case None if env.clusterConfig.mode == ClusterMode.Worker => {
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(s"private apps session $tokenHash not found locally - from helper")
          env.clusterAgent.isSessionValid(tokenHash, Some(ctx.request)).flatMap {
            case Some(user) =>
              user.save(
                Duration(user.expiredAt.getMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
              )
              ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
              Right(NgAccess.NgAllowed).vfuture
            case None       => createSession()
          }
        }
        case None                                                 => createSession()
      }
      .flatMap { r =>
        if (config.validateAudience) {
          val profile = token.split("\\.")(1).decodeBase64.parseJson
          val aud     = profile.select("aud").asOpt[String].getOrElse("--")
          Try(Uri.apply(aud)) match {
            case Failure(e)   =>
              Errors
                .craftResponseResult(
                  "unauthorized",
                  Results.Unauthorized,
                  ctx.request,
                  None,
                  None,
                  attrs = ctx.attrs
                )
                .map(v => Left(v))
            case Success(uri) => {
              val currentUrl = s"${ctx.request.theProtocol}://${ctx.request.theDomain}${ctx.request.thePath}"
              if (currentUrl.startsWith(uri.toString())) {
                r.vfuture
              } else {
                Errors
                  .craftResponseResult(
                    "unauthorized",
                    Results.Unauthorized,
                    ctx.request,
                    None,
                    None,
                    attrs = ctx.attrs
                  )
                  .map(v => Left(v))
              }
            }
          }
        } else {
          r.vfuture
        }
      }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(OIDCAuthTokenConfig.format)
      .getOrElse(OIDCAuthTokenConfig.default)
    env.proxyState.authModule(config.ref) match {
      case None                                     => {
        Errors
          .craftResponseResult(
            "bad auth. module",
            Results.InternalServerError,
            ctx.request,
            None,
            None,
            attrs = ctx.attrs
          )
          .map(NgAccess.NgDenied)
      }
      case Some(authModuleConfig) if config.opaque  => {
        val oauth2Config = authModuleConfig.asInstanceOf[OAuth2ModuleConfig]
        getSession(ctx, oauth2Config, config).flatMap {
          case Left(err) => NgAccess.NgDenied.apply(err).vfuture
          case Right(v)  => v.vfuture
        }
      }
      case Some(authModuleConfig) if !config.opaque => {
        val oauth2Config = authModuleConfig.asInstanceOf[OAuth2ModuleConfig]
        oauth2Config.jwtVerifier match {
          case None               =>
            Errors
              .craftResponseResult(
                "bad jwt settings",
                Results.InternalServerError,
                ctx.request,
                None,
                None,
                attrs = ctx.attrs
              )
              .map(NgAccess.NgDenied)
          case Some(algoSettings) => {
            val jwtVerifier = LocalJwtVerifier(
              enabled = true,
              source = InHeader(config.headerName, "Bearer "),
              algoSettings = algoSettings
            )
            jwtVerifier
              .verifyGen(
                request = ctx.request,
                desc = ctx.route.legacy,
                apikey = ctx.apikey,
                user = ctx.user,
                elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
                attrs = ctx.attrs
              ) { _ =>
                getSession(ctx, oauth2Config, config)
              }
              .flatMap {
                case Left(err) => NgAccess.NgDenied.apply(err).vfuture
                case Right(v)  => v.vfuture
              }
          }
        }
      }
    }
  }
}
