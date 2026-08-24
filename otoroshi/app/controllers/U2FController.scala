package otoroshi.controllers

import java.util.concurrent.TimeUnit

import otoroshi.actions.{BackOfficeAction, BackOfficeActionAuth}
import org.apache.pekko.http.scaladsl.util.FastFuture
import otoroshi.auth.WebAuthnSupport
import otoroshi.env.Env
import otoroshi.events.*
import otoroshi.models.BackOfficeUser
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.RightsChecker.{SuperAdminOnly, TenantAdminOnly}
import otoroshi.models.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.*
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.BCryptHelper
import otoroshi.utils.syntax.implicits.*

import scala.concurrent.duration.Duration

class U2FController(
    BackOfficeAction: BackOfficeAction,
    BackOfficeActionAuth: BackOfficeActionAuth,
    cc: ControllerComponents
)(using env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-u2f-controller")

  def loginPage() =
    BackOfficeAction { (ctx: otoroshi.actions.BackOfficeActionContext[play.api.mvc.AnyContent]) =>
      Ok(otoroshi.views.html.backoffice.u2flogin(env))
    }

  /////////// Simple admins ////////////////////////////////////////////////////////////////////////////////////////////

  def simpleLogin =
    BackOfficeAction.async(parse.json) { ctx =>
      implicit val req = ctx.request
      val usernameOpt  = (ctx.request.body \ "username").asOpt[String]
      val passwordOpt  = (ctx.request.body \ "password").asOpt[String]
      (usernameOpt, passwordOpt) match {
        case (Some(username), Some(pass)) => {
          env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
            case Some(user) => {
              val password = user.password
              val label    = user.label
              if (BCryptHelper.checkpw(pass, password)) {
                if (logger.isDebugEnabled) logger.debug(s"Login successful for simple admin '$username'")
                BackOfficeUser(
                  randomId = IdGenerator.token(64),
                  name = username,
                  email = username,
                  profile = Json.obj(
                    "name"  -> label,
                    "email" -> username
                  ),
                  token = Json.obj(),
                  authConfigId = "none",
                  simpleLogin = true,
                  tags = Seq.empty,
                  metadata = Map.empty,
                  rights = user.rights,
                  location = user.location,
                  adminEntityValidators = user.adminEntityValidators
                ).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                  env.datastores.simpleAdminDataStore.hasAlreadyLoggedIn(username).map {
                    case false => {
                      env.datastores.simpleAdminDataStore.alreadyLoggedIn(username)
                      Alerts
                        .send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua))
                    }
                    case true  => {
                      Alerts
                        .send(
                          AdminLoggedInAlert(
                            env.snowflakeGenerator.nextIdStr(),
                            env.env,
                            boUser,
                            ctx.from,
                            ctx.ua,
                            "local"
                          )
                        )
                    }
                  }
                  Ok(Json.obj("username" -> username)).addingToSession("bousr" -> boUser.randomId)
                }
              } else {
                Unauthorized(Json.obj("error" -> "not authorized")).future
              }
            }
            case None       => Unauthorized(Json.obj("error" -> "not authorized")).future
          }
        }
        case _                            => Unauthorized(Json.obj("error" -> "not authorized")).future
      }
    }

  /*
  def registerSimpleAdmin = BackOfficeActionAuth.async(parse.json) { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      val usernameOpt = (ctx.request.body \ "username").asOpt[String]
      val passwordOpt = (ctx.request.body \ "password").asOpt[String]
      val labelOpt = (ctx.request.body \ "label").asOpt[String]
      val rights = UserRights(Seq(UserRight(TenantAccess(ctx.currentTenant.value), Seq(TeamAccess("*"))))) // UserRights.readFromObject(ctx.request.body)
      (usernameOpt, passwordOpt, labelOpt) match {
        case (Some(username), Some(password), Some(label)) => {
          val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
          env.datastores.simpleAdminDataStore.registerUser(SimpleOtoroshiAdmin(
            username = username,
            password = saltedPassword,
            label = label,
            createdAt = DateTime.now(),
            typ = OtoroshiAdminType.SimpleAdmin,
            metadata = Map.empty,
            rights = rights,
            location = EntityLocation(ctx.currentTenant, Seq(TeamId.all))  // EntityLocation.readFromKey(ctx.request.body)
          )).map { _ =>
            Ok(Json.obj("username" -> username))
          }
        }
        case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
      }
    }
  }

  def simpleAdmins = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition = (paginationPage - 1) * paginationPageSize
      env.datastores.simpleAdminDataStore.findAll() map { users =>
        Ok(JsArray(users.filter(ctx.canUserRead).drop(paginationPosition).take(paginationPageSize).map(_.json)))
      }
    }
  }

  def deleteAdmin(username: String) = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
        case None => NotFound(Json.obj("error" -> "User not found !")).future
        case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
        case Some(_) => {
          env.datastores.simpleAdminDataStore.deleteUser(username).map { d =>
            val event = BackOfficeEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user,
              "DELETE_ADMIN",
              s"Admin deleted an Admin",
              ctx.from,
              ctx.ua,
              Json.obj("username" -> username)
            )
            Audit.send(event)
            Alerts.send(U2FAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
            Ok(Json.obj("done" -> true))
          }
        }
      }
    }
  }
   */

  /////////// WebAuthn admins ////////////////////////////////////////////////////////////////////////////////////////////
  /*
  def webAuthnAdmins() = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition = (paginationPage - 1) * paginationPageSize
      env.datastores.webAuthnAdminDataStore.findAll() map { users =>
        Ok(JsArray(users.filter(ctx.canUserRead).drop(paginationPosition).take(paginationPageSize).map(_.json)))
      }
    }
  }

  def webAuthnDeleteAdmin(username: String, id: String) = BackOfficeActionAuth.async { ctx =>
    ctx.checkRights(TenantAdminOnly) {
      env.datastores.webAuthnAdminDataStore.findByUsername(username).flatMap {
        case None => NotFound(Json.obj("error" -> "User not found !")).future
        case Some(user) if !ctx.canUserWrite(user) => ctx.fforbidden
        case Some(_) => {
          env.datastores.webAuthnAdminDataStore.deleteUser(username).map { d =>
            val event = BackOfficeEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user,
              "DELETE_WEBAUTHN_ADMIN",
              s"Admin deleted a WebAuthn Admin",
              ctx.from,
              ctx.ua,
              Json.obj("username" -> username, "id" -> id)
            )
            Audit.send(event)
            Alerts
              .send(WebAuthnAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event, ctx.from, ctx.ua))
            Ok(Json.obj("done" -> true))
          }
        }
      }
    }
  }
   */
  def webAuthnRegistrationStart() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      ctx.checkRights(TenantAdminOnly) {
        val username = (ctx.request.body \ "username").as[String]
        val label    = (ctx.request.body \ "label").as[String]
        val origin   = (ctx.request.body \ "origin").as[String]
        env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
          // an user always keeps the same handle, otherwise the keys registered before would not be usable anymore
          val handle = users.find(_.username == username).map(_.handle)
          WebAuthnSupport.registrationStart(users, username, label, origin, handle).map { request =>
            Ok(request)
          }
        }
      }
    }

  def webAuthnRegistrationFinish() =
    BackOfficeActionAuth.async(parse.json) { ctx =>
      ctx.checkRights(SuperAdminOnly) {
        val otoroshi = (ctx.request.body \ "otoroshi").as[JsObject]
        val username = (otoroshi \ "username").as[String]
        val password = (otoroshi \ "password").as[String]
        val label    = (otoroshi \ "label").as[String]
        env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
          WebAuthnSupport.registrationFinish(users, ctx.request.body).flatMap {
            case Left(err)           => BadRequest(Json.obj("error" -> err)).future
            case Right(registration) => {
              val credential = registration.credential
              env.datastores.webAuthnAdminDataStore.findByUsername(username).flatMap {
                case None                                                        => {
                  val rights = UserRights(
                    Seq(UserRight(TenantAccess(ctx.currentTenant.value), Seq(TeamAccess("*"))))
                  ) // UserRights.readFromObject(otoroshi)
                  env.datastores.webAuthnAdminDataStore
                    .registerUser(
                      WebAuthnOtoroshiAdmin(
                        username = username,
                        password = BCrypt.hashpw(password, BCrypt.gensalt()),
                        label = label,
                        handle = registration.handle,
                        credentials = Map(credential.id -> credential.json),
                        createdAt = DateTime.now(),
                        typ = OtoroshiAdminType.WebAuthnAdmin,
                        metadata = Map.empty,
                        rights = rights,
                        adminEntityValidators = Map.empty,
                        location = EntityLocation(
                          ctx.currentTenant,
                          Seq(TeamId.all)
                        ) //EntityLocation.readFromKey(ctx.request.body)
                      )
                    )
                    .map { _ =>
                      Ok(Json.obj("username" -> username))
                    }
                }
                case Some(user) if BCryptHelper.checkpw(password, user.password) => {
                  // update user
                  env.datastores.webAuthnAdminDataStore
                    .registerUser(
                      user.copy(credentials = user.credentials + (credential.id -> credential.json))
                    )
                    .map { _ =>
                      Ok(Json.obj("username" -> username))
                    }
                }
                case Some(_)                                                     => Unauthorized(Json.obj("error" -> "bad credentials")).future
              }
            }
          }
        }
      }
    }

  def webAuthnLoginStart() =
    BackOfficeAction.async(parse.json) { ctx =>
      val usernameOpt = (ctx.request.body \ "username").asOpt[String]
      val passwordOpt = (ctx.request.body \ "password").asOpt[String]
      val origin      = (ctx.request.body \ "origin").as[String]
      (usernameOpt, passwordOpt) match {
        case (Some(username), Some(password)) => {
          env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
            users.find(u => u.username == username) match {
              case Some(user) if BCryptHelper.checkpw(password, user.password) => {
                WebAuthnSupport.loginStart(users, username, origin).map { request =>
                  Ok(request)
                }
              }
              case _                                                           => BadRequest(Json.obj("error" -> "bad request")).future
            }
          }
        }
        case (_, _)                           => BadRequest(Json.obj("error" -> "bad request")).future
      }
    }

  def webAuthnLoginFinish() =
    BackOfficeAction.async(parse.json) { ctx =>
      implicit val req = ctx.request

      val otoroshi    = (ctx.request.body \ "otoroshi").as[JsObject]
      val usernameOpt = (otoroshi \ "username").asOpt[String]
      val passwordOpt = (otoroshi \ "password").asOpt[String]
      (usernameOpt, passwordOpt) match {
        case (Some(username), Some(pass)) => {
          env.datastores.webAuthnAdminDataStore.findAll().flatMap { users =>
            users.find(u => u.username == username) match {
              case None                                                     => BadRequest(Json.obj("error" -> "Bad user")).future
              case Some(user) if !BCryptHelper.checkpw(pass, user.password) =>
                Unauthorized(Json.obj("error" -> "Not Authorized")).future
              case Some(user)                                               => {
                WebAuthnSupport.loginFinish(users, ctx.request.body).flatMap {
                  case Left(err)     => BadRequest(Json.obj("error" -> err)).future
                  case Right(result) => {
                    if (logger.isDebugEnabled) logger.debug(s"Login successful for user '$username'")
                    WebAuthnSupport.updateSignatureCount(user, result).flatMap { _ =>
                      BackOfficeUser(
                        randomId = IdGenerator.token(64),
                        name = username,
                        email = username,
                        profile = Json.obj(
                          "name"  -> user.label,
                          "email" -> username
                        ),
                        token = Json.obj(),
                        authConfigId = "none",
                        simpleLogin = false,
                        tags = Seq.empty,
                        metadata = Map.empty,
                        rights = user.rights,
                        location = user.location,
                        adminEntityValidators = user.adminEntityValidators
                      ).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                        env.datastores.webAuthnAdminDataStore.hasAlreadyLoggedIn(username).map {
                          case false => {
                            env.datastores.webAuthnAdminDataStore.alreadyLoggedIn(username)
                            Alerts.send(
                              AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua)
                            )
                          }
                          case true  => {
                            Alerts.send(
                              AdminLoggedInAlert(
                                env.snowflakeGenerator.nextIdStr(),
                                env.env,
                                boUser,
                                ctx.from,
                                ctx.ua,
                                "local"
                              )
                            )
                          }
                        }
                        Ok(
                          Json.obj("username" -> username)
                        ).addingToSession("bousr" -> boUser.randomId)
                      }
                    }
                  }
                }
              }
            }
          }
        }
        case (_, _)                       => Unauthorized(Json.obj("error" -> "Not Authorized")).future
      }
    }
}
