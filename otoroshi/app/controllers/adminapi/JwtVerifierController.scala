package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models.GlobalJwtVerifier
import otoroshi.utils.controllers.{ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SeqEntityAndContext}
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class JwtVerifierController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[GlobalJwtVerifier, JsValue] with CrudControllerHelper[GlobalJwtVerifier, JsValue] {

  implicit val ec  = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: GlobalJwtVerifier): String = entity.id

  override def readEntity(json: JsValue): Either[String, GlobalJwtVerifier] = GlobalJwtVerifier._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: GlobalJwtVerifier): JsValue = GlobalJwtVerifier._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[GlobalJwtVerifier]]] = {
    env.datastores.globalJwtVerifierDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_GLOBAL_JWT_VERIFIER",
        message = "User accessed a global jwt verifier",
        metadata = Json.obj("GlobalJwtVerifierId" -> id),
        alert = "GlobalJwtVerifierAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[GlobalJwtVerifier]]] = {
    env.datastores.globalJwtVerifierDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_GLOBAL_JWT_VERIFIERS",
        message = "User accessed all global jwt verifiers",
        metadata = Json.obj(),
        alert = "GlobalJwtVerifiersAccessed"
      ))
    }
  }

  override def createEntityOps(entity: GlobalJwtVerifier)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[GlobalJwtVerifier]]] = {
    env.datastores.globalJwtVerifierDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_GLOBAL_JWT_VERIFIER",
          message = "User created a global jwt verifier",
          metadata = entity.asJson.as[JsObject],
          alert = "GlobalJwtVerifierCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Global jwt verifier not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: GlobalJwtVerifier)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[GlobalJwtVerifier]]] = {
    env.datastores.globalJwtVerifierDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_GLOBAL_JWT_VERIFIER",
          message = "User updated a global jwt verifier",
          metadata = entity.asJson.as[JsObject],
          alert = "GlobalJwtVerifierUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Global jwt verifier not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[GlobalJwtVerifier]]] = {
    env.datastores.globalJwtVerifierDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_GLOBAL_JWT_VERIFIER",
          message = "User deleted a global jwt verifier",
          metadata = Json.obj("GlobalJwtVerifierId" -> id),
          alert = "GlobalJwtVerifierDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Global jwt verifier not deleted ...")
        ))
      }
    }
  }
}
