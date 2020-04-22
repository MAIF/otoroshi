package controllers.adminapi

import actions.ApiAction
import auth.{AuthModuleConfig, BasicAuthModule}
import env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}
import security.IdGenerator
import utils.JsonPatchHelpers.patchJson

class AuthModulesController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-auth-modules-api")

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
}