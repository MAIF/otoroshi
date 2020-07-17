package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import auth.AuthModuleConfig
import env.Env
import models._
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.{RightsChecker, Team, TeamId, Tenant, TenantId}
import otoroshi.script.Script
import otoroshi.tcp._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader, Result}
import security.IdGenerator
import ssl.Cert
import otoroshi.utils.syntax.implicits._

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

  def initiateTenant() = ApiAction.async { ctx =>
    Ok(Tenant(
      id = TenantId("new-organization"),
      name = "New Organization",
      description = "A organization to do whatever you want",
      metadata = Map.empty
    ).json).future
  }

  def initiateTeam() = ApiAction.async { ctx =>
    Ok(Team(
      id = TeamId("new-team"),
      tenant = TenantId("default"),
      name = "New Team",
      description = "A team to do whatever you want",
      metadata = Map.empty
    ).json).future
  }

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
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
  }

  def initiateServiceGroup() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
      Ok(process(group.toJson, ctx.request)).future
    }
  }

  def initiateService() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
      Ok(process(desc.toJson, ctx.request)).future
    }
  }

  def initiateTcpService() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      Ok(
        process(
          env.datastores.tcpServiceDataStore.template.json,
          ctx.request
        )
      ).future
    }
  }

  def initiateCertificate() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      env.datastores.certificatesDataStore.template.map { cert =>
        Ok(process(cert.toJson, ctx.request))
      }
    }
  }

  def initiateGlobalConfig() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      Ok(process(env.datastores.globalConfigDataStore.template.toJson, ctx.request)).future
    }
  }

  def initiateJwtVerifier() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      Ok(
        process(env.datastores.globalJwtVerifierDataStore.template.asJson, ctx.request)
      ).future
    }
  }

  def initiateAuthModule() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      Ok(
        process(env.datastores.authConfigsDataStore.template(ctx.request.getQueryString("mod-type")).asJson, ctx.request)
      ).future
    }
  }

  def initiateScript() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      Ok(
        process(
          env.datastores.scriptDataStore.template.toJson,
          ctx.request
        )
      ).future
    }
  }

  def initiateSimpleAdmin() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val pswd: String = ctx.request
        .getQueryString("rawPassword")
        .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
        .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
      Ok(
        process(Json.obj(
          "username" -> "user@otoroshi.io",
          "password" -> pswd,
          "label" -> "user@otoroshi.io"
        ),
          ctx.request)
      ).future
    }
  }

  def initiateWebauthnAdmin() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val pswd: String = ctx.request
        .getQueryString("rawPassword")
        .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
        .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
      Ok(
        process(Json.obj(
          "username" -> "user@otoroshi.io",
          "password" -> pswd,
          "label" -> "user@otoroshi.io"
        ),
          ctx.request)
      ).future
    }
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
    ctx.checkRights(RightsChecker.Anyone) {
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
}
