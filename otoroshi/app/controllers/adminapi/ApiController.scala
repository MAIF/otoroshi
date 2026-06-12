package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.mvc._

class ApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-admin-api")

  val sourceBodyParser = BodyParser("ApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

}
