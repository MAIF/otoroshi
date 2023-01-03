package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class NgRouteCompositionsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[NgRouteComposition, JsValue]
    with CrudControllerHelper[NgRouteComposition, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-ng-service-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: NgRouteComposition): String = entity.id

  override def readEntity(json: JsValue): Either[String, NgRouteComposition] =
    NgRouteComposition.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: NgRouteComposition): JsValue = NgRouteComposition.fmt.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[NgRouteComposition]]] = {
    env.datastores.routeCompositionDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_NG_ROUTE_COMPOSITION",
          message = "User accessed a route-composition",
          metadata = Json.obj("NgRouteCompositionId" -> id),
          alert = "NgRouteCompositionAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], SeqEntityAndContext[NgRouteComposition]]] = {
    env.datastores.routeCompositionDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_NG_ROUTE_COMPOSITIONS",
          message = "User accessed all route-compositions",
          metadata = Json.obj(),
          alert = "NgRouteCompositionsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: NgRouteComposition
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], EntityAndContext[NgRouteComposition]]] = {
    env.datastores.routeCompositionDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_NG_ROUTE_COMPOSITION",
            message = "User created a route-composition",
            metadata = entity.json.as[JsObject],
            alert = "NgRouteCompoisitionCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route-composition not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: NgRouteComposition
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], EntityAndContext[NgRouteComposition]]] = {
    env.datastores.routeCompositionDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_NG_ROUTE_COMPOSITION",
            message = "User updated a route-composition",
            metadata = entity.json.as[JsObject],
            alert = "NgRouteCompositionUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route-composition not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], NoEntityAndContext[NgRouteComposition]]] = {
    env.datastores.routeCompositionDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_NG_ROUTE_COMPOSITION",
            message = "User deleted a route-composition",
            metadata = Json.obj("NgRouteCompositionId" -> id),
            alert = "NgRouteCompositionDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route-composition not deleted ...")
          )
        )
      }
    }
  }

  def initiateRouteComposition() = ApiAction {
    val defaultService = NgRouteComposition(
      location = EntityLocation.default,
      id = s"route-composition_${IdGenerator.uuid}",
      name = "New route-composition",
      description = "A new route-composition",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      capture = false,
      debugFlow = false,
      exportReporting = false,
      groups = Seq("default"),
      client = NgClientConfig.default,
      routes = Seq(
        NgMinimalRoute(
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath("new-route.oto.tools")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false
          ),
          backend = NgMinimalBackend(
            targets = Seq(
              NgTarget(
                id = "target_1",
                hostname = "mirror.otoroshi.io",
                port = 443,
                tls = true
              )
            ),
            root = "/",
            rewrite = false,
            loadBalancing = RoundRobin
          ),
          overridePlugins = false,
          plugins = NgPlugins.empty
        )
      ),
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          )
        )
      )
    )
    env.datastores.globalConfigDataStore
      .latest()
      .templates
      .service
      .map { template =>
        Ok(defaultService.json.asObject.deepMerge(template))
      }
      .getOrElse {
        Ok(defaultService.json)
      }
  }

  def form() = ApiAction {
    env.openApiSchema.asForms.get("otoroshi.next.models.NgRouteComposition") match {
      case Some(value) =>
        Ok(
          Json.obj(
            "schema" -> value.schema,
            "flow"   -> value.flow
          )
        )
      case _           => NotFound(Json.obj("error" -> "Schema and flow not found"))
    }
  }

  def fromOpenapi() = ApiAction.async(parse.json) { ctx =>
    (ctx.request.body.select("domain").asOpt[String], ctx.request.body.select("openapi").asOpt[String]) match {
      case (Some(domain), Some(openapi)) =>
        NgRouteComposition
          .fromOpenApi(domain, openapi)
          .map(service => Ok(service.json))
      case _                             => BadRequest(Json.obj("error" -> "missing domain and/or openapi value")).vfuture
    }
  }
}
