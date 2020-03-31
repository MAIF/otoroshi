package auth

import java.net.URLEncoder

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import controllers.routes
import env.Env
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.Redirect
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}
import security.IdGenerator
import storage.BasicStore
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.joda.time.DateTime
import play.api.libs.ws.{WSProxyServer, WSResponse}
import utils.http.MtlsConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object GenericOauth2ModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-global-oauth2-config")

  val _fmt = new Format[GenericOauth2ModuleConfig] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[GenericOauth2ModuleConfig])
    }

    override def writes(o: GenericOauth2ModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        GenericOauth2ModuleConfig(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
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
          mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue])
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class GenericOauth2ModuleConfig(
    id: String,
    name: String,
    desc: String,
    sessionMaxAge: Int = 86400,
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
) extends OAuth2ModuleConfig {
  def `type`: String                                        = "oauth2"
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def asJson = Json.obj(
    "type"                 -> "oauth2",
    "id"                   -> this.id,
    "name"                 -> this.name,
    "desc"                 -> this.desc,
    "sessionMaxAge"        -> this.sessionMaxAge,
    "clientId"             -> this.clientId,
    "clientSecret"         -> this.clientSecret,
    "authorizeUrl"         -> this.authorizeUrl,
    "tokenUrl"             -> this.tokenUrl,
    "userInfoUrl"          -> this.userInfoUrl,
    "introspectionUrl"     -> this.introspectionUrl,
    "loginUrl"             -> this.loginUrl,
    "logoutUrl"            -> this.logoutUrl,
    "scope"                -> this.scope,
    "claims"               -> this.claims,
    "useCookie"            -> this.useCookie,
    "useJson"              -> this.useJson,
    "readProfileFromToken" -> this.readProfileFromToken,
    "accessTokenField"     -> this.accessTokenField,
    "jwtVerifier"          -> jwtVerifier.map(_.asJson).getOrElse(JsNull).as[JsValue],
    "nameField"            -> this.nameField,
    "emailField"           -> this.emailField,
    "apiKeyMetaField"      -> this.apiKeyMetaField,
    "apiKeyTagsField"      -> this.apiKeyTagsField,
    "otoroshiDataField"    -> this.otoroshiDataField,
    "callbackUrl"          -> this.callbackUrl,
    "oidConfig"            -> this.oidConfig.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "mtlsConfig"           -> this.mtlsConfig.json,
    "proxy"                -> WSProxyServerJson.maybeProxyToJson(this.proxy),
    "extraMetadata"        -> this.extraMetadata,
    "refreshTokens"        -> this.refreshTokens
  )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                   = s"global-oauth-$id"
}

