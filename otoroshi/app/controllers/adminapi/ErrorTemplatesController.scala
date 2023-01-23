package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models.ErrorTemplate
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class ErrorTemplatesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[ErrorTemplate, JsValue]
    with CrudControllerHelper[ErrorTemplate, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-error-templates-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: ErrorTemplate): String = entity.serviceId

  override def readEntity(json: JsValue): Either[String, ErrorTemplate] =
    ErrorTemplate.fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: ErrorTemplate): JsValue = ErrorTemplate.fmt.writes(entity)

  override def findByIdOps(id: String, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ErrorTemplate]]] = {
    env.datastores.errorTemplateDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_ERROR_TEMPLATE",
          message = "User accessed a error template",
          metadata = Json.obj("ErrorTemplateId" -> id),
          alert = "ErrorTemplateAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[ErrorTemplate]]] = {
    env.datastores.errorTemplateDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_ERROR_TEMPLATES",
          message = "User accessed all error templates",
          metadata = Json.obj(),
          alert = "ErrorTemplatesAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: ErrorTemplate,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ErrorTemplate]]] = {
    env.datastores.errorTemplateDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_ERROR_TEMPLATE",
            message = "User created a error template",
            metadata = entity.json.as[JsObject],
            alert = "ErrorTemplateCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: ErrorTemplate,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ErrorTemplate]]] = {
    env.datastores.errorTemplateDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_ERROR_TEMPLATE",
            message = "User updated a error template",
            metadata = entity.json.as[JsObject],
            alert = "ErrorTemplateUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[ErrorTemplate]]] = {
    env.datastores.errorTemplateDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_ERROR_TEMPLATE",
            message = "User deleted a error template",
            metadata = Json.obj("ErrorTemplateId" -> id),
            alert = "ErrorTemplateDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not deleted ...")
          )
        )
      }
    }
  }
}
