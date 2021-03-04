package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit, GlobalConfigModification}
import models.GlobalConfig
import otoroshi.models.RightsChecker
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}
import otoroshi.utils.json.JsonPatchHelpers.patchJson

class GlobalConfigController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-global-config-api")

  def globalConfig() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.globalConfigDataStore.findById("global").map {
        case None => NotFound(Json.obj("error" -> "GlobalConfig not found"))
        case Some(ak) => {
          Audit.send(
            AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "ACCESS_GLOBAL_CONFIG",
              s"User accessed global Otoroshi config",
              ctx.from,
              ctx.ua
            )
          )
          Ok(ak.toJson)
        }
      }
    }
  }

  def updateGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      val user = ctx.user.getOrElse(ctx.apiKey.toJson)
      GlobalConfig.fromJsonSafe(ctx.request.body) match {
        case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
        case JsSuccess(ak, _) => {
          env.datastores.globalConfigDataStore.findById("global").map(_.get).flatMap { conf =>
            val admEvt = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_GLOBAL_CONFIG",
              s"User updated global Otoroshi config",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(admEvt)
            Alerts.send(
              GlobalConfigModification(env.snowflakeGenerator.nextIdStr(),
                env.env,
                user,
                conf.toJson,
                ak.toJson,
                admEvt,
                ctx.from,
                ctx.ua)
            )
            ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
          }
        }
      }
    }
  }

  def patchGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      val user = ctx.user.getOrElse(ctx.apiKey.toJson)
      env.datastores.globalConfigDataStore.findById("global").map(_.get).flatMap { conf =>
        val currentConfigJson = conf.toJson
        val newConfigJson = patchJson(ctx.request.body, currentConfigJson)
        GlobalConfig.fromJsonSafe(newConfigJson) match {
          case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
          case JsSuccess(ak, _) => {
            val admEvt = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_GLOBAL_CONFIG",
              s"User updated global Otoroshi config",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(admEvt)
            Alerts.send(
              GlobalConfigModification(env.snowflakeGenerator.nextIdStr(),
                env.env,
                user,
                conf.toJson,
                ak.toJson,
                admEvt,
                ctx.from,
                ctx.ua)
            )
            ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
          }
        }
      }
    }
  }
}