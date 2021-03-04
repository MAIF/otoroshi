package controllers

import otoroshi.actions.{ApiAction, PrivateAppsAction}
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import auth.{BasicAuthModule, BasicAuthUser}
import env.Env

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.mindrot.jbcrypt.BCrypt
import otoroshi.utils.mailer.EmailLocation
import play.api.libs.json.Json
import play.api.mvc._
import otoroshi.security.IdGenerator
import otoroshi.utils.future.Implicits._

import scala.concurrent.Future

class PrivateAppsController(ApiAction: ApiAction, PrivateAppsAction: PrivateAppsAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  private lazy val secret = new SecretKeySpec(env.secretSession.getBytes, "AES")

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  def home = PrivateAppsAction { ctx =>
    Ok(views.html.privateapps.home(ctx.user, env))
  }

  def redirect = PrivateAppsAction { ctx =>
    implicit val request = ctx.request
    Redirect(
      //request.session
      //  .get("pa-redirect-after-login")
      //  .getOrElse(
      routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
      //  )
    ) //.removingFromSession("pa-redirect-after-login")
  }

  def error(message: Option[String] = None) = PrivateAppsAction { ctx =>
    Ok(views.html.otoroshi.error(message.getOrElse(""), env))
  }

  def withShortSession(
      req: RequestHeader
  )(f: (BasicAuthModule, BasicAuthUser, Long) => Future[Result]): Future[Result] = {
    req.getQueryString("session") match {
      case None => NotFound(Json.obj("error" -> s"session not found")).future
      case Some(cipheredSessionId) => {
        val sessionIdBytes = java.util.Base64.getUrlDecoder.decode(cipheredSessionId)
        val cipher: Cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, secret)
        val sessionId = new String(cipher.doFinal(sessionIdBytes))
        env.datastores.rawDataStore.get(s"${env.rootScheme}:self-service:sessions:$sessionId").flatMap {
          case None => NotFound(Json.obj("error" -> s"session not found")).future
          case Some(sessionRaw) => {
            env.datastores.rawDataStore.pttl(s"${env.rootScheme}:self-service:sessions:$sessionId").flatMap { ttl =>
              val session  = Json.parse(sessionRaw.utf8String)
              val username = (session \ "username").as[String]
              val id       = (session \ "auth").as[String]
              env.datastores.authConfigsDataStore.findById(id).flatMap {
                case Some(auth) => {
                  auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
                    case bam: BasicAuthModule if bam.authConfig.webauthn => {
                      bam.authConfig.users.find(_.email == username) match {
                        case None => NotFound(Json.obj("error" -> s"user not found")).future
                        case Some(user) => {
                          f(bam, user, ttl)
                        }
                      }
                    }
                    case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
                  }
                }
                case None =>
                  NotFound(
                    Json.obj("error" -> s"GlobalAuthModule with id $id not found")
                  ).future
              }
            }
          }
        }
      }
    }
  }

  def registerSessionForUser(authModuleId: String, username: String): Future[(String, String)] = {
    import scala.concurrent.duration._
    val sessionId = IdGenerator.token(32)
    env.datastores.rawDataStore
      .set(
        s"${env.rootScheme}:self-service:sessions:$sessionId",
        ByteString(
          Json.stringify(
            Json.obj(
              "username" -> username,
              "auth"     -> authModuleId
            )
          )
        ),
        Some(10.minutes.toMillis)
      )
      .map { _ =>
        val host           = "http://" + env.privateAppsHost + env.privateAppsPort.map(p => ":" + p).getOrElse("")
        val cipher: Cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val bytes             = cipher.doFinal(sessionId.getBytes)
        val cipheredSessionId = java.util.Base64.getUrlEncoder.encodeToString(bytes)
        (cipheredSessionId, host)
      }
  }

  def registerSession(authModuleId: String, username: String) = ApiAction.async { ctx =>
    ctx.canWriteAuthModule(authModuleId) {
      registerSessionForUser(authModuleId, username).map {
        case (cipheredSessionId, host) => Ok(Json.obj("sessionId" -> cipheredSessionId, "host" -> host))
      }
    }
  }

  def selfRegistrationStart() = Action.async { req =>
    withShortSession(req) {
      case (bam, _, _) =>
        bam.webAuthnRegistrationStart(req.body.asJson.get).map {
          case Left(err)  => BadRequest(err)
          case Right(reg) => Ok(reg)
        }
    }
  }

  def selfRegistrationFinish() = Action.async { req =>
    withShortSession(req) {
      case (bam, _, _) =>
        bam.webAuthnRegistrationFinish(req.body.asJson.get).map {
          case Left(err)  => BadRequest(err)
          case Right(reg) => Ok(reg)
        }
    }
  }

  def selfRegistrationDelete() = Action.async { req =>
    withShortSession(req) {
      case (bam, user, _) =>
        bam.webAuthnRegistrationDelete(user).map {
          case Left(err)  => BadRequest(err)
          case Right(reg) => Ok(reg)
        }
    }
  }

  def selfUpdateProfilePage() = Action.async { req =>
    withShortSession(req) {
      case (bam, user, ttl) =>
        Ok(
          views.html.otoroshi.selfUpdate(
            Json.obj(
              "name"                  -> user.name,
              "email"                 -> user.email,
              "hasWebauthnDeviceReg"  -> user.webauthn.isDefined,
              "mustRegWebauthnDevice" -> bam.authConfig.webauthn
            ),
            req.getQueryString("session").get,
            ttl,
            bam.authConfig.webauthn,
            env
          )
        ).future
    }
  }

  def selfUpdateProfile() = Action.async(parse.json) { req =>
    withShortSession(req) {
      case (bam, user, _) =>
        var newUser = user
        (req.body \ "password").asOpt[String] match {
          case Some(pass) if BCrypt.checkpw(pass, user.password) => {
            val name          = (req.body \ "name").asOpt[String].getOrElse(user.name)
            val newPassword   = (req.body \ "newPassword").asOpt[String]
            val reNewPassword = (req.body \ "reNewPassword").asOpt[String]
            (newPassword, reNewPassword) match {
              case (Some(p1), Some(p2)) if p1 == p2 =>
                val password = BCrypt.hashpw(p1, BCrypt.gensalt(10))
                newUser = newUser.copy(name = name, password = password)
                val conf = bam.authConfig.copy(users = bam.authConfig.users.filterNot(_.email == user.email) :+ newUser)
                conf.save().map { _ =>
                  Ok(
                    Json.obj(
                      "name"                  -> newUser.name,
                      "email"                 -> newUser.email,
                      "hasWebauthnDeviceReg"  -> newUser.webauthn.isDefined,
                      "mustRegWebauthnDevice" -> bam.authConfig.webauthn
                    )
                  )
                }
              case (None, None) =>
                val password = user.password
                newUser = newUser.copy(name = name, password = password)
                val conf = bam.authConfig.copy(users = bam.authConfig.users.filterNot(_.email == user.email) :+ newUser)
                conf.save().map { _ =>
                  Ok(
                    Json.obj(
                      "name"                  -> newUser.name,
                      "email"                 -> newUser.email,
                      "hasWebauthnDeviceReg"  -> newUser.webauthn.isDefined,
                      "mustRegWebauthnDevice" -> bam.authConfig.webauthn
                    )
                  )
                }
              case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "bad password 1")))
            }
          }
          case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "bad password 2")))
        }
    }
  }

  def sendSelfUpdateLink(authModuleId: String, username: String) = ApiAction.async { ctx =>
    registerSessionForUser(authModuleId, username).flatMap {
      case (sessionId, host) =>
        env.datastores.authConfigsDataStore.findById(authModuleId).flatMap {
          case Some(auth) => {
            auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
              case bam: BasicAuthModule if bam.authConfig.webauthn => {
                bam.authConfig.users.find(_.email == username) match {
                  case None => NotFound(Json.obj("error" -> s"user not found")).future
                  case Some(user) => {
                    env.datastores.globalConfigDataStore
                      .singleton()
                      .flatMap { config =>
                        config.mailerSettings
                          .map(
                            _.asMailer(config, env).send(
                              from = EmailLocation("Otoroshi", s"otoroshi@${env.domain}"),
                              to = Seq(EmailLocation(user.name, user.email)),
                              subject = s"Otoroshi - update your profile",
                              html = s"""
                                        |You can update your user profile at the following <a href="${host}/privateapps/profile?session=${sessionId}">link</a>
                                      """.stripMargin
                            )
                          )
                          .getOrElse(FastFuture.successful(()))
                      }
                      .map { _ =>
                        Ok(Json.obj("done" -> true))
                      }
                  }
                }
              }
              case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
            }
          }
          case None =>
            NotFound(
              Json.obj("error" -> s"GlobalAuthModule with id $authModuleId not found")
            ).future
        }
    }
  }
}
