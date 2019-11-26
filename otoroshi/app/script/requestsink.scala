package otoroshi.script

import akka.http.scaladsl.util.FastFuture
import env.Env
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import utils.TypedMap

import scala.concurrent.{ExecutionContext, Future}

trait RequestSink extends StartableAndStoppable with NamedPlugin {
  def matches(context: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = false
  def handle(context: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = FastFuture.successful(Results.NotImplemented(Json.obj("error" -> "not implemented yet")))
}

case class RequestSinkContext(
  snowflake: String,
  index: Int,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
) {
  def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray  => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _              => None
    }
  }
  def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray  => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _              => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

object DefaultRequestSink extends RequestSink

object CompilingRequestSink extends RequestSink