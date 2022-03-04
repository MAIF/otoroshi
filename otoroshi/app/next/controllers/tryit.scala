package otoroshi.next.controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.actions.{ApiAction, BackOfficeActionAuth}
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.{NgNamedPlugin, NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsNull, JsString, JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class TryItController(
  BackOfficeActionAuth: BackOfficeActionAuth,
  cc: ControllerComponents
)(implicit
  env: Env
) extends AbstractController(cc) {

  implicit val ec = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("TryItController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def call() = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val requestId = UUID.randomUUID().toString
      env.proxyState.enableReportFor(requestId)
      val jsonBody = bodyRaw.utf8String.parseJson
      val method = jsonBody.select("method").asOpt[String].map(_.toUpperCase()).getOrElse("GET")
      val path = jsonBody.select("path").asOpt[String].getOrElse("/")
      val _headers = jsonBody.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      // TODO: support cookies here !
      val bodyBase64 = jsonBody.select("base_64").asOpt[Boolean].getOrElse(false)
      val body: Option[ByteString] = jsonBody.select("body").asOpt[String].filter(_.nonEmpty).map { rb =>
        if (bodyBase64) {
          rb.byteString.decodeBase64
        } else {
          rb.byteString
        }
      }

      val routeFromId = jsonBody.select("route_id").asOpt[String].flatMap(id => env.proxyState.route(id)).flatMap(_.frontend.domains.map(_.domain).headOption)
      val routeFromJson = jsonBody.select("route").asOpt[String].flatMap(json => NgRoute.fmt.reads(json.parseJson).asOpt).flatMap(_.frontend.domains.map(_.domain).headOption)

      routeFromId.orElse(routeFromJson) match {
        case None => InternalServerError(Json.obj("error" -> "route not found !")).vfuture
        case Some(hostname) => {
          val headers = _headers ++ Map(
            "Otoroshi-Try-It-Request-Id" -> requestId,
            "Host" -> hostname
          )
          // TODO: support TLS here
          // TODO: support mTLS here
          val wsRequest = env.Ws.url(s"http://127.0.0.1:${env.port}${path}")
            .withMethod(method)
            .addHttpHeaders(headers.toSeq: _*)
            // TODO: support cookies here !
            .withRequestTimeout(1.minute)
          val respF = body match {
            case None => wsRequest.execute()
            case Some(content) => wsRequest.withBody(Source(content.grouped(32 * 1024).toList)).execute()
          }
          respF.flatMap { resp =>
            val report: JsValue = env.proxyState.report(requestId).map(_.json).getOrElse(JsNull)
            val status = resp.status
            val headers = resp.headers.mapValues(_.last)
            resp.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map { respBodyRaw =>
              // TODO: support cookies here !
              Ok(Json.obj(
                "status" -> status,
                "headers" -> headers,
                "body_base_64" -> respBodyRaw.encodeBase64.utf8String,
                "report" -> report
              ))
            }
          }
        }
      }
    }
  }
}