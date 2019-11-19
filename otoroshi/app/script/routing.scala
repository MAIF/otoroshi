package otoroshi.script

import akka.http.scaladsl.util.FastFuture
import env.Env
import models.ServiceDescriptor
import play.api.libs.json._
import play.api.mvc.RequestHeader
import utils.TypedMap

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class PreRoutingRef(enabled: Boolean = false,
                        excludedPatterns: Seq[String] = Seq.empty[String],
                        refs: Seq[String] = Seq.empty,
                        config: JsValue = Json.obj()) {
  def json: JsValue = PreRoutingRef.format.writes(this)
}

object PreRoutingRef {
  val format = new Format[PreRoutingRef] {
    override def writes(o: PreRoutingRef): JsValue = Json.obj(
      "enabled"          -> o.enabled,
      "refs"             -> JsArray(o.refs.map(JsString.apply)),
      "config"           -> o.config,
      "excludedPatterns" -> JsArray(o.excludedPatterns.map(JsString.apply)),
    )
    override def reads(json: JsValue): JsResult[PreRoutingRef] =
      Try {
        JsSuccess(
          PreRoutingRef(
            refs = (json \ "refs")
              .asOpt[Seq[String]]
              .orElse((json \ "ref").asOpt[String].map(r => Seq(r)))
              .getOrElse(Seq.empty),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            config = (json \ "config").asOpt[JsValue].getOrElse(Json.obj()),
            excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

trait PreRouting extends StartableAndStoppable {

  val funit = FastFuture.successful(())

  def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = funit
}

case class PreRoutingContext(
  snowflake: String,
  index: Int,
  request: RequestHeader,
  descriptor: ServiceDescriptor,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue
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

object DefaultPreRouting extends PreRouting {
  override def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = funit
}

object CompilingPreRouting extends PreRouting {
  override def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = funit
}