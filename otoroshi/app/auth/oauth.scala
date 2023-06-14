package otoroshi.auth

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.joda.time.DateTime
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.models.{TeamAccess, TenantAccess, UserRight, UserRights, _}
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.http.MtlsConfig
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSProxyServer, WSResponse}
import play.api.mvc.Results.Redirect
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object GenericOauth2ModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-global-oauth2-config")

  val _fmt = new Format[GenericOauth2ModuleConfig] {

    override def reads(json: JsValue) =
      fromJson(json) match {
        case Left(e)  => JsError(e.getMessage)
        case Right(v) => JsSuccess(v.asInstanceOf[GenericOauth2ModuleConfig])
      }

    override def writes(o: GenericOauth2ModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        GenericOauth2ModuleConfig(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          userValidators = (json \ "userValidators")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
            .getOrElse(Seq.empty),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
          clientId = (json \ "clientId").asOpt[String].getOrElse("client"),
          clientSecret = (json \ "clientSecret").asOpt[String].getOrElse("secret"),
          authorizeUrl = (json \ "authorizeUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/authorize"),
          tokenUrl = (json \ "tokenUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/token"),
          userInfoUrl = (json \ "userInfoUrl").asOpt[String].getOrElse("http://localhost:8082/userinfo"),
          introspectionUrl =
            (json \ "introspectionUrl").asOpt[String].getOrElse("http://localhost:8082/token/introspect"),
          loginUrl = (json \ "loginUrl").asOpt[String].getOrElse("http://localhost:8082/login"),
          logoutUrl = (json \ "logoutUrl").asOpt[String].getOrElse("http://localhost:8082/logout"),
          accessTokenField = (json \ "accessTokenField").asOpt[String].getOrElse("access_token"),
          nameField = (json \ "nameField").asOpt[String].getOrElse("name"),
          emailField = (json \ "emailField").asOpt[String].getOrElse("email"),
          apiKeyMetaField = (json \ "apiKeyMetaField").asOpt[String].getOrElse("apkMeta"),
          apiKeyTagsField = (json \ "apiKeyTagsField").asOpt[String].getOrElse("apkTags"),
          scope = (json \ "scope").asOpt[String].getOrElse("openid profile email name"),
          claims = (json \ "claims").asOpt[String].getOrElse("email name"),
          refreshTokens = (json \ "refreshTokens").asOpt[Boolean].getOrElse(false),
          useJson = (json \ "useJson").asOpt[Boolean].getOrElse(false),
          pkce = (json \ "pkce").asOpt[PKCEConfig](PKCEConfig._fmt.reads),
          noWildcardRedirectURI = (json \ "noWildcardRedirectURI").asOpt[Boolean].getOrElse(false),
          useCookie = (json \ "useCookie").asOpt[Boolean].getOrElse(false),
          readProfileFromToken = (json \ "readProfileFromToken").asOpt[Boolean].getOrElse(false),
          jwtVerifier = (json \ "jwtVerifier").asOpt[JsValue].flatMap(v => AlgoSettings.fromJson(v).toOption),
          otoroshiDataField = (json \ "otoroshiDataField").asOpt[String].getOrElse("app_metadata | otoroshi_data"),
          callbackUrl = (json \ "callbackUrl")
            .asOpt[String]
            .getOrElse("http://privateapps.oto.tools:8080/privateapps/generic/callback"),
          oidConfig = (json \ "oidConfig").asOpt[String],
          proxy = (json \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
          extraMetadata = (json \ "extraMetadata").asOpt[JsObject].getOrElse(Json.obj()),
          mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue]),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
          superAdmins = (json \ "superAdmins").asOpt[Boolean].getOrElse(true), // for backward compatibility reasons
          rightsOverride = (json \ "rightsOverride")
            .asOpt[Map[String, JsArray]]
            .map(_.mapValues(UserRights.readFromArray))
            .getOrElse(Map.empty),
          dataOverride = (json \ "dataOverride").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
          otoroshiRightsField = (json \ "otoroshiRightsField").asOpt[String].getOrElse("otoroshi_rights")
        )
      )
    } recover { case e =>
      Left(e)
    } get
}

