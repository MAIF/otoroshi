package controllers.adminapi

import actions.ApiAction
import auth.{AuthModuleConfig, BasicAuthModule}
import env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils._

import scala.concurrent.{ExecutionContext, Future}

class AuthModulesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[AuthModuleConfig, JsValue] with CrudControllerHelper[AuthModuleConfig, JsValue] {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-auth-modules-api")

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


  /*
  def findAllGlobalAuthModules() = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findGlobalAuthModuleById(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        )
    }
  }

  def createGlobalAuthModule() = ApiAction.async(parse.json) { ctx =>
    val id = (ctx.request.body \ "id").asOpt[String]
    val body = ctx.request.body
      .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
    AuthModuleConfig._fmt.reads(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
      case JsSuccess(newVerifier, _) =>
        env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateGlobalAuthModule(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).asFuture
      case Some(verifier) => {
        AuthModuleConfig._fmt.reads(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
          case JsSuccess(newVerifier, _) => {
            env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchGlobalAuthModule(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson     = verifier.asJson
        val patchedVerifier = patchJson(ctx.request.body, currentJson)
        AuthModuleConfig._fmt.reads(patchedVerifier) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
          case JsSuccess(newVerifier, _) => {
            env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteGlobalAuthModule(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.delete(id).map(_ => Ok(Json.obj("deleted" -> true)))
  }*/
}