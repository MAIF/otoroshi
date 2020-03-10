package controllers

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import auth.AuthModuleConfig
import env.Env
import models._
import org.mindrot.jbcrypt.BCrypt
import otoroshi.script.Script
import otoroshi.tcp._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader, Result}
import security.IdGenerator
import ssl.Cert

import scala.concurrent.Future

class TemplatesController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-templates-api")

  def process(json: JsValue, req: RequestHeader): JsValue = {
    val over = req.queryString
      .filterNot(_._1 == "rawPassword")
      .map(t => Json.obj(t._1 -> t._2.head))
      .foldLeft(Json.obj())(_ ++ _)
    json.as[JsObject] ++ over
  }

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    groupId match {
      case Some(gid) => {
        env.datastores.serviceGroupDataStore.findById(gid).map {
          case Some(group) => {
            val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid)
            Ok(process(apiKey.toJson, ctx.request))
          }
          case None => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
        }
      }
      case None => {
        val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
        FastFuture.successful(Ok(process(apiKey.toJson, ctx.request)))
      }
    }
  }

  def initiateServiceGroup() = ApiAction { ctx =>
    val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
    Ok(process(group.toJson, ctx.request))
  }

  def initiateService() = ApiAction { ctx =>
    val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
    Ok(process(desc.toJson, ctx.request))
  }

  def initiateTcpService() = ApiAction { ctx =>
    Ok(
      process(
        env.datastores.tcpServiceDataStore.template.json,
        ctx.request
      )
    )
  }

  def initiateCertificate() = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.template.map { cert =>
      Ok(process(cert.toJson, ctx.request))
    }
  }

  def initiateGlobalConfig() = ApiAction { ctx =>
    Ok(process(env.datastores.globalConfigDataStore.template.toJson, ctx.request))
  }

  def initiateJwtVerifier() = ApiAction { ctx =>
    Ok(
      process(env.datastores.globalJwtVerifierDataStore.template.asJson, ctx.request)
    )
  }

  def initiateAuthModule() = ApiAction { ctx =>
    Ok(
      process(env.datastores.authConfigsDataStore.template(ctx.request.getQueryString("mod-type")).asJson, ctx.request)
    )
  }

  def initiateScript() = ApiAction { ctx =>
    Ok(
      process(
        env.datastores.scriptDataStore.template.toJson,
        ctx.request
      )
    )
  }

  def initiateSimpleAdmin() = ApiAction { ctx =>
    val pswd: String = ctx.request
      .getQueryString("rawPassword")
      .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
      .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
    Ok(
      process(Json.obj(
                "username"        -> "user@otoroshi.io",
                "password"        -> pswd,
                "label"           -> "user@otoroshi.io",
                "authorizedGroup" -> JsNull
              ),
              ctx.request)
    )
  }

  def initiateWebauthnAdmin() = ApiAction { ctx =>
    val pswd: String = ctx.request
      .getQueryString("rawPassword")
      .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
      .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
    Ok(
      process(Json.obj(
                "username"        -> "user@otoroshi.io",
                "password"        -> pswd,
                "label"           -> "user@otoroshi.io",
                "authorizedGroup" -> JsNull
              ),
              ctx.request)
    )
  }

  private def patchTemplate[T](entity: => JsValue,
                               patch: JsValue,
                               format: Format[T],
                               save: T => Future[Boolean]): Future[Result] = {
    val merged = entity.as[JsObject].deepMerge(patch.as[JsObject])
    format.reads(merged) match {
      case JsError(e)           => FastFuture.successful(BadRequest(Json.obj("error" -> s"bad entity $e")))
      case JsSuccess(entity, _) => save(entity).map(_ => Created(format.writes(entity)))
    }
  }

  def createFromTemplate(entity: String) = ApiAction.async(parse.json) { ctx =>
    val patch = ctx.request.body
    entity.toLowerCase() match {
      case "services" =>
        patchTemplate[ServiceDescriptor](
          env.datastores.serviceDescriptorDataStore
            .initiateNewDescriptor()
            .copy(subdomain = IdGenerator.token(32).toLowerCase(),
                  domain = s"${IdGenerator.token(32).toLowerCase()}.${IdGenerator.token(8).toLowerCase()}")
            .toJson,
          patch,
          ServiceDescriptor._fmt,
          _.save()
        )
      case "groups" =>
        patchTemplate[ServiceGroup](env.datastores.serviceGroupDataStore.initiateNewGroup().toJson,
                                    patch,
                                    ServiceGroup._fmt,
                                    _.save())
      case "apikeys" =>
        patchTemplate[ApiKey](env.datastores.apiKeyDataStore.initiateNewApiKey("default").toJson,
                              patch,
                              ApiKey._fmt,
                              _.save())
      case "certificates" =>
        env.datastores.certificatesDataStore.template
          .flatMap(cert => patchTemplate[Cert](cert.toJson, patch, Cert._fmt, _.save()))
      case "globalconfig" =>
        patchTemplate[GlobalConfig](env.datastores.globalConfigDataStore.template.toJson,
                                    patch,
                                    GlobalConfig._fmt,
                                    _.save())
      case "verifiers" =>
        patchTemplate[GlobalJwtVerifier](env.datastores.globalJwtVerifierDataStore.template.asJson,
                                         patch,
                                         GlobalJwtVerifier._fmt,
                                         _.save())
      case "auths" =>
        patchTemplate[AuthModuleConfig](
          env.datastores.authConfigsDataStore.template(ctx.request.getQueryString("mod-type")).asJson,
          patch,
          AuthModuleConfig._fmt,
          _.save()
        )
      case "scripts" =>
        patchTemplate[Script](env.datastores.scriptDataStore.template.toJson, patch, Script._fmt, _.save())
      case "tcp/services" =>
        patchTemplate[TcpService](env.datastores.tcpServiceDataStore.template.json, patch, TcpService.fmt, _.save())
      case _ => FastFuture.successful(NotFound(Json.obj("error" -> "entity not found")))
    }
  }
}
