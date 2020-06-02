package controllers.adminapi

import actions.ApiAction
import akka.stream.scaladsl.{FileIO, Framing}
import akka.util.ByteString
import env.Env
import events.{AdminApiEvent, Alerts, Audit, OtoroshiExportAlert}
import otoroshi.models.RightsChecker
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}

class ImportExportController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-import-export-api")

  lazy val sourceBodyParser = BodyParser("Import/Export BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def fullExport() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      ctx.request.accepts("application/x-ndjson") match {
        case true => {
          env.datastores.fullNdJsonExport(100, 1, 4).map { source =>
            val event = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "FULL_OTOROSHI_EXPORT",
              s"Admin exported Otoroshi",
              ctx.from,
              ctx.ua,
              Json.obj()
            )
            Audit.send(event)
            Alerts.send(
              OtoroshiExportAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(Json.obj()),
                event,
                Json.obj(),
                ctx.from,
                ctx.ua)
            )
            Ok.sendEntity(
              HttpEntity.Streamed
                .apply(source.map(v => ByteString(Json.stringify(v) + "\n")), None, Some("application/x-ndjson"))
            )
              .as("application/x-ndjson")
          }
        }
        case false => {
          env.datastores.globalConfigDataStore.fullExport().map { e =>
            val event = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "FULL_OTOROSHI_EXPORT",
              s"Admin exported Otoroshi",
              ctx.from,
              ctx.ua,
              e
            )
            Audit.send(event)
            Alerts.send(
              OtoroshiExportAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(Json.obj()),
                event,
                e,
                ctx.from,
                ctx.ua)
            )
            Ok(Json.prettyPrint(e)).as("application/json")
          }
        }
      }
    }
  }

  def fullImportFromFile() = ApiAction.async(parse.temporaryFile) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      ctx.request.headers.get("X-Content-Type") match {
        case Some("application/x-ndjson") => {
          val body = FileIO.fromPath(ctx.request.body.path)
          val source = body
            .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
            .map(v => Json.parse(v.utf8String))
          env.datastores
            .fullNdJsonImport(source)
            .map(_ => Ok(Json.obj("done" -> true))) recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
        }
        case _ => {
          val source = scala.io.Source.fromFile(ctx.request.body.path.toFile, "utf-8").getLines().mkString("\n")
          val json = Json.parse(source).as[JsObject]
          env.datastores.globalConfigDataStore
            .fullImport(json)
            .map(_ => Ok(Json.obj("done" -> true)))
            .recover {
              case e => InternalServerError(Json.obj("error" -> e.getMessage))
            }
        }
      }
    }
  }

  def fullImport() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      ctx.request.contentType match {
        case Some("application/x-ndjson") => {
          val source = ctx.request.body
            .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
            .map(v => Json.parse(v.utf8String))
          env.datastores
            .fullNdJsonImport(source)
            .map(_ => Ok(Json.obj("done" -> true))) recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
        }
        case _ => {
          ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
            val json = Json.parse(body.utf8String).as[JsObject]
            env.datastores.globalConfigDataStore
              .fullImport(json)
              .map(_ => Ok(Json.obj("done" -> true)))
          } recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
        }
      }
    }
  }
}