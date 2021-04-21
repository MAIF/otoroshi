package otoroshi.auth

import java.util
import akka.http.scaladsl.util.FastFuture
import com.google.common.base.Charsets
import org.apache.pulsar.client.api.PulsarClientException.AuthenticationException
import otoroshi.auth.LdapAuthModuleConfig.fromJson
import otoroshi.controllers.routes
import otoroshi.env.Env

import javax.naming.{CommunicationException, Context, ServiceUnavailableException}
import javax.naming.directory.{InitialDirContext, SearchControls}
import otoroshi.models._
import otoroshi.models.{TeamAccess, TenantAccess, UserRight, UserRights}
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, _}
import play.api.mvc._
import otoroshi.security.{IdGenerator, OtoroshiClaim}

import javax.naming.ldap.{Control, InitialLdapContext}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LdapAuthUser(
    name: String,
    email: String,
    metadata: JsObject = Json.obj(),
    userRights: Option[UserRights]
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
          "metadata" -> o.metadata,
          "userRights" -> o.userRights.map(UserRights.format.writes)
        )
      override def reads(json: JsValue)    =
        Try {
          JsSuccess(
            LdapAuthUser(
              name = (json \ "name").as[String],
              email = (json \ "email").as[String],
              metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
              userRights = (json \ "userRights").asOpt[UserRights](UserRights.format)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
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
      val location = otoroshi.models.EntityLocation.readFromKey(json)
      Right(
        LdapAuthModuleConfig(
          location = location,
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          basicAuth = (json \ "basicAuth").asOpt[Boolean].getOrElse(false),
          allowEmptyPassword = (json \ "allowEmptyPassword").asOpt[Boolean].getOrElse(false),
          serverUrls = (json \ "serverUrl").asOpt[String] match {
            case Some(url) => Seq(url)
            case None => (json \ "serverUrls").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          },
          searchBase = (json \ "searchBase").as[String],
          userBase = (json \ "userBase").asOpt[String].filterNot(_.trim.isEmpty),
          groupFilters = (json \ "groupFilter").asOpt[String] match {
            case Some(filter) => location.teams.map(t => GroupFilter(filter, TenantAccess(location.tenant.value), t.value))
            case None => (json \ "groupFilters").asOpt[Seq[GroupFilter]](Reads.seq(GroupFilter._fmt)).getOrElse(Seq.empty[GroupFilter])
          },
          searchFilter = (json \ "searchFilter").as[String],
          adminUsername = (json \ "adminUsername").asOpt[String].filterNot(_.trim.isEmpty),
          adminPassword = (json \ "adminPassword").asOpt[String].filterNot(_.trim.isEmpty),
          nameField = (json \ "nameField").as[String],
          emailField = (json \ "emailField").as[String],
          metadataField = (json \ "metadataField").asOpt[String].filterNot(_.trim.isEmpty),
          extraMetadata = (json \ "extraMetadata").asOpt[JsObject].getOrElse(Json.obj()),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          sessionCookieValues =
            (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
          superAdmins = (json \ "superAdmins").asOpt[Boolean].getOrElse(false), // for backward compatibility reasons
          rightsOverride = (json \ "rightsOverride")
            .asOpt[Map[String, JsArray]]
            .map(_.mapValues(UserRights.readFromArray))
            .getOrElse(Map.empty),
          dataOverride = (json \ "dataOverride").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
          groupRights = (json \ "groupRights")
            .asOpt[Map[String, JsObject]]
            .map(_.mapValues(GroupRights.reads).collect {
              case (key, Some(v)) => (key, v)
            })
            .getOrElse(Map.empty),
        )
      )
    } recover { case e =>
      e.printStackTrace()
      Left(e)
    } get
}

case class GroupRights (userRights: UserRights, users: Seq[String])

object GroupRights {
  def _fmt = new Format[GroupRights] {
    override def writes(o: GroupRights) =
      Json.obj(
        "rights"  -> o.userRights.json,
        "users"          -> o.users
      )

    override def reads(json: JsValue): JsResult[GroupRights] =
      Try {
        JsSuccess(
          GroupRights(
            userRights = (json \ "rights").asOpt[UserRights](UserRights.format).getOrElse(UserRights(Seq.empty)),
            users = (json \ "users").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }

  def reads(json: JsObject): Option[GroupRights] =
    this._fmt.reads(json).asOpt
}

case class GroupFilter (group: String, tenant: TenantAccess, team: String)

object GroupFilter {
  def _fmt = new Format[GroupFilter] {
      override def writes(o: GroupFilter) =
        Json.obj(
          "group" -> o.group,
          "team" -> o.team,
          "tenant"   -> o.tenant.raw
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            GroupFilter(
              group = (json \ "group").asOpt[String].getOrElse(""),
              tenant = (json \ "tenant").asOpt[String].map(TenantAccess(_)).getOrElse(TenantAccess("*:rw")),
              team = (json \ "team").asOpt[String].getOrElse("")
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

case class LdapAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    sessionMaxAge: Int = 86400,
    basicAuth: Boolean = false,
    allowEmptyPassword: Boolean = false,
    serverUrls: Seq[String] = Seq.empty,
    searchBase: String,
    userBase: Option[String] = None,
    groupFilters: Seq[GroupFilter] = Seq.empty,
    searchFilter: String = "(mail=${username})",
    adminUsername: Option[String] = None,
    adminPassword: Option[String] = None,
    nameField: String = "cn",
    emailField: String = "mail",
    metadataField: Option[String] = None,
    extraMetadata: JsObject = Json.obj(),
    tags: Seq[String],
    metadata: Map[String, String],
    sessionCookieValues: SessionCookieValues,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    superAdmins: Boolean = false,
    rightsOverride: Map[String, UserRights] = Map.empty,
    dataOverride: Map[String, JsObject] = Map.empty,
    groupRights: Map[String, GroupRights] = Map.empty
) extends AuthModuleConfig {
  def `type`: String = "ldap"

  def theDescription: String = desc
  def theMetadata: Map[String,String] = metadata
  def theName: String = name
  def theTags: Seq[String] = tags

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
      "serverUrls"          -> serverUrls,
      "searchBase"          -> searchBase,
      "userBase"            -> userBase.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "groupFilters"        -> JsArray(groupFilters.map(o => GroupFilter._fmt.writes(o))),
      "searchFilter"        -> searchFilter,
      "adminUsername"       -> adminUsername.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "adminPassword"       -> adminPassword.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "nameField"           -> nameField,
      "emailField"          -> emailField,
      "metadataField"       -> metadataField.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "extraMetadata"       -> extraMetadata,
      "metadata"            -> metadata,
      "tags"                -> JsArray(tags.map(JsString.apply)),
      "sessionCookieValues" -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "superAdmins"         -> superAdmins,
      "rightsOverride"      -> JsObject(rightsOverride.mapValues(_.json)),
      "dataOverride"        -> JsObject(dataOverride),
      "groupRights"         -> JsObject(groupRights.mapValues(GroupRights._fmt.writes))
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)

  override def cookieSuffix(desc: ServiceDescriptor) = s"ldap-auth-$id"

  private def getLdapContext(principal: String, password: String, url: String): util.Hashtable[String, AnyRef] = {
    val env = new util.Hashtable[String, AnyRef]
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    env.put(Context.SECURITY_PRINCIPAL, principal)
    env.put(Context.SECURITY_CREDENTIALS, password)
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.PROVIDER_URL, url)
    env
  }

  private def getInitialLdapContext(principal: String, password: String, url: String) = {
    new InitialLdapContext(getLdapContext(principal, password, url), Array.empty[Control])
  }

  private def getInitialDirContext(principal: String, password: String, url: String) =
    new InitialDirContext(getLdapContext(principal, password, url))

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
    if (!allowEmptyPassword && password.trim.isEmpty) {
      LdapAuthModuleConfig.logger.error("Empty user password are not allowed for this LDAP auth. module")
      Left("Empty user password are not allowed for this LDAP auth. module")
    } else if (!allowEmptyPassword && adminPassword.exists(_.trim.isEmpty)) {
      LdapAuthModuleConfig.logger.error("Empty admin password are not allowed for this LDAP auth. module")
      Left("Empty admin password are not allowed for this LDAP auth. module")
    } else
      _bindUser(serverUrls.filter(_ => true), username, password)
  }

  private def getDefaultSearchControls() = {
    val searchControls = new SearchControls()
    searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE)
    searchControls
  }

  private def _bindUser(urls: Seq[String], username: String, password: String) : Either[String, LdapAuthUser] = {
    import javax.naming._
    import collection.JavaConverters._

    if (urls.isEmpty)
      Left(s"Missing LDAP server URLs or all down")
    else {
      val url = urls.head
      try {
        val ctx = getInitialLdapContext(
          adminUsername.map(u => u).getOrElse(""),
          adminPassword.map(p => p).getOrElse(""),
          url
        )
        LdapAuthModuleConfig.logger.debug(s"bind user for $username")

        //                          GROUP      TENANT       LIST[TEAM]    LIST[USER]
        val usersInGroup: Map[((String, TenantAccess), Seq[String]), Seq[String]] = groupFilters
          .groupBy(record => (record.group, record.tenant))
          .map { group => (group._1, group._2.map(_.team)) }
          .map { filter =>
            LdapAuthModuleConfig.logger.debug(s"searching `$searchBase` with filter `${filter._1._1}` ")
            val groupSearch = ctx.search(searchBase, filter._1._1, getDefaultSearchControls())

            val uids: Seq[String] = if (groupSearch.hasMore) {
              val item = groupSearch.next()
              val attrs = item.getAttributes
              attrs.getAll.asScala.toSeq.filter(a => a.getID == "uniqueMember" || a.getID == "member").flatMap { attr =>
                attr.getAll.asScala.toSeq.map(_.toString)
              }
            } else {
              Seq.empty[String]
            }

            groupSearch.close()
            (filter, uids)
          }

        LdapAuthModuleConfig.logger.debug(s"found ${usersInGroup.flatMap(_._2).size} users in group : ${usersInGroup.mkString(", ")}")
        LdapAuthModuleConfig.logger.debug(
          s"searching user in ${userBase.map(_ + ",").getOrElse("") + searchBase} with filter ${searchFilter.replace("${username}", username)}"
        )
        val res = ctx.search(
          userBase.map(_ + ",").getOrElse("") + searchBase,
          searchFilter.replace("${username}", username),
          getDefaultSearchControls()
        )
        val boundUser: Either[String, LdapAuthUser] = if (res.hasMore) {
          val item = res.next()
          val dn = item.getNameInNamespace
          LdapAuthModuleConfig.logger.debug(s"found user with dn `$dn`")

          val userGroup = usersInGroup
            .find { group => group._2.exists(g => g.contains(dn)) }

          if (userGroup.isDefined) {
            val group = userGroup.get
            LdapAuthModuleConfig.logger.debug(s"user found in ${group._1} group")
            val attrs = item.getAttributes

            try {
              val ctx2 = getInitialDirContext(dn, password, url)
              ctx2.close()

              val email = attrs.get(emailField).toString.split(":").last.trim

              Right(
                LdapAuthUser(
                  name = attrs.get(nameField).toString.split(":").last.trim,
                  email,
                  metadata = extraMetadata.deepMerge(
                    metadataField
                      .map(m => Json.parse(attrs.get(m).toString.split(":").last.trim).as[JsObject])
                      .getOrElse(Json.obj())
                  ),
                  userRights = Some(
                    UserRights(
                      (
                        usersInGroup
                          .filter { group => group._2.exists(g => g.contains(dn)) }
                          .map(userGroup =>
                            UserRight(
                              userGroup._1._1._2,
                              userGroup._1._2.map(team => TeamAccess(s"$team:${userGroup._1._1._2.raw.split(":")(1)}")))
                          ).toList
                          ++
                          groupRights.values
                            .filter { group => group.users.contains(email) }
                            .flatMap { group => group.userRights.rights }
                            .toList
                        )
                        .groupBy(f => f.tenant)
                        .map(m => UserRight(m._1, m._2.flatMap(_.teams)))
                        .toSeq
                    ))
                )
              )
            } catch {
              case _: ServiceUnavailableException | _: CommunicationException => Left(s"Communication error")
              case e: Throwable =>
                LdapAuthModuleConfig.logger.debug(s"bind failed", e)
                Left(s"bind failed ${e.getMessage}")
            }
          } else {
            LdapAuthModuleConfig.logger.debug(s"user not found in groups")
            Left(s"user not found in group")
          }
        } else {
          LdapAuthModuleConfig.logger.debug(s"no user found")
          Left(s"no user found")
        }
        res.close()
        ctx.close()
        boundUser
      } catch {
        case _ : CommunicationException | _ : ServiceUnavailableException =>
          _bindUser(urls.tail, username, password)
        case e: Throwable =>
          LdapAuthModuleConfig.logger.debug(s"error on LDAP searching method", e)
          Left(s"error on LDAP searching method ${e.getMessage}")
      }
    }
  }

  def checkConnection(): Future[(Boolean, String)] = {
    val env = new util.Hashtable[String, AnyRef]
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    adminUsername.foreach(u => env.put(Context.SECURITY_PRINCIPAL, u))
    adminPassword.foreach(p => env.put(Context.SECURITY_CREDENTIALS, p))

    try {
      for (url <- serverUrls) {
        env.put(Context.PROVIDER_URL, url)
        scala.util.Try {
          val ctx2 = new InitialDirContext(env)
          ctx2.close()
        } match {
          case Success(_) => return FastFuture.successful((true, "--"))
          case Failure(_: ServiceUnavailableException | _: CommunicationException) =>
          case Failure(e) =>  throw e
        }
      }
      FastFuture.successful((false, "Missing LDAP server URLs or all down"))
    } catch {
      case e: Exception => FastFuture.successful((false, e.getMessage))
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
            tags = Seq.empty,
            metadata = Map.empty,
            location = authConfig.location
          )
        )
      case None       => Left(s"You're not authorized here")
    }
  }

  private def userRightContainsTenant(userRights: UserRights): Boolean =
    userRights.rights.exists(f => f.tenant.containsWildcard || f.tenant.value.equals(authConfig.location.tenant.value))

  private def hasOverrideRightsForEmailAndTenant(email: String): Option[UserRight] =
    authConfig.rightsOverride.get(email)
      .flatMap(_.rights.find(p => p.tenant.value.equals(authConfig.location.tenant.value)))

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
            tags = Seq.empty,
            metadata = Map.empty,
            rights =
              if (authConfig.superAdmins) UserRights.superAdmin
              else {
                user.userRights match {
                  case Some(userRight) if userRightContainsTenant(userRight) =>
                    hasOverrideRightsForEmailAndTenant(user.email) match {
                      case Some(rightOverride) => UserRights(Seq(rightOverride))
                      case None =>
                        println(user.userRights)
                        UserRights(
                          Seq(userRight.rights.find(f => f.tenant.containsWildcard ||
                          f.tenant.value.equals(authConfig.location.tenant.value)).get)
                        )
                    }
                  case None =>
                    authConfig.rightsOverride.getOrElse(
                      user.email,
                      UserRights(Seq(
                          UserRight(
                            TenantAccess(authConfig.location.tenant.value),
                            authConfig.location.teams.map(t => TeamAccess(t.value))
                          ))
                      )
                    )
                }
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
            .Unauthorized(otoroshi.views.html.oto.error("You are not authorized here", env))
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
              case None                       => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindUser(username, password, descriptor) match {
                  case Left(_)     => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
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
            otoroshi.views.html.oto
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
            .Unauthorized(otoroshi.views.html.oto.error("You are not authorized here", env))
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
              case None                       => Results.Forbidden(otoroshi.views.html.oto.error("Forbidden access", env)).future
              case Some((username, password)) =>
                bindAdminUser(username, password) match {
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
          .Ok(otoroshi.views.html.oto.login(s"/backoffice/auth0/callback?hash=$hash", "POST", token, false, env))
          .addingToSession(
            "bo-redirect-after-login" -> redirect.getOrElse(
              routes.BackOfficeController.dashboard().absoluteURL(env.exposedRootSchemeIsHttps)
            )
          )
          .future
      }
    }
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
