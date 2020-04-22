package controllers.adminapi

import actions.ApiAction
import env.Env
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.{AbstractController, ControllerComponents}
import security.IdGenerator
import ssl.ClientCertificateValidator

class ClientValidatorsController(ApiAction: ApiAction, cc: ControllerComponents)(
  implicit env: Env
) extends AbstractController(cc) {

  import utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  def findAllClientValidators() = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findClientValidatorById(id: String) = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientCertificateValidator with id $id not found")
        )
    }
  }

  def createClientValidator() = ApiAction.async(parse.json) { ctx =>
    val id = (ctx.request.body \ "id").asOpt[String]
    val body = ctx.request.body
      .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
    ClientCertificateValidator.fromJson(body) match {
      case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
      case Right(newVerifier) =>
        env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateClientValidator(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientValidator with id $id not found")
        ).asFuture
      case Some(verifier) => {
        ClientCertificateValidator.fromJson(ctx.request.body) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
          case Right(newVerifier) => {
            env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchClientValidator(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientValidator with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson = verifier.asJson
        val newVerifier = utils.JsonPatchHelpers.patchJson(ctx.request.body, currentJson)
        ClientCertificateValidator.fromJson(newVerifier) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
          case Right(newVerifier) => {
            env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteClientValidator(id: String) = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.delete(id).map(_ => Ok(Json.obj("deleted" -> true)))
  }

}