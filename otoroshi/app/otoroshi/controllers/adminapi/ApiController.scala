package otoroshi.controllers.adminapi

import akka.stream.Materializer
import otoroshi.actions.ApiAction
import akka.util.ByteString
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.ExecutionContext

class ApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-admin-api")

  val sourceBodyParser = BodyParser("ApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

}