case class PKCEConfig(enabled: Boolean = false, algorithm: String = "S256") extends AsJson {
  def asJson: JsValue = {
    Json.obj(
      "enabled"   -> enabled,
      "algorithm" -> algorithm
    )
  }
}

object PKCEConfig {
  val _fmt: Format[PKCEConfig] = new Format[PKCEConfig] {
    override def reads(json: JsValue): JsResult[PKCEConfig] =
      JsSuccess(
        PKCEConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          algorithm = (json \ "algorithm").asOpt[String].getOrElse("S256")
        )
      )
    override def writes(o: PKCEConfig): JsValue             = o.asJson
  }
}

case class GenericOauth2ModuleConfig(
    id: String,
    name: String,
    desc: String,
    clientSideSessionEnabled: Boolean,
    sessionMaxAge: Int = 86400,
    userValidators: Seq[JsonPathValidator] = Seq.empty,
    clientId: String = "client",
    clientSecret: String = "secret",
    tokenUrl: String = "http://localhost:8082/oauth/token",
    authorizeUrl: String = "http://localhost:8082/oauth/authorize",
    userInfoUrl: String = "http://localhost:8082/userinfo",
    introspectionUrl: String = "http://localhost:8082/token/introspect",
    loginUrl: String = "http://localhost:8082/login",
    logoutUrl: String = "http://localhost:8082/logout",
    scope: String = "openid profile email name",
    claims: String = "email name",
    useCookie: Boolean = false,
    useJson: Boolean = false,
    pkce: Option[PKCEConfig] = None,
    noWildcardRedirectURI: Boolean = false,
    readProfileFromToken: Boolean = false,
    jwtVerifier: Option[AlgoSettings] = None,
    accessTokenField: String = "access_token",
    nameField: String = "name",
    emailField: String = "email",
    apiKeyMetaField: String = "apkMeta",
    apiKeyTagsField: String = "apkTags",
    otoroshiDataField: String = "app_metadata|otoroshi_data",
    callbackUrl: String = "http://privateapps.oto.tools:8080/privateapps/generic/callback",
    oidConfig: Option[String] = None,
    proxy: Option[WSProxyServer] = None,
    extraMetadata: JsObject = Json.obj(),
    mtlsConfig: MtlsConfig = MtlsConfig(),
    refreshTokens: Boolean = false,
    tags: Seq[String],
    metadata: Map[String, String],
    sessionCookieValues: SessionCookieValues,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    superAdmins: Boolean = false,
    rightsOverride: Map[String, UserRights] = Map.empty,
    dataOverride: Map[String, JsObject] = Map.empty,
    otoroshiRightsField: String = "otoroshi_rights"
) extends OAuth2ModuleConfig {
  def theDescription: String                                            = desc
  def theMetadata: Map[String, String]                                  = metadata
  def theName: String                                                   = name
  def theTags: Seq[String]                                              = tags
  def `type`: String                                                    = "oauth2"
  def humanName: String                                                 = "OAuth2 / OIDC provider"
  override def authModule(config: GlobalConfig): AuthModule             = GenericOauth2Module(this)
  override def withLocation(location: EntityLocation): AuthModuleConfig = copy(location = location)
  override def _fmt()(implicit env: Env): Format[AuthModuleConfig]      = AuthModuleConfig._fmt(env)
  override def form: Option[Form]                                       = None
  override def asJson                                                   =
    location.jsonWithKey ++ Json.obj(
      "type"                     -> "oauth2",
      "id"                       -> this.id,
      "name"                     -> this.name,
      "desc"                     -> this.desc,
      "clientSideSessionEnabled" -> this.clientSideSessionEnabled,
      "sessionMaxAge"            -> this.sessionMaxAge,
      "userValidators"           -> JsArray(userValidators.map(_.json)),
      "clientId"                 -> this.clientId,
      "clientSecret"             -> this.clientSecret,
      "authorizeUrl"             -> this.authorizeUrl,
      "tokenUrl"                 -> this.tokenUrl,
      "userInfoUrl"              -> this.userInfoUrl,
      "introspectionUrl"         -> this.introspectionUrl,
      "loginUrl"                 -> this.loginUrl,
      "logoutUrl"                -> this.logoutUrl,
      "scope"                    -> this.scope,
      "claims"                   -> this.claims,
      "useCookie"                -> this.useCookie,
      "useJson"                  -> this.useJson,
      "pkce"                     -> this.pkce.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "noWildcardRedirectURI"    -> this.noWildcardRedirectURI,
      "readProfileFromToken"     -> this.readProfileFromToken,
      "accessTokenField"         -> this.accessTokenField,
      "jwtVerifier"              -> jwtVerifier.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "nameField"                -> this.nameField,
      "emailField"               -> this.emailField,
      "apiKeyMetaField"          -> this.apiKeyMetaField,
      "apiKeyTagsField"          -> this.apiKeyTagsField,
      "otoroshiDataField"        -> this.otoroshiDataField,
      "callbackUrl"              -> this.callbackUrl,
      "oidConfig"                -> this.oidConfig.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "mtlsConfig"               -> this.mtlsConfig.json,
      "proxy"                    -> WSProxyServerJson.maybeProxyToJson(this.proxy),
      "extraMetadata"            -> this.extraMetadata,
      "metadata"                 -> this.metadata,
      "tags"                     -> JsArray(tags.map(JsString.apply)),
      "refreshTokens"            -> this.refreshTokens,
      "sessionCookieValues"      -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "superAdmins"              -> superAdmins,
      "rightsOverride"           -> JsObject(rightsOverride.mapValues(_.json)),
      "dataOverride"             -> JsObject(dataOverride),
      "otoroshiRightsField"      -> this.otoroshiRightsField
    )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean]  = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                    = s"global-oauth-$id"
}