case class GenericOauth2Module(authConfig: OAuth2ModuleConfig) extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._
  import utils.http.Implicits._

  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = authConfig.scope // "openid profile email name"
    val claims       = Option(authConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")
    val queryParam   = if (authConfig.useCookie) "" else s"?desc=${descriptor.id}"
    val redirectUri  = authConfig.callbackUrl + queryParam
    val loginUrl =
      s"${authConfig.loginUrl}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
        s"desc" -> descriptor.id,
        s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
          routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
        )
      )
      .asFuture
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = authConfig.scope // "openid profile email name"
    val claims       = Option(authConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")

    val redirectUri = authConfig.callbackUrl
    val loginUrl =
      s"${authConfig.loginUrl}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
        "bo-redirect-after-login" -> redirect.getOrElse(
          routes.BackOfficeController.dashboard().absoluteURL(env.exposedRootSchemeIsHttps)
        )
      )
      .asFuture
  }

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Option[String]] = {
    Option(authConfig.logoutUrl)
      .filterNot(_.isEmpty)
      .map {
        case url if url.contains("?") => s"$url&client_id=${authConfig.clientId}"
        case url                      => s"$url?client_id=${authConfig.clientId}"
      }
      .asFuture
  }

  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                      env: Env): Future[Option[String]] = {
    Option(authConfig.logoutUrl)
      .filterNot(_.isEmpty)
      .map {
        case url if url.contains("?") => s"$url&client_id=${authConfig.clientId}"
        case url                      => s"$url?client_id=${authConfig.clientId}"
      }
      .asFuture
  }

  def getToken(code: String, clientId: String, clientSecret: Option[String], redirectUri: String, config: GlobalConfig)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val builder =
      env.MtlsWs
        .url(authConfig.tokenUrl, authConfig.mtlsConfig)
        .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))
    val future1 = if (authConfig.useJson) {
      builder.post(
        Json.obj(
          "code"         -> code,
          "grant_type"   -> "authorization_code",
          "client_id"    -> clientId,
          "redirect_uri" -> redirectUri
        ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
      )
    } else {
      builder.post(
        Map(
          "code"         -> code,
          "grant_type"   -> "authorization_code",
          "client_id"    -> clientId,
          "redirect_uri" -> redirectUri
        ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
      )(writeableOf_urlEncodedSimpleForm)
    }
    // TODO: check status code
    future1.map(_.json)
  }

  def refreshTheToken(refreshToken: String, clientId: String, clientSecret: Option[String], redirectUri: String, config: GlobalConfig)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val builder =
      env.MtlsWs
        .url(authConfig.tokenUrl, authConfig.mtlsConfig)
        .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))
    val future1 = if (authConfig.useJson) {
      builder.post(
        Json.obj(
          "refresh_token"         -> refreshToken,
          "grant_type"   -> "refresh_token",
          "client_id"    -> clientId,
          "redirect_uri" -> redirectUri
        ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
      )
    } else {
      builder.post(
        Map(
          "refresh_token"         -> refreshToken,
          "grant_type"   -> "refresh_token",
          "client_id"    -> clientId,
          "redirect_uri" -> redirectUri
        ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
      )(writeableOf_urlEncodedSimpleForm)
    }
    // TODO: check status code
    future1.map(_.json)
  }

  def getUserInfoRaw(accessToken: String, config: GlobalConfig)(implicit env: Env, ec: ExecutionContext): Future[WSResponse] = {
    val builder2 = env.MtlsWs
      .url(authConfig.userInfoUrl, authConfig.mtlsConfig)
      .withMaybeProxyServer(authConfig.proxy.orElse(config.proxies.auth))
    val future2 = if (authConfig.useJson) {
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

  def getUserInfo(accessToken: String, config: GlobalConfig)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    getUserInfoRaw(accessToken, config).map(_.json)
  }

  def readProfileFromToken(accessToken: String)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val algoSettings = authConfig.jwtVerifier.get
    val tokenHeader =
      Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
    val tokenBody =
      Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
    val kid = (tokenHeader \ "kid").asOpt[String]
    val alg = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
    algoSettings.asAlgorithmF(InputMode(alg, kid)).flatMap {
      case Some(algo) => {
        Try(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).map { _ =>
          FastFuture.successful(tokenBody)
        } recoverWith {
          case e => Success(FastFuture.failed(e))
        } get
      }
      case None => FastFuture.failed(new RuntimeException("Bad algorithm"))
    }
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    val clientId     = authConfig.clientId
    val clientSecret = Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty)
    val queryParam   = if (authConfig.useCookie) "" else s"?desc=${descriptor.id}"
    val redirectUri  = authConfig.callbackUrl + queryParam
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            getToken(code, clientId, clientSecret, redirectUri, config)
              .flatMap { rawToken =>
                val accessToken = (rawToken \ authConfig.accessTokenField).as[String]
                val f = if (authConfig.readProfileFromToken && authConfig.jwtVerifier.isDefined) {
                  readProfileFromToken(accessToken)
                } else {
                  getUserInfo(accessToken, config)
                }
                f.map(r => (r, rawToken))
              }
              .map { tuple =>
                val (user, rawToken) = tuple
                val meta: Option[JsObject] = PrivateAppsUser
                  .select(user, authConfig.otoroshiDataField)
                  .asOpt[String]
                  .map(s => Json.parse(s))
                  .orElse(
                    Option(PrivateAppsUser.select(user, authConfig.otoroshiDataField))
                  )
                  .map(_.asOpt[JsObject].getOrElse(Json.obj()))
                Right(
                  PrivateAppsUser(
                    randomId = IdGenerator.token(64),
                    name = (user \ authConfig.nameField)
                      .asOpt[String]
                      .orElse((user \ "sub").asOpt[String])
                      .getOrElse("No Name"),
                    email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@oto.tools"),
                    profile = user,
                    token = rawToken,
                    authConfigId = authConfig.id,
                    realm = authConfig.cookieSuffix(descriptor),
                    otoroshiData = Some(authConfig.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))
                  )
                )
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
    val redirectUri  = authConfig.callbackUrl
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            getToken(code, clientId, clientSecret, redirectUri, config)
              .flatMap { rawToken =>
                val accessToken = (rawToken \ authConfig.accessTokenField).as[String]
                val f = if (authConfig.readProfileFromToken && authConfig.jwtVerifier.isDefined) {
                  readProfileFromToken(accessToken)
                } else {
                  getUserInfo(accessToken, config)
                }
                f.map(r => (r, rawToken))
              }
              .map { tuple =>
                val (user, rawToken) = tuple
                Right(
                  BackOfficeUser(
                    randomId = IdGenerator.token(64),
                    name = (user \ authConfig.nameField)
                      .asOpt[String]
                      .orElse((user \ "sub").asOpt[String])
                      .getOrElse("No Name"),
                    email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@oto.tools"),
                    profile = user,
                    authConfigId = authConfig.id,
                    authorizedGroup = None,
                    simpleLogin = false
                  )
                )
              }
          }
        }
      }
    }
  }

  private def isAccessTokenAValidJwtToken(accessToken: String)(f: Option[Boolean] => Future[Unit])(implicit executionContext: ExecutionContext, env: Env): Future[Unit] = {
    Try {
      val algoSettings = authConfig.jwtVerifier.get
      val tokenHeader =
        Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
      val kid = (tokenHeader \ "kid").asOpt[String]
      val alg = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
      algoSettings.asAlgorithmF(InputMode(alg, kid)).map {
        case Some(algo) => {
          Try(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).map { _ =>
            Some(true)
          } match {
            case Failure(e) => Some(false)
            case Success(opt) => opt
          }
        }
        case None => Some(false)
      }
    } match {
      case Failure(e) => f(None)
      case Success(fopt) => fopt.flatMap(v => f(v))
    }
  }

  private def renewToken(refreshToken: String, user: RefreshableUser)(implicit executionContext: ExecutionContext, env: Env): Future[Unit] = {
    refreshTheToken(refreshToken, authConfig.clientId, Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty), authConfig.callbackUrl, env.datastores.globalConfigDataStore.latest()).map { res =>
      val rtok = (res \ "refresh_token").asOpt[String].getOrElse(refreshToken)
      val token = res.as[JsObject] ++ Json.obj("refresh_token" -> rtok)
      user.updateToken(token)
    }
  }

  // https://auth0.com/docs/tokens/guides/get-refresh-tokens
  // https://auth0.com/docs/tokens/guides/use-refresh-tokens
  // https://auth0.com/docs/tokens/concepts/refresh-tokens
  def handleTokenRefresh(auth: OAuth2ModuleConfig, user: RefreshableUser)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (auth.refreshTokens) {
      (user.token \ "refresh_token").asOpt[String] match { // TODO: config 10 min
        case Some(refreshToken) if user.lastRefresh.plusMinutes(10).isBefore(DateTime.now()) => (user.token \ auth.accessTokenField).asOpt[String] match {
          case Some(accessToken) => isAccessTokenAValidJwtToken(accessToken) {
            case Some(true) => FastFuture.successful(())
            case Some(false) => renewToken(refreshToken, user)
            case _ => getUserInfoRaw(accessToken, env.datastores.globalConfigDataStore.latest()).flatMap {
              case r if r.status != 200 => renewToken(refreshToken, user)
              case _s => FastFuture.successful(())
            }
          }
          case None => FastFuture.successful(())
        }
        case _ => FastFuture.successful(())
      }
    } else {
      FastFuture.successful(())
    }
  }
}

object GenericOauth2Module {
  def handleTokenRefresh(auth: AuthModuleConfig, user: RefreshableUser)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    auth match {
      case a: OAuth2ModuleConfig => a.authModule(env.datastores.globalConfigDataStore.latest()) match {
        case m: GenericOauth2Module => m.handleTokenRefresh(a, user)
        case _ => FastFuture.successful(())
      }
      case _ => FastFuture.successful(())
    }
  }
}
