package controllers.adminapi

import actions.ApiAction
import env.Env
import otoroshi.tcp.TcpService
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.{AbstractController, ControllerComponents}
import security.IdGenerator

class TcpServiceApiController(ApiAction: ApiAction, cc: ControllerComponents)(
  implicit env: Env
) extends AbstractController(cc) {

  import utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-tcp-service-api")

  def findAllTcpServices() = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findAll().map(all => Ok(JsArray(all.map(_.json))))
  }

  def findTcpServiceById(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).map {
      case Some(service) => Ok(service.json)
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        )
    }
  }

  def createTcpService() = ApiAction.async(parse.json) { ctx =>
    val id = (ctx.request.body \ "id").asOpt[String]
    val body = ctx.request.body
      .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
    TcpService.fromJsonSafe(body) match {
      case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
      case Right(service) =>
        env.datastores.tcpServiceDataStore.set(service).map { _ =>
          Ok(service.json)
        }
    }
  }

  def updateTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        TcpService.fromJsonSafe(ctx.request.body) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(service) => {
            env.datastores.tcpServiceDataStore.set(service).map { _ =>
              Ok(service.json)
            }
          }
        }
      }
    }
  }

  def patchTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        val currentJson   = initialTcpService.json
        val newTcpService = utils.JsonPatchHelpers.patchJson(ctx.request.body, currentJson)
        TcpService.fromJsonSafe(newTcpService) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(newTcpService) => {
            env.datastores.tcpServiceDataStore.set(newTcpService).map { _ =>
              Ok(newTcpService.json)
            }
          }
        }
      }
    }
  }

  def deleteTcpService(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.delete(id).map { _ =>
      Ok(Json.obj("deleted" -> true))
    }
  }
}

