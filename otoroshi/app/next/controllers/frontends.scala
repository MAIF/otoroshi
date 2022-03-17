package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class NgFrontendsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-frontends-api")

  def form() = ApiAction {
    env.openApiSchema.asForms.get("otoroshi.next.models.NgFrontend") match {
      case Some(value)  => Ok(Json.obj(
        "schema" -> value.schema.deepMerge(Json.obj(
          "methods" -> Json.obj(
            "isMulti" -> true,
            "array"   -> false,
            "format"  -> "select",
            "options" ->  Json.arr(
              "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH"
            )
          )
        )),
        "flow" -> value.flow
      ))
      case _            => NotFound(Json.obj("error" -> "Schema and flow not found"))
    }
  }
}
