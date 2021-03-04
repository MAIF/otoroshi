package otoroshi.auth

import java.util

import akka.http.scaladsl.util.FastFuture
import com.google.common.base.Charsets
import otoroshi.controllers.routes
import otoroshi.env.Env
import javax.naming.Context
import javax.naming.directory.InitialDirContext
import otoroshi.models._
import otoroshi.models.{TeamAccess, TenantAccess, UserRight, UserRights}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import otoroshi.security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LdapAuthUser(
    name: String,
    email: String,
    metadata: JsObject = Json.obj()
) {
  def asJson: JsValue = LdapAuthUser.fmt.writes(this)
}

object LdapAuthUser {
  def fmt =
    new Format[LdapAuthUser] {
      override def writes(o: LdapAuthUser) =
        Json.obj(
          "name"     -> o.name,
          "email"    -> o.email,
          "metadata" -> o.metadata
        )
      override def reads(json: JsValue)    =
        Try {
          JsSuccess(
            LdapAuthUser(
              name = (json \ "name").as[String],
              email = (json \ "email").as[String],
              metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj())
            )
          )
        } recover {
          case e => JsError(e.getMessage)
        } get
    }
}

object LdapAuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-ldap-auth-config")

  def fromJsons(value: JsValue): LdapAuthModuleConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[LdapAuthModuleConfig] {

    override def reads(json: JsValue) =
      fromJson(json) match {
        case Left(e)  => JsError(e.getMessage)
        case Right(v) => JsSuccess(v.asInstanceOf[LdapAuthModuleConfig])
      }

    override def writes(o: LdapAuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, LdapAuthModuleConfig] =
    Try {
      Right(
        LdapAuthModuleConfig(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          basicAuth = (json \ "basicAuth").asOpt[Boolean].getOrElse(false),
          allowEmptyPassword = (json \ "allowEmptyPassword").asOpt[Boolean].getOrElse(false),
          serverUrl = (json \ "serverUrl").as[String],
          searchBase = (json \ "searchBase").as[String],
          userBase = (json \ "userBase").asOpt[String].filterNot(_.trim.isEmpty),
          groupFilter = (json \ "groupFilter").asOpt[String].filterNot(_.trim.isEmpty),
          searchFilter = (json \ "searchFilter").as[String],
          adminUsername = (json \ "adminUsername").asOpt[String].filterNot(_.trim.isEmpty),
          adminPassword = (json \ "adminPassword").asOpt[String].filterNot(_.trim.isEmpty),
          nameField = (json \ "nameField").as[String],
          emailField = (json \ "emailField").as[String],
          metadataField = (json \ "metadataField").asOpt[String].filterNot(_.trim.isEmpty),
          extraMetadata = (json \ "extraMetadata").asOpt[JsObject].getOrElse(Json.obj()),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
          superAdmins = (json \ "superAdmins").asOpt[Boolean].getOrElse(false), // for backward compatibility reasons
          rightsOverride = (json \ "rightsOverride")
            .asOpt[Map[String, JsArray]]
            .map(_.mapValues(UserRights.readFromArray))
            .getOrElse(Map.empty),
          dataOverride = (json \ "dataOverride").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
        )
      )
    } recover {
      case e =>
        e.printStackTrace()
        Left(e)
    } get
}

