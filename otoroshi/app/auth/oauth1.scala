package auth

import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.{AuthModule, AuthModuleConfig, SessionCookieValues}
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, FromJson, GlobalConfig, PrivateAppsUser, ServiceDescriptor, TeamAccess, TenantAccess, UserRight, UserRights}
import play.api.Logger
import play.api.libs.json.{Format, JsArray, JsError, JsObject, JsString, JsSuccess, JsValue, Json, Reads}
import play.api.mvc.Results.{Ok, Redirect}
import play.api.mvc.{AnyContent, Request, RequestHeader, Result, Results}

import javax.xml.crypto.dsig.SignatureMethod
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

  override def fromJson(json: JsValue): Either[Throwable, Oauth1ModuleConfig] =
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
          signatureMethod = (json \ "signatureMethod").as[String]
            .toLowerCase match {
            case "HMAC-SHA1"    => SignatureMethod.HMAC_SHA1
            case "HMAC-SHA256"  => SignatureMethod.HMAC_SHA256
            case "HMAC-SHA512"  => SignatureMethod.HMAC_SHA512
            case _              => SignatureMethod.HMAC_SHA1
          },
          requestTokenURL = (json \ "requestTokenURL").as[String],
          authorizeURL = (json \ "authorizeURL").as[String],
          accessTokenURL = (json \ "accessTokenURL").as[String],
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues())
        )
      )
    } recover { case e =>
      e.printStackTrace()
      Left(e)
    } get
}

case class Oauth1ModuleConfig(
                                 id: String,
                                 name: String,
                                 desc: String,
                                 sessionMaxAge: Int = 86400,
                                 consumerKey: String,
                                 consumerSecret: String,
                                 signatureMethod: String,
                                 requestTokenURL: String,
                                 authorizeURL: String,
                                 accessTokenURL: String,
                                 tags: Seq[String],
                                 metadata: Map[String, String],
                                 sessionCookieValues: SessionCookieValues,
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
      "sessionMaxAge"       -> sessionMaxAge,
      "metadata"            -> metadata,
      "tags"                -> JsArray(tags.map(JsString.apply)),
      "sessionCookieValues" -> SessionCookieValues.fmt.writes(this.sessionCookieValues)
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)

  override def cookieSuffix(desc: ServiceDescriptor) = s"ldap-auth-$id"
}

case class Oauth1AuthModule(authConfig: Oauth1ModuleConfig) extends AuthModule {

  import otoroshi.utils.future.Implicits._

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                        ec: ExecutionContext,
                                                                                                        env: Env
  ): Future[Result] = ???

  override def paLogout(request: RequestHeader, user: Option[PrivateAppsUser], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                                                    ec: ExecutionContext,
                                                                                                                                    env: Env
  ) = FastFuture.successful(Right(None))

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
                                                                                                             ec: ExecutionContext,
                                                                                                             env: Env
  ): Future[Either[String, PrivateAppsUser]] = ???

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
                                                                         ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {

    implicit val req = request

    val params = Map(
      "oauth_callback"          -> "",
      "oauth_consumer_key"      -> "",
      "oauth_nonce"             -> "",
      "oauth_signature"         -> "",
      "oauth_signature_method"  -> "",
      "oauth_timestamp"         -> "",
      "oauth_version"           -> "1.0"
    )

    env.Ws
      .url(s"${authConfig.requestTokenURL}?${params.map(_.productIterator.mkString("=")).mkString("&")}")
      .get()
      .map { result =>
        println(result.body)

        Ok("")
      }

    /*val redirect     = request.getQueryString("redirect")
    val clientId     = config.clientId
    val responseType = "code"
    val scope        = authConfig.scope // "openid profile email name"
    val claims       = Option(authConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")
    val hash         = env.sign(s"${authConfig.id}:::backoffice")
    val redirectUri  = authConfig.callbackUrl.applyOn {
      case url if !authConfig.useCookie && url.contains("?")  => url + s"&hash=$hash"
      case url if !authConfig.useCookie && !url.contains("?") => url + s"?hash=$hash"
      case url                                                => url
    }
    val loginUrl     =
      s"${config.}?scope=$scope&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
      "hash"                    -> hash,
      "bo-redirect-after-login" -> redirect.getOrElse(
        routes.BackOfficeController.dashboard().absoluteURL(env.exposedRootSchemeIsHttps)
      )
    ).asFuture*/
  }

  override def boLogout(request: RequestHeader,  user: BackOfficeUser, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(Right(None))

  override def boCallback(
                           request: Request[AnyContent],
                           config: GlobalConfig
                         )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) =>
          env.datastores.authConfigsDataStore
            .getUserForToken(token)
            .map(_.flatMap(a => BackOfficeUser.fmt.reads(a).asOpt))
            .map {
              case Some(user) => Right(user)
              case None       => Left("No user found")
            }
        case _           => FastFuture.successful(Left("Forbidden access"))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None       => FastFuture.successful(Left("No Authorization form here"))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).map {
                case false => Left("Bad token")
                case true  => bindAdminUser(username, password)
              }
            }
            case _                                             => {
              FastFuture.successful(Left("Authorization form is not complete"))
            }
          }
        }
      }
    }
  }
}

