package otoroshi.next.proxy

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.ServiceLocation
import otoroshi.script.RequestHandler
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Request, Result}
import cats.data._

import scala.concurrent.{ExecutionContext, Future}

class ProxyEngine() extends RequestHandler {

  override def name: String = "Otoroshi newest proxy engine"

  override def description: Option[String] = "This plugin holds the next generation otoroshi proxy engine implementation".some

  override def configRoot: Option[String] = "NextGenProxyEngine".some

  override def defaultConfig: Option[JsObject] = {
    Json.obj(
      configRoot.get -> Json.obj(
        "enabled" -> true,
        "domains" -> Json.arr()
      )
    ).some
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    config.select("domains").asOpt[Seq[String]].getOrElse(Seq.empty)
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    // import cats.implicits._
    // (for {
    //   // TODO: handle concurrent requests counter and alerting
    //   requestLocation <- extractRequestLocation(request)
    //   route           <- findRoute(request, requestLocation)
    // } yield {
    //   ???
    // }).value.map {
    //   case _ => ???
    // }
    ???
  }

  def extractRequestLocation(value: Request[Source[ByteString, _]])(implicit ec: ExecutionContext, env: Env): EitherT[Future, ProxyEngineError, ServiceLocation] = {
    ???
  }

  def findRoute(value: Request[Source[ByteString, _]], location: ServiceLocation)(implicit ec: ExecutionContext, env: Env): EitherT[Future, ProxyEngineError, ServiceLocation] = {
    ???
  }
}

sealed trait ProxyEngineError
