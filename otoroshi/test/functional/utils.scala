package functional

import java.net.ServerSocket
import java.util.Optional

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.util.ByteString
import modules.OtoroshiComponentsInstances
import org.scalatest.TestSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.components.OneServerPerSuiteWithComponents
import org.slf4j.LoggerFactory
import play.api.ApplicationLoader.Context
import play.api.libs.json.{JsValue, Json}
import play.api.{BuiltInComponents, Configuration}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Random, Try}

trait AddConfiguration {
  def getConfiguration(configuration: Configuration): Configuration
}

class OtoroshiTestComponentsInstances(context: Context, conf: Configuration => Configuration)
  extends OtoroshiComponentsInstances(context) {
  override def configuration = conf(super.configuration)
}

trait OneServerPerSuiteWithMyComponents
  extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  def otoroshiComponents = new OtoroshiTestComponentsInstances(context, getConfiguration)
  override def components: BuiltInComponents = otoroshiComponents
}

object Implicits {
  implicit class BetterFuture[A](val fu: Future[A]) extends AnyVal {
    def await(): A = {
      Await.result(fu, 60.seconds)
    }
  }
  implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
    def asOption: Option[A] = {
      if (opt.isPresent) {
        Some(opt.get())
      } else {
        None
      }
    }
  }
}

object HttpResponses {

  def NotFound(path: String) = HttpResponse(
    404,
    entity =
      HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> s"$path not found")))
  )

  def GatewayTimeout() = HttpResponse(
    504,
    entity = HttpEntity(ContentTypes.`application/json`,
      Json.stringify(Json.obj("error-test" -> s"Target servers timeout")))
  )

  def BadGateway(message: String) = HttpResponse(
    502,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def BadRequest(message: String) = HttpResponse(
    400,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def Unauthorized(message: String) = HttpResponse(
    401,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def Ok(json: JsValue) = HttpResponse(
    200,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(json))
  )
}

class TargetService(host: Option[String], path: String, contentType: String, result: HttpRequest => String) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (HttpMethods.GET, p) if p.toString() == path && host.isEmpty => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request)))
          )
        )
      }
      case (HttpMethods.GET, p) if p.toString() == path && TargetService.extractHost(request) == host.get => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request)))
          )
        )
      }
      case (_, p) => {
        FastFuture.successful(HttpResponses.NotFound(p.toString()))
      }
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): TargetService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

object TargetService {

  import Implicits._

  def apply(host: Option[String], path: String, contentType: String, result: HttpRequest => String): TargetService = {
    new TargetService(host, path, contentType, result)
  }

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port         = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  private val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  def extractHost(request: HttpRequest): String =
    request.getHeader("Otoroshi-Proxied-Host").asOption.map(_.value()).getOrElse("--")
}