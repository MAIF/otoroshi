package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import otoroshi.actions.ApiAction
import org.apache.pekko.stream.scaladsl.{FileIO, Framing}
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit, OtoroshiExportAlert}
import otoroshi.models.RightsChecker
import otoroshi.next.extensions.FooAdminExtension
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}

import scala.concurrent.ExecutionContext
import org.apache.pekko.stream.scaladsl.Source
import play.api.mvc
import play.api.libs.Files
import play.api.mvc.AnyContent

class ImportExportController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-import-export-api")

  lazy val sourceBodyParser: BodyParser[Source[ByteString, _]] = BodyParser("Import/Export BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def fullExport(): mvc.Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        if (!ctx.request.accepts("application/json") && ctx.request.accepts("application/x-ndjson")) {
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
              OtoroshiExportAlert(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(Json.obj()),
                event,
                Json.obj(),
                ctx.from,
                ctx.ua
              )
            )
            Ok.sendEntity(
              HttpEntity.Streamed
                  .apply(source.map(v => ByteString(Json.stringify(v) + "\n")), None, Some("application/x-ndjson"))
            ).as("application/x-ndjson")
          }
        } else {
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
              OtoroshiExportAlert(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(Json.obj()),
                event,
                e,
                ctx.from,
                ctx.ua
              )
            )
            Ok(Json.prettyPrint(e)).as("application/json")
          }
        }
      }
    }

  def fullImportFromFile(): mvc.Action[Files.TemporaryFile] =
    ApiAction.async(parse.temporaryFile) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        ctx.request.headers.get("X-Content-Type") match {
          case Some("application/x-ndjson") =>
              val body   = FileIO.fromPath(ctx.request.body.path)
              val source = body
                .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
                .map(v => Json.parse(v.utf8String))
              env.datastores
                .fullNdJsonImport(source)
                .map(_ => Ok(Json.obj("done" -> true))) recover { case e =>
                InternalServerError(Json.obj("error" -> e.getMessage))
              }
          case _                            =>
              val source = scala.io.Source.fromFile(ctx.request.body.path.toFile, "utf-8").getLines().mkString("\n")
              val json   = Json.parse(source).as[JsObject]
              env.datastores.globalConfigDataStore
                .fullImport(json)
                .map(_ => Ok(Json.obj("done" -> true)))
                .recover { case e =>
                  InternalServerError(Json.obj("error" -> e.getMessage))
                }
        }
      }
    }

  def fullImport(): mvc.Action[Source[ByteString, _]] =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        ctx.request.contentType match {
          case Some("application/x-ndjson") =>
              val source = ctx.request.body
                .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
                .map(v => Json.parse(v.utf8String))
              env.datastores
                .fullNdJsonImport(source)
                .map(_ => Ok(Json.obj("done" -> true))) recover { case e =>
                InternalServerError(Json.obj("error" -> e.getMessage))
              }
          case _                            =>
              ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
                val json = Json.parse(body.utf8String).as[JsObject]
                env.datastores.globalConfigDataStore
                  .fullImport(json)
                  .map(_ => Ok(Json.obj("done" -> true)))
              } recover { case e =>
                InternalServerError(Json.obj("error" -> e.getMessage))
              }
        }
      }
    }
}
