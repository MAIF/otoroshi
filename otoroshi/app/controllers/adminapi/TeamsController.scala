package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models.Team
import otoroshi.utils.controllers.{ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SeqEntityAndContext}
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class TeamsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[Team, JsValue] with CrudControllerHelper[Team, JsValue] {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-teams-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: Team): String = entity.id.value

  override def readEntity(json: JsValue): Either[String, Team] = Team.format.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: Team): JsValue = Team.format.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Team]]] = {
    env.datastores.teamDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_TEAM",
        message = "User accessed a Team",
        metadata = Json.obj("TeamId" -> id),
        alert = "TeamAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Team]]] = {
    env.datastores.teamDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_TEAMS",
        message = "User accessed all teams",
        metadata = Json.obj(),
        alert = "TeamtsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: Team)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Team]]] = {
    env.datastores.teamDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_TEAM",
          message = "User created a team",
          metadata = entity.json.as[JsObject],
          alert = "TeamCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "team not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: Team)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Team]]] = {
    env.datastores.teamDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_TEAM",
          message = "User updated a team",
          metadata = entity.json.as[JsObject],
          alert = "TeamUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "team not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Team]]] = {
    env.datastores.teamDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_TEAM",
          message = "User deleted a team",
          metadata = Json.obj("TeamId" -> id),
          alert = "TeamDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "team not deleted ...")
        ))
      }
    }
  }
}