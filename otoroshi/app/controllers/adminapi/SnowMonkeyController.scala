package controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.events._
import models.SnowMonkeyConfig
import otoroshi.models.RightsChecker
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsError, JsSuccess, Json}
import play.api.mvc.{AbstractController, ControllerComponents}
import otoroshi.utils.json.JsonPatchHelpers.patchJson

class SnowMonkeyController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-snow-monkey-api")


  def startSnowMonkey() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.chaosDataStore.startSnowMonkey().map { _ =>
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "STARTED_SNOWMONKEY",
          s"User started snowmonkey",
          ctx.from,
          ctx.ua,
          Json.obj()
        )
        Audit.send(event)
        Alerts.send(
          SnowMonkeyStartedAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        Ok(Json.obj("done" -> true))
      }
    }
  }

  def stopSnowMonkey() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.chaosDataStore.stopSnowMonkey().map { _ =>
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "STOPPED_SNOWMONKEY",
          s"User stopped snowmonkey",
          ctx.from,
          ctx.ua,
          Json.obj()
        )
        Audit.send(event)
        Alerts.send(
          SnowMonkeyStoppedAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        Ok(Json.obj("done" -> true))
      }
    }
  }

  def getSnowMonkeyOutages() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.chaosDataStore.getOutages().map { outages =>
        Ok(JsArray(outages.map(_.asJson)))
      }
    }
  }

  def getSnowMonkeyConfig() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.globalConfigDataStore.singleton().map { c =>
        Ok(c.snowMonkeyConfig.asJson)
      }
    }
  }

  def updateSnowMonkey() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      SnowMonkeyConfig.fromJsonSafe(ctx.request.body) match {
        case JsError(e) => BadRequest(Json.obj("error" -> "Bad SnowMonkeyConfig format")).future
        case JsSuccess(config, _) => {
          config.save().map { _ =>
            val event = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATED_SNOWMONKEY_CONFIG",
              s"User updated snowmonkey config",
              ctx.from,
              ctx.ua,
              config.asJson
            )
            Audit.send(event)
            Alerts.send(
              SnowMonkeyConfigUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            Ok(config.asJson)
          }
        }
      }
    }
  }

  def patchSnowMonkey() = ApiAction.async(parse.json) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.globalConfigDataStore.findById("global").map(_.get).flatMap { globalConfig =>
        val currentSnowMonkeyConfigJson = globalConfig.snowMonkeyConfig.asJson
        val newSnowMonkeyConfigJson = patchJson(ctx.request.body, currentSnowMonkeyConfigJson)
        SnowMonkeyConfig.fromJsonSafe(newSnowMonkeyConfigJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad SnowMonkeyConfig format")).future
          case JsSuccess(newSnowMonkeyConfig, _) => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "PATCH_SNOWMONKEY_CONFIG",
              s"User patched snowmonkey config",
              ctx.from,
              ctx.ua,
              newSnowMonkeyConfigJson
            )
            Audit.send(event)
            Alerts.send(
              SnowMonkeyConfigUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            newSnowMonkeyConfig.save().map(_ => Ok(newSnowMonkeyConfig.asJson))
          }
        }
      }
    }
  }

  def resetSnowMonkey() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      env.datastores.chaosDataStore.resetOutages().map { _ =>
        val event: AdminApiEvent = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "RESET_SNOWMONKEY_OUTAGES",
          s"User reset snowmonkey outages for the day",
          ctx.from,
          ctx.ua,
          Json.obj()
        )
        Audit.send(event)
        Alerts.send(
          SnowMonkeyResetAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        Ok(Json.obj("done" -> true))
      }
    }
  }
}
