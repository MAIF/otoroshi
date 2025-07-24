package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.jobs.AnonymousReportingJob
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.ExecutionContext
import play.api.mvc
import play.api.mvc.AnyContent

class InfosApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  val logger: Logger = Logger("otoroshi-infos-api")

  def infos(): mvc.Action[AnyContent] = ApiAction.async { ctx =>
    val full = ctx.request.getQueryString("full").contains("true")
    if (full) {
      AnonymousReportingJob.buildReport(env.datastores.globalConfigDataStore.latest()).map(report => Ok(report))
    } else {
      Ok(
        Json.obj(
          "otoroshi_cluster_id"  -> env.datastores.globalConfigDataStore.latest().otoroshiId,
          "otoroshi_version"     -> env.otoroshiVersion,
          "otoroshi_version_sem" -> env.otoroshiVersionSem.json,
          "java_version"         -> env.theJavaVersion.json,
          "os"                   -> env.os.json,
          "datastore"            -> env.datastoreKind,
          "env"                  -> env.env
        )
      ).future
    }
  }

  def version(): mvc.Action[AnyContent] = ApiAction { ctx =>
    Ok(env.otoroshiVersionSem.json)
  }
}
