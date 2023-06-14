package otoroshi.models

import java.util.concurrent.TimeUnit
import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import otoroshi.auth.{AuthModuleConfig, GenericOauth2Module, ValidableUser}
import otoroshi.env.Env
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{RequestHeader, Result, Results}
import otoroshi.storage.BasicStore
import otoroshi.utils.json.JsonImplicits._
import otoroshi.cluster._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.{MultiAuthModule, NgMultiAuthModuleConfig}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

case class PrivateAppsUser(
    randomId: String,
    name: String,
    email: String,
    profile: JsValue,
    token: JsValue = Json.obj(),
    realm: String,
    authConfigId: String,
    otoroshiData: Option[JsValue],
    createdAt: DateTime = DateTime.now(),
    expiredAt: DateTime = DateTime.now(),
    lastRefresh: DateTime = DateTime.now(),
    tags: Seq[String],
    metadata: Map[String, String],
    location: otoroshi.models.EntityLocation
) extends RefreshableUser
    with ValidableUser
    with otoroshi.models.EntityLocationSupport {

  def theDescription: String           = name
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags

  def picture: Option[String]             = (profile \ "picture").asOpt[String]
  def field(name: String): Option[String] = (profile \ name).asOpt[String]
  def userId: Option[String]              = (profile \ "user_id").asOpt[String].orElse((profile \ "sub").asOpt[String])

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this.copy(expiredAt = DateTime.now().plus(duration.toMillis)), Some(duration))
      .map(_ => this.copy(expiredAt = DateTime.now().plus(duration.toMillis)))

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.privateAppsUserDataStore.delete(randomId)

  def toJson: JsValue        = PrivateAppsUser.fmt.writes(this)
  def lightJson: JsValue     = asJsonCleaned
  def asJsonCleaned: JsValue =
    Json.obj(
      "name"     -> name,
      "email"    -> email,
      "profile"  -> profile,
      "metadata" -> otoroshiData,
      "tags"     -> tags
    )

  def withAuthModuleConfig[A](f: AuthModuleConfig => A)(implicit ec: ExecutionContext, env: Env): Unit = {
    // env.datastores.authConfigsDataStore.findById(authConfigId).map {
    env.proxyState.authModuleAsync(authConfigId).map {
      case None       => ()
      case Some(auth) => f(auth)
    }
  }
  override def updateToken(tok: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.privateAppsUserDataStore.set(
      copy(
        token = tok,
        lastRefresh = DateTime.now()
      ),
      Some((expiredAt.toDate.getTime - System.currentTimeMillis()).millis)
    )
  }

  override def internalId: String = randomId
  override def json: JsValue      = PrivateAppsUser.fmt.writes(this)
}

object PrivateAppsUser {

  def fromCookie(sessionId: String, reqOpt: Option[RequestHeader])(implicit env: Env): Option[PrivateAppsUser] = {
    reqOpt
      .map { req =>
        val verifier =
          JWT.require(Algorithm.HMAC512(env.otoroshiSecret)).withIssuer("otoroshi").acceptLeeway(10).build()
        req.cookies
          .filter(_.name.startsWith("oto-papps-tsess-"))
          .map { cookie =>
            Try(verifier.verify(cookie.value))
          }
          .collect { case Success(decodedToken) =>
            decodedToken
          }
          .find { token =>
            Try(token.getClaim("sessid").asString()).toOption.contains(sessionId)
          }
          .flatMap { token =>
            Try(token.getClaim("sess").asString()).toOption
          }
          .flatMap { encryptedSession =>
            Try(env.aesDecrypt(encryptedSession)).toOption
          }
          .flatMap { decryptedSession =>
            PrivateAppsUser.fmt.reads(Json.parse(decryptedSession)).asOpt
          }
      }
      .collectFirst { case Some(session) =>
        session
      }
  }

  def select(from: JsValue, selector: String): JsValue = {
    val parts = selector.split("\\|")
    parts.foldLeft(from) { (o, rawPath) =>
      val path = rawPath.trim
      (o \ path).asOpt[JsValue].getOrElse(JsNull)
    }
  }

  val fmt = new Format[PrivateAppsUser] {
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          PrivateAppsUser(
            randomId = (json \ "randomId").as[String],
            name = (json \ "name").as[String],
            email = (json \ "email").as[String],
            authConfigId = (json \ "authConfigId").asOpt[String].getOrElse("none"),
            profile = (json \ "profile").as[JsValue],
            token = (json \ "token").asOpt[JsValue].getOrElse(Json.obj()),
            realm = (json \ "realm").asOpt[String].getOrElse("none"),
            otoroshiData = (json \ "otoroshiData").asOpt[JsValue],
            createdAt = new DateTime((json \ "createdAt").as[Long]),
            expiredAt = new DateTime((json \ "expiredAt").as[Long]),
            lastRefresh = new DateTime((json \ "lastRefresh").as[Long]),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            location = otoroshi.models.EntityLocation.readFromKey(json)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get

    override def writes(o: PrivateAppsUser) =
      o.location.jsonWithKey ++ Json.obj(
        "randomId"     -> o.randomId,
        "name"         -> o.name,
        "email"        -> o.email,
        "authConfigId" -> o.authConfigId,
        "profile"      -> o.profile,
        "token"        -> o.token,
        "realm"        -> o.realm,
        "otoroshiData" -> o.otoroshiData,
        "createdAt"    -> o.createdAt.toDate.getTime,
        "expiredAt"    -> o.expiredAt.toDate.getTime,
        "lastRefresh"  -> o.lastRefresh.toDate.getTime,
        "metadata"     -> o.metadata,
        "tags"         -> JsArray(o.tags.map(JsString.apply))
      )
  }
}

trait PrivateAppsUserDataStore extends BasicStore[PrivateAppsUser]

object PrivateAppsUserHelper {

