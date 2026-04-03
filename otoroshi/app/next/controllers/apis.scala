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
  ApiSubscription,
  ApiSubscriptionDisabled,
  ApiSubscriptionEnabled,
  ApiSubscriptionPending
}
import org.joda.time.DateTime
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.api.WriteAction.Create
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, ApiDeploymentEvent, Audit}
import otoroshi.models.Draft
import otoroshi.next.models.{NgClientConfig, NgRoute}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsError, JsObject, JsSuccess, JsValue, Json, Reads}
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

  def findAllByKind(kind: String) =
    ApiAction.async { ctx =>
      Results
        .Ok(
          JsArray(
            env.proxyState
              .allDrafts()
              .filter(_.id.startsWith(kind))
              .map(_.json)
          )
        )
        .future
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
    api.plans.find(_.id == planId) match {
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
      ctx.canWriteService(apiId) {
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

        val isDraft = ctx.request.getQueryString("version").contains("Draft")

        def findDraft[A](id: String, fmt: Reads[A]): Future[Option[A]] =
          env.proxyState
            .allDrafts()
            .find(_.id == id)
            .flatMap(draft => fmt.reads(draft.content).asOpt)
            .future

        def findApi(): Future[Option[Api]] =
          if (isDraft) findDraft(apiId, Api.format)
          else env.datastores.apiDataStore.findById(apiId)

        def saveSubscription(sub: ApiSubscription): Future[Boolean] =
          if (isDraft) {
            val draft = Draft(
              id = sub.id,
              content = ApiSubscription.format.writes(sub).as[JsObject],
              name = sub.name,
              description = sub.description,
              kind = "api-subscription"
            )
            env.datastores.draftsDataStore.set(draft)
          } else {
            env.datastores.apiSubscriptionDataStore.set(sub)
          }

        findApi().flatMap {
          case None      => Results.NotFound.vfuture
          case Some(api) =>
            val result = for {
              validApi     <- validateApi(api)
              _validPlan   <- validatePlan(validApi, planId)
              subscription <- validateBody(ctx)
            } yield subscription

            result match {
              case Left(errorResult)   => errorResult.vfuture
              case Right(subscription) =>
                println(api.plans.map(_.raw))
                println(api.plans.map(_.rateLimiting))
                ApiSubscription
                  .validate(subscription.apiRef, subscription, Create, isDraft = isDraft)
                  .flatMap {
                    case Left(error) => BadRequest(Json.obj("error" -> error)).vfuture
                    case Right(sub)  =>
                      saveSubscription(sub.copy(status = ApiSubscriptionPending)).map {
                        case true  => Ok(Json.obj("done" -> true))
                        case false => BadRequest(Json.obj("error" -> "something bad happened"))
                      }
                  }
            }
        }
      }
    }
  }

  def confirmSubscription(apiId: String, subscriptionId: String) = {
    ApiAction.async(parse.json) { ctx =>
      ctx.canWriteService(apiId) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_API",
            "User tried to confirm a subscription",
            ctx.from,
            ctx.ua,
            Json.obj(
              "apiId"          -> apiId,
              "subscriptionId" -> subscriptionId
            )
          )
        )

        val isDraft = ctx.request.getQueryString("version").contains("Draft")

        def findDraft[A](id: String, fmt: Reads[A]): Future[Option[A]] =
          env.proxyState
            .allDrafts()
            .find(_.id == id)
            .flatMap(draft => fmt.reads(draft.content).asOpt)
            .future

        def findApi(): Future[Option[Api]] =
          if (isDraft) findDraft(apiId, Api.format)
          else env.datastores.apiDataStore.findById(apiId)

        def findSubscription(): Future[Option[ApiSubscription]] =
          if (isDraft) findDraft(subscriptionId, ApiSubscription.format)
          else env.datastores.apiSubscriptionDataStore.findById(subscriptionId)

        def enableSubscription(subscription: ApiSubscription): Future[Boolean] =
          if (isDraft) {
            env.proxyState.allDrafts().find(_.id == subscriptionId) match {
              case None        => false.future
              case Some(draft) =>
                val updated = draft.copy(
                  content = draft.content.as[JsObject].deepMerge(Json.obj("status" -> ApiSubscriptionEnabled.name))
                )
                env.datastores.draftsDataStore.set(updated)
            }
          } else {
            env.datastores.apiSubscriptionDataStore.set(subscription.copy(status = ApiSubscriptionEnabled))
          }

        findApi().flatMap {
          case None      => Results.NotFound.vfuture
          case Some(api) =>
            validateApi(api) match {
              case Left(errorResult) => errorResult.vfuture
              case Right(_)          =>
                findSubscription().flatMap {
                  case None               =>
                    NotFound(Json.obj("error" -> "subscription not found")).future
                  case Some(subscription) =>
                    validatePlan(api, subscription.planRef) match {
                      case Left(_)  => BadRequest(Json.obj("error" -> "invalid plan")).future
                      case Right(_) =>
                        enableSubscription(subscription).map {
                          case true  => NoContent
                          case false => BadRequest(Json.obj("error" -> "something bad happened"))
                        }
                    }
                }
            }
        }
      }
    }
  }

  def duplicate(apiId: String) = {
    ApiAction.async(parse.json) { ctx =>
      ctx.canReadService(apiId) {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_API",
            "User tried to duplicate an API",
            ctx.from,
            ctx.ua,
            Json.obj("apiId" -> apiId)
          )
        )

        env.datastores.apiDataStore.findById(apiId) flatMap {
          case None      => Results.NotFound.vfuture
          case Some(api) =>
            val newApiId = s"api_${IdGenerator.uuid}"
            env.datastores.apiDataStore
              .set(
                api.copy(
                  id = newApiId,
                  state = ApiStaging,
                  deployments = Seq.empty,
                  contextPath = ctx.request.body.selectAsOptString("contextPath").getOrElse("/vnew"),
                  version = ctx.request.body.selectAsOptString("version").getOrElse("vnew"),
                  metadata = api.metadata + ("copy_from_api" -> apiId)
                )
              )
              .map {
                case false => BadRequest(Json.obj("error" -> "failed to duplicate API"))
                case true  => Ok(Json.obj("id" -> newApiId))
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
                          state = if (apiDraft.state == ApiStaging) ApiPublished else api.state,
                          documentation = api.documentation,
                          clients = api.clients
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
