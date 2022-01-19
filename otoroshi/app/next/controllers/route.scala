package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class RoutesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc)
    with BulkControllerHelper[Route, JsValue]
    with CrudControllerHelper[Route, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-route-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: Route): String = entity.id

  override def readEntity(json: JsValue): Either[String, Route] =
    Route.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: Route): JsValue = Route.fmt.writes(entity)

  override def findByIdOps(
    id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Route]]] = {
    env.datastores.routeDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_ROUTE",
          message = "User accessed a route",
          metadata = Json.obj("RouteId" -> id),
          alert = "RouteAccessed"
        )
      )
    }
  }

  override def findAllOps(
    req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Route]]] = {
    env.datastores.routeDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_ROUTES",
          message = "User accessed all routes",
          metadata = Json.obj(),
          alert = "RoutesAccessed"
        )
      )
    }
  }

  override def createEntityOps(
    entity: Route
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Route]]] = {
    env.datastores.routeDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_ROUTE",
            message = "User created a route",
            metadata = entity.json.as[JsObject],
            alert = "RouteCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
    entity: Route
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Route]]] = {
    env.datastores.routeDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_ROUTE",
            message = "User updated a route",
            metadata = entity.json.as[JsObject],
            alert = "RouteUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
    id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Route]]] = {
    env.datastores.routeDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_ROUTE",
            message = "User deleted a route",
            metadata = Json.obj("RouteId" -> id),
            alert = "RouteDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not deleted ...")
          )
        )
      }
    }
  }

  def initiateRoute() = ApiAction {
    Ok(Route(
      location = EntityLocation.default,
      id = s"route_${IdGenerator.uuid}",
      name = "New route",
      description = "A new route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = true,
      groups = Seq("default"),
      frontend = Frontend(
        domains = Seq(DomainAndPath("new-route.oto.tools")),
        headers = Map.empty,
        stripPath = true,
        apikey = ApiKeyRouteMatcher()
      ),
      backends = Backends(
        targets = Seq(Backend(
          id = "target_1",
          hostname = "mirror.otoroshi.io",
          port = 443,
          tls = true
        )),
        root = "/",
        loadBalancing = RoundRobin
      ),
      client = ClientConfig(),
      healthCheck = HealthCheck(false, "/"),
      plugins = NgPlugins(Seq(
        PluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost],
        )
      ))
    ).json)
  }
}
