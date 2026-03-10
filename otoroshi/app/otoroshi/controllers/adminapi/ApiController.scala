package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.actions.ApiAction
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.mvc.*

import scala.concurrent.ExecutionContext

class ApiController(ApiAction: ApiAction, cc: ControllerComponents)(using env: Env) extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-admin-api")

  val sourceBodyParser: BodyParser[Source[ByteString, ?]] = BodyParser("ApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

}
