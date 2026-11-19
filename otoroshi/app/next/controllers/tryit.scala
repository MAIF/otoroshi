package otoroshi.next.controllers

import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.util.ByteString
import otoroshi.actions.BackOfficeActionAuth
import otoroshi.env.Env
import otoroshi.events.{KafkaConfig, KafkaSettings}
import otoroshi.next.models.{NgRoute, NgRouteComposition}
import otoroshi.next.utils.{TryIt, TryItRequest}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}

import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try

class TryItController(
    BackOfficeActionAuth: BackOfficeActionAuth,
    cc: ControllerComponents
)(using
    env: Env
) extends AbstractController(cc) {

  implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
  implicit val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("TryItController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def kafkaDataExporterTryIt() = BackOfficeActionAuth.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val jsonBody = bodyRaw.utf8String.parseJson
      jsonBody \ "config" match {
        case JsDefined(config) =>
          KafkaConfig.format.reads(config) match {
            case JsSuccess(kafka, _) =>
              Try {
                val seconds = (jsonBody \ "timeout").asOpt[Int].getOrElse(15)

                val race =
                  org.apache.pekko.pattern.after(new DurationInt(seconds).seconds, using = env.otoroshiActorSystem.scheduler)(
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
      val jsonBody       = bodyRaw.utf8String.parseJson
      val request        = TryItRequest.lenient(jsonBody)
      val isServiceTryIt = entity.contains("service")

      val routeFromIdOpt: Option[Either[NgRouteComposition, NgRoute]]   = jsonBody
        .select("route_id")
        .asOpt[String]
        .flatMap(id =>
          if (isServiceTryIt) {
            env.proxyState.allNgServices().find(_.id == id).filter(e => ctx.canUserRead(e)).map(e => Left(e))
          } else {
            env.proxyState.route(id).filter(e => ctx.canUserRead(e)).map(e => Right(e))
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
      // the frontend_idx only picks a route of a composition found by its id
      val tried                                                         = routeFromIdOpt
        .map(route => (route, request))
        .orElse(routeFromJsonOpt.map(route => (route, request.copy(frontendIdx = None))))

      val notFound = NotFound(Json.obj("error" -> "route not found"))
      tried match {
        case None                   => notFound.vfuture
        case Some((route, request)) =>
          TryIt.run(route, request, e => ctx.canUserRead(e)).map {
            case None         => notFound
            case Some(result) => Ok(result)
          }
      }
    }
  }
}
