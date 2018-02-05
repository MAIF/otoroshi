package controllers

import java.util.concurrent.TimeUnit

import actions.{BackOfficeAction, BackOfficeActionAuth}
import akka.http.scaladsl.util.FastFuture
import com.yubico.u2f.U2F
import com.yubico.u2f.attestation.MetadataService
import com.yubico.u2f.data.DeviceRegistration
import com.yubico.u2f.data.messages.{
  AuthenticateRequestData,
  AuthenticateResponse,
  RegisterRequestData,
  RegisterResponse
}
import env.Env
import events._
import models.BackOfficeUser
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Controller
import security.IdGenerator

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import utils.future.Implicits._

class U2FController(BackOfficeAction: BackOfficeAction, BackOfficeActionAuth: BackOfficeActionAuth)(implicit env: Env)
    extends Controller {

  implicit lazy val ec = env.backOfficeExecutionContext

  lazy val logger = Logger("otoroshi-u2f-controller")

  val u2f             = U2F.withoutAppIdValidation() // new U2F()
  val metadataService = new MetadataService()

  def loginPage() = BackOfficeAction { ctx =>
    Ok(views.html.backoffice.u2flogin(env))
  }

  def simpleLogin = BackOfficeAction.async(parse.json) { ctx =>
    implicit val req = ctx.request
    val usernameOpt  = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt  = (ctx.request.body \ "password").asOpt[String]
    (usernameOpt, passwordOpt) match {
      case (Some(username), Some(pass)) => {
        env.datastores.simpleAdminDataStore.findByUsername(username).flatMap {
          case Some(user) => {
            val password = (user \ "password").as[String]
            val label    = (user \ "label").as[String]
            if (BCrypt.checkpw(pass, password)) {
              logger.info(s"Login successful for simple admin '$username'")
              BackOfficeUser(IdGenerator.token(64),
                             username,
                             username,
                             Json.obj(
                               "name"  -> label,
                               "email" -> username
                             )).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                env.datastores.simpleAdminDataStore.hasAlreadyLoggedIn(username).map {
                  case false => {
                    env.datastores.simpleAdminDataStore.alreadyLoggedIn(username)
                    Alerts.send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                  }
                  case true => {
                    Alerts.send(AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                  }
                }
                Ok(Json.obj("username" -> username)).addingToSession("bousr" -> boUser.randomId)
              }
            } else {
              Unauthorized(Json.obj("error" -> "not authorized")).asFuture
            }
          }
          case None => Unauthorized(Json.obj("error" -> "not authorized")).asFuture
        }
      }
      case _ => Unauthorized(Json.obj("error" -> "not authorized")).asFuture
    }
  }

  def registerSimpleAdmin = BackOfficeActionAuth.async(parse.json) { ctx =>
    val usernameOpt = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt = (ctx.request.body \ "password").asOpt[String]
    val labelOpt    = (ctx.request.body \ "label").asOpt[String]
    (usernameOpt, passwordOpt, labelOpt) match {
      case (Some(username), Some(password), Some(label)) => {
        val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        env.datastores.simpleAdminDataStore.registerUser(username, saltedPassword, label).map { _ =>
          Ok(Json.obj("username" -> username))
        }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def simpleAdmins = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.simpleAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def deleteAdmin(username: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.simpleAdminDataStore.deleteUser(username).map { d =>
      val event = BackOfficeEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        ctx.user,
        "DELETE_ADMIN",
        s"Admin deleted an Admin",
        ctx.from,
        Json.obj("username" -> username)
      )
      Audit.send(event)
      Alerts.send(U2FAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event))
      Ok(Json.obj("done" -> true))
    }
  }

  def u2fRegisterStart() = BackOfficeActionAuth.async(parse.json) { ctx =>
    val appId = s"${env.rootScheme}${env.backOfficeHost}"
    (ctx.request.body \ "username").asOpt[String] match {
      case Some(u) => {
        import collection.JavaConversions.asJavaIterable
        env.datastores.u2FAdminDataStore.getUserRegistration(u).flatMap { it =>
          val registerRequestData = u2f.startRegistration(appId, it.map(_._1))
          logger.info(s"registerRequestData ${Json.prettyPrint(Json.parse(registerRequestData.toJson))}")
          env.datastores.u2FAdminDataStore
            .addRequest(registerRequestData.getRequestId, registerRequestData.toJson)
            .map {
              case true  => Ok(Json.obj("username"               -> u, "data" -> Json.parse(registerRequestData.toJson)))
              case false => InternalServerError(Json.obj("error" -> "error while persisting"))
            }
        }
      }
      case None => FastFuture.successful(BadRequest(Json.obj("error" -> "no username provided")))
    }
  }

  def u2fRegisterFinish() = BackOfficeActionAuth.async(parse.json) { ctx =>
    import collection.JavaConversions._
    val usernameOpt = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt = (ctx.request.body \ "password").asOpt[String]
    val labelOpt    = (ctx.request.body \ "label").asOpt[String]
    val responseOpt = (ctx.request.body \ "tokenResponse").asOpt[JsObject]
    (usernameOpt, passwordOpt, labelOpt, responseOpt) match {
      case (Some(username), Some(password), Some(label), Some(response)) => {
        val registerResponse = RegisterResponse.fromJson(Json.stringify(response))
        env.datastores.u2FAdminDataStore.getRequest(registerResponse.getRequestId).flatMap {
          case None => FastFuture.successful(NotFound(Json.obj("error" -> "Unknown request")))
          case Some(data) => {
            val registerRequestData = RegisterRequestData.fromJson(data)
            val registration        = u2f.finishRegistration(registerRequestData, registerResponse)
            val attestation         = metadataService.getAttestation(registration.getAttestationCertificate)
            env.datastores.u2FAdminDataStore.deleteRequest(registerResponse.getRequestId).flatMap { _ =>
              val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
              env.datastores.u2FAdminDataStore.registerUser(username, saltedPassword, label, registration).map { _ =>
                val jsonAttestation = Json.obj(
                  "isTrusted"          -> attestation.isTrusted,
                  "metadataIdentifier" -> attestation.getMetadataIdentifier,
                  "vendorProperties"   -> JsObject(attestation.getVendorProperties.toMap.mapValues(JsString.apply)),
                  "deviceProperties"   -> JsObject(attestation.getDeviceProperties.toMap.mapValues(JsString.apply)),
                  "transports"         -> JsArray(attestation.getTransports.toIndexedSeq.map(t => JsString(t.name()))),
                  "registration"       -> Json.parse(registration.toJsonWithAttestationCert)
                )
                logger.info(s"$username => ${Json.prettyPrint(jsonAttestation)}")
                Ok(
                  Json.obj(
                    "username"    -> username,
                    "attestation" -> jsonAttestation
                  )
                )
              }
            }
          }
        }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def u2fAuthenticationStart() = BackOfficeAction.async(parse.json) { ctx =>
    val appId = s"${env.rootScheme}${env.backOfficeHost}"
    (ctx.request.body \ "username").asOpt[String] match {
      case Some(u) => {
        import collection.JavaConversions.asJavaIterable
        env.datastores.u2FAdminDataStore.getUserRegistration(u).flatMap { it =>
          val authenticateRequestData = u2f.startAuthentication(appId, it.map(_._1))
          logger.info(s"authenticateRequestData ${Json.prettyPrint(Json.parse(authenticateRequestData.toJson))}")
          env.datastores.u2FAdminDataStore
            .addRequest(authenticateRequestData.getRequestId, authenticateRequestData.toJson)
            .map {
              case true  => Ok(Json.obj("username"               -> u, "data" -> Json.parse(authenticateRequestData.toJson)))
              case false => InternalServerError(Json.obj("error" -> "error while persisting"))
            }
        }
      }
      case None => FastFuture.successful(BadRequest(Json.obj("error" -> "no username provided")))
    }
  }

  def u2fAuthenticationFinish() = BackOfficeAction.async(parse.json) { ctx =>
    implicit val req = ctx.request
    import collection.JavaConversions._
    val usernameOpt = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt = (ctx.request.body \ "password").asOpt[String]
    val responseOpt = (ctx.request.body \ "tokenResponse").asOpt[JsObject]
    (usernameOpt, passwordOpt, responseOpt) match {
      case (Some(username), Some(pass), Some(response)) => {
        val authenticateResponse = AuthenticateResponse.fromJson(Json.stringify(response))
        env.datastores.u2FAdminDataStore.getRequest(authenticateResponse.getRequestId).flatMap {
          case None => FastFuture.successful(NotFound(Json.obj("error" -> "Unknown request")))
          case Some(data) => {
            env.datastores.u2FAdminDataStore.getUserRegistration(username).flatMap { it =>
              val authenticateRequest = AuthenticateRequestData.fromJson(data)
              val registration        = u2f.finishAuthentication(authenticateRequest, authenticateResponse, it.map(_._1))
              env.datastores.u2FAdminDataStore.deleteRequest(authenticateRequest.getRequestId).flatMap { _ =>
                val keyHandle = registration.getKeyHandle
                val user      = it.filter(_._1.getKeyHandle == keyHandle).head._2
                val password  = (user \ "password").as[String]
                val label     = (user \ "label").as[String]
                if (BCrypt.checkpw(pass, password)) {
                  env.datastores.u2FAdminDataStore.registerUser(username, password, label, registration).flatMap { _ =>
                    logger.info(s"Login successful for user '$username'")
                    BackOfficeUser(IdGenerator.token(64),
                                   username,
                                   username,
                                   Json.obj(
                                     "name"  -> label,
                                     "email" -> username
                                   )).save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS)).map { boUser =>
                      env.datastores.u2FAdminDataStore.hasAlreadyLoggedIn(username).map {
                        case false => {
                          env.datastores.u2FAdminDataStore.alreadyLoggedIn(username)
                          Alerts.send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                        }
                        case true => {
                          Alerts.send(AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                        }
                      }
                      Ok(
                        Json.obj(
                          "username" -> username
                        )
                      ).addingToSession("bousr" -> boUser.randomId)
                    }
                  }
                } else {
                  FastFuture.successful(Unauthorized(Json.obj("error" -> "Not Authorized")))
                }
              }
            }
          }
        }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def u2fAdmins() = BackOfficeActionAuth.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.u2FAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def deleteU2FAdmin(username: String, id: String) = BackOfficeActionAuth.async { ctx =>
    env.datastores.u2FAdminDataStore.deleteUser(username, id).map { d =>
      val event = BackOfficeEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        ctx.user,
        "DELETE_U2F_ADMIN",
        s"Admin deleted an U2F Admin",
        ctx.from,
        Json.obj("username" -> username, "id" -> id)
      )
      Audit.send(event)
      Alerts.send(U2FAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, event))
      Ok(Json.obj("done" -> true))
    }
  }
}