case class LdapAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    sessionMaxAge: Int = 86400,
    basicAuth: Boolean = false,
    allowEmptyPassword: Boolean = false,
    serverUrl: String,
    searchBase: String,
    userBase: Option[String] = None,
    groupFilter: Option[String] = None,
    searchFilter: String = "(mail=${username})",
    adminUsername: Option[String] = None,
    adminPassword: Option[String] = None,
    nameField: String = "cn",
    emailField: String = "mail",
    metadataField: Option[String] = None,
    extraMetadata: JsObject = Json.obj(),
    metadata: Map[String, String],
    sessionCookieValues: SessionCookieValues,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    superAdmins: Boolean = false,
    rightsOverride: Map[String, UserRights] = Map.empty,
    dataOverride: Map[String, JsObject] = Map.empty
) extends AuthModuleConfig {
  def `type`: String = "ldap"

  override def authModule(config: GlobalConfig): AuthModule = LdapAuthModule(this)

  override def asJson =
    location.jsonWithKey ++ Json.obj(
      "type"                -> "ldap",
      "id"                  -> id,
      "name"                -> name,
      "desc"                -> desc,
      "basicAuth"           -> basicAuth,
      "allowEmptyPassword"  -> allowEmptyPassword,
      "sessionMaxAge"       -> sessionMaxAge,
      "serverUrl"           -> serverUrl,
      "searchBase"          -> searchBase,
      "userBase"            -> userBase.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "groupFilter"         -> groupFilter.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "searchFilter"        -> searchFilter,
      "adminUsername"       -> adminUsername.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "adminPassword"       -> adminPassword.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "nameField"           -> nameField,
      "emailField"          -> emailField,
      "metadataField"       -> metadataField.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "extraMetadata"       -> extraMetadata,
      "metadata"            -> metadata,
      "sessionCookieValues" -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "superAdmins"         -> superAdmins,
      "rightsOverride"      -> JsObject(rightsOverride.mapValues(_.json)),
      "dataOverride"        -> JsObject(dataOverride)
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)

  override def cookieSuffix(desc: ServiceDescriptor) = s"ldap-auth-$id"

  /*
    val ldapAdServer = "ldap://ldap.forumsys.com:389"
    val ldapSearchBase = "dc=example,dc=com"
    val searchFilter = "(uid=${username})"

    val ldapUsername = "cn=read-only-admin,dc=example,dc=com"
    val ldapPassword = "password"

    val nameField = "cn"
    val emailField = "mail"
   */
  def bindUser(username: String, password: String): Either[String, LdapAuthUser] = {

    import java.util

    import javax.naming._
    import javax.naming.directory._
    import javax.naming.ldap._
    import collection.JavaConverters._

    if (!allowEmptyPassword && password.trim.isEmpty) {
      LdapAuthModuleConfig.logger.error("Empty user password are not allowed for this LDAP auth. module")
      Left("Empty user password are not allowed for this LDAP auth. module")
    } else if (!allowEmptyPassword && adminPassword.exists(_.trim.isEmpty)) {
      LdapAuthModuleConfig.logger.error("Empty admin password are not allowed for this LDAP auth. module")
      Left("Empty admin password are not allowed for this LDAP auth. module")
    } else {

      val env = new util.Hashtable[String, AnyRef]
      env.put(Context.SECURITY_AUTHENTICATION, "simple")
      adminUsername.foreach(u => env.put(Context.SECURITY_PRINCIPAL, u))
      adminPassword.foreach(p => env.put(Context.SECURITY_CREDENTIALS, p))
      env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
      env.put(Context.PROVIDER_URL, serverUrl)

      val ctx = new InitialLdapContext(env, Array.empty[Control])

      val searchControls = new SearchControls()
      searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE)

      LdapAuthModuleConfig.logger.debug(s"bind user for ${username}")

      val usersInGroup: Seq[String]               = groupFilter
        .map { filter =>
          LdapAuthModuleConfig.logger.debug(s"searching `$searchBase` with filter `$filter` ")
          val groupSearch = ctx.search(searchBase, filter, searchControls)
          val uids        = if (groupSearch.hasMore) {
            val item  = groupSearch.next()
            val attrs = item.getAttributes
            attrs.getAll.asScala.toSeq.filter(a => a.getID == "uniqueMember" || a.getID == "member").flatMap { attr =>
              attr.getAll.asScala.toSeq.map(_.toString)
            }
          } else {
            Seq.empty[String]
          }
          groupSearch.close()
          uids
        }
        .getOrElse(Seq.empty[String])
      LdapAuthModuleConfig.logger.debug(s"found ${usersInGroup.size} users in group : ${usersInGroup.mkString(", ")}")
      LdapAuthModuleConfig.logger.debug(
        s"searching user in ${userBase.map(_ + ",").getOrElse("") + searchBase} with filter ${searchFilter.replace("${username}", username)}"
      )
      val res                                     = ctx.search(
        userBase.map(_ + ",").getOrElse("") + searchBase,
        searchFilter.replace("${username}", username),
        searchControls
      )
      val boundUser: Either[String, LdapAuthUser] = if (res.hasMore) {
        val item = res.next()
        val dn   = item.getNameInNamespace
        LdapAuthModuleConfig.logger.debug(s"found user with dn `$dn`")
        if (groupFilter.map(_ => usersInGroup.contains(dn)).getOrElse(true)) {
          LdapAuthModuleConfig.logger.debug(s"user found in group")
          val attrs = item.getAttributes
          val env2  = new util.Hashtable[String, AnyRef]
          env2.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
          env2.put(Context.PROVIDER_URL, serverUrl)
          env2.put(Context.SECURITY_AUTHENTICATION, "simple")
          env2.put(Context.SECURITY_PRINCIPAL, dn)
          env2.put(Context.SECURITY_CREDENTIALS, password)
          scala.util.Try {
            val ctx2 = new InitialDirContext(env2)
            ctx2.close()
            Right(
              LdapAuthUser(
                name = attrs.get(nameField).toString.split(":").last.trim,
                email = attrs.get(emailField).toString.split(":").last.trim,
                metadata = extraMetadata.deepMerge(
                  metadataField
                    .map(m => Json.parse(attrs.get(m).toString.split(":").last.trim).as[JsObject])
                    .getOrElse(Json.obj())
                )
              )
            )
          } recover {
            case e =>
              LdapAuthModuleConfig.logger.error(s"bind failed", e)
              Left(s"bind failed ${e.getMessage}")
          } get
        } else {
          LdapAuthModuleConfig.logger.debug(s"user not found in group")
          Left(s"user not found in group")
        }
      } else {
        LdapAuthModuleConfig.logger.debug(s"no user found")
        Left(s"no user found")
      }
      res.close()
      ctx.close()
      boundUser
    }
  }

  def checkConnection(): Future[(Boolean, String)] =
    FastFuture.successful {
      val env = new util.Hashtable[String, AnyRef]
      env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
      env.put(Context.PROVIDER_URL, serverUrl)
      env.put(Context.SECURITY_AUTHENTICATION, "simple")
      adminUsername.foreach(u => env.put(Context.SECURITY_PRINCIPAL, u))
      adminPassword.foreach(p => env.put(Context.SECURITY_CREDENTIALS, p))
      scala.util.Try {
        val ctx2 = new InitialDirContext(env)
        ctx2.close()
      } match {
        case Success(_) => (true, "--")
        case Failure(e) => (false, e.getMessage)
      }
    }
}

