package otoroshi.controllers.adminapi

import otoroshi.actions.{ApiAction, ApiActionContext}
import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.{BackOfficeUser, PrivateAppsUser}
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.RightsChecker.{SuperAdminOnly, TenantAdminOnly}
import otoroshi.models.{UserRights, _}
import otoroshi.utils.controllers.{AdminApiHelper, JsonApiError, SendAuditAndAlert}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import otoroshi.security.IdGenerator

import scala.concurrent.Future

class UsersController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec = env.otoroshiExecutionContext

  private val fakeBackOfficeUser = BackOfficeUser(
    randomId = IdGenerator.token,
    name = "fake user",
    email = "fake.user@otoroshi.io",
    profile = Json.obj(),
    simpleLogin = false,
    authConfigId = "none",
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights.superAdmin,
    location = EntityLocation()
  )

  def sessions() =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val options = SendAuditAndAlert("ACCESS_ADMIN_SESSIONS", s"User accessed admin session", None, Json.obj(), ctx)
        fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: BackOfficeUser) => e.toJson, options) {
          env.datastores.backOfficeUserDataStore.findAll().map(_.filter(ctx.canUserRead)).fright[JsonApiError]
        }
      }
    }

  def discardSession(id: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
          env.datastores.backOfficeUserDataStore.findById(id).flatMap {
            case None                                        => NotFound(Json.obj("error" -> "Session not found !")).future
            case Some(session) if !ctx.canUserWrite(session) => ctx.fforbidden
            case Some(_)                                     => {
              env.datastores.backOfficeUserDataStore.discardSession(id) map { _ =>
                val event = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  None,
                  "DISCARD_SESSION",
                  s"Admin discarded an Admin session",
                  ctx.from,
                  ctx.ua,
                  Json.obj("sessionId" -> id)
                )
                Audit.send(event)
                Alerts
                  .send(
                    SessionDiscardedAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      fakeBackOfficeUser,
                      event,
                      ctx.from,
                      ctx.ua
                    )
                  )
                Ok(Json.obj("done" -> true))
              }
            }
          }
        } recover { case _ =>
          Ok(Json.obj("done" -> false))
        }
      }
    }

  def discardAllSessions() =
    ApiAction.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
          env.datastores.backOfficeUserDataStore.discardAllSessions() map { _ =>
            val event = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              None,
              "DISCARD_SESSIONS",
              s"Admin discarded Admin sessions",
              ctx.from,
              ctx.ua,
              Json.obj()
            )
            Audit.send(event)
            Alerts
              .send(
                SessionsDiscardedAlert(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  fakeBackOfficeUser,
                  event,
                  ctx.from,
                  ctx.ua
                )
              )
            Ok(Json.obj("done" -> true))
          }
        } recover { case _ =>
          Ok(Json.obj("done" -> false))
        }
      }
    }

  def privateAppsSessions() =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val options = SendAuditAndAlert(
          "ACCESS_PRIVATE_APPS_SESSIONS",
          s"User accessed private apps session",
          None,
          Json.obj(),
          ctx
        )
        fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: PrivateAppsUser) => e.toJson, options) {
          env.datastores.privateAppsUserDataStore.findAll().map(_.filter(ctx.canUserRead)).fright[JsonApiError]
        }
      }
    }

  def discardPrivateAppsSession(id: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
          env.datastores.privateAppsUserDataStore.findById(id).flatMap {
            case None                                        => Results.NotFound(Json.obj("error" -> "Session not found")).future
            case Some(session) if !ctx.canUserWrite(session) => ctx.fforbidden
            case Some(_)                                     => {
              env.datastores.privateAppsUserDataStore.delete(id) map { _ =>
                val event = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  None,
                  "DISCARD_PRIVATE_APPS_SESSION",
                  s"Admin discarded a private app session",
                  ctx.from,
                  ctx.ua,
                  Json.obj("sessionId" -> id)
                )
                Audit.send(event)
                Alerts
                  .send(
                    SessionDiscardedAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      fakeBackOfficeUser,
                      event,
                      ctx.from,
                      ctx.ua
                    )
                  )
                Ok(Json.obj("done" -> true))
              }
            }
          }
        } recover { case _ =>
          Ok(Json.obj("done" -> false))
        }
      }
    }

  def discardAllPrivateAppsSessions() =
    ApiAction.async { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
          env.datastores.privateAppsUserDataStore.deleteAll() map { _ =>
            val event = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              None,
              "DISCARD_PRIVATE_APPS_SESSIONS",
              s"Admin discarded private apps sessions",
              ctx.from,
              ctx.ua,
              Json.obj()
            )
            Audit.send(event)
            Alerts
              .send(
                SessionsDiscardedAlert(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  fakeBackOfficeUser,
                  event,
                  ctx.from,
                  ctx.ua
                )
              )
            Ok(Json.obj("done" -> true))
          }
        } recover { case _ =>
          Ok(Json.obj("done" -> false))
        }
      }
    }

  def checkNewUserRights(ctx: ApiActionContext[_], rights: UserRights)(f: => Future[Result]): Future[Result] = {
    if (!ctx.userIsSuperAdmin && rights.superAdmin) {
      println("bim !")
      FastFuture.successful(Forbidden(Json.obj("error" -> "you can't set superadmin rights to an admin")))
    } else {
      val pass: Boolean = ctx.backOfficeUser match {
        case Left(_)           =>
          println("left")
          true
        case Right(None)       =>
          println("right none")
          true
        case Right(Some(user)) => {
          println(s"right some ${user.json}")
          val tenantAccesses    = user.rights.rights.map(_.tenant)
          val newTenantAccesses = rights.rights.map(_.tenant)
          val badTenantAccess   = newTenantAccesses.exists(v => !tenantAccesses.contains(v))
          val badTeamAccess     = user.rights.rights.exists { right =>
            user.rights.rights.find(_.tenant.value == right.tenant.value) match {
              case None    => false
              case Some(r) => {
                val teams    = r.teams
                val newTeams = right.teams
                newTeams.exists(v => !teams.contains(v))
              }
            }
          }
          !(badTenantAccess && badTeamAccess)
        }
      }
      if (pass) {
        f
      } else {
        FastFuture.successful(Forbidden(Json.obj("error" -> "you can't set superadmin rights to an admin")))
      }
    }
  }

  def registerSimpleAdmin =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val usernameOpt = (ctx.request.body \ "username").asOpt[String]
        val passwordOpt = (ctx.request.body \ "password").asOpt[String]
        val labelOpt    = (ctx.request.body \ "label").asOpt[String]
        val rights      =
          UserRights(
            Seq(UserRight(TenantAccess(ctx.currentTenant.value), Seq(TeamAccess("*"))))
          ) // UserRights.readFromObject(ctx.request.body)
        checkNewUserRights(ctx, rights) {
          (usernameOpt, passwordOpt, labelOpt) match {
            case (Some(username), Some(password), Some(label)) => {
              val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
              env.datastores.simpleAdminDataStore
                .registerUser(
                  SimpleOtoroshiAdmin(
                    username = username,
                    password = saltedPassword,
                    label = label,
                    createdAt = DateTime.now(),
                    typ = OtoroshiAdminType.SimpleAdmin,
                    metadata = (ctx.request.body \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
                    rights = rights,
                    location =
                      EntityLocation(ctx.currentTenant, Seq(TeamId.all)) // EntityLocation.readFromKey(ctx.request.body)
                  )
                )
                .map { _ =>
                  Ok(Json.obj("username" -> username))
                }
            }
            case _                                             => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
          }
        }
      }
    }

  def simpleAdmins =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val options = SendAuditAndAlert("ACCESS_SIMPLE_ADMINS", s"User accessed simple admins", None, Json.obj(), ctx)
        fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: JsValue) => e, options) {
          env.datastores.simpleAdminDataStore.findAll().map(_.filter(ctx.canUserRead).map(_.json)).fright[JsonApiError]
        }
      }
    }

  def deleteAdmin(username: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
          case None                                  => NotFound(Json.obj("error" -> "User not found !")).future
          case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
          case Some(_)                               => {
            env.datastores.simpleAdminDataStore.deleteUser(username).map { d =>
              val event = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                None,
                "DELETE_ADMIN",
                s"Admin deleted an Admin",
                ctx.from,
                ctx.ua,
                Json.obj("username" -> username)
              )
              Audit.send(event)
              Alerts.send(
                U2FAdminDeletedAlert(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  fakeBackOfficeUser,
                  event,
                  ctx.from,
                  ctx.ua
                )
              )
              Ok(Json.obj("done" -> true))
            }
          }
        }
      }
    }

  def updateAdmin(username: String) =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
          case None                                  => NotFound(Json.obj("error" -> "user not found")).future
          case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
          case Some(_)                               => {
            val _newUser = SimpleOtoroshiAdmin.fmt.reads(ctx.request.body).get
            checkNewUserRights(ctx, _newUser.rights) {
              val newUser = _newUser.copy(username = username)
              env.datastores.simpleAdminDataStore.registerUser(newUser).map { _ =>
                Ok(newUser.json)
              }
            }
          }
        }
      }
    }

  def updateWebAuthnAdmin(username: String) =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.webAuthnAdminDataStore.findByUsername(username).flatMap {
          case None                                  => NotFound(Json.obj("error" -> "user not found")).future
          case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
          case Some(_)                               => {
            val _newUser = WebAuthnOtoroshiAdmin.fmt.reads(ctx.request.body).get
            checkNewUserRights(ctx, _newUser.rights) {
              val newUser = _newUser.copy(username = username)
              env.datastores.webAuthnAdminDataStore.registerUser(newUser).map { _ =>
                Ok(newUser.json)
              }
            }
          }
        }
      }
    }

  def webAuthnAdmins() =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val options =
          SendAuditAndAlert("ACCESS_WEBAUTHN_ADMINS", s"User accessed webauthn admins", None, Json.obj(), ctx)
        fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: JsValue) => e, options) {
          env.datastores.webAuthnAdminDataStore
            .findAll()
            .map(_.filter(ctx.canUserRead).map(_.json))
            .fright[JsonApiError]
        }
      }
    }

  def registerWebAuthnAdmin() =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val usernameOpt   = (ctx.request.body \ "username").asOpt[String]
        val passwordOpt   = (ctx.request.body \ "password").asOpt[String]
        val labelOpt      = (ctx.request.body \ "label").asOpt[String]
        val credentialOpt = (ctx.request.body \ "credential").asOpt[JsValue]
        val handleOpt     = (ctx.request.body \ "handle").asOpt[String]
        val rights        = UserRights.readFromObject(ctx.request.body)
        checkNewUserRights(ctx, rights) {
          (usernameOpt, passwordOpt, labelOpt, handleOpt) match {
            case (Some(username), Some(password), Some(label), Some(handle)) => {
              val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
              env.datastores.webAuthnAdminDataStore
                .registerUser(
                  WebAuthnOtoroshiAdmin(
                    username = username,
                    password = saltedPassword,
                    label = label,
                    handle = handle,
                    credentials =
                      credentialOpt.map(v => Map((v \ "keyId" \ "id").as[String] -> v)).getOrElse(Map.empty),
                    createdAt = DateTime.now(),
                    typ = OtoroshiAdminType.WebAuthnAdmin,
                    metadata = (ctx.request.body \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
                    rights = rights,
                    location =
                      EntityLocation(ctx.currentTenant, Seq(TeamId.all)) // EntityLocation.readFromKey(ctx.request.body)
                  )
                )
                .map { _ =>
                  Ok(Json.obj("username" -> username))
                }
            }
            case _                                                           => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
          }
        }
      }
    }

  def webAuthnDeleteAdmin(username: String, id: String) =
    ApiAction.async { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        env.datastores.webAuthnAdminDataStore.findByUsername(username).flatMap {
          case None                                  => NotFound(Json.obj("error" -> "User not found !")).future
          case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
          case Some(_)                               => {
            env.datastores.webAuthnAdminDataStore.deleteUser(username).map { d =>
              val event = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                None,
                "DELETE_WEBAUTHN_ADMIN",
                s"Admin deleted a WebAuthn Admin",
                ctx.from,
                ctx.ua,
                Json.obj("username" -> username, "id" -> id)
              )
              Audit.send(event)
              Alerts
                .send(
                  WebAuthnAdminDeletedAlert(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    fakeBackOfficeUser,
                    event,
                    ctx.from,
                    ctx.ua
                  )
                )
              Ok(Json.obj("done" -> true))
            }
          }
        }
      }
    }
}
