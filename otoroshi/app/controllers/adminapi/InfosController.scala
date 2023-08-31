package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.jobs.AnonymousReportingJob
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}

class InfosApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-infos-api")

  def infos() = ApiAction.async { ctx =>
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

  def version() = ApiAction { ctx =>
    Ok(env.otoroshiVersionSem.json)
  }
}
