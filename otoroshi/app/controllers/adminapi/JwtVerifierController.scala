package controllers.adminapi

import actions.ApiAction
import env.Env
import models.GlobalJwtVerifier
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils._

import scala.concurrent.{ExecutionContext, Future}

class JwtVerifierController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[GlobalJwtVerifier, JsValue] with CrudControllerHelper[GlobalJwtVerifier, JsValue] {

  implicit val ec  = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

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

  /*
  def findAllGlobalJwtVerifiers() = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findGlobalJwtVerifiersById(id: String) = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        )
    }
  }

  def createGlobalJwtVerifier() = ApiAction.async(parse.json) { ctx =>
    val id = (ctx.request.body \ "id").asOpt[String]
    val body = ctx.request.body
      .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
    GlobalJwtVerifier.fromJson(body) match {
      case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
      case Right(newVerifier) =>
        env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateGlobalJwtVerifier(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        ).asFuture
      case Some(verifier) => {
        GlobalJwtVerifier.fromJson(ctx.request.body) match {
          case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
          case Right(newVerifier) => {
            env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchGlobalJwtVerifier(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson = verifier.asJson
        val newVerifier = patchJson(ctx.request.body, currentJson)
        GlobalJwtVerifier.fromJson(newVerifier) match {
          case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
          case Right(newVerifier) => {
            env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteGlobalJwtVerifier(id: String) = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.delete(id).map(_ => Ok(Json.obj("deleted" -> true)))
  }
*/
}
