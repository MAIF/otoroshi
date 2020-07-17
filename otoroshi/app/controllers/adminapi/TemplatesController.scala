package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import auth.{AuthModuleConfig, BasicAuthModuleConfig, GenericOauth2ModuleConfig, LdapAuthModuleConfig}
import env.Env
import models._
import org.mindrot.jbcrypt.BCrypt
import otoroshi.models.OtoroshiAdminType.WebAuthnAdmin
import otoroshi.models.{RightsChecker, SimpleOtoroshiAdmin, Team, TeamId, Tenant, TenantId}
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
    Ok(env.datastores.tenantDataStore.template.json).future
  }

  def initiateTeam() = ApiAction.async { ctx =>
    Ok(env.datastores.teamDataStore.template(ctx.currentTenant).json).future
  }

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      groupId match {
        case Some(gid) => {
          env.datastores.serviceGroupDataStore.findById(gid).map {
            case Some(group) => {
              val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid)
              val finalKey = apiKey.copy(location = apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
              Ok(process(finalKey.toJson, ctx.request))
            }
            case None => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
          }
        }
        case None => {
          val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
          val finalKey = apiKey.copy(location = apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
          FastFuture.successful(Ok(process(finalKey.toJson, ctx.request)))
        }
      }
    }
  }

  def initiateServiceGroup() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
      val finalGroup = group.copy(location = group.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
      Ok(process(finalGroup.toJson, ctx.request)).future
    }
  }

  def initiateService() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
      val finaldesc = desc.copy(location = desc.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
      Ok(process(finaldesc.toJson, ctx.request)).future
    }
  }

  def initiateTcpService() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val service = env.datastores.tcpServiceDataStore.template
      val finalService = service.copy(location = service.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))

      Ok(
        process(
          finalService.json,
          ctx.request
        )
      ).future
    }
  }

  def initiateCertificate() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      env.datastores.certificatesDataStore.template.map { cert =>
        val finalCert = cert.copy(location = cert.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(process(finalCert.toJson, ctx.request))
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
      val jwt = env.datastores.globalJwtVerifierDataStore.template
      val finalJwt = jwt.copy(location = jwt.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
      Ok(
        process(finalJwt.asJson, ctx.request)
      ).future
    }
  }

  def initiateAuthModule() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val module = env.datastores.authConfigsDataStore.template(ctx.request.getQueryString("mod-type")).applyOn {
        case c: LdapAuthModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        case c: BasicAuthModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        case c: GenericOauth2ModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
      }
      Ok(
        process(module.asJson, ctx.request)
      ).future
    }
  }

  def initiateScript() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.Anyone) {
      val script = env.datastores.scriptDataStore.template
      val finalScript = script.copy(location = script.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
      Ok(
        process(
          finalScript.toJson,
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
          "label" -> "user@otoroshi.io",
          "rights" -> Json.arr(
            Json.obj("tenant" -> ctx.currentTenant.value, "teams" -> Json.arr("default", ctx.oneAuthorizedTeam.value))
          )
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
          "label" -> "user@otoroshi.io",
          "rights" -> Json.arr(
            Json.obj("tenant" -> ctx.currentTenant.value, "teams" -> Json.arr("default", ctx.oneAuthorizedTeam.value))
          )
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
              .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
              .toJson,
            patch,
            ServiceDescriptor._fmt,
            _.save()
          )
        case "groups" =>
          patchTemplate[ServiceGroup](env.datastores.serviceGroupDataStore.initiateNewGroup()
            .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
            .toJson,
            patch,
            ServiceGroup._fmt,
            _.save())
        case "apikeys" =>
          patchTemplate[ApiKey](env.datastores.apiKeyDataStore.initiateNewApiKey("default")
            .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
            .toJson,
            patch,
            ApiKey._fmt,
            _.save())
        case "tenants" =>
          patchTemplate[ServiceGroup](env.datastores.tenantDataStore.template.json,
            patch,
            ServiceGroup._fmt,
            _.save())
        case "teams" =>
          patchTemplate[ServiceGroup](env.datastores.teamDataStore.template(ctx.currentTenant).json,
            patch,
            ServiceGroup._fmt,
            _.save())
        case "certificates" =>
          env.datastores.certificatesDataStore.template
            .flatMap(cert => patchTemplate[Cert](cert
              .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
              .toJson, patch, Cert._fmt, _.save()))
        case "globalconfig" =>
          patchTemplate[GlobalConfig](env.datastores.globalConfigDataStore.template.toJson,
            patch,
            GlobalConfig._fmt,
            _.save())
        case "verifiers" =>
          patchTemplate[GlobalJwtVerifier](env.datastores.globalJwtVerifierDataStore.template
              .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
              .asJson,
            patch,
            GlobalJwtVerifier._fmt,
            _.save())
        case "auths" =>
          patchTemplate[AuthModuleConfig](
            env.datastores.authConfigsDataStore.template(ctx.request.getQueryString("mod-type")).applyOn {
              case c: LdapAuthModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
              case c: BasicAuthModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
              case c: GenericOauth2ModuleConfig => c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
            }.asJson,
            patch,
            AuthModuleConfig._fmt,
            _.save()
          )
        case "scripts" =>
          patchTemplate[Script](env.datastores.scriptDataStore.template
            .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
            .toJson, patch, Script._fmt, _.save())
        case "tcp/services" =>
          patchTemplate[TcpService](env.datastores.tcpServiceDataStore.template
            .applyOn(v => v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
            .json, patch, TcpService.fmt, _.save())
        case _ => FastFuture.successful(NotFound(Json.obj("error" -> "entity not found")))
      }
    }
  }
}