  import otoroshi.utils.http.RequestImplicits._

  case class PassWithAuthContext(
      req: RequestHeader,
      query: ServiceDescriptorQuery,
      descriptor: ServiceDescriptor,
      attrs: TypedMap,
      config: GlobalConfig,
      logger: Logger
  )

  def isPrivateAppsSessionValid(req: RequestHeader, desc: ServiceDescriptor, attrs: TypedMap)(implicit
      executionContext: ExecutionContext,
      env: Env
  ): Future[Option[PrivateAppsUser]] = {
    attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case Some(preExistingUser) => FastFuture.successful(Some(preExistingUser))
      case _                     => {
        desc.authConfigRef match {
          case Some(ref) =>
            env.proxyState.authModuleAsync(ref).flatMap {
              case None       => FastFuture.successful(None)
              case Some(auth) => isPrivateAppsSessionValidWithAuth(req, desc, auth)
            }
          case None      =>
            FastFuture.successful(None)
        }
      }
    }
  }

  def isPrivateAppsSessionValidWithAuth(req: RequestHeader, desc: ServiceDescriptor, auth: AuthModuleConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[PrivateAppsUser]] = {
    val expected = "oto-papps-" + auth.cookieSuffix(desc)
    req.cookies
      .get(expected)
      .flatMap { cookie =>
        env.extractPrivateSessionId(cookie)
      }
      .orElse(
        req.getQueryString("pappsToken").flatMap(value => env.extractPrivateSessionIdFromString(value))
      )
      .orElse(
        req.headers.get("Otoroshi-Token").flatMap(value => env.extractPrivateSessionIdFromString(value))
      )
      .map { id =>
        if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"private apps session checking for $id - from helper")
        env.datastores.privateAppsUserDataStore.findById(id).flatMap {
          case Some(user)                                           =>
            GenericOauth2Module.handleTokenRefresh(auth, user)
            FastFuture.successful(Some(user))
          case None if env.clusterConfig.mode == ClusterMode.Worker => {
            if (Cluster.logger.isDebugEnabled)
              Cluster.logger.debug(s"private apps session $id not found locally - from helper")
            env.clusterAgent.isSessionValid(id, Some(req)).map {
              case Some(user) =>
                user.save(
                  Duration(user.expiredAt.getMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                )
                Some(user)
              case None       => None
            }
          }
          case None                                                 => FastFuture.successful(None)
        }
      } getOrElse {
      FastFuture.successful(None)
    }
  }

  def isPrivateAppsSessionValidWithMultiAuth(req: RequestHeader, route: NgRoute)(implicit
      ec: ExecutionContext,
      env: Env
  ) = {
    route.plugins
      .getPluginByClass[MultiAuthModule]
      .map(multiAuth =>
        NgMultiAuthModuleConfig.format.reads(multiAuth.config.raw) match {
          case JsSuccess(config, _) =>
            req.cookies.filter(cookie => cookie.name.startsWith("oto-papps")) match {
              case cookies if cookies.nonEmpty =>
                val r: Future[Option[JsObject]] = config.modules
                  .flatMap(module => env.proxyState.authModule(module))
                  .find(module =>
                    cookies.exists(cookie => cookie.name == s"oto-papps-${module.routeCookieSuffix(route)}")
                  )
                  .map(authModuleConfig =>
                    PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(req, route.legacy, authModuleConfig).map {
                      case None          => None
                      case Some(session) => (session.profile.as[JsObject] ++ Json.obj("access_type" -> "session")).some
                    }
                  )
                  .getOrElse(None.vfuture)

                r
            }
          case JsError(_)           => None.vfuture
        }
      )
      .getOrElse(None.vfuture)
  }

  def passWithAuth[T](
      ctx: PassWithAuthContext,
      callDownstream: (GlobalConfig, Option[ApiKey], Option[PrivateAppsUser]) => Future[Either[Result, T]],
      errorResult: (Results.Status, String, String) => Future[Either[Result, T]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, T]] = {

    val PassWithAuthContext(req, query, descriptor, attrs, config, logger) = ctx

    isPrivateAppsSessionValid(req, descriptor, attrs).flatMap {
      case Some(paUsr) =>
        callDownstream(config, None, Some(paUsr))
      case None        => {
        val redirect   = req
          .getQueryString("redirect")
          .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}")
        val redirectTo =
          env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
            .confidentialAppLoginPage()
            .url + s"?desc=${descriptor.id}&redirect=${redirect}"
        if (logger.isTraceEnabled) logger.trace("should redirect to " + redirectTo)
        descriptor.authConfigRef match {
          case None      =>
            errorResult(
              InternalServerError,
              "Auth. config. ref not found on the descriptor",
              "errors.auth.config.ref.not.found"
            )
          case Some(ref) => {
            // env.datastores.authConfigsDataStore.findById(ref).flatMap {
            env.proxyState.authModuleAsync(ref).flatMap {
              case None       =>
                errorResult(
                  InternalServerError,
                  "Auth. config. not found on the descriptor",
                  "errors.auth.config.not.found"
                )
              case Some(auth) => {
                FastFuture
                  .successful(
                    Left(
                      Results
                        .Redirect(redirectTo)
                        .discardingCookies(
                          env.removePrivateSessionCookies(
                            query.toHost,
                            descriptor,
                            auth
                          ): _*
                        )
                    )
                  )
              }
            }
          }
        }
      }
    }
  }
}
