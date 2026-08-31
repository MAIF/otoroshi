package otoroshi.auth

import java.util.Base64
import org.apache.pekko.http.scaladsl.util.FastFuture
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.models.*
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.auth.implicits.ResultWithPrivateAppSession
import otoroshi.models.{OtoroshiAdminType, UserRight, UserRights, WebAuthnOtoroshiAdmin}
import otoroshi.utils.crypto.BCryptHelper
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.*
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.{JsonPathValidator, JsonValidator}

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class WebAuthnDetails(handle: String, credentials: Map[String, JsValue]) {
  def asJson: JsValue = WebAuthnDetails.fmt.writes(this)
}

object WebAuthnDetails {
  def fmt =
    new Format[WebAuthnDetails] {
      override def writes(o: WebAuthnDetails) =
        Json.obj(
          "handle"      -> o.handle,
          "credentials" -> o.credentials
        )
      override def reads(json: JsValue)       =
        Try {
          JsSuccess(
            WebAuthnDetails(
              handle = (json \ "handle").as[String],
              credentials = (json \ "credentials").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

case class BasicAuthUser(
    name: String,
    password: String,
    email: String,
    webauthn: Option[WebAuthnDetails] = None,
    metadata: JsObject = Json.obj(),
    tags: Seq[String],
    rights: UserRights,
    adminEntityValidators: Map[String, Seq[JsonValidator]]
) {
  def asJson: JsValue = BasicAuthUser.fmt.writes(this)
}

object BasicAuthUser {
  def fmt =
    new Format[BasicAuthUser] {
      override def writes(o: BasicAuthUser) =
        Json.obj(
          "name"                  -> o.name,
          "password"              -> o.password,
          "email"                 -> o.email,
          "metadata"              -> o.metadata,
          "tags"                  -> o.tags,
          "webauthn"              -> o.webauthn.map(_.asJson).getOrElse(JsNull).as[JsValue],
          "rights"                -> o.rights.json,
          "adminEntityValidators" -> o.adminEntityValidators.view.mapValues(v => JsArray(v.map(_.json))).toMap
        )
      override def reads(json: JsValue)     =
        Try {
          JsSuccess(
            BasicAuthUser(
              name = (json \ "name").as[String],
              password = (json \ "password").as[String],
              email = (json \ "email").as[String],
              webauthn = (json \ "webauthn").asOpt(using WebAuthnDetails.fmt),
              metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
              tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
              rights = UserRights.readFromObject(json),
              adminEntityValidators = json
                .select("adminEntityValidators")
                .asOpt[JsObject]
                .map { obj =>
                  obj.value.view.mapValues { arr =>
                    arr.asArray.value.toSeq
                      .map { item =>
                        JsonValidator.format.reads(item)
                      }
                      .collect { case JsSuccess(v, _) =>
                        v
                      }.toSeq
                  }.toMap
                }
                .getOrElse(Map.empty[String, Seq[JsonValidator]])
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
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

    override def reads(json: JsValue) =
      fromJson(json) match {
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
          clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          basicAuth = (json \ "basicAuth").asOpt[Boolean].getOrElse(false),
          webauthn = (json \ "webauthn").asOpt[Boolean].getOrElse(false),
          users = (json \ "users").asOpt(using Reads.seq(using BasicAuthUser.fmt)).getOrElse(Seq.empty[BasicAuthUser]).toSeq,
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq,
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(using SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
          userValidators = (json \ "userValidators")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
            .getOrElse(Seq.empty).toSeq,
          remoteValidators = (json \ "remoteValidators")
            .asOpt[Seq[JsValue]]
            .map(_.flatMap(v => RemoteUserValidatorSettings.format.reads(v).asOpt))
            .getOrElse(Seq.empty).toSeq,
          allowedUsers = json.select("allowedUsers").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
          deniedUsers = json.select("deniedUsers").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
        )
      )
    } recover { case e =>
      Left(e)
    } get
}

case class BasicAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    users: Seq[BasicAuthUser] = Seq.empty[BasicAuthUser],
    clientSideSessionEnabled: Boolean,
    sessionMaxAge: Int = 86400,
    userValidators: Seq[JsonPathValidator] = Seq.empty,
    remoteValidators: Seq[RemoteUserValidatorSettings] = Seq.empty,
    basicAuth: Boolean = false,
    webauthn: Boolean = false,
    tags: Seq[String],
    metadata: Map[String, String],
    sessionCookieValues: SessionCookieValues,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    allowedUsers: Seq[String] = Seq.empty,
    deniedUsers: Seq[String] = Seq.empty
) extends AuthModuleConfig {
  def `type`: String                                                    = "basic"
  def humanName: String                                                 = "In memory auth. provider"
  override def form: Option[Form]                                       = None
  override def withLocation(location: EntityLocation): AuthModuleConfig = copy(location = location)
  override def authModule(config: GlobalConfig): AuthModule             = BasicAuthModule(this)
  override def asJson                                                   =
    location.jsonWithKey ++ Json.obj(
      "type"                     -> "basic",
      "id"                       -> this.id,
      "name"                     -> this.name,
      "desc"                     -> this.desc,
      "basicAuth"                -> this.basicAuth,
      "webauthn"                 -> this.webauthn,
      "clientSideSessionEnabled" -> this.clientSideSessionEnabled,
      "sessionMaxAge"            -> this.sessionMaxAge,
      "metadata"                 -> this.metadata,
      "tags"                     -> JsArray(tags.map(JsString.apply)),
      "users"                    -> Writes.seq(using BasicAuthUser.fmt).writes(this.users),
      "sessionCookieValues"      -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "userValidators"           -> JsArray(userValidators.map(_.json)),
      "allowedUsers"             -> this.allowedUsers,
      "deniedUsers"              -> this.deniedUsers,
      "remoteValidators"         -> JsArray(remoteValidators.map(_.json))
    )
  def save()(using ec: ExecutionContext, env: Env): Future[Boolean]  = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                    = s"basic-auth-$id"
  def theDescription: String                                            = desc
  def theMetadata: Map[String, String]                                  = metadata
  def theName: String                                                   = name
  def theTags: Seq[String]                                              = tags

  override def _fmt()(using env: Env): Format[AuthModuleConfig] = AuthModuleConfig._fmt(env)
}

object BasicAuthModule {
  def defaultConfig = BasicAuthModuleConfig(
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "New auth. module",
    desc = "New auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues(),
    clientSideSessionEnabled = true
  )
//  def apply(): BasicAuthModule = BasicAuthModule(defaultConfig)
}

case class BasicAuthModule(authConfig: BasicAuthModuleConfig) extends AuthModule {

  def this() = this(BasicAuthModule.defaultConfig)

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), StandardCharsets.UTF_8)
  def extractUsernamePassword(header: String): Option[(String, String)] = {
    val base64 = header.replace("Basic ", "").replace("basic ", "")
    Option(base64)
      .map(decodeBase64)
      .map(_.split(":").toSeq)
      .filter(v => v.nonEmpty && v.length > 1)
      .flatMap(a => a.headOption.map(head => (head, a.tail.mkString(":"))))

  }

  def bindUser(username: String, password: String, descriptor: ServiceDescriptor)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ErrorReason, PrivateAppsUser]] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCryptHelper.checkpw(password, u.password)) match {
      case Some(user) =>
        PrivateAppsUser(
          randomId = IdGenerator.token(64),
          name = user.name,
          email = user.email,
          profile = Json.obj(
            "name"     -> user.name,
            "email"    -> user.email,
            "metadata" -> user.metadata,
            "tags"     -> user.tags
          ),
          realm = authConfig.cookieSuffix(descriptor),
          otoroshiData = Some(user.metadata),
          authConfigId = authConfig.id,
          tags = Seq.empty,
          metadata = Map.empty,
          location = authConfig.location
        ).validate(descriptor, isRoute = true, authConfig)
      case None       => Left(ErrorReason(s"You're not authorized here")).vfuture
    }
  }

  def bindAdminUser(username: String, password: String, descriptor: ServiceDescriptor)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ErrorReason, BackOfficeUser]] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCryptHelper.checkpw(password, u.password)) match {
      case Some(user) =>
        BackOfficeUser(
          randomId = IdGenerator.token(64),
          name = user.name,
          email = user.email,
          profile = Json.obj(
            "name"     -> user.name,
            "email"    -> user.email,
            "metadata" -> user.metadata,
            "tags"     -> user.tags
          ),
          simpleLogin = false,
          authConfigId = authConfig.id,
          tags = Seq.empty,
          metadata = Map.empty,
          rights = user.rights,
          adminEntityValidators = user.adminEntityValidators,
          location = authConfig.location
        ).validate(descriptor, isRoute = true, authConfig)
      case None       => Left(ErrorReason(s"You're not authorized here")).vfuture
    }
  }

  override def paLoginPage(
      request: RequestHeader,
      config: GlobalConfig,
      descriptor: ServiceDescriptor,
      isRoute: Boolean
  )(using
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req = request
    val redirect     = request
      .getQueryString("redirect")
      .filter(redirect =>
        request.getQueryString("hash").contains(env.sign(s"desc=${descriptor.id}&redirect=${redirect}")) ||
        request.getQueryString("hash").contains(env.sign(s"route=${descriptor.id}&redirect=${redirect}"))
      )
      .map(redirectBase64Encoded =>
        new String(Base64.getUrlDecoder.decode(redirectBase64Encoded), StandardCharsets.UTF_8)
      )
    val hash         = env.sign(s"${authConfig.id}:::${descriptor.id}")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>
      if (authConfig.basicAuth) {

        def unauthorized() =
          Results
            .Unauthorized("")
            .withHeaders("WWW-Authenticate" -> s"""Basic realm="${authConfig.cookieSuffix(descriptor)}"""")
            .addingToPrivateAppSession(
              s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
                routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
              )
            )
            .future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") =>
            extractUsernamePassword(auth) match {
              case None                       => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindUser(username, password, descriptor) flatMap {
                  case Left(_)     => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
                  case Right(user) =>
                    env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                      if (isRoute) {
                        Results.Redirect(
                          s"/privateapps/generic/callback?route=true&ref=${authConfig.id}&desc=${descriptor.id}&token=$token&hash=$hash"
                        )
                      } else {
                        Results.Redirect(s"/privateapps/generic/callback?desc=${descriptor.id}&token=$token&hash=$hash")
                      }
                    }
                }
            }
          case _                                       => unauthorized()
        }
      } else {
        Results
          .Ok(
            otoroshi.views.html.oto
              .login(
                if (isRoute)
                  s"/privateapps/generic/callback?route=true&ref=${authConfig.id}&desc=${descriptor.id}&hash=$hash"
                else
                  s"/privateapps/generic/callback?desc=${descriptor.id}&hash=$hash",
                "POST",
                token,
                authConfig.webauthn,
                env
              )
          )
          .addingToPrivateAppSession(
            s"pa-redirect-after-login-${authConfig.cookieSuffix(descriptor)}" -> redirect.getOrElse(
              routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
          .future
      }
    }
  }

  override def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(using
      ec: ExecutionContext,
      env: Env
  ) = FastFuture.successful(Right(None))

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, PrivateAppsUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) =>
          env.datastores.authConfigsDataStore
            .getUserForToken(token)
            .map(_.flatMap(a => PrivateAppsUser.fmt.reads(a).asOpt))
            .flatMap {
              case Some(user) =>
                user.validate(
                  descriptor,
                  isRoute = true,
                  authConfig
                )
              case None       => Left(ErrorReason("No user found")).vfuture
            }
        case _           => FastFuture.successful(Left(ErrorReason("Forbidden access")))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None       => FastFuture.successful(Left(ErrorReason("No Authorization form here")))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).flatMap {
                case false => Left(ErrorReason("Bad token")).vfuture
                case true  =>
                  authConfig.users
                    .find(u => u.email == username)
                    .filter(u => BCryptHelper.checkpw(password, u.password)) match {
                    case Some(user) =>
                      PrivateAppsUser(
                        randomId = IdGenerator.token(64),
                        name = user.name,
                        email = user.email,
                        profile = Json.obj(
                          "name"     -> user.name,
                          "email"    -> user.email,
                          "metadata" -> user.metadata,
                          "tags"     -> user.tags
                        ),
                        realm = authConfig.cookieSuffix(descriptor),
                        otoroshiData = Some(user.metadata),
                        authConfigId = authConfig.id,
                        tags = Seq.empty,
                        metadata = Map.empty,
                        location = authConfig.location
                      ).validate(
                        descriptor,
                        isRoute = true,
                        authConfig
                      )
                    case None       => Left(ErrorReason(s"You're not authorized here")).vfuture
                  }
              }
            }
            case _                                             => {
              FastFuture.successful(Left(ErrorReason("Authorization form is not complete")))
            }
          }
        }
      }
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    val hash         = env.sign(s"${authConfig.id}:::backoffice")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>
      if (authConfig.basicAuth) {

        def unauthorized() =
          Results
            .Unauthorized(otoroshi.views.html.oto.error("You are not authorized here", env))
            .withHeaders("WWW-Authenticate" -> "otoroshi-admin-realm")
            .addingToSession(
              "bo-redirect-after-login" -> redirect.getOrElse(
                routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
              )
            )
            .future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") =>
            extractUsernamePassword(auth) match {
              case None                       => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindAdminUser(username, password, env.backOfficeServiceDescriptor) flatMap {
                  case Left(_)     => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
                  case Right(user) =>
                    env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                      Results.Redirect(s"/backoffice/auth0/callback?token=$token&hash=$hash")
                    }
                }
            }
          case _                                       => unauthorized()
        }
      } else {
        Results
          .Ok(
            otoroshi.views.html.oto
              .login(s"/backoffice/auth0/callback?hash=$hash", "POST", token, authConfig.webauthn, env)
          )
          .addingToSession(
            "bo-redirect-after-login" -> redirect.getOrElse(
              routes.BackOfficeController.dashboard.absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
          .future
      }
    }
  }
  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(using
      ec: ExecutionContext,
      env: Env
  ) =
    FastFuture.successful(Right(None))

  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(using ec: ExecutionContext, env: Env): Future[Either[ErrorReason, BackOfficeUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) =>
          env.datastores.authConfigsDataStore
            .getUserForToken(token)
            .map(_.flatMap(a => BackOfficeUser.fmt.reads(a).asOpt))
            .map {
              case Some(user) => Right(user)
              case None       => Left(ErrorReason("No user found"))
            }
        case _           => FastFuture.successful(Left(ErrorReason("Forbidden access")))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None       => FastFuture.successful(Left(ErrorReason("No Authorization form here")))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).flatMap {
                case false => Left(ErrorReason("Bad token")).vfuture
                case true  => bindAdminUser(username, password, env.backOfficeServiceDescriptor)
              }
            }
            case _                                             => {
              FastFuture.successful(Left(ErrorReason("Authorization form is not complete")))
            }
          }
        }
      }
    }
  }

  /////////// Webauthn

  // the users of this auth. module that can log in with a webauthn device, seen as webauthn users
  private def webAuthnUsers: Seq[WebAuthnOtoroshiAdmin] = authConfig.users.filter(_.webauthn.isDefined).map { usr =>
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
      location = authConfig.location,
      adminEntityValidators = usr.adminEntityValidators
    )
  }

  def webAuthnLoginStart(
      body: JsValue,
      descriptor: ServiceDescriptor
  )(using env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val usernameOpt = (body \ "username").asOpt[String]
    val passwordOpt = (body \ "password").asOpt[String]
    val origin      = (body \ "origin").as[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(password)) => {
        bindUser(username, password, descriptor).map(_.toOption) flatMap {
          case Some(_) => WebAuthnSupport.loginStart(webAuthnUsers, username, origin).map(request => Right(request))
          case _       => FastFuture.successful(Left("bad request"))
        }
      }
      case (_, _)                           => {
        FastFuture.successful(Left("bad request"))
      }
    }
  }

  def webAuthnAdminLoginStart(
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val usernameOpt = (body \ "username").asOpt[String]
    val passwordOpt = (body \ "password").asOpt[String]
    val origin      = (body \ "origin").as[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(password)) => {
        bindAdminUser(username, password, env.backOfficeServiceDescriptor).map(_.toOption) flatMap {
          case Some(_) => WebAuthnSupport.loginStart(webAuthnUsers, username, origin).map(request => Right(request))
          case _       => FastFuture.successful(Left("bad request"))
        }
      }
      case (_, _)                           => {
        FastFuture.successful(Left("bad request"))
      }
    }
  }

  def webAuthnLoginFinish(
      body: JsValue,
      descriptor: ServiceDescriptor
  )(using env: Env, ec: ExecutionContext): Future[Either[ErrorReason, PrivateAppsUser]] = {
    val otoroshi    = (body \ "otoroshi").as[JsObject]
    val usernameOpt = (otoroshi \ "username").asOpt[String]
    val passwordOpt = (otoroshi \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        val users = webAuthnUsers
        users.find(u => u.username == username) match {
          case None    => FastFuture.successful(Left(ErrorReason("Bad user")))
          case Some(_) => {
            bindUser(username, pass, descriptor) flatMap {
              case Left(err)   => FastFuture.successful(Left(err))
              case Right(user) => {
                WebAuthnSupport.loginFinish(users, body).map {
                  case Left(err) => Left(ErrorReason(err))
                  case Right(_)  => Right(user)
                }
              }
            }
          }
        }
      }
      case (_, _)                       => FastFuture.successful(Left(ErrorReason("Not Authorized")))
    }
  }

  def webAuthnAdminLoginFinish(
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[ErrorReason, BackOfficeUser]] = {
    val otoroshi    = (body \ "otoroshi").as[JsObject]
    val usernameOpt = (otoroshi \ "username").asOpt[String]
    val passwordOpt = (otoroshi \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        val users = webAuthnUsers
        users.find(u => u.username == username) match {
          case None    => FastFuture.successful(Left(ErrorReason("Bad user")))
          case Some(_) => {
            bindAdminUser(username, pass, env.backOfficeServiceDescriptor) flatMap {
              case Left(err)   => FastFuture.successful(Left(err))
              case Right(user) => {
                WebAuthnSupport.loginFinish(users, body).map {
                  case Left(err) => Left(ErrorReason(err))
                  case Right(_)  => Right(user)
                }
              }
            }
          }
        }
      }
      case (_, _)                       => FastFuture.successful(Left(ErrorReason("Not Authorized")))
    }
  }

  def webAuthnRegistrationStart(
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val username = (body \ "username").as[String]
    val label    = (body \ "label").as[String]
    val origin   = (body \ "origin").as[String]
    // an user always keeps the same handle, otherwise the keys registered before would not be usable anymore
    val handle   = authConfig.users.find(_.email == username).flatMap(_.webauthn).map(_.handle)
    WebAuthnSupport.registrationStart(webAuthnUsers, username, label, origin, handle).map(request => Right(request))
  }

  def webAuthnRegistrationFinish(
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val username = (body \ "otoroshi" \ "username").as[String]
    WebAuthnSupport.registrationFinish(webAuthnUsers, body).flatMap {
      case Left(err)           => FastFuture.successful(Left(err))
      case Right(registration) => {
        authConfig.users.find(_.email == username) match {
          case None       => FastFuture.successful(Left("bad user"))
          case Some(user) => {
            val credential = registration.credential
            val webauthn   = user.webauthn match {
              case None          =>
                WebAuthnDetails(handle = registration.handle, credentials = Map(credential.id -> credential.json))
              case Some(details) =>
                details.copy(credentials = details.credentials + (credential.id -> credential.json))
            }
            val newUser    = user.copy(webauthn = Some(webauthn))
            val conf       = authConfig.copy(users = authConfig.users.filterNot(_.email == username) :+ newUser)
            conf.save().map { _ =>
              Right(Json.obj("username" -> username))
            }
          }
        }
      }
    }
  }

  def webAuthnRegistrationDelete(
      user: BasicAuthUser
  )(using env: Env, ec: ExecutionContext): Future[Either[String, JsValue]] = {
    val conf = authConfig.copy(users = authConfig.users.filterNot(_.email == user.email) :+ user.copy(webauthn = None))
    conf.save().map { _ =>
      Right(Json.obj("username" -> user.email))
    }
  }
}
