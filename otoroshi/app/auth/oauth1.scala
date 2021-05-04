package auth

import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.{AuthModule, AuthModuleConfig, SessionCookieValues}
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, FromJson, GlobalConfig, PrivateAppsUser, RefreshableUser, ServiceDescriptor, TeamAccess, TenantAccess, UserRight, UserRights}
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.Signatures
import play.api.Logger
import play.api.libs.json.{Format, JsArray, JsError, JsObject, JsString, JsSuccess, JsValue, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSResponse
import play.api.mvc.Results.{Ok, Redirect}
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import java.net.URLEncoder
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object Oauth1ModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-ldap-auth-config")

  def fromJsons(value: JsValue): Oauth1ModuleConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[Oauth1ModuleConfig] {

    override def reads(json: JsValue) =
      fromJson(json) match {
        case Left(e)  => JsError(e.getMessage)
        case Right(v) => JsSuccess(v.asInstanceOf[Oauth1ModuleConfig])
      }

    override def writes(o: Oauth1ModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, Oauth1ModuleConfig] = {
    Try {
      val location = otoroshi.models.EntityLocation.readFromKey(json)
      Right(
        Oauth1ModuleConfig(
          location = location,
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          consumerKey = (json \ "consumerKey").as[String],
          consumerSecret = (json \ "consumerSecret").as[String],
          //signatureMethod = (json \ "signatureMethod").as[String],
          httpMethod = (json \ "httpMethod")
            .asOpt[String]
            .map(OAuth1Provider(_))
            .getOrElse(OAuth1Provider.Post),
          requestTokenURL = (json \ "requestTokenURL").as[String],
          authorizeURL = (json \ "authorizeURL").as[String],
          profileURL = (json \ "profileURL")
            .asOpt[String]
            .getOrElse("https://api.clever-cloud.com/v2/self"),
          accessTokenURL = (json \ "accessTokenURL").as[String],
          callbackURL = (json \ "callbackURL")
            .asOpt[String]
            .getOrElse("http://otoroshi.oto.tools:9999/backoffice/auth0/callback"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          rightsOverride = (json \ "rightsOverride")
            .asOpt[Map[String, JsArray]]
            .map(_.mapValues(UserRights.readFromArray))
            .getOrElse(Map.empty),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues())
        )
      )
    } recover { case e =>
      e.printStackTrace()
      Left(e)
    } get
  }
}

sealed trait OAuth1Provider {
  def name: String
  def methods: OAuth1ProviderMethods
}

case class OAuth1ProviderMethods (requestToken: String, accessToken: String)

object OAuth1Provider {
  case object Post extends OAuth1Provider {
    val name = "post"
    val methods: OAuth1ProviderMethods = OAuth1ProviderMethods(
      requestToken   = "POST",
      accessToken    = "POST",
    )
  }
  case object Get extends OAuth1Provider {
    val name = "get"
    val methods: OAuth1ProviderMethods = OAuth1ProviderMethods(
      requestToken   = "GET",
      accessToken    = "GET"
    )
  }

  def apply(value: String): OAuth1Provider = value match {
    case "post"     => Post
    case "get"      => Get
    case _          => Post
  }
}

case class Oauth1ModuleConfig(
                                 id: String,
                                 name: String,
                                 desc: String,
                                 sessionMaxAge: Int = 86400,
                                 consumerKey: String,
                                 consumerSecret: String,
                                 httpMethod: OAuth1Provider = OAuth1Provider.Post,
                                 requestTokenURL: String,
                                 authorizeURL: String,
                                 accessTokenURL: String,
                                 profileURL: String,
                                 callbackURL: String,
                                 tags: Seq[String],
                                 metadata: Map[String, String],
                                 sessionCookieValues: SessionCookieValues,
                                 rightsOverride: Map[String, UserRights] = Map.empty,
                                 location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
                               ) extends AuthModuleConfig {
  def `type`: String = "oauth1"
  def theDescription: String = desc
  def theMetadata: Map[String,String] = metadata
  def theName: String = name
  def theTags: Seq[String] = tags

  override def authModule(config: GlobalConfig): AuthModule = Oauth1AuthModule(this)

  override def asJson =
    location.jsonWithKey ++ Json.obj(
      "type"          -> "oauth1",
      "id"                  -> id,
      "name"                -> name,
      "desc"                -> desc,
      "consumerKey"         -> consumerKey,
      "consumerSecret"      -> consumerSecret,
      //"signatureMethod"     -> signatureMethod,
      "requestTokenURL"     -> requestTokenURL,
      "authorizeURL"        -> authorizeURL,
      "profileURL"     -> profileURL,
      "accessTokenURL"      -> accessTokenURL,
      "callbackURL"         -> callbackURL,
      "sessionMaxAge"       -> sessionMaxAge,
      "metadata"            -> metadata,
      "tags"                -> JsArray(tags.map(JsString.apply)),
      "rightsOverride"      -> JsObject(rightsOverride.mapValues(_.json)),
      "httpMethod"          -> httpMethod.name,
      "sessionCookieValues" -> SessionCookieValues.fmt.writes(this.sessionCookieValues)
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)

  override def cookieSuffix(desc: ServiceDescriptor) = s"ldap-auth-$id"
}

object Oauth1AuthModule {

  def encodeURI(str: String): String = URLEncoder.encode(str, "UTF-8")

  def sign(params: Map[String, String], url: String, method: String, consumerSecret: String, tokenSecret: Option[String] = None): String = {

    val sortedEncodedParams = encodeURI(params.toSeq.sortBy(_._1).map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&"))
    val encodedURL = encodeURI(url)

    val base = s"$method&$encodedURL&$sortedEncodedParams"
    val key = s"${encodeURI(consumerSecret)}&${tokenSecret.map(encodeURI).getOrElse("")}"
    val signature = Base64.getEncoder.encodeToString(Signatures.hmac("HmacSHA1", base, key))

    if (method == "POST") signature else encodeURI(signature)
  }

  def get(env: Env, url: String): Future[WSResponse] = env.Ws
    .url(url)
    .get()

  def post(env: Env, url: String, body: Map[String, String]): Future[WSResponse] = env.Ws
    .url(url)
    .addHttpHeaders(("Content-Type", "application/x-www-form-urlencoded"))
    .post(body)(writeableOf_urlEncodedSimpleForm)

  def getOauth1TemplateRequest(callbackURL: Option[String]): Map[String, String] = {
    val signatureMethod = "HMAC-SHA1"

    val nonce = IdGenerator.token.slice(0, 12)
    val timestamp = System.currentTimeMillis / 1000

    val params = Map(
      "oauth_nonce"             -> nonce,
      "oauth_signature_method"  -> signatureMethod,
      "oauth_timestamp"         -> timestamp.toString,
      "oauth_version"           -> "1.0"
    )

    callbackURL
      .map(u => params ++ Map("oauth_callback" -> u))
      .getOrElse(params)
  }

  def strBodyToMap(body: String): Map[String, String] = body
    .split("&")
    .map(_.split("=", 2))
    .map(value => (value(0), value(1)))
    .toMap

  def mapOfSeqToMap(m: Map[String, Seq[String]]): Map[String, String] = m.map(t => (t._1, t._2.head))
}

case class Oauth1AuthModule(authConfig: Oauth1ModuleConfig) extends AuthModule {

  import auth.Oauth1AuthModule._

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                        ec: ExecutionContext,
                                                                                                        env: Env
  ): Future[Result] = {
    implicit val _r: RequestHeader = request

    val baseParams: Map[String, String] = getOauth1TemplateRequest(Some(authConfig.callbackURL)) ++ Map("oauth_consumer_key" -> authConfig.consumerKey)

    val signature = sign(baseParams, authConfig.requestTokenURL, authConfig.httpMethod.methods.requestToken, authConfig.consumerSecret)

    (if (authConfig.httpMethod.methods.requestToken == "POST") {
      post(env, authConfig.requestTokenURL, baseParams ++ Map("oauth_signature" -> signature))
    } else {
      get(env, s"${authConfig.requestTokenURL}?${baseParams.map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")}&oauth_signature=$signature")
    })
      .map { result =>
        if (result.status > 300) {
            env.logger.error("result.body")
            Ok(otoroshi.views.html.oto.error("OAuth request token call failed", env))
        } else {
          val parameters = strBodyToMap(result.body)

          if (parameters("oauth_callback_confirmed") == "true") {
            val redirect      = request.getQueryString("redirect")
            val hash          = env.sign(s"${authConfig.id}:::backoffice")
            val oauth_token   = parameters("oauth_token")
            Redirect(s"${authConfig.authorizeURL}?oauth_token=$oauth_token&perms=read")
              .addingToSession(
                "oauth_token_secret" -> parameters("oauth_token_secret"),
                s"desc"                                                           -> descriptor.id,
                "hash"                                                            -> hash,
                s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
                  routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
                )
              )
          }
          else
            Ok(otoroshi.views.html.oto.error("OAuth request token call failed", env))
        }
      }
  }

  override def paLogout(request: RequestHeader, user: Option[PrivateAppsUser], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                                                    ec: ExecutionContext,
                                                                                                                                    env: Env
  ) = FastFuture.successful(Right(None))

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                             ec: ExecutionContext,
                                                                                                             env: Env
  ): Future[Either[String, PrivateAppsUser]] = callback(request, config, isBoLogin = false, Some(descriptor))
    .asInstanceOf[Future[Either[String, PrivateAppsUser]]]

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
                                                                         ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {

    implicit val _r: RequestHeader = request

    val baseParams: Map[String, String] = getOauth1TemplateRequest(Some(authConfig.callbackURL)) ++ Map("oauth_consumer_key" -> authConfig.consumerKey)

    val signature = sign(baseParams, authConfig.requestTokenURL, authConfig.httpMethod.methods.requestToken, authConfig.consumerSecret)

    (if (authConfig.httpMethod.methods.requestToken == "POST") {
      post(env, authConfig.requestTokenURL, baseParams ++ Map("oauth_signature" -> signature))
    } else {
      get(env, s"${authConfig.requestTokenURL}?${baseParams.map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")}&oauth_signature=$signature")
    })
      .map { result =>
        val parameters = strBodyToMap(result.body)

        if (parameters("oauth_callback_confirmed") == "true") {
          val redirect      = request.getQueryString("redirect")
          val hash          = env.sign(s"${authConfig.id}:::backoffice")
          val oauth_token   = parameters("oauth_token")
          Redirect(
            s"${authConfig.authorizeURL}?oauth_token=$oauth_token&perms=read"
          ).addingToSession(
            "oauth_token_secret" -> parameters("oauth_token_secret"),
            "hash"                    -> hash,
            "bo-redirect-after-login" -> redirect.getOrElse(
              routes.BackOfficeController.dashboard().absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
        }
        else
          Ok(otoroshi.views.html.oto.error("OAuth request token failed", env))
      }

  }

  override def boLogout(request: RequestHeader,  user: BackOfficeUser, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(Right(None))

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)
                         (implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] =
    callback(request, config, isBoLogin = true).asInstanceOf[Future[Either[String, BackOfficeUser]]]

  private def callback(
                        request: Request[AnyContent],
                        config: GlobalConfig,
                        isBoLogin: Boolean,
                        descriptor: Option[ServiceDescriptor] = None
                      )(implicit ec: ExecutionContext, env: Env): Future[Either[String, RefreshableUser]] = {

    val method = authConfig.httpMethod.methods.accessToken
    val queries = mapOfSeqToMap(request.queryString)

    val baseParams = getOauth1TemplateRequest(None) ++ Map(
      "oauth_consumer_key"      -> authConfig.consumerKey,
      "oauth_token"             -> queries("oauth_token"),
      "oauth_verifier"          -> queries("oauth_verifier")
    )

    val signature = sign(baseParams, authConfig.accessTokenURL, method, authConfig.consumerSecret, Some(request.session.get("oauth_token_secret").get))

    (if (method == "POST") {
      post(env, authConfig.accessTokenURL, baseParams ++ Map("oauth_signature" -> signature))
    } else {
      get(env, s"${authConfig.accessTokenURL}?${baseParams.map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")}&oauth_signature=$signature")
    })
      .flatMap { result =>
        val bodyParams = strBodyToMap(result.body)

        val oauth_token = bodyParams("oauth_token")

        val userParams = getOauth1TemplateRequest(None) ++ Map(
          "oauth_consumer_key" -> authConfig.consumerKey,
          "oauth_token" -> oauth_token,
        )

        val oauthTokenSecret = bodyParams("oauth_token_secret")

        val signature = sign(userParams, authConfig.profileURL, "GET", authConfig.consumerSecret, Some(oauthTokenSecret))

        env.Ws.url(authConfig.profileURL)
          .addHttpHeaders(("Authorization", s"""OAuth oauth_consumer_key="${authConfig.consumerKey}",oauth_token="$oauth_token",oauth_signature_method="HMAC-SHA1",oauth_signature="$signature",oauth_timestamp="${userParams("oauth_timestamp")}",oauth_nonce="${userParams("oauth_nonce")}",oauth_version="1.0""""))
          .get()
          .flatMap { result =>
            (result.header("Content-Type") match {
              case Some("application/json") =>
                val userJson = Json.parse(result.body)
                Some(Map(
                  "profile" -> userJson,
                  "email" -> (userJson \ "email").asOpt[String].getOrElse("no.name@oto.tools"),
                  "name" -> (userJson \ "name").asOpt[String].getOrElse("No name")
                ))
              case Some(value) if value.contains("text/plain")  =>
                val fields = strBodyToMap(result.body)
                Some(Map(
                  "profile" -> Json.toJson(fields),
                  "email" -> fields.getOrElse("email", fields.getOrElse("mail", "no.name@oto.tools")),
                  "name" -> fields.getOrElse("fullname", fields.getOrElse("username", fields.getOrElse("name", "No name")))
                ))
              case _ => None
            })
              .map { data =>
                FastFuture.successful(Right(
                  if(isBoLogin)
                    BackOfficeUser(
                      randomId = IdGenerator.token(64),
                      name = data("name").toString,
                      email = data("email").toString,
                      profile = data("profile").asInstanceOf[JsObject],
                      simpleLogin = false,
                      authConfigId = authConfig.id,
                      tags = Seq.empty,
                      metadata = Map.empty,
                      rights = authConfig.rightsOverride.getOrElse(
                        data("email").toString,
                        UserRights(
                          Seq(
                            UserRight(
                              TenantAccess(authConfig.location.tenant.value),
                              authConfig.location.teams.map(t => TeamAccess(t.value))
                            )
                          )
                        )
                      ),
                      location = authConfig.location
                    )
                  else {
                    PrivateAppsUser(
                      randomId = IdGenerator.token(64),
                      name = data("name").toString,
                      email = data("email").toString,
                      profile = data("profile").asInstanceOf[JsObject],
                      authConfigId = authConfig.id,
                      tags = Seq.empty,
                      metadata = Map.empty,
                      location = authConfig.location,
                      realm = authConfig.cookieSuffix(descriptor.get),
                      otoroshiData = None
                    )
                  }
                ))
              }
              .getOrElse(FastFuture.successful(Left("Missing content type from provider")))
          }
          .recover {
            case e: Throwable => Left(e.getMessage)
          }
      }
  }
}










