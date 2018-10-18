package auth

import akka.http.scaladsl.util.FastFuture
import controllers.routes
import env.Env
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import security.IdGenerator

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class LdapAuthUser(
    name: String,
    email: String,
    metadata: JsObject = Json.obj()
) {
  def asJson: JsValue = LdapAuthUser.fmt.writes(this)
}

object LdapAuthUser {
  def fmt = new Format[LdapAuthUser] {
    override def writes(o: LdapAuthUser) = Json.obj(
      "name"     -> o.name,
      "email"    -> o.email,
      "metadata" -> o.metadata,
    )
    override def reads(json: JsValue) =
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

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[LdapAuthModuleConfig])
    }

    override def writes(o: LdapAuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        LdapAuthModuleConfig(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          serverUrl = (json \ "serverUrl").as[String],
          searchBase = (json \ "searchBase").as[String],
          userBase = (json \ "userBase").asOpt[String].filterNot(_.trim.isEmpty),
          groupFilter = (json \ "groupFilter").asOpt[String].filterNot(_.trim.isEmpty),
          searchFilter = (json \ "searchFilter").as[String],
          adminUsername = (json \ "adminUsername").asOpt[String].filterNot(_.trim.isEmpty),
          adminPassword = (json \ "adminPassword").asOpt[String].filterNot(_.trim.isEmpty),
          nameField = (json \ "nameField").as[String],
          emailField = (json \ "emailField").as[String],
          metadataField = (json \ "metadataField").asOpt[String].filterNot(_.trim.isEmpty)
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class LdapAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    sessionMaxAge: Int = 86400,
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
) extends AuthModuleConfig {
  def `type`: String = "ldap"

  override def authModule(config: GlobalConfig): AuthModule = LdapAuthModule(this)

  override def asJson = Json.obj(
    "type"          -> "ldap",
    "id"            -> this.id,
    "name"          -> this.name,
    "desc"          -> this.desc,
    "sessionMaxAge" -> this.sessionMaxAge,
    "serverUrl"     -> this.serverUrl,
    "searchBase"    -> this.searchBase,
    "userBase"      -> this.userBase.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "groupFilter"   -> this.groupFilter.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "searchFilter"  -> this.searchFilter,
    "adminUsername" -> this.adminUsername.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "adminPassword" -> this.adminPassword.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "nameField"     -> this.nameField,
    "emailField"    -> this.emailField,
    "metadataField" -> this.metadataField.map(JsString.apply).getOrElse(JsNull).as[JsValue]
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
  def bindUser(username: String, password: String): Option[LdapAuthUser] = {

    import java.util

    import javax.naming._
    import javax.naming.directory._
    import javax.naming.ldap._
    import collection.JavaConverters._

    val env = new util.Hashtable[String, AnyRef]
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    adminUsername.foreach(u => env.put(Context.SECURITY_PRINCIPAL, u))
    adminPassword.foreach(p => env.put(Context.SECURITY_CREDENTIALS, p))
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.PROVIDER_URL, serverUrl)

    val ctx = new InitialLdapContext(env, Array.empty[Control])

    val searchControls = new SearchControls()
    searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE)

    val usersInGroup: Seq[String] = groupFilter
      .map { filter =>
        val groupSearch = ctx.search(searchBase, filter, searchControls)
        val uids = if (groupSearch.hasMore) {
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
    val res = ctx.search(userBase.map(_ + ",").getOrElse("") + searchBase,
                         searchFilter.replace("${username}", username),
                         searchControls)
    val boundUser: Option[LdapAuthUser] = if (res.hasMore) {
      val item = res.next()
      val dn   = item.getNameInNamespace
      if (groupFilter.map(_ => usersInGroup.contains(dn)).getOrElse(true)) {
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
          Some(
            LdapAuthUser(
              name = attrs.get(nameField).toString.split(":").last.trim,
              email = attrs.get(emailField).toString.split(":").last.trim,
              metadata = metadataField
                .map(m => Json.parse(attrs.get(m).toString.split(":").last.trim).as[JsObject])
                .getOrElse(Json.obj())
            )
          )
        } recover {
          case _ => None
        } get
      } else {
        None
      }
    } else {
      None
    }
    res.close()
    ctx.close()
    boundUser
  }
}

case class LdapAuthModule(authConfig: LdapAuthModuleConfig) extends AuthModule {
  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().map { token =>
      Results
        .Ok(views.html.otoroshi.login(s"/privateapps/generic/callback?desc=${descriptor.id}", "POST", token, env))
        .addingToSession(
          "pa-redirect-after-login" -> redirect.getOrElse(
            routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
          )
        )
    }
  }
  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ) = FastFuture.successful(())
  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    implicit val req = request
    request.body.asFormUrlEncoded match {
      case None => FastFuture.successful(Left("No Authorization form here"))
      case Some(form) => {
        (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
          case (Some(username), Some(password), Some(token)) => {
            env.datastores.authConfigsDataStore.validateLoginToken(token).map {
              case false => Left("Bad token")
              case true =>
                authConfig.bindUser(username, password) match {
                  case Some(user) =>
                    Right(
                      PrivateAppsUser(
                        randomId = IdGenerator.token(64),
                        name = user.name,
                        email = user.email,
                        profile = user.asJson,
                        realm = authConfig.cookieSuffix(descriptor),
                        otoroshiData = user.metadata.asOpt[Map[String, String]]
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

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().map { token =>
      Results
        .Ok(views.html.otoroshi.login(s"/backoffice/auth0/callback", "POST", token, env))
        .addingToSession(
          "bo-redirect-after-login" -> redirect.getOrElse(
            routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
          )
        )
    }
  }
  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(())
  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    implicit val req = request
    request.body.asFormUrlEncoded match {
      case None => FastFuture.successful(Left("No Authorization form here"))
      case Some(form) => {
        (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
          case (Some(username), Some(password), Some(token)) => {
            env.datastores.authConfigsDataStore.validateLoginToken(token).map {
              case false => Left("Bad token")
              case true =>
                authConfig.bindUser(username, password) match {
                  case Some(user) =>
                    Right(
                      BackOfficeUser(
                        randomId = IdGenerator.token(64),
                        name = user.name,
                        email = user.email,
                        profile = user.asJson,
                        authorizedGroup = None
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
