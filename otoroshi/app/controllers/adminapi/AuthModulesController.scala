package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.auth.{AuthModuleConfig, BasicAuthModule}
import otoroshi.env.Env
import otoroshi.models.ApiKey
import otoroshi.utils.controllers.{ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SeqEntityAndContext}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class AuthModulesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[AuthModuleConfig, JsValue] with CrudControllerHelper[AuthModuleConfig, JsValue] {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-auth-modules-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: AuthModuleConfig): String = entity.id

  override def readEntity(json: JsValue): Either[String, AuthModuleConfig] = AuthModuleConfig._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: AuthModuleConfig): JsValue = AuthModuleConfig._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[AuthModuleConfig]]] = {
    env.datastores.authConfigsDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_AUTH_MODULE",
        message = "User accessed an Auth. module",
        metadata = Json.obj("AuthModuleConfigId" -> id),
        alert = "AuthModuleConfigAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[AuthModuleConfig]]] = {
    env.datastores.authConfigsDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_AUTH_MODULES",
        message = "User accessed all Auth. modules",
        metadata = Json.obj(),
        alert = "AuthModuleConfigsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: AuthModuleConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[AuthModuleConfig]]] = {
    env.datastores.authConfigsDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_AUTH_MODULE",
          message = "User created an Auth. module",
          metadata = entity.asJson.as[JsObject],
          alert = "AuthModuleConfigCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Auth. module not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: AuthModuleConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[AuthModuleConfig]]] = {
    env.datastores.authConfigsDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_AUTH_MODULE",
          message = "User updated an Auth. module",
          metadata = entity.asJson.as[JsObject],
          alert = "AuthModuleConfigUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Auth. module not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[AuthModuleConfig]]] = {
    env.datastores.authConfigsDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_AUTH_MODULE",
          message = "User deleted an Auth. module",
          metadata = Json.obj("AuthModuleConfigId" -> id),
          alert = "AuthModuleConfigDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Auth. module not deleted ...")
        ))
      }
    }
  }


  def startRegistration(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case Some(auth) if !ctx.canUserWrite(auth) => ctx.fforbidden
      case Some(auth) => {
        auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
          case bam: BasicAuthModule if bam.authConfig.webauthn =>
            bam.webAuthnRegistrationStart(ctx.request.body.asJson.get).map {
              case Left(err)  => BadRequest(err)
              case Right(reg) => Ok(reg)
            }
          case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
        }
      }
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).future
    }
  }

  def finishRegistration(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case Some(auth) if !ctx.canUserWrite(auth) => ctx.fforbidden
      case Some(auth) => {
        auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
          case bam: BasicAuthModule if bam.authConfig.webauthn =>
            bam.webAuthnRegistrationFinish(ctx.request.body.asJson.get).map {
              case Left(err)  => BadRequest(err)
              case Right(reg) => Ok(reg)
            }
          case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
        }
      }
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).future
    }
  }
}