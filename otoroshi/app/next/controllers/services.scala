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

class NgServicesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[NgService, JsValue]
    with CrudControllerHelper[NgService, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-ng-service-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: NgService): String = entity.id

  override def readEntity(json: JsValue): Either[String, NgService] =
    NgService.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: NgService): JsValue = NgService.fmt.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[NgService]]] = {
    env.datastores.servicesDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_NG_SERVICE",
          message = "User accessed a service",
          metadata = Json.obj("NgServiceId" -> id),
          alert = "NgServiceAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[NgService]]] = {
    env.datastores.servicesDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_NG_SERVICES",
          message = "User accessed all services",
          metadata = Json.obj(),
          alert = "NgServicesAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: NgService
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgService]]] = {
    env.datastores.servicesDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_NG_SERVICE",
            message = "User created a service",
            metadata = entity.json.as[JsObject],
            alert = "NgServiceCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "service not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: NgService
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgService]]] = {
    env.datastores.servicesDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_NG_SERVICE",
            message = "User updated a service",
            metadata = entity.json.as[JsObject],
            alert = "NgServiceUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "service not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[NgService]]] = {
    env.datastores.servicesDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_NG_SERVICE",
            message = "User deleted a route",
            metadata = Json.obj("NgServiceId" -> id),
            alert = "NgServiceDeletedAlert"
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

  def initiateService() = ApiAction {
    Ok(
      NgService(
        location = EntityLocation.default,
        id = s"ng-service_${IdGenerator.uuid}",
        name = "New service",
        description = "A new service",
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        debugFlow = false,
        exportReporting = false,
        groups = Seq("default"),
        client = ClientConfig(),
        routes = Seq(
          NgMinimalRoute(
            frontend = NgFrontend(
              domains = Seq(NgDomainAndPath("new-route.oto.tools")),
              headers = Map.empty,
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
              targetRefs = Seq.empty,
              root = "/",
              rewrite = false,
              loadBalancing = RoundRobin
            )
          )
        ),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[OverrideHost]
            )
          )
        )
      ).json
    )
  }
}