case class LdapAuthModule(authConfig: LdapAuthModuleConfig) extends AuthModule {

  import otoroshi.utils.future.Implicits._

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def extractUsernamePassword(header: String): Option[(String, String)] = {
    val base64 = header.replace("Basic ", "").replace("basic ", "")
    Option(base64)
      .map(decodeBase64)
      .map(_.split(":").toSeq)
      .flatMap(a => a.headOption.flatMap(head => a.lastOption.map(last => (head, last))))
  }

  def bindUser(username: String, password: String, descriptor: ServiceDescriptor): Either[String, PrivateAppsUser] = {
    authConfig.bindUser(username, password).toOption match {
      case Some(user) =>
        Right(
          PrivateAppsUser(
            randomId = IdGenerator.token(64),
            name = user.name,
            email = user.email,
            profile = user.asJson,
            realm = authConfig.cookieSuffix(descriptor),
            // otoroshiData = authConfig.dataOverride.get(user.email).map(v => authConfig.extraMetadata.deepMerge(v)).orElse(Some(user.metadata)),
            otoroshiData = authConfig.dataOverride
              .get(user.email)
              .map(v => authConfig.extraMetadata.deepMerge(v))
              .orElse(Some(authConfig.extraMetadata.deepMerge(user.metadata))),
            authConfigId = authConfig.id,
            metadata = Map.empty,
            location = authConfig.location
          )
        )
      case None       => Left(s"You're not authorized here")
    }
  }

  def bindAdminUser(username: String, password: String): Either[String, BackOfficeUser] = {
    authConfig.bindUser(username, password).toOption match {
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
            rights =
              if (authConfig.superAdmins) UserRights.superAdmin
              else {
                authConfig.rightsOverride.getOrElse(
                  user.email,
                  UserRights(
                    Seq(
                      UserRight(
                        TenantAccess(authConfig.location.tenant.value),
                        authConfig.location.teams.map(t => TeamAccess(t.value))
                      )
                    )
                  )
                )
              },
            location = authConfig.location
          )
        )
      case None       => Left(s"You're not authorized here")
    }
  }

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    val hash         = env.sign(s"${authConfig.id}:::${descriptor.id}")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>
      if (authConfig.basicAuth) {

        def unauthorized() =
          Results
            .Unauthorized(views.html.oto.error("You are not authorized here", env))
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
              case None                       => Results.Forbidden(views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindUser(username, password, descriptor) match {
                  case Left(_)     => Results.Forbidden(views.html.oto.error("Forbidden access", env)).future
                  case Right(user) =>
                    env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                      Results.Redirect(s"/privateapps/generic/callback?desc=${descriptor.id}&token=$token&hash=$hash")
                    }
                }
            }
          case _                                       => unauthorized()
        }
      } else {
        Results
          .Ok(
            views.html.oto
              .login(s"/privateapps/generic/callback?desc=${descriptor.id}&hash=$hash", "POST", token, false, env)
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

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ) = FastFuture.successful(None)

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
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
                case true  => bindUser(username, password, descriptor)
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

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
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
            .Unauthorized(views.html.oto.error("You are not authorized here", env))
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
              case None                       => Results.Forbidden(views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindAdminUser(username, password) match {
                  case Left(_)     => Results.Forbidden(views.html.oto.error("Forbidden access", env)).future
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
          .Ok(views.html.oto.login(s"/backoffice/auth0/callback?hash=$hash", "POST", token, false, env))
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
