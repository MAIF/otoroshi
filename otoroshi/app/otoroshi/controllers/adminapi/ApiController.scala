package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import otoroshi.actions.ApiAction
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.ExecutionContext
import org.apache.pekko.stream.scaladsl.Source

class ApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-admin-api")

  val sourceBodyParser: BodyParser[Source[ByteString, _]] = BodyParser("ApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

}
