package otoroshi.next.controllers

import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.actions.BackOfficeActionAuth
import otoroshi.env.Env
import otoroshi.events.{KafkaConfig, KafkaSettings}
import otoroshi.next.models.{NgRoute, NgRouteComposition, NgTarget, NgTlsConfig}
import otoroshi.next.plugins.ForceHttpsTraffic
import otoroshi.next.utils.JsonHelpers
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}

import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try

class TryItController(
    BackOfficeActionAuth: BackOfficeActionAuth,
    cc: ControllerComponents
)(implicit
    env: Env
) extends AbstractController(cc) {

  implicit val ec  = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("TryItController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def dataExporterCall() = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val jsonBody = bodyRaw.utf8String.parseJson
      jsonBody \ "config" match {
        case JsDefined(config) =>
          KafkaConfig.format.reads(config) match {
            case JsSuccess(kafka, _) =>
              Try {
                val seconds = (jsonBody \ "timeout").asOpt[Int].getOrElse(15)

                val race =
                  akka.pattern.after(new DurationInt(seconds).seconds, using = env.otoroshiActorSystem.scheduler)(
                    Future.successful(BadRequest(Json.obj("error" -> "Failed to connect to kafka")))
                  )
                Future.firstCompletedOf(
                  Seq(
                    Future {
                      Try {
                        val consumerSettings = KafkaSettings.consumerTesterSettings(env, kafka)
                        val consumer         = ConsumerSettings
                          .createKafkaConsumer(consumerSettings)
                        consumer.listTopics(java.time.Duration.of(seconds, ChronoUnit.SECONDS))
                        consumer.close()
                        Ok(Json.obj("status" -> "success"))
                      } recover { case e: Throwable =>
                        throw e
                        BadRequest(Json.obj("error" -> e.getMessage))
                      } get
                    },
                    race
                  )
                )
              } recover { case e: Throwable =>
                throw e
                BadRequest(Json.obj("error" -> e.getMessage)).future
              } get
            case JsError(errors)     => BadRequest(Json.obj("error" -> errors.toString, "message" -> "Bad config")).future
          }
        case _: JsUndefined    =>
          BadRequest(Json.obj("error" -> "missing config")).future
      }
    }
  }

  def call(entity: Option[String] = None) = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val requestId                = UUID.randomUUID().toString
      env.proxyState.enableReportFor(requestId)
      val jsonBody                 = bodyRaw.utf8String.parseJson
      val method                   = jsonBody.select("method").asOpt[String].map(_.toUpperCase()).getOrElse("GET")
      val path                     = jsonBody.select("path").asOpt[String].getOrElse("/")
      val _headers                 = jsonBody.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val cookies                  =
        jsonBody.select("cookies").asOpt[Seq[JsObject]].map(_.map(JsonHelpers.cookieFromJson)).getOrElse(Seq.empty)
      val clientCert               = jsonBody.select("client_cert").asOpt[String]
      val bodyBase64               = jsonBody.select("base_64").asOpt[Boolean].getOrElse(false)
      val body: Option[ByteString] = jsonBody.select("body").asOpt[String].filter(_.nonEmpty).map { rb =>
        if (bodyBase64) {
          rb.byteString.decodeBase64
        } else {
          rb.byteString
        }
      }

      val isServiceTryIt = entity.contains("service")

      val routeFromIdOpt: Option[Either[NgRouteComposition, NgRoute]]   = jsonBody
        .select("route_id")
        .asOpt[String]
        .flatMap(id =>
          if (isServiceTryIt) {
            env.proxyState.allNgServices().find(_.id == id).map(e => Left(e))
          } else {
            env.proxyState.route(id).map(e => Right(e))
          }
        )
      val routeFromJsonOpt: Option[Either[NgRouteComposition, NgRoute]] =
        jsonBody
          .select("route")
          .asOpt[String]
          .flatMap(json => {
            if (isServiceTryIt)
              NgRouteComposition.fmt.reads(json.parseJson).asOpt.map(e => Left(e))
            else
              NgRoute.fmt.reads(json.parseJson).asOpt.map(e => Right(e))
          })
      val maybeRoute                                                    = routeFromIdOpt.orElse(routeFromJsonOpt)
      val routeFromId: Option[String]                                   = routeFromIdOpt.flatMap {
        case Left(ngService: NgRouteComposition) =>
          jsonBody.select("frontend_idx").asOpt[Int] match {
            case Some(idx) => ngService.routes.lift(idx).flatMap(_.frontend.domains.map(_.domain).headOption)
            case _         => ngService.routes.headOption.flatMap(_.frontend.domains.map(_.domain).headOption)
          }
        case Right(ngRoute: NgRoute)             => ngRoute.frontend.domains.map(_.domain).headOption
      }
      val routeFromJson: Option[String]                                 = routeFromJsonOpt.flatMap {
        case Left(ngService: NgRouteComposition) =>
          ngService.routes.headOption.flatMap(_.frontend.domains.map(_.domain).headOption)
        case Right(ngRoute: NgRoute)             => ngRoute.frontend.domains.map(_.domain).headOption
      }

      routeFromId.orElse(routeFromJson) match {
        case None           => InternalServerError(Json.obj("error" -> "route not found !")).vfuture
        case Some(hostname) => {
          val headers   = _headers ++ Map(
            "Otoroshi-Try-It-Request-Id" -> requestId,
            "Host"                       -> hostname
          )
          val isHttps   = maybeRoute.exists {
            case Left(ngService: NgRouteComposition) => ngService.plugins.hasPlugin[ForceHttpsTraffic]
            case Right(ngRoute: NgRoute)             => ngRoute.plugins.hasPlugin[ForceHttpsTraffic]
          }
          val url       =
            if (isHttps) s"https://${hostname}:${env.httpsPort}${path}" else s"http://${hostname}:${env.port}${path}"
          val target    = NgTarget(
            id = s"tryit-${requestId}",
            hostname = hostname,
            port = if (isHttps) env.httpsPort else env.port,
            tls = isHttps,
            ipAddress = "127.0.0.1".some,
            tlsConfig = NgTlsConfig(
              certs = clientCert.toSeq,
              enabled = true,
              loose = true,
              trustAll = true
            )
          ).legacy
          val wsRequest = env.gatewayClient
            .akkaUrlWithTarget(url, target)
            .withFollowRedirects(false)
            .withMethod(method)
            .addHttpHeaders(headers.toSeq: _*)
            .withCookies(cookies: _*)
            .withRequestTimeout(1.minute)
          val respF     = body match {
            case None          => wsRequest.execute()
            case Some(content) => wsRequest.withBody(Source(content.grouped(32 * 1024).toList)).execute()
          }
          respF.flatMap { resp =>
            val report: JsValue = env.proxyState.report(requestId).map(_.json).getOrElse(JsNull)
            val status          = resp.status
            val headers         = resp.headers.mapValues(_.last)
            resp.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map { respBodyRaw =>
              Ok(
                Json.obj(
                  "status"       -> status,
                  "headers"      -> headers,
                  "body_base_64" -> respBodyRaw.encodeBase64.utf8String,
                  "cookies"      -> JsArray(resp.cookies.map(c => JsonHelpers.wsCookieToJson(c))),
                  "report"       -> report
                )
              )
            }
          }
        }
      }
    }
  }
}
