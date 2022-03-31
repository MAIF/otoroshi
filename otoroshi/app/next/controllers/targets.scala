package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class NgTargetsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[StoredNgTarget, JsValue]
    with CrudControllerHelper[StoredNgTarget, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-targets-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: StoredNgTarget): String = entity.id

  override def readEntity(json: JsValue): Either[String, StoredNgTarget] =
    StoredNgTarget.format.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: StoredNgTarget): JsValue = StoredNgTarget.format.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[StoredNgTarget]]] = {
    env.datastores.targetsDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_STORED_NG_TARGET",
          message = "User accessed a target",
          metadata = Json.obj("StoredNgTargetId" -> id),
          alert = "StoredNgTargetAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[StoredNgTarget]]] = {
    env.datastores.targetsDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_STORED_NG_TARGETS",
          message = "User accessed all targets",
          metadata = Json.obj(),
          alert = "StoredNgTargetsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: StoredNgTarget
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredNgTarget]]] = {
    env.datastores.targetsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_STORED_NG_TARGET",
            message = "User created a target",
            metadata = entity.json.as[JsObject],
            alert = "StoredNgTargetCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "target not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: StoredNgTarget
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredNgTarget]]] = {
    env.datastores.targetsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_STORED_NG_TARGET",
            message = "User updated a target",
            metadata = entity.json.as[JsObject],
            alert = "StoredNgTargetUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "target not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[StoredNgTarget]]] = {
    env.datastores.targetsDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_STORED_NG_TARGET",
            message = "User deleted a target",
            metadata = Json.obj("StoredNgTargetId" -> id),
            alert = "StoredNgTargetDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "target not deleted ...")
          )
        )
      }
    }
  }

  def form() = ApiAction {
    env.openApiSchema.asForms.get("otoroshi.next.models.NgTarget") match {
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

  def initiateStoredNgTarget() = ApiAction {
    val defaultTarget = StoredNgTarget(
      location = EntityLocation.default,
      id = s"target_${IdGenerator.uuid}",
      name = "New target",
      description = "A new target",
      tags = Seq.empty,
      metadata = Map.empty,
      target = NgTarget(
        id = "target_1",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )
    )
    env.datastores.globalConfigDataStore
      .latest()
      .templates
      .target
      .map { template =>
        Ok(defaultTarget.json.asObject.deepMerge(template))
      }
      .getOrElse {
        Ok(defaultTarget.json)
      }
  }
}
