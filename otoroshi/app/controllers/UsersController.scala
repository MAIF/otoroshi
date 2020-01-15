package controllers

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import events._
import models.BackOfficeUser
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc._
import security.IdGenerator

class UsersController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  private val fakeBackOfficeUser = BackOfficeUser(
    randomId = IdGenerator.token,
    name = "fake user",
    email = "fake.user@otoroshi.io",
    profile = Json.obj(),
    authorizedGroup = None,
    simpleLogin = false
  )

  def sessions() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.backOfficeUserDataStore.sessions() map { sessions =>
      Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def discardSession(id: String) = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
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
            SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  fakeBackOfficeUser,
                                  event,
                                  ctx.from,
                                  ctx.ua)
          )
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def discardAllSessions() = ApiAction.async { ctx =>
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
            SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(),
                                   env.env,
                                   fakeBackOfficeUser,
                                   event,
                                   ctx.from,
                                   ctx.ua)
          )
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def privateAppsSessions() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.privateAppsUserDataStore.findAll() map { sessions =>
      Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
    }
  }

  def discardPrivateAppsSession(id: String) = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().filter(!_.apiReadOnly).flatMap { _ =>
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
            SessionDiscardedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  fakeBackOfficeUser,
                                  event,
                                  ctx.from,
                                  ctx.ua)
          )
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def discardAllPrivateAppsSessions() = ApiAction.async { ctx =>
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
            SessionsDiscardedAlert(env.snowflakeGenerator.nextIdStr(),
                                   env.env,
                                   fakeBackOfficeUser,
                                   event,
                                   ctx.from,
                                   ctx.ua)
          )
        Ok(Json.obj("done" -> true))
      }
    } recover {
      case _ => Ok(Json.obj("done" -> false))
    }
  }

  def auditEvents() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.auditDataStore.findAllRaw().map { elems =>
      val filtered = elems.drop(paginationPosition).take(paginationPageSize)
      Ok.chunked(
          Source
            .single(ByteString("["))
            .concat(
              Source
                .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                .intersperse(ByteString(","))
            )
            .concat(Source.single(ByteString("]")))
        )
        .as("application/json")
    }
  }

  def alertEvents() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.alertDataStore.findAllRaw().map { elems =>
      val filtered = elems.drop(paginationPosition).take(paginationPageSize)
      Ok.chunked(
          Source
            .single(ByteString("["))
            .concat(
              Source
                .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
                .intersperse(ByteString(","))
            )
            .concat(Source.single(ByteString("]")))
        )
        .as("application/json")
    }
  }

  def registerSimpleAdmin = ApiAction.async(parse.json) { ctx =>
    val usernameOpt        = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt        = (ctx.request.body \ "password").asOpt[String]
    val labelOpt           = (ctx.request.body \ "label").asOpt[String]
    val authorizedGroupOpt = (ctx.request.body \ "authorizedGroup").asOpt[String]
    (usernameOpt, passwordOpt, labelOpt, authorizedGroupOpt) match {
      case (Some(username), Some(password), Some(label), authorizedGroup) => {
        val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        env.datastores.simpleAdminDataStore.registerUser(username, saltedPassword, label, authorizedGroup).map { _ =>
          Ok(Json.obj("username" -> username))
        }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def simpleAdmins = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.simpleAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def deleteAdmin(username: String) = ApiAction.async { ctx =>
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
        U2FAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(), env.env, fakeBackOfficeUser, event, ctx.from, ctx.ua)
      )
      Ok(Json.obj("done" -> true))
    }
  }

  def webAuthnAdmins() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.webAuthnAdminDataStore.findAll() map { users =>
      Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
    }
  }

  def registerWebAuthnAdmin() = ApiAction.async(parse.json) { ctx =>
    val usernameOpt        = (ctx.request.body \ "username").asOpt[String]
    val passwordOpt        = (ctx.request.body \ "password").asOpt[String]
    val labelOpt           = (ctx.request.body \ "label").asOpt[String]
    val authorizedGroupOpt = (ctx.request.body \ "authorizedGroup").asOpt[String]
    val credentialOpt      = (ctx.request.body \ "credential").asOpt[JsValue]
    val handleOpt          = (ctx.request.body \ "handle").asOpt[String]
    (usernameOpt, passwordOpt, labelOpt, authorizedGroupOpt, handleOpt) match {
      case (Some(username), Some(password), Some(label), _, Some(handle)) => {
        val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        env.datastores.webAuthnAdminDataStore
          .registerUser(username,
                        saltedPassword,
                        label,
                        authorizedGroupOpt,
                        credentialOpt.getOrElse(Json.obj()),
                        handle)
          .map { _ =>
            Ok(Json.obj("username" -> username))
          }
      }
      case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
    }
  }

  def webAuthnDeleteAdmin(username: String, id: String) = ApiAction.async { ctx =>
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
          WebAuthnAdminDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                    env.env,
                                    fakeBackOfficeUser,
                                    event,
                                    ctx.from,
                                    ctx.ua)
        )
      Ok(Json.obj("done" -> true))
    }
  }
}
