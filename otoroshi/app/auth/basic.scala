package auth

import java.security.SecureRandom
import java.util.Optional

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.google.common.base.Charsets
import com.yubico.webauthn._
import com.yubico.webauthn.data._
import controllers.{LocalCredentialRepository, routes}
import env.Env
import models._
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.{OtoroshiAdminType, UserRight, UserRights, WebAuthnOtoroshiAdmin}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class WebAuthnDetails(handle: String, credentials: Map[String, JsValue]) {
  def asJson: JsValue = WebAuthnDetails.fmt.writes(this)
}

object WebAuthnDetails {
  def fmt = new Format[WebAuthnDetails] {
    override def writes(o: WebAuthnDetails) = Json.obj(
      "handle"       -> o.handle,
      "credentials" -> o.credentials
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          WebAuthnDetails(
            handle = (json \ "handle").as[String],
            credentials = (json \ "credentials").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

case class BasicAuthUser(
    name: String,
    password: String,
    email: String,
    webauthn: Option[WebAuthnDetails] = None,
    metadata: JsObject = Json.obj(),
    rights: UserRights
) {
  def asJson: JsValue = BasicAuthUser.fmt.writes(this)
}

object BasicAuthUser {
  def fmt = new Format[BasicAuthUser] {
    override def writes(o: BasicAuthUser) = Json.obj(
      "name"     -> o.name,
      "password" -> o.password,
      "email"    -> o.email,
      "metadata" -> o.metadata,
      "webauthn" -> o.webauthn.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "rights"   -> o.rights.json
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          BasicAuthUser(
            name = (json \ "name").as[String],
            password = (json \ "password").as[String],
            email = (json \ "email").as[String],
            webauthn = (json \ "webauthn").asOpt(WebAuthnDetails.fmt),
            metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
            rights = UserRights.readFromObject(json)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object BasicAuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-basic-auth-config")

  def fromJsons(value: JsValue): BasicAuthModuleConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[BasicAuthModuleConfig] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[BasicAuthModuleConfig])
    }

    override def writes(o: BasicAuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        BasicAuthModuleConfig(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          basicAuth = (json \ "basicAuth").asOpt[Boolean].getOrElse(false),
          webauthn = (json \ "webauthn").asOpt[Boolean].getOrElse(false),
          users = (json \ "users").asOpt(Reads.seq(BasicAuthUser.fmt)).getOrElse(Seq.empty[BasicAuthUser]),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          sessionCookieValues = (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues())
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class BasicAuthModuleConfig(
                                  id: String,
                                  name: String,
                                  desc: String,
                                  users: Seq[BasicAuthUser] = Seq.empty[BasicAuthUser],
                                  sessionMaxAge: Int = 86400,
                                  basicAuth: Boolean = false,
                                  webauthn: Boolean = false,
                                  metadata: Map[String, String],
                                  sessionCookieValues: SessionCookieValues,
                                  location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends AuthModuleConfig {
  def `type`: String                                        = "basic"
  override def authModule(config: GlobalConfig): AuthModule = BasicAuthModule(this)
  override def asJson = location.jsonWithKey ++ Json.obj(
    "type"          -> "basic",
    "id"                  -> this.id,
    "name"                -> this.name,
    "desc"                -> this.desc,
    "basicAuth"           -> this.basicAuth,
    "webauthn"            -> this.webauthn,
    "sessionMaxAge"       -> this.sessionMaxAge,
    "metadata"            -> this.metadata,
    "users"               -> Writes.seq(BasicAuthUser.fmt).writes(this.users),
    "sessionCookieValues"  -> SessionCookieValues.fmt.writes(this.sessionCookieValues)
  )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                   = s"basic-auth-$id"
}

case class BasicAuthModule(authConfig: BasicAuthModuleConfig) extends AuthModule {

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def extractUsernamePassword(header: String): Option[(String, String)] = {
    val base64 = header.replace("Basic ", "").replace("basic ", "")
    Option(base64)
      .map(decodeBase64)
      .map(_.split(":").toSeq)
      .flatMap(a => a.headOption.flatMap(head => a.lastOption.map(last => (head, last))))

  }

  def bindUser(username: String, password: String, descriptor: ServiceDescriptor): Either[String, PrivateAppsUser] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCrypt.checkpw(password, u.password)) match {
      case Some(user) =>
        Right(
          PrivateAppsUser(
            randomId = IdGenerator.token(64),
            name = user.name,
            email = user.email,
            profile = user.asJson,
            realm = authConfig.cookieSuffix(descriptor),
            otoroshiData = Some(user.metadata),
            authConfigId = authConfig.id,
            metadata = Map.empty,
            location = authConfig.location
          )
        )
      case None => Left(s"You're not authorized here")
    }
  }

  def bindAdminUser(username: String, password: String): Either[String, BackOfficeUser] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCrypt.checkpw(password, u.password)) match {
      case Some(user) =>
        Right(
          BackOfficeUser(
            randomId = IdGenerator.token(64),
            name = user.name,
            email = user.email,
            profile = user.asJson,
            simpleLogin = false,
            authConfigId = authConfig.id,
            metadata = Map.empty,
            rights = user.rights,
            location = authConfig.location
          )
        )
      case None => Left(s"You're not authorized here")
    }
  }

  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    val hash         = env.sign(s"${authConfig.id}:::${descriptor.id}")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>
      if (authConfig.basicAuth) {

        def unauthorized() =
          Results
            .Unauthorized("")
            .withHeaders("WWW-Authenticate" -> s"""Basic realm="${authConfig.cookieSuffix(descriptor)}"""")
            .addingToSession(
              s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
                routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
              )
            )
            .future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") =>
            extractUsernamePassword(auth) match {
              case None => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindUser(username, password, descriptor) match {
                  case Left(_) => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
                  case Right(user) =>
                    env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                      Results.Redirect(s"/privateapps/generic/callback?desc=${descriptor.id}&token=$token&hash=$hash")
                    }
                }
            }
          case _ => unauthorized()
        }
      } else {
        Results
          .Ok(
            views.html.otoroshi
              .login(s"/privateapps/generic/callback?desc=${descriptor.id}&hash=$hash", "POST", token, authConfig.webauthn, env)
          )
          .addingToSession(
            s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
              routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
          .future
      }
    }
  }

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ) = FastFuture.successful(None)

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) =>
          env.datastores.authConfigsDataStore
            .getUserForToken(token)
            .map(_.flatMap(a => PrivateAppsUser.fmt.reads(a).asOpt))
            .map {
              case Some(user) => Right(user)
              case None       => Left("No user found")
            }
        case _ => FastFuture.successful(Left("Forbidden access"))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None => FastFuture.successful(Left("No Authorization form here"))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).map {
                case false => Left("Bad token")
                case true =>
                  authConfig.users
                    .find(u => u.email == username)
                    .filter(u => BCrypt.checkpw(password, u.password)) match {
                    case Some(user) =>
                      Right(
                        PrivateAppsUser(
                          randomId = IdGenerator.token(64),
                          name = user.name,
                          email = user.email,
                          profile = user.asJson,
                          realm = authConfig.cookieSuffix(descriptor),
                          otoroshiData = Some(user.metadata),
                          authConfigId = authConfig.id,
                          metadata = Map.empty,
                          location = authConfig.location
                        )
                      )
                    case None => Left(s"You're not authorized here")
                  }
              }
            }
            case _ => {
              FastFuture.successful(Left("Authorization form is not complete"))
            }
          }
        }
      }
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    val hash         = env.sign(s"${authConfig.id}:::backoffice")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>
      if (authConfig.basicAuth) {

        def unauthorized() =
          Results
            .Unauthorized(views.html.otoroshi.error("You are not authorized here", env))
            .withHeaders("WWW-Authenticate" -> "otoroshi-admin-realm")
            .addingToSession(
              "bo-redirect-after-login" -> redirect.getOrElse(
                routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
              )
            )
            .future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") =>
            extractUsernamePassword(auth) match {
              case None => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindAdminUser(username, password) match {
                  case Left(_) => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
                  case Right(user) =>
                    env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                      Results.Redirect(s"/backoffice/auth0/callback?token=$token&hash=$hash")
                    }
                }
            }
          case _ => unauthorized()
        }
      } else {
        Results
          .Ok(views.html.otoroshi.login(s"/backoffice/auth0/callback?hash=$hash", "POST", token, authConfig.webauthn, env))
          .addingToSession(
            "bo-redirect-after-login" -> redirect.getOrElse(
              routes.BackOfficeController.dashboard().absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
          .future
      }
    }
  }
  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(None)

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
        case _ => FastFuture.successful(Left("Forbidden access"))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None => FastFuture.successful(Left("No Authorization form here"))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).map {
                case false => Left("Bad token")
                case true  => bindAdminUser(username, password)
              }
            }
            case _ => {
              FastFuture.successful(Left("Authorization form is not complete"))
            }
          }
        }
      }
    }
  }

  /////////// Webauthn

  private val base64Encoder = java.util.Base64.getUrlEncoder
  private val base64Decoder = java.util.Base64.getUrlDecoder
  private val random        = new SecureRandom()
  private val jsonMapper = new ObjectMapper()
    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    .setSerializationInclusion(Include.NON_ABSENT)
    .registerModule(new Jdk8Module())

  def webAuthnLoginStart(
      body: JsValue,
      descriptor: ServiceDescriptor
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {

    import collection.JavaConverters._

    val usernameOpt   = (body \ "username").asOpt[String]
    val passwordOpt   = (body \ "password").asOpt[String]
    val reqOrigin     = (body \ "origin").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials =usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(password)) => {
        bindUser(username, password, descriptor).toOption match {
          case Some(_) => {
            val rpIdentity: RelyingPartyIdentity =
              RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
            val rp: RelyingParty = RelyingParty.builder
              .identity(rpIdentity)
              .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
              .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
              .build
            val request: AssertionRequest =
              rp.startAssertion(StartAssertionOptions.builder.username(Optional.of(username)).build)

            val registrationRequestId = IdGenerator.token(32)
            val jsonRequest: String   = jsonMapper.writeValueAsString(request)
            val finalRequest = Json.obj(
              "requestId" -> registrationRequestId,
              "request"   -> Json.parse(jsonRequest),
              "username"  -> username,
              "label"     -> "--"
            )

            env.datastores.webAuthnRegistrationsDataStore
              .setRegistrationRequest(registrationRequestId, finalRequest)
              .map { _ =>
                Right(finalRequest)
              }
          }
          case _ => FastFuture.successful(Left("bad request"))
        }
      }
      case (_, _) => {
        FastFuture.successful(Left("bad request"))
      }
    }
  }

  def webAuthnAdminLoginStart(body: JsValue)(implicit env: Env,
                                             ec: ExecutionContext): Future[Either[String, JsValue]] = {

    import collection.JavaConverters._

    val usernameOpt   = (body \ "username").asOpt[String]
    val passwordOpt   = (body \ "password").asOpt[String]
    val reqOrigin     = (body \ "origin").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials = usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(password)) => {
        bindAdminUser(username, password).toOption match {
          case Some(_) => {
            val rpIdentity: RelyingPartyIdentity =
              RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
            val rp: RelyingParty = RelyingParty.builder
              .identity(rpIdentity)
              .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
              .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
              .build
            val request: AssertionRequest =
              rp.startAssertion(StartAssertionOptions.builder.username(Optional.of(username)).build)

            val registrationRequestId = IdGenerator.token(32)
            val jsonRequest: String   = jsonMapper.writeValueAsString(request)
            val finalRequest = Json.obj(
              "requestId" -> registrationRequestId,
              "request"   -> Json.parse(jsonRequest),
              "username"  -> username,
              "label"     -> "--"
            )

            env.datastores.webAuthnRegistrationsDataStore
              .setRegistrationRequest(registrationRequestId, finalRequest)
              .map { _ =>
                Right(finalRequest)
              }
          }
          case _ => FastFuture.successful(Left("bad request"))
        }
      }
      case (_, _) => {
        FastFuture.successful(Left("bad request"))
      }
    }
  }

  def webAuthnLoginFinish(
      body: JsValue,
      descriptor: ServiceDescriptor
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, PrivateAppsUser]] = {

    import collection.JavaConverters._

    val json          = body
    val webauthn      = (json \ "webauthn").as[JsObject]
    val otoroshi      = (json \ "otoroshi").as[JsObject]
    val reqOrigin     = (otoroshi \ "origin").as[String]
    val reqId         = (json \ "requestId").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials = usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    val usernameOpt = (otoroshi \ "username").asOpt[String]
    val passwordOpt = (otoroshi \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        users.find(u => u.username == username) match {
          case None => FastFuture.successful(Left("Bad user"))
          case Some(user) => {
            env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(reqId).flatMap {
              case None => FastFuture.successful(Left("bad request"))
              case Some(rawRequest) => {
                val request =
                  jsonMapper.readValue(Json.stringify((rawRequest \ "request").as[JsValue]), classOf[AssertionRequest])

                bindUser(username, pass, descriptor) match {
                  case Left(err) => FastFuture.successful(Left(err))
                  case Right(user) => {
                    val rpIdentity: RelyingPartyIdentity =
                      RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
                    val rp: RelyingParty = RelyingParty.builder
                      .identity(rpIdentity)
                      .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
                      .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
                      .build
                    val pkc = PublicKeyCredential.parseAssertionResponseJson(Json.stringify(webauthn))
                    Try(
                      rp.finishAssertion(
                        FinishAssertionOptions
                          .builder()
                          .request(request)
                          .response(pkc)
                          .build()
                      )
                    ) match {
                      case Failure(e) =>
                        FastFuture.successful(Left("bad request"))
                      case Success(result) if !result.isSuccess =>
                        FastFuture.successful(Left("bad request"))
                      case Success(result) if result.isSuccess => {
                        FastFuture.successful(Right(user))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      case (_, _) => FastFuture.successful(Left("Not Authorized"))
    }
  }

  def webAuthnAdminLoginFinish(body: JsValue)(implicit env: Env,
                                              ec: ExecutionContext): Future[Either[String, BackOfficeUser]] = {

    import collection.JavaConverters._

    val json          = body
    val webauthn      = (json \ "webauthn").as[JsObject]
    val otoroshi      = (json \ "otoroshi").as[JsObject]
    val reqOrigin     = (otoroshi \ "origin").as[String]
    val reqId         = (json \ "requestId").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials = usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    val usernameOpt = (otoroshi \ "username").asOpt[String]
    val passwordOpt = (otoroshi \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        users.find(u => u.username == username) match {
          case None => FastFuture.successful(Left("Bad user"))
          case Some(user) => {
            env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(reqId).flatMap {
              case None => FastFuture.successful(Left("bad request"))
              case Some(rawRequest) => {
                val request =
                  jsonMapper.readValue(Json.stringify((rawRequest \ "request").as[JsValue]), classOf[AssertionRequest])

                bindAdminUser(username, pass) match {
                  case Left(err) => FastFuture.successful(Left(err))
                  case Right(user) => {
                    val rpIdentity: RelyingPartyIdentity =
                      RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
                    val rp: RelyingParty = RelyingParty.builder
                      .identity(rpIdentity)
                      .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
                      .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
                      .build
                    val pkc = PublicKeyCredential.parseAssertionResponseJson(Json.stringify(webauthn))
                    Try(
                      rp.finishAssertion(
                        FinishAssertionOptions
                          .builder()
                          .request(request)
                          .response(pkc)
                          .build()
                      )
                    ) match {
                      case Failure(e) =>
                        FastFuture.successful(Left("bad request"))
                      case Success(result) if !result.isSuccess =>
                        FastFuture.successful(Left("bad request"))
                      case Success(result) if result.isSuccess => {
                        FastFuture.successful(Right(user))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      case (_, _) => FastFuture.successful(Left("Not Authorized"))
    }
  }

  def webAuthnRegistrationStart(body: JsValue)(implicit env: Env,
                                               ec: ExecutionContext): Future[Either[String, JsValue]] = {

    import collection.JavaConverters._

    val username      = (body \ "username").as[String]
    val label         = (body \ "label").as[String]
    val reqOrigin     = (body \ "origin").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials = usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    val rpIdentity: RelyingPartyIdentity = RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
    val rp: RelyingParty = RelyingParty.builder
      .identity(rpIdentity)
      .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
      .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
      .build

    val userHandle = new Array[Byte](64)
    random.nextBytes(userHandle)

    val registrationRequestId = IdGenerator.token(32)
    val request: PublicKeyCredentialCreationOptions = rp.startRegistration(
      StartRegistrationOptions.builder
        .user(
          UserIdentity.builder
            .name(username)
            .displayName(label)
            .id(new ByteArray(userHandle))
            .build
        )
        .build
    )

    val jsonRequest = jsonMapper.writeValueAsString(request)
    val finalRequest = Json.obj(
      "requestId" -> registrationRequestId,
      "request"   -> Json.parse(jsonRequest),
      "username"  -> username,
      "label"     -> label,
      "handle"    -> base64Encoder.encodeToString(userHandle)
    )

    env.datastores.webAuthnRegistrationsDataStore.setRegistrationRequest(registrationRequestId, finalRequest).map { _ =>
      Right(finalRequest)
    }
  }

  def webAuthnRegistrationFinish(body: JsValue)(implicit env: Env,
                                                ec: ExecutionContext): Future[Either[String, JsValue]] = {

    import collection.JavaConverters._

    val json          = body
    val responseJson  = Json.stringify((json \ "webauthn").as[JsValue])
    val otoroshi      = (json \ "otoroshi").as[JsObject]
    val reqOrigin     = (otoroshi \ "origin").as[String]
    val reqId         = (json \ "requestId").as[String]
    val handle        = (otoroshi \ "handle").as[String]
    val reqOriginHost = Uri(reqOrigin).authority.host.address()
    val reqOriginDomain: String = reqOriginHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }

    val users = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
      WebAuthnOtoroshiAdmin(
        username = usr.email,
        password = "foo",
        label = "foo",
        handle = usr.webauthn.get.handle,
        credentials = usr.webauthn.get.credentials,
        createdAt = DateTime.now(),
        typ = OtoroshiAdminType.WebAuthnAdmin,
        metadata = Map.empty,
        rights = usr.rights,
        location = authConfig.location
      )
    }

    val rpIdentity: RelyingPartyIdentity = RelyingPartyIdentity.builder.id(reqOriginDomain).name("Otoroshi").build
    val rp: RelyingParty = RelyingParty.builder
      .identity(rpIdentity)
      .credentialRepository(new LocalCredentialRepository(users, jsonMapper, base64Decoder))
      .origins(Seq(reqOrigin, reqOriginDomain).toSet.asJava)
      .build
    val pkc = PublicKeyCredential.parseRegistrationResponseJson(responseJson)

    env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(reqId).flatMap {
      case None => FastFuture.successful(Left("bad request"))
      case Some(rawRequest) => {
        val request = jsonMapper.readValue(Json.stringify((rawRequest \ "request").as[JsValue]),
                                           classOf[PublicKeyCredentialCreationOptions])

        Try(
          rp.finishRegistration(
            FinishRegistrationOptions
              .builder()
              .request(request)
              .response(pkc)
              .build()
          )
        ) match {
          case Failure(e) =>
            e.printStackTrace()
            FastFuture.successful(Left("bad request"))
          case Success(result) => {
            val username           = (otoroshi \ "username").as[String]
            val password           = (otoroshi \ "password").as[String]
            val label              = (otoroshi \ "label").as[String]
            val saltedPassword     = BCrypt.hashpw(password, BCrypt.gensalt())
            val credential         = Json.parse(jsonMapper.writeValueAsString(result))
            val user               = authConfig.users.find(_.email == username).get
            val newUser = user.webauthn match {
              case None => {
                user.copy(
                  webauthn = Some(
                    WebAuthnDetails(
                      handle = handle,
                      credentials = Map((credential \ "keyId" \ "id").as[String] -> credential)
                    )
                  )
                )
              }
              case Some(wbathn) => {
                user.copy(
                  webauthn = Some(
                    WebAuthnDetails(
                      handle = wbathn.handle,
                      credentials = wbathn.credentials + ((credential \ "keyId" \ "id").as[String] -> credential)
                    )
                  )
                )
              }
            }
            val conf = authConfig.copy(users = authConfig.users.filterNot(_.email == username) :+ newUser)
            conf.save().map { _ =>
              Right(Json.obj("username" -> username))
            }
          }
        }
      }
    }

  }

  def webAuthnRegistrationDelete(user: BasicAuthUser)(implicit env: Env,
                                                      ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val conf = authConfig.copy(users = authConfig.users.filterNot(_.email == user.email) :+ user.copy(webauthn = None))
    conf.save().map { _ =>
      Right(Json.obj("username" -> user.email))
    }
  }
}
