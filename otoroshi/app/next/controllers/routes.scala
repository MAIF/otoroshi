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

class NgRoutesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc)
    with BulkControllerHelper[NgRoute, JsValue]
    with CrudControllerHelper[NgRoute, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-routes-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: NgRoute): String = entity.id

  override def readEntity(json: JsValue): Either[String, NgRoute] =
    NgRoute.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: NgRoute): JsValue = NgRoute.fmt.writes(entity)

  override def findByIdOps(
    id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[NgRoute]]] = {
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
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[NgRoute]]] = {
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
    entity: NgRoute
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoute]]] = {
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
    entity: NgRoute
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoute]]] = {
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
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[NgRoute]]] = {
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
    Ok(NgRoute(
      location = EntityLocation.default,
      id = s"route_${IdGenerator.uuid}",
      name = "New route",
      description = "A new route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = true,
      groups = Seq("default"),
      frontend = NgFrontend(
        domains = Seq(NgDomainAndPath("new-route.oto.tools")),
        headers = Map.empty,
        methods = Seq.empty,
        stripPath = true,
        apikey = ApiKeyRouteMatcher()
      ),
      backend = NgBackend(
        targets = Seq(NgTarget(
          id = "target_1",
          hostname = "mirror.otoroshi.io",
          port = 443,
          tls = true
        )),
        targetRefs = Seq.empty,
        root = "/",
        loadBalancing = RoundRobin
      ),
      client = ClientConfig(),
      healthCheck = HealthCheck(false, "/"),
      plugins = NgPlugins(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost],
        )
      ))
    ).json)
  }
}
