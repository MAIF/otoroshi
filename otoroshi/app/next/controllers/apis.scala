package otoroshi.next.controllers.adminapi

import akka.NotUsed
import akka.stream.scaladsl.Source
import next.models.{
  Api,
  ApiDeployment,
  ApiDeprecated,
  ApiDocumentationPlan,
  ApiPlanStatus,
  ApiPublished,
  ApiRemoved,
  ApiStaging,
  ApiSubscription
}
import org.joda.time.DateTime
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, ApiDeploymentEvent, Audit}
import otoroshi.next.models.{NgClientConfig, NgRoute}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsError, JsObject, JsSuccess, JsValue, Json}
import play.api.mvc._

import java.util.concurrent.TimeUnit
import scala.+:
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class ApisController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apis-controller")

  case class RouteStats(
      calls: Long = 0,
      dataIn: Long = 0,
      dataOut: Long = 0,
      rate: Double = 0.0,
      duration: Double = 0.0,
      overhead: Double = 0.0
  ) {
    def json = Json.obj(
      "calls"    -> calls,
      "dataIn"   -> dataIn,
      "dataOut"  -> dataOut,
      "rate"     -> round(rate),
      "duration" -> round(duration),
      "overhead" -> round(overhead)
    )

    private def round(value: Double): Double = {
      if (value == 0 || value.toString == "Infinity") {
        0
      } else {
        (value * 100).round / 100.toDouble
      }
    }
  }

  def draftLiveStats(id: String, every: Option[Int]) =
    ApiAction.async { ctx =>
      ctx.canReadService(id) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_LIVESTATS_OF_DRAFT",
            "User accessed draft livestats",
            ctx.from,
            ctx.ua,
            Json.obj("draftId" -> id)
          )
        )

        def fetch(): Future[JsObject] = {
          env.datastores.draftsDataStore.findById(id) flatMap {
            case None      => Json.obj().vfuture
            case Some(api) =>
              Api.format
                .reads(api.content)
                .get
                .toRoutes
                .flatMap(routes => Future.sequence(routes.map(getStatsOfRoute)))
                .map(stats => foldStats(stats).json)
          }
        }

        every match {
          case Some(millis) =>
            Ok.chunked(
              Source
                .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(millis, TimeUnit.MILLISECONDS), NotUsed)
                .flatMapConcat(_ => Source.future(fetch()))
                .map(json => s"data: ${Json.stringify(json)}\n\n")
            ).as("text/event-stream")
              .future
          case None         =>
            Ok.chunked(Source.single(1).flatMapConcat(_ => Source.future(fetch()))).as("application/json").future
        }
      }
    }

  def foldStats(stats: Seq[RouteStats]) = {
    stats.foldLeft(RouteStats()) { case (acc, item) =>
      acc.copy(
        calls = acc.calls + item.calls,
        rate = acc.rate + item.rate,
        duration = acc.duration + item.duration,
        overhead = acc.overhead + item.overhead
      )
    }
  }

  def getStatsOfRoute(route: NgRoute) = {
    for {
      calls    <- env.datastores.serviceDescriptorDataStore.calls(route.id)
      dataIn   <- env.datastores.serviceDescriptorDataStore.dataInFor(route.id)
      dataOut  <- env.datastores.serviceDescriptorDataStore.dataOutFor(route.id)
      rate     <- env.datastores.serviceDescriptorDataStore.callsPerSec(route.id)
      duration <- env.datastores.serviceDescriptorDataStore.callsDuration(route.id)
      overhead <- env.datastores.serviceDescriptorDataStore.callsOverhead(route.id)
    } yield {
      RouteStats(
        calls = calls,
        dataIn = dataIn,
        dataOut = dataOut,
        rate = rate,
        duration = duration,
        overhead = overhead
      )
    }
  }

  def liveStats(id: String, every: Option[Int]) =
    ApiAction.async { ctx =>
      ctx.canReadService(id) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_LIVESTATS",
            "User accessed api livestats",
            ctx.from,
            ctx.ua,
            Json.obj("apiId" -> id)
          )
        )

        def fetch(): Future[JsObject] = {
          env.datastores.apiDataStore.findById(id) flatMap {
            case None      => Json.obj().vfuture
            case Some(api) =>
              api.toRoutes
                .flatMap(routes => Future.sequence(routes.map(getStatsOfRoute)))
                .map(stats => foldStats(stats).json)
          }
        }

        every match {
          case Some(millis) =>
            Ok.chunked(
              Source
                .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(millis, TimeUnit.MILLISECONDS), NotUsed)
                .flatMapConcat(_ => Source.future(fetch()))
                .map(json => s"data: ${Json.stringify(json)}\n\n")
            ).as("text/event-stream")
              .future
          case None         =>
            Ok.chunked(Source.single(1).flatMapConcat(_ => Source.future(fetch()))).as("application/json").future
        }
      }
    }

  def start(id: String) = {
    ApiAction.async { ctx =>
      ctx.canReadService(id) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIS",
            "User started the api",
            ctx.from,
            ctx.ua,
            Json.obj("apiId" -> id)
          )
        )

        toggleApiRoutesStatus(id, newStatus = true)
      }
    }
  }

  def toggleApiRoutesStatus(apiId: String, newStatus: Boolean): Future[Result] = {
    env.datastores.apiDataStore.findById(apiId).flatMap {
      case Some(api) =>
        env.datastores.apiDataStore
          .set(api.copy(state = ApiPublished, routes = api.routes.map(route => route.copy(enabled = newStatus))))
          .flatMap(_ => Results.Ok.future)
      case None      => Results.NotFound.future
    }
  }

  def stop(id: String) = {
    ApiAction.async { ctx =>
      ctx.canReadService(id) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIS",
            "User stopped the api",
            ctx.from,
            ctx.ua,
            Json.obj("apiId" -> id)
          )
        )

        toggleApiRoutesStatus(id, newStatus = false)
      }
    }
  }

  def getRoute(apiId: String, routeId: String) = {
    ApiAction.async { ctx =>
      ctx.canReadService(apiId) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIS",
            "User get route of api",
            ctx.from,
            ctx.ua,
            Json.obj("apiId" -> apiId, "routeId" -> routeId)
          )
        )

        val notFoundMessage = Json.obj("error" -> "route not found")

        env.datastores.apiDataStore
          .findById(apiId)
          .flatMap {
            case Some(api) =>
              api.routes
                .find(_.id == routeId)
                .map(route =>
                  api.routeToNgRoute(route).map {
                    case Some(route) => route.json
                    case None        => notFoundMessage
                  }
                )
                .getOrElse(notFoundMessage.vfuture)
                .map(data => Ok(data))
            case None      => Results.NotFound(notFoundMessage).vfuture
          }
      }
    }
  }

  def getHttpClientSettings(apiId: String) = ApiAction.async { _ =>
    Ok(NgClientConfig.default.json).future
  }

  private def validateApi(api: Api): Either[Result, Api] = {
    if (!api.enabled)
      Left(BadRequest(Json.obj("error" -> "api is disabled")))
    else if (api.state == ApiDeprecated || api.state == ApiRemoved)
      Left(BadRequest(Json.obj("error" -> s"api is ${api.state.name}")))
    else
      Right(api)
  }

  private def validatePlan(api: Api, planId: String): Either[Result, ApiDocumentationPlan] =
    api.documentation.flatMap(_.plans.find(_.id == planId)) match {
      case None                                                 => Left(BadRequest(Json.obj("error" -> "plan not found")))
      case Some(plan) if plan.status != ApiPlanStatus.Published =>
        Left(BadRequest(Json.obj("error" -> "plan is not published")))
      case Some(plan)                                           => Right(plan)
    }

  private def validateBody(ctx: ApiActionContext[JsValue]): Either[Result, ApiSubscription] =
    ctx.request.body
      .asOpt(ApiSubscription.format)
      .toRight(BadRequest(Json.obj("error" -> "wrong subscription format")))

  def subscribe(apiId: String, planId: String) = {
    ApiAction.async(parse.json) { ctx =>
      ctx.canReadService(apiId) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_API",
            "User tried to subscribe to a plan of an API",
            ctx.from,
            ctx.ua,
            Json.obj(
              "apiId"  -> apiId,
              "planId" -> planId
            )
          )
        )

        env.datastores.apiDataStore.findById(apiId) flatMap {
          case None      => Results.NotFound.vfuture
          case Some(api) =>
            val result = for {
              validApi     <- validateApi(api)
              _validPlan   <- validatePlan(validApi, apiId)
              subscription <- validateBody(ctx)
            } yield subscription

            result match {
              case Left(errorResult)   => errorResult.vfuture
              case Right(subscription) =>
                env.datastores.apiSubscriptionDataStore.set(subscription).map {
                  case true  => Ok(Json.obj("done" -> true))
                  case false => BadRequest(Json.obj("error" -> "something bad happened"))
                }
            }
        }
      }
    }
  }

  def createNewVersion(apiId: String) = {
    ApiAction.async(parse.json) { ctx =>
      ctx.canReadService(apiId) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_API",
            "User tried to create a new API",
            ctx.from,
            ctx.ua,
            Json.obj(
              "apiId" -> apiId
            )
          )
        )

        env.datastores.apiDataStore.findById(apiId) flatMap {
          case None      => Results.NotFound.future
          case Some(api) =>
            ApiDeployment._fmt.reads(ctx.request.body) match {
              case JsError(_)               => Results.BadRequest(Json.obj("error" -> "bad entity")).future
              case JsSuccess(deployment, _) =>
                env.datastores.draftsDataStore.findById(apiId) flatMap {
                  case None               => Results.NotFound.future
                  case Some(draftWrapper) =>
                    Api.format.reads(draftWrapper.content) match {
                      case JsError(_)             => Results.NotFound.future
                      case JsSuccess(apiDraft, _) =>
                        val updatedApi = apiDraft.copy(
                          deployments = (Seq(deployment) ++ api.deployments).slice(0, 5),
                          id = api.id,
                          routes = apiDraft.routes.map(route => route.copy(id = s"${route.id}_prod")),
                          state = if (apiDraft.state == ApiStaging) ApiPublished else api.state
                        )

                        env.datastores.apiDataStore
                          .set(updatedApi)
                          .map(result => {

                            ApiDeploymentEvent(
                              `@id` = env.snowflakeGenerator.nextIdStr(),
                              `@timestamp` = DateTime.now(),
                              apiRef = deployment.apiRef,
                              owner =
                                if (deployment.owner.isEmpty)
                                  ctx.user.map(user => Json.stringify(user)).getOrElse(deployment.owner)
                                else deployment.owner,
                              at = deployment.at,
                              apiDefinition = deployment.apiDefinition,
                              version = api.version,
                              `@service` = api.name,
                              `@serviceId` = apiId
                            ).toAnalytics()

                            if (result) {
                              Results.Created(updatedApi.json)
                            } else
                              Results.BadRequest(Json.obj("error" -> "something wrong happened"))
                          })
                    }
                }
            }
        }
      }
    }
  }

  def fromOpenapi() = ApiAction.async(parse.json) { ctx =>
    {
      val body = ctx.request.body
      (
        body.selectAsOptString("domain"),
        body.selectAsOptString("openapi"),
        body.selectAsOptString("contextPath"),
        body.selectAsOptString("backendHostname"),
        body.selectAsOptString("backendPath")
      ) match {
        case (Some(domain), Some(openapi), Some(contextPath), Some(backendHostname), Some(backendPath)) =>
          Api
            .fromOpenApi(domain, openapi, contextPath, backendHostname, backendPath)
            .map(service => Ok(service.json))
        case _                                                                                          => BadRequest(Json.obj("error" -> "missing values")).vfuture
      }
    }
  }
}
