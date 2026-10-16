package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Sink, Source}
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit, OtoroshiExportAlert}
import otoroshi.models.RightsChecker
import otoroshi.next.extensions.FooAdminExtension
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsNumber, JsObject, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents, Result}

import java.nio.file.{Files, Path}
import scala.concurrent.Future
import scala.util.Try

class ImportExportController(ApiAction: ApiAction, cc: ControllerComponents)(using env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-import-export-api")

  // the lines of an ndjson file, with their number, the blank ones left out
  private def ndjsonLines(file: Path): Source[(ByteString, Long), ?] =
    FileIO
      .fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, allowTruncation = true))
      .zipWithIndex
      .filterNot(_._1.utf8String.trim.isEmpty)

  // a line of a full ndjson export: its key, its type, its value, and when it expires
  private def isExportLine(line: ByteString): Boolean = Try(Json.parse(line.utf8String)).toOption.exists { json =>
    (json \ "k").asOpt[String].isDefined && (json \ "w").asOpt[String].isDefined && (json \ "v").isDefined &&
    (json \ "t").toOption.forall(_.isInstanceOf[JsNumber])
  }

  // the import empties the datastore before it writes the lines: the whole file is read first, a line that is not a
  // line of an export refuses the import and leaves the datastore as it is
  private def importNdJsonFile(file: Path): Future[Result] =
    ndjsonLines(file)
      .collect { case (line, index) if !isExportLine(line) => index + 1 }
      .runWith(Sink.headOption)
      .flatMap {
        case Some(lineNumber) =>
          Future.successful(BadRequest(Json.obj("error" -> s"line $lineNumber is not a line of an otoroshi export")))
        case None             =>
          env.datastores
            .fullNdJsonImport(ndjsonLines(file).map { case (line, _) => Json.parse(line.utf8String) })
            .map(_ => Ok(Json.obj("done" -> true)))
            .recover { case e =>
              InternalServerError(Json.obj("error" -> e.getMessage))
            }
      }

  lazy val sourceBodyParser = BodyParser("Import/Export BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def fullExport() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        !ctx.request.accepts("application/json") && ctx.request.accepts("application/x-ndjson") match {
          case true  => {
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
    }

  def fullImportFromFile() =
    ApiAction.async(parse.temporaryFile) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        ctx.request.headers.get("X-Content-Type") match {
          case Some("application/x-ndjson") => importNdJsonFile(ctx.request.body.path)
          case _                            => {
            val source = scala.io.Source.fromFile(ctx.request.body.path.toFile, "utf-8").getLines().mkString("\n")
            // a body that is not a json object is refused before anything is imported
            Try(Json.parse(source)).toOption.collect { case obj: JsObject => obj } match {
              case None       => Future.successful(BadRequest(Json.obj("error" -> "the body is not a json object")))
              case Some(json) =>
                env.datastores.globalConfigDataStore
                  .fullImport(json)
                  .map(_ => Ok(Json.obj("done" -> true)))
                  .recover { case e =>
                    InternalServerError(Json.obj("error" -> e.getMessage))
                  }
            }
          }
        }
      }
    }

  def fullImport() =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        ctx.request.contentType match {
          case Some("application/x-ndjson") => {
            // the body is written to a file to be read twice: checked, then imported
            val file = Files.createTempFile("otoroshi-import-", ".ndjson")
            ctx.request.body
              .runWith(FileIO.toPath(file))
              .flatMap(_ => importNdJsonFile(file))
              .andThen { case _ => Files.deleteIfExists(file) }
          }
          case _                            => {
            ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
              // a body that is not a json object is refused before anything is imported
              Try(Json.parse(body.utf8String)).toOption.collect { case obj: JsObject => obj } match {
                case None       => Future.successful(BadRequest(Json.obj("error" -> "the body is not a json object")))
                case Some(json) =>
                  env.datastores.globalConfigDataStore
                    .fullImport(json)
                    .map(_ => Ok(Json.obj("done" -> true)))
              }
            } recover { case e =>
              InternalServerError(Json.obj("error" -> e.getMessage))
            }
          }
        }
      }
    }
}
