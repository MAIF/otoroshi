package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import env.Env
import events._
import models.{BackOfficeUser, PrivateAppsUser}
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc._
import security.IdGenerator
import utils.{AdminApiHelper, JsonApiError, SendAuditAndAlert}

class UsersController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) with AdminApiHelper {

  implicit lazy val ec = env.otoroshiExecutionContext

  private val fakeBackOfficeUser = BackOfficeUser(
    randomId = IdGenerator.token,
    name = "fake user",
    email = "fake.user@otoroshi.io",
    profile = Json.obj(),
    simpleLogin = false,
    authConfigId = "none",
    metadata = Map.empty,
    teams = Seq(TeamAccess("*", true, true)),
    tenants = Seq(TenantAccess("*", true, true))
  )

  def sessions() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val options = SendAuditAndAlert("ACCESS_ADMIN_SESSIONS", s"User accessed admin session", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: BackOfficeUser) => e.toJson, options) {
        env.datastores.backOfficeUserDataStore.findAll().fright[JsonApiError]
      }
    }
    // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    // val paginationPageSize: Int =
    //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    // val paginationPosition = (paginationPage - 1) * paginationPageSize
    // env.datastores.backOfficeUserDataStore.sessions() map { sessions =>
    //   Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize)))
    // }
  }

  def discardSession(id: String) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
  }

  def discardAllSessions() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
  }

  def privateAppsSessions() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val options = SendAuditAndAlert("ACCESS_PRIVATE_APPS_SESSIONS", s"User accessed private apps session", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: PrivateAppsUser) => e.toJson, options) {
        env.datastores.privateAppsUserDataStore.findAll().fright[JsonApiError]
      }
      // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      // val paginationPageSize: Int =
      //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      // val paginationPosition = (paginationPage - 1) * paginationPageSize
      // env.datastores.privateAppsUserDataStore.findAll() map { sessions =>
      //   Ok(JsArray(sessions.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
      // }
    }
  }

  def discardPrivateAppsSession(id: String) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
  }

  def discardAllPrivateAppsSessions() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
  }

  def registerSimpleAdmin = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val usernameOpt = (ctx.request.body \ "username").asOpt[String]
      val passwordOpt = (ctx.request.body \ "password").asOpt[String]
      val labelOpt = (ctx.request.body \ "label").asOpt[String]
      (usernameOpt, passwordOpt, labelOpt) match {
        case (Some(username), Some(password), Some(label)) => {
          val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
          env.datastores.simpleAdminDataStore.registerUser(SimpleOtoroshiAdmin(
            username = username,
            password = saltedPassword,
            label = label,
            createdAt = (ctx.request.body \ "teams").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
            typ = OtoroshiAdminType.SimpleAdmin,
            metadata = (ctx.request.body \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            teams = (ctx.request.body \ "teams").asOpt[JsArray].map(a => a.value.map(v => TeamAccess(v.as[String]))).getOrElse(Seq.empty),
            tenants = (ctx.request.body \ "tenants").asOpt[JsArray].map(a => a.value.map(v => TenantAccess(v.as[String]))).getOrElse(Seq.empty)
          )).map { _ =>
            Ok(Json.obj("username" -> username))
          }
        }
        case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
      }
    }
  }

  def simpleAdmins = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val options = SendAuditAndAlert("ACCESS_SIMPLE_ADMINS", s"User accessed simple admins", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: JsValue) => e, options) {
        env.datastores.simpleAdminDataStore.findAll().map(_.map(_.json)).fright[JsonApiError]
      }
      // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      // val paginationPageSize: Int =
      //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      // val paginationPosition = (paginationPage - 1) * paginationPageSize
      // env.datastores.simpleAdminDataStore.findAll() map { users =>
      //   Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
      // }
    }
  }

  def deleteAdmin(username: String) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
  }

  def webAuthnAdmins() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val options = SendAuditAndAlert("ACCESS_WEBAUTHN_ADMINS", s"User accessed webauthn admins", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: JsValue) => e, options) {
        env.datastores.webAuthnAdminDataStore.findAll().map(_.map(_.json)).fright[JsonApiError]
      }
      // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      // val paginationPageSize: Int =
      //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      // val paginationPosition = (paginationPage - 1) * paginationPageSize
      // env.datastores.webAuthnAdminDataStore.findAll() map { users =>
      //   Ok(JsArray(users.drop(paginationPosition).take(paginationPageSize)))
      // }
    }
  }

  def registerWebAuthnAdmin() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
      val usernameOpt = (ctx.request.body \ "username").asOpt[String]
      val passwordOpt = (ctx.request.body \ "password").asOpt[String]
      val labelOpt = (ctx.request.body \ "label").asOpt[String]
      val credentialOpt = (ctx.request.body \ "credential").asOpt[JsValue]
      val handleOpt = (ctx.request.body \ "handle").asOpt[String]
      (usernameOpt, passwordOpt, labelOpt, handleOpt) match {
        case (Some(username), Some(password), Some(label), Some(handle)) => {
          val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
          env.datastores.webAuthnAdminDataStore
            .registerUser(WebAuthnOtoroshiAdmin(
              username = username,
              password = saltedPassword,
              label = label,
              handle = handle,
              credentials = credentialOpt.map(v => Map((v \ "keyId" \ "id").as[String] -> v)).getOrElse(Map.empty),
              createdAt = (ctx.request.body \ "teams").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
              typ = OtoroshiAdminType.WebAuthnAdmin,
              metadata = (ctx.request.body \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
              teams = (ctx.request.body \ "teams").asOpt[JsArray].map(a => a.value.map(v => TeamAccess(v.as[String]))).getOrElse(Seq.empty),
              tenants = (ctx.request.body \ "tenants").asOpt[JsArray].map(a => a.value.map(v => TenantAccess(v.as[String]))).getOrElse(Seq.empty)
            ))
            .map { _ =>
              Ok(Json.obj("username" -> username))
            }
        }
        case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
      }
    }
  }

  def webAuthnDeleteAdmin(username: String, id: String) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.TenantAdmin) {
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
}
