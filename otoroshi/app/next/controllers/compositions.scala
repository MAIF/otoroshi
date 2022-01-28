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

class NgRoutesCompositionsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc)
    with BulkControllerHelper[NgRoutesComposition, JsValue]
    with CrudControllerHelper[NgRoutesComposition, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-routes-composition-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: NgRoutesComposition): String = entity.id

  override def readEntity(json: JsValue): Either[String, NgRoutesComposition] =
    NgRoutesComposition.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: NgRoutesComposition): JsValue = NgRoutesComposition.fmt.writes(entity)

  override def findByIdOps(
    id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[NgRoutesComposition]]] = {
    env.datastores.routesCompositionDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_ROUTES_COMPOSITION",
          message = "User accessed a routes-composition",
          metadata = Json.obj("RouteId" -> id),
          alert = "RoutesCompositionAccessed"
        )
      )
    }
  }

  override def findAllOps(
    req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[NgRoutesComposition]]] = {
    env.datastores.routesCompositionDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_ROUTES_COMPOSITIONS",
          message = "User accessed all routes-compositions",
          metadata = Json.obj(),
          alert = "RoutesCompositionsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
    entity: NgRoutesComposition
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoutesComposition]]] = {
    env.datastores.routesCompositionDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_ROUTES_COMPOSITION",
            message = "User created a routes-composition",
            metadata = entity.json.as[JsObject],
            alert = "RoutesCompositionCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "routes-composition not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
    entity: NgRoutesComposition
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoutesComposition]]] = {
    env.datastores.routesCompositionDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_ROUTES_COMPOSITION",
            message = "User updated a routes-composition",
            metadata = entity.json.as[JsObject],
            alert = "RoutesCompositionUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "routes-composition not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
    id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[NgRoutesComposition]]] = {
    env.datastores.routesCompositionDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_ROUTES_COMPOSITION",
            message = "User deleted a route",
            metadata = Json.obj("RouteId" -> id),
            alert = "RoutesCompositionDeletedAlert"
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

  def initiateRoutesComposition() = ApiAction {
    Ok(NgRoutesComposition(
      location = EntityLocation.default,
      id = s"routecomp_${IdGenerator.uuid}",
      name = "New route composition",
      description = "A new route composition",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = true,
      groups = Seq("default"),
      routes = Seq(NgMinimalRoute(
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath("new-route.oto.tools")),
          headers = Map.empty,
          methods = Seq.empty,
          stripPath = true,
          exact = false,
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
          rewrite = false,
          loadBalancing = RoundRobin
        )
      )),
      client = ClientConfig(),
      plugins = NgPlugins(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost],
        )
      ))
    ).json)
  }
}