case class GenericOauth2Module(authConfig: OAuth2ModuleConfig) extends AuthModule {

  lazy val logger = Logger("otoroshi-global-oauth2-module")

  import otoroshi.utils.http.Implicits._
  import otoroshi.utils.syntax.implicits._
  import play.api.libs.ws.DefaultBodyWritables._

  def this() = this(GenericOauth2Module.defaultConfig)

  private def encryptState(signingObject: JsValue)(implicit env: Env) = {
    val cipher: Cipher = Cipher.getInstance("AES")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      new SecretKeySpec(env.otoroshiSecret.padTo(16, "0").mkString("").take(16).getBytes, "AES")
    )
    val bytes          = cipher.doFinal(Json.stringify(signingObject).getBytes)
    java.util.Base64.getUrlEncoder.encodeToString(bytes)
  }

  override def paLoginPage(
      request: RequestHeader,
      config: GlobalConfig,
      descriptor: ServiceDescriptor,
      isRoute: Boolean
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = authConfig.scope // "openid profile email name"
    val claims       = Option(authConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")
    val queryParam   = if (authConfig.useCookie) "" else s"?desc=${descriptor.id}"
    val hash         = env.sign(s"${authConfig.id}:::${descriptor.id}")
    val redirectUri  =
      if (authConfig.noWildcardRedirectURI) authConfig.callbackUrl
      else
        (authConfig.callbackUrl + queryParam).applyOn {
          case url if !authConfig.useCookie && url.contains("?")  => url + s"&hash=$hash"
          case url if !authConfig.useCookie && !url.contains("?") => url + s"?hash=$hash"
          case url                                                => url
        }

    val state =
      if (authConfig.noWildcardRedirectURI)
        encryptState(
          Json.obj(
            "descriptor" -> descriptor.id,
            "hash"       -> hash
          )
        )
      else ""

    if (authConfig.noWildcardRedirectURI && logger.isDebugEnabled) {
      logger.debug(s"secret used $redirectUri")
      logger.debug(state)
    }

    val (loginUrl, sessionParams) = authConfig.pkce match {
      case Some(pcke) if pcke.enabled =>
        val (codeVerifier, codeChallenge, codeChallengeMethod) = generatePKCECodes(authConfig.pkce.map(_.algorithm))
        if (logger.isDebugEnabled)
          logger.debug(
            s"using pkce flow with code_verifier = $codeVerifier, code_challenge = $codeChallenge and code_challenge_method = $codeChallengeMethod"
          )
        (
          s"${authConfig.loginUrl}?&scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri&code_challenge=$codeChallenge&code_challenge_method=$codeChallengeMethod",
          Seq((s"${authConfig.id}-code_verifier" -> codeVerifier))
        )
      case _                          =>
        if (logger.isDebugEnabled) logger.debug(s"not using pkce flow")
        (
          s"${authConfig.loginUrl}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri",
          Seq.empty[(String, String)]
        )
    }

    Redirect(
      if (authConfig.noWildcardRedirectURI) s"$loginUrl&state=$state" else loginUrl
    ).addingToSession(
      sessionParams ++ Map(
        // s"${authConfig.id}-desc"                                          -> descriptor.id,
        "route"                                                           -> s"$isRoute",
        "ref"                                                             -> authConfig.id,
        "hash"                                                            -> hash,
        s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
          routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
        )
      ): _*
    ).asFuture
  }

  private def generatePKCECodes(codeChallengeMethod: Option[String] = Some("S256")) = {
    val code         = new Array[Byte](120)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(code)

    val codeVerifier = new String(Base64.getUrlEncoder.withoutPadding().encodeToString(code)).slice(0, 120)

    val bytes  = codeVerifier.getBytes("US-ASCII")
    val md     = MessageDigest.getInstance("SHA-256")
    md.update(bytes, 0, bytes.length)
    val digest = md.digest

    codeChallengeMethod match {
      case Some("S256") =>
        (codeVerifier, org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(digest), "S256")
      case _            => (codeVerifier, codeVerifier, "plain")
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = authConfig.scope // "openid profile email name"
    val claims       = Option(authConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")
    val hash         = env.sign(s"${authConfig.id}:::backoffice")
    val redirectUri  =
      if (authConfig.noWildcardRedirectURI) authConfig.callbackUrl
      else
        authConfig.callbackUrl.applyOn {
          case url if !authConfig.useCookie && url.contains("?")  => url + s"&hash=$hash"
          case url if !authConfig.useCookie && !url.contains("?") => url + s"?hash=$hash"
          case url                                                => url
        }

    val state = if (authConfig.noWildcardRedirectURI) encryptState(Json.obj("hash" -> hash)) else ""

    val (loginUrl, sessionParams) = authConfig.pkce match {
      case Some(pcke) if pcke.enabled =>
        val (codeVerifier, codeChallenge, codeChallengeMethod) = generatePKCECodes(authConfig.pkce.map(_.algorithm))
        if (logger.isDebugEnabled)
          logger.debug(
            s"using pkce flow with code_verifier = $codeVerifier, code_challenge = $codeChallenge and code_challenge_method = $codeChallengeMethod"
          )
        (
          s"${authConfig.loginUrl}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri&code_challenge=$codeChallenge&code_challenge_method=$codeChallengeMethod",
          Seq((s"${authConfig.id}-code_verifier" -> codeVerifier))
        )
      case _                          =>
        if (logger.isDebugEnabled) logger.debug(s"not using pkce flow")
        (
          s"${authConfig.loginUrl}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri",
          Seq.empty[(String, String)]
        )
    }

    Redirect(
      if (authConfig.noWildcardRedirectURI) s"$loginUrl&state=$state" else loginUrl
    ).addingToSession(
      sessionParams ++ Map(
        "hash"                    -> hash,
        "bo-redirect-after-login" -> redirect.getOrElse(
          routes.BackOfficeController.dashboard.absoluteURL(env.exposedRootSchemeIsHttps)
        )
      ): _*
    ).asFuture
  }

  override def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]] = {
    Option(authConfig.logoutUrl)
      .filterNot(_.isEmpty)
      .map {
        case url if url.contains("?") => Right(Some(s"$url&client_id=${authConfig.clientId}"))
        case url                      => Right(Some(s"$url?client_id=${authConfig.clientId}"))
      }
      .getOrElse(Right(None))
      .asFuture
  }

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]] = {
    Option(authConfig.logoutUrl)
      .filterNot(_.isEmpty)
      .map {
        case url if url.contains("?") => Right(Some(s"$url&client_id=${authConfig.clientId}"))
        case url                      => Right(Some(s"$url?client_id=${authConfig.clientId}"))
      }
      .getOrElse(Right(None))
      .asFuture
  }

  def getToken(
      code: String,
      clientId: String,
      clientSecret: Option[String],
      redirectUri: String,
      config: GlobalConfig,
      codeVerifier: Option[String] = None
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[JsValue] = {
    val builder =
      env.MtlsWs
        .url(authConfig.tokenUrl, authConfig.mtlsConfig)
        .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))

    val future1 = if (authConfig.useJson) {
      val params = Json.obj(
        "code"         -> code,
        "grant_type"   -> "authorization_code",
        "client_id"    -> clientId,
        "redirect_uri" -> redirectUri
      ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())

      builder.post(codeVerifier match {
        case None           => params
        case Some(verifier) => params ++ Json.obj("code_verifier" -> verifier.replace(s"${authConfig.id}-", ""))
      })
    } else {
      val params = Map(
        "code"         -> code,
        "grant_type"   -> "authorization_code",
        "client_id"    -> clientId,
        "redirect_uri" -> redirectUri
      ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
      builder.post(
        codeVerifier match {
          case None           => params
          case Some(verifier) => params ++ Map("code_verifier" -> verifier.replace(s"${authConfig.id}-", ""))
        }
      )(writeableOf_urlEncodedSimpleForm)
    }
    // TODO: check status code
    future1.map(_.json)
  }

  def refreshTheToken(
      refreshToken: String,
      clientId: String,
      clientSecret: Option[String],
      redirectUri: String,
      config: GlobalConfig
  )(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val builder =
      env.MtlsWs
        .url(authConfig.tokenUrl, authConfig.mtlsConfig)
        .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))
    val future1 = if (authConfig.useJson) {
      builder.post(
        Json.obj(
          "refresh_token" -> refreshToken,
          "grant_type"    -> "refresh_token",
          "client_id"     -> clientId,
          "redirect_uri"  -> redirectUri
        ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
      )
    } else {
      builder.post(
        Map(
          "refresh_token" -> refreshToken,
          "grant_type"    -> "refresh_token",
          "client_id"     -> clientId,
          "redirect_uri"  -> redirectUri
        ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
      )(writeableOf_urlEncodedSimpleForm)
    }
    // TODO: check status code
    future1.map(_.json)
  }

  def getUserInfoRaw(accessToken: String, config: GlobalConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[WSResponse] = {
    val builder2 = env.MtlsWs
      .url(authConfig.userInfoUrl, authConfig.mtlsConfig)
      .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))
    val future2  = if (authConfig.useJson) {
      builder2.post(
        Json.obj(
          "access_token" -> accessToken
        )
      )
    } else {
      builder2.post(
        Map(
          "access_token" -> accessToken
        )
      )(writeableOf_urlEncodedSimpleForm)
    }
    future2
  }

  def extractOtoroshiRights(profile: JsValue, default: Option[UserRights]): UserRights = {
    val rights: Seq[JsValue] = (profile \ authConfig.otoroshiRightsField).asOpt[JsValue] match {
      case Some(JsArray(values))   =>
        values.flatMap {
          case JsArray(value)    => value
          case obj @ JsObject(_) => Seq(obj)
          case _                 => Seq.empty
        }
      case Some(obj @ JsObject(_)) => Seq(obj)
      case _                       => Seq.empty
    }
    if (rights.isEmpty && default.isDefined) {
      default.get
    } else {
      val zeRights = rights.flatMap(r => UserRight.format.reads(r).asOpt)

      def merge(accesses: Seq[TeamAccess]): Seq[TeamAccess] = {
        accesses
          .groupBy(_.value)
          .map {
            case (teamName, group) => {
              if (group.exists(_.canReadWrite)) {
                TeamAccess(teamName, true, true)
              } else if (group.exists(_.canWrite)) {
                TeamAccess(teamName, false, true)
              } else {
                TeamAccess(teamName, true, false)
              }
            }
          }
          .toSeq
          .distinct
      }

      val newRights = zeRights
        .groupBy(_.tenant.value)
        .map {
          case (tenantName, group) => {
            if (group.exists(_.tenant.canReadWrite)) {
              UserRight(TenantAccess(tenantName, true, true), merge(group.flatMap(_.teams)))
            } else if (group.exists(_.tenant.canWrite)) {
              UserRight(TenantAccess(tenantName, false, true), merge(group.flatMap(_.teams)))
            } else {
              UserRight(TenantAccess(tenantName, true, false), merge(group.flatMap(_.teams)))
            }
          }
        }
        .toSeq

      UserRights(newRights)
    }
  }

  def getUserInfo(accessToken: String, config: GlobalConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[JsValue] = {
    getUserInfoRaw(accessToken, config).map(_.json)
  }

  def readProfileFromToken(accessToken: String)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val algoSettings = authConfig.jwtVerifier.get
    val tokenHeader  =
      Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
    val tokenBody    =
      Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
    val kid          = (tokenHeader \ "kid").asOpt[String]
    val alg          = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
    algoSettings.asAlgorithmF(InputMode(alg, kid)).flatMap {
      case Some(algo) => {
        Try(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).map { _ =>
          FastFuture.successful(tokenBody)
        } recoverWith { case e =>
          Success(FastFuture.failed(e))
        } get
      }
      case None       => FastFuture.failed(new RuntimeException("Bad algorithm"))
    }
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    val clientId     = authConfig.clientId
    val clientSecret = Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty)
    val redirectUri  =
      if (authConfig.noWildcardRedirectURI)
        authConfig.callbackUrl
      else
        authConfig.callbackUrl + (if (authConfig.useCookie) "" else s"?desc=${descriptor.id}")

    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None        => {
        request.getQueryString("code") match {
          case None       => Left("No code :(").asFuture
          case Some(code) => {
            getToken(
              code,
              clientId,
              clientSecret,
              redirectUri,
              config,
              request.session.get(s"${authConfig.id}-code_verifier")
            )
              .flatMap { rawToken =>
                val accessToken = (rawToken \ authConfig.accessTokenField).as[String]
                val f           = if (authConfig.readProfileFromToken && authConfig.jwtVerifier.isDefined) {
                  readProfileFromToken(accessToken)
                } else {
                  getUserInfo(accessToken, config)
                }
                f.map(r => (r, rawToken))
              }
              .map { tuple =>
                val (user, rawToken)       = tuple
                val meta: Option[JsObject] = PrivateAppsUser
                  .select(user, authConfig.otoroshiDataField)
                  .asOpt[String]
                  .map(s => Json.parse(s))
                  .orElse(
                    Option(PrivateAppsUser.select(user, authConfig.otoroshiDataField))
                  )
                  .map(_.asOpt[JsObject].getOrElse(Json.obj()))
                val email                  = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@oto.tools")
                PrivateAppsUser(
                  randomId = IdGenerator.token(64),
                  name = (user \ authConfig.nameField)
                    .asOpt[String]
                    .orElse((user \ "sub").asOpt[String])
                    .getOrElse("No Name"),
                  email = email,
                  profile = user,
                  token = rawToken,
                  authConfigId = authConfig.id,
                  realm = authConfig.cookieSuffix(descriptor),
                  otoroshiData = authConfig.dataOverride
                    .get(email)
                    .map(v => authConfig.extraMetadata.deepMerge(v))
                    .orElse(Some(authConfig.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))),
                  tags = authConfig.theTags,
                  metadata = authConfig.metadata,
                  location = authConfig.location
                ).validate(authConfig.userValidators)
              }
          }
        }
      }
    }
  }

  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    val clientId     = authConfig.clientId
    val clientSecret = Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty)
    val hash         = env.sign(s"${authConfig.id}:::backoffice")
    val redirectUri  =
      if (authConfig.noWildcardRedirectURI)
        authConfig.callbackUrl
      else
        authConfig.callbackUrl.applyOn {
          case url if !authConfig.useCookie && url.contains("?")  => url + s"&hash=$hash"
          case url if !authConfig.useCookie && !url.contains("?") => url + s"?hash=$hash"
          case url                                                => url
        }

    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None        => {
        request.getQueryString("code") match {
          case None       => Left("No code :(").asFuture
          case Some(code) => {
            getToken(
              code,
              clientId,
              clientSecret,
              redirectUri,
              config,
              request.session.get(s"${authConfig.id}-code_verifier")
            )
              .flatMap { rawToken =>
                val accessToken = (rawToken \ authConfig.accessTokenField).as[String]
                val f           = if (authConfig.readProfileFromToken && authConfig.jwtVerifier.isDefined) {
                  readProfileFromToken(accessToken)
                } else {
                  getUserInfo(accessToken, config)
                }
                f.map(r => (r, rawToken))
              }
              .map { tuple =>
                val (user, rawToken) = tuple
                val email            = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@oto.tools")

                BackOfficeUser(
                  randomId = IdGenerator.token(64),
                  name = (user \ authConfig.nameField)
                    .asOpt[String]
                    .orElse((user \ "sub").asOpt[String])
                    .getOrElse("No Name"),
                  email = email,
                  profile = user,
                  authConfigId = authConfig.id,
                  simpleLogin = false,
                  tags = Seq.empty,
                  metadata = Map.empty,
                  rights =
                    if (authConfig.superAdmins) UserRights.superAdmin
                    else {
                      authConfig.rightsOverride.getOrElse(
                        email,
                        extractOtoroshiRights(
                          user,
                          UserRights(
                            Seq(
                              UserRight(
                                TenantAccess(authConfig.location.tenant.value),
                                authConfig.location.teams.map(t => TeamAccess(t.value))
                              )
                            )
                          ).some
                        )
                      )
                    },
                  location = authConfig.location
                ).validate(authConfig.userValidators)
              }
          }
        }
      }
    }
  }

  private def isAccessTokenAValidJwtToken(
      accessToken: String
  )(f: Option[Boolean] => Future[Unit])(implicit executionContext: ExecutionContext, env: Env): Future[Unit] = {
    Try {
      val algoSettings = authConfig.jwtVerifier.get
      val tokenHeader  =
        Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
      val kid          = (tokenHeader \ "kid").asOpt[String]
      val alg          = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
      algoSettings.asAlgorithmF(InputMode(alg, kid)).map {
        case Some(algo) => {
          Try(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).map { _ =>
            Some(true)
          } match {
            case Failure(e)   => Some(false)
            case Success(opt) => opt
          }
        }
        case None       => Some(false)
      }
    } match {
      case Failure(e)    => f(None)
      case Success(fopt) => fopt.flatMap(v => f(v))
    }
  }

  private def renewToken(refreshToken: String, user: RefreshableUser)(implicit
      executionContext: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    refreshTheToken(
      refreshToken,
      authConfig.clientId,
      Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty),
      authConfig.callbackUrl,
      env.datastores.globalConfigDataStore.latest()
    ).map { res =>
      val rtok  = (res \ "refresh_token").asOpt[String].getOrElse(refreshToken)
      val token = res.as[JsObject] ++ Json.obj("refresh_token" -> rtok)
      user.updateToken(token)
    }
  }

  // https://auth0.com/docs/tokens/guides/get-refresh-tokens
  // https://auth0.com/docs/tokens/guides/use-refresh-tokens
  // https://auth0.com/docs/tokens/concepts/refresh-tokens
  def handleTokenRefresh(auth: OAuth2ModuleConfig, user: RefreshableUser)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Unit] = {
    if (auth.refreshTokens) {
      (user.token \ "refresh_token").asOpt[String] match { // TODO: config 10 min
        case Some(refreshToken) if user.lastRefresh.plusMinutes(10).isBefore(DateTime.now()) =>
          (user.token \ auth.accessTokenField).asOpt[String] match {
            case Some(accessToken) =>
              isAccessTokenAValidJwtToken(accessToken) {
                case Some(true)  => FastFuture.successful(())
                case Some(false) => renewToken(refreshToken, user)
                case _           =>
                  getUserInfoRaw(accessToken, env.datastores.globalConfigDataStore.latest()).flatMap {
                    case r if r.status != 200 => renewToken(refreshToken, user)
                    case _s                   => FastFuture.successful(())
                  }
              }
            case None              => FastFuture.successful(())
          }
        case _                                                                               => FastFuture.successful(())
      }
    } else {
      FastFuture.successful(())
    }
  }
}

object GenericOauth2Module {
  def defaultConfig = GenericOauth2ModuleConfig(
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "New auth. module",
    desc = "New auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues(),
    clientSideSessionEnabled = true
  )

  def handleTokenRefresh(auth: AuthModuleConfig, user: RefreshableUser)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    auth match {
      case a: OAuth2ModuleConfig =>
        a.authModule(env.datastores.globalConfigDataStore.latest()) match {
          case m: GenericOauth2Module => m.handleTokenRefresh(a, user)
          case _                      => FastFuture.successful(())
        }
      case _                     => FastFuture.successful(())
    }
  }
}
