package otoroshi.controllers.adminapi

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import org.mindrot.jbcrypt.BCrypt
import otoroshi.actions.ApiAction
import otoroshi.auth._
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.ServiceDescriptor.toJson
import otoroshi.models._
import otoroshi.next.events.TrafficCaptureEvent
import otoroshi.next.models.{NgRoute, StoredNgBackend}
import otoroshi.plugins.loggers.{RequestBodyEvent, ResponseBodyEvent}
import otoroshi.plugins.mirror.MirroringEvent
import otoroshi.script.Script
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientCertificateValidator}
import otoroshi.tcp._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future
import scala.reflect.runtime.universe._

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

  def initiateTenant() =
    ApiAction.async { ctx =>
      Ok(env.datastores.tenantDataStore.template(env).json).future
    }

  def initiateTeam() =
    ApiAction.async { ctx =>
      Ok(env.datastores.teamDataStore.template(ctx.currentTenant).json).future
    }

  def initiateApiKey(groupId: Option[String]) =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        groupId match {
          case Some(gid) => {
            env.datastores.serviceGroupDataStore.findById(gid).map {
              case Some(group) => {
                val apiKey   = env.datastores.apiKeyDataStore.initiateNewApiKey(gid, env)
                val finalKey = apiKey
                  .copy(location = apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                Ok(process(finalKey.toJson, ctx.request))
              }
              case None        => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
            }
          }
          case None      => {
            val apiKey   = env.datastores.apiKeyDataStore.initiateNewApiKey("default", env)
            val finalKey = apiKey.copy(location =
              apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))
            )
            FastFuture.successful(Ok(process(finalKey.toJson, ctx.request)))
          }
        }
      }
    }

  def initiateServiceGroup() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val group      = env.datastores.serviceGroupDataStore.initiateNewGroup(env)
        val finalGroup =
          group.copy(location = group.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(process(finalGroup.toJson, ctx.request)).future
      }
    }

  def initiateService() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val desc      = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
        val finaldesc =
          desc.copy(location = desc.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(process(finaldesc.toJson, ctx.request)).future
      }
    }

  def initiateTcpService() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val service      = env.datastores.tcpServiceDataStore.template(env)
        val finalService =
          service.copy(location = service.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))

        Ok(
          process(
            finalService.json,
            ctx.request
          )
        ).future
      }
    }

  def initiateCertificate() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        env.datastores.certificatesDataStore.nakedTemplate(env).map { cert =>
          val finalCert =
            cert.copy(location = cert.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
          Ok(process(finalCert.toJson, ctx.request))
        }
      }
    }

  def initiateGlobalConfig() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        Ok(process(env.datastores.globalConfigDataStore.template.toJson, ctx.request)).future
      }
    }

  def initiateJwtVerifier() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val jwt      = env.datastores.globalJwtVerifierDataStore.template(env)
        val finalJwt =
          jwt.copy(location = jwt.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(
          process(finalJwt.asJson, ctx.request)
        ).future
      }
    }

  def initiateAuthModule() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val module = env.datastores.authConfigsDataStore
          .template(ctx.request.getQueryString("mod-type"), env)
          .applyOn(c => c.withLocation(c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
        Ok(
          process(module.asJson, ctx.request)
        ).future
      }
    }

  def findAllTemplates() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        Ok(process(Json.obj("templates" -> env.datastores.authConfigsDataStore.templates()
          .map(template => Json.obj(
            "type" ->template.`type`,
            "label" -> template.humanName
          ))), ctx.request)
        ).future
      }
    }

  def initiateScript() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val script      = env.datastores.scriptDataStore.template(env)
        val finalScript =
          script.copy(location = script.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(
          process(
            finalScript.toJson,
            ctx.request
          )
        ).future
      }
    }

  def initiateSimpleAdmin() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val pswd: String = ctx.request
          .getQueryString("rawPassword")
          .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
          .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
        Ok(
          process(
            Json.obj(
              "username" -> "user@otoroshi.io",
              "password" -> pswd,
              "label"    -> "user@otoroshi.io",
              "rights"   -> Json.arr(
                Json.obj(
                  "tenant" -> ctx.currentTenant.value,
                  "teams"  -> Json.arr("default", ctx.oneAuthorizedTeam.value)
                )
              )
            ),
            ctx.request
          )
        ).future
      }
    }

  def initiateWebauthnAdmin() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val pswd: String = ctx.request
          .getQueryString("rawPassword")
          .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
          .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
        Ok(
          process(
            Json.obj(
              "username" -> "user@otoroshi.io",
              "password" -> pswd,
              "label"    -> "user@otoroshi.io",
              "rights"   -> Json.arr(
                Json.obj(
                  "tenant" -> ctx.currentTenant.value,
                  "teams"  -> Json.arr("default", ctx.oneAuthorizedTeam.value)
                )
              )
            ),
            ctx.request
          )
        ).future
      }
    }

  def initiateDataExporterConfig() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val module =
          env.datastores.dataExporterConfigDataStore.template(ctx.request.getQueryString("type")).applyOn { c =>
            c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
          }
        Ok(
          process(module.json, ctx.request)
        ).future
      }
    }

  private def patchTemplate[T](
      entity: => JsValue,
      patch: JsValue,
      format: Format[T],
      save: T => Future[Boolean]
  ): Future[Result] = {
    val merged = entity.as[JsObject].deepMerge(patch.as[JsObject])
    format.reads(merged) match {
      case JsError(e)           => FastFuture.successful(BadRequest(Json.obj("error" -> s"bad entity $e")))
      case JsSuccess(entity, _) => save(entity).map(_ => Created(format.writes(entity)))
    }
  }

  def createFromTemplate(entity: String) =
    ApiAction.async(parse.json) { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val patch = ctx.request.body
        entity.toLowerCase() match {
          case "services"     =>
            patchTemplate[ServiceDescriptor](
              env.datastores.serviceDescriptorDataStore
                .initiateNewDescriptor()
                .copy(
                  subdomain = IdGenerator.token(32).toLowerCase(),
                  domain = s"${IdGenerator.token(32).toLowerCase()}.${IdGenerator.token(8).toLowerCase()}"
                )
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .toJson,
              patch,
              ServiceDescriptor._fmt,
              _.save()
            )
          case "groups"       =>
            patchTemplate[ServiceGroup](
              env.datastores.serviceGroupDataStore
                .initiateNewGroup(env)
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .toJson,
              patch,
              ServiceGroup._fmt,
              _.save()
            )
          case "apikeys"      =>
            patchTemplate[ApiKey](
              env.datastores.apiKeyDataStore
                .initiateNewApiKey("default", env)
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .toJson,
              patch,
              ApiKey._fmt,
              _.save()
            )
          case "tenants"      =>
            patchTemplate[ServiceGroup](
              env.datastores.tenantDataStore.template(env).json,
              patch,
              ServiceGroup._fmt,
              _.save()
            )
          case "teams"        =>
            patchTemplate[ServiceGroup](
              env.datastores.teamDataStore.template(ctx.currentTenant).json,
              patch,
              ServiceGroup._fmt,
              _.save()
            )
          case "certificates" =>
            env.datastores.certificatesDataStore
              .nakedTemplate(env)
              .flatMap(cert =>
                patchTemplate[Cert](
                  cert
                    .applyOn(v =>
                      v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                    )
                    .toJson,
                  patch,
                  Cert._fmt,
                  _.save()
                )
              )
          case "globalconfig" =>
            patchTemplate[GlobalConfig](
              env.datastores.globalConfigDataStore.template.toJson,
              patch,
              GlobalConfig._fmt,
              _.save()
            )
          case "verifiers"    =>
            patchTemplate[GlobalJwtVerifier](
              env.datastores.globalJwtVerifierDataStore
                .template(env)
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .asJson,
              patch,
              GlobalJwtVerifier._fmt,
              _.save()
            )
          case "auths"        =>
            patchTemplate[AuthModuleConfig](
              env.datastores.authConfigsDataStore
                .template(ctx.request.getQueryString("mod-type"), env)
                .applyOn(c => c.withLocation(c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
                .asJson,
              patch,
              AuthModuleConfig._fmt(env),
              _.save()
            )
          case "scripts"      =>
            patchTemplate[Script](
              env.datastores.scriptDataStore
                .template(env)
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .toJson,
              patch,
              Script._fmt,
              _.save()
            )
          case "tcp/services" =>
            patchTemplate[TcpService](
              env.datastores.tcpServiceDataStore
                .template(env)
                .applyOn(v =>
                  v.copy(location = v.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
                .json,
              patch,
              TcpService.fmt,
              _.save()
            )
          case _              => FastFuture.successful(NotFound(Json.obj("error" -> "entity not found")))
        }
      }
    }

  def templateSpec(eventType: String = "GatewayEvent"): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {

        def rec(tpe: Type): List[List[TermSymbol]] = {
          val collected = tpe.members.collect {
            case m: TermSymbol if m.isCaseAccessor => m
          }.toList

          if (collected.nonEmpty)
            collected
              .flatMap(m => {
                m match {
                  case r: MethodSymbol =>
                    if (r.returnType.typeArgs.nonEmpty) {
                      rec(r.returnType.typeArgs.head).map(m :: _)
                    } else {
                      rec(r.returnType).map(m :: _)
                    }
                  case symbol          => List(List(symbol))
                }
              })
          else
            List(Nil)
        }

        val map = Map(
          "GatewayEvent"                     -> typeOf[GatewayEvent],
          "MaxConcurrentRequestReachedAlert" -> typeOf[MaxConcurrentRequestReachedAlert],
          "CircuitBreakerOpenedAlert"        -> typeOf[CircuitBreakerOpenedAlert],
          "CircuitBreakerClosedAlert"        -> typeOf[CircuitBreakerClosedAlert],
          "SessionDiscardedAlert"            -> typeOf[SessionDiscardedAlert],
          "SessionsDiscardedAlert"           -> typeOf[SessionsDiscardedAlert],
          "PanicModeAlert"                   -> typeOf[PanicModeAlert],
          "OtoroshiExportAlert"              -> typeOf[OtoroshiExportAlert],
          "U2FAdminDeletedAlert"             -> typeOf[U2FAdminDeletedAlert],
          "BlackListedBackOfficeUserAlert"   -> typeOf[BlackListedBackOfficeUserAlert],
          "AdminLoggedInAlert"               -> typeOf[AdminLoggedInAlert],
          "AdminFirstLogin"                  -> typeOf[AdminFirstLogin],
          "AdminLoggedOutAlert"              -> typeOf[AdminLoggedOutAlert],
          "GlobalConfigModification"         -> typeOf[GlobalConfigModification],
          "RevokedApiKeyUsageAlert"          -> typeOf[RevokedApiKeyUsageAlert],
          "ServiceGroupCreatedAlert"         -> typeOf[ServiceGroupCreatedAlert],
          "ServiceGroupUpdatedAlert"         -> typeOf[ServiceGroupUpdatedAlert],
          "ServiceGroupDeletedAlert"         -> typeOf[ServiceGroupDeletedAlert],
          "ServiceCreatedAlert"              -> typeOf[ServiceCreatedAlert],
          "ServiceUpdatedAlert"              -> typeOf[ServiceUpdatedAlert],
          "ServiceDeletedAlert"              -> typeOf[ServiceDeletedAlert],
          "ApiKeyCreatedAlert"               -> typeOf[ApiKeyCreatedAlert],
          "ApiKeyUpdatedAlert"               -> typeOf[ApiKeyUpdatedAlert],
          "ApiKeyDeletedAlert"               -> typeOf[ApiKeyDeletedAlert],
          "TrafficCaptureEvent"              -> typeOf[TrafficCaptureEvent],
          "TcpEvent"                         -> typeOf[TcpEvent],
          "HealthCheckEvent"                 -> typeOf[HealthCheckEvent],
          "RequestBodyEvent"                 -> typeOf[RequestBodyEvent],
          "ResponseBodyEvent"                -> typeOf[ResponseBodyEvent],
          "MirroringEvent"                   -> typeOf[MirroringEvent],
          "BackOfficeEvent"                  -> typeOf[BackOfficeEvent],
          "AdminApiEvent"                    -> typeOf[AdminApiEvent],
          "SnowMonkeyOutageRegisteredEvent"  -> typeOf[SnowMonkeyOutageRegisteredEvent],
          "CircuitBreakerOpenedEvent"        -> typeOf[CircuitBreakerOpenedEvent],
          "CircuitBreakerClosedEvent"        -> typeOf[CircuitBreakerClosedEvent],
          "MaxConcurrentRequestReachedEvent" -> typeOf[MaxConcurrentRequestReachedEvent],
          "JobRunEvent"                      -> typeOf[JobRunEvent],
          "JobErrorEvent"                    -> typeOf[JobErrorEvent],
          "JobStoppedEvent"                  -> typeOf[JobStoppedEvent],
          "JobStartedEvent"                  -> typeOf[JobStartedEvent]
        )

        map.get(eventType) match {
          case Some(value) =>
            val fields: Seq[String] = rec(value)
              .map(term => term.map(_.name).mkString(".").trim)
            Ok(
              JsArray(
                fields.distinct.sorted
                  .map(JsString)
              )
            ).future
          case None        => BadRequest("Event type unkown").future
        }
      }
    }

  def initiateResources() =
    ApiAction.async(parse.json) { ctx =>
      ctx.request.body.select("content").asOpt[JsValue] match {
        case Some(JsArray(values))                              => {
          Source(values.toList)
            .mapAsync(1) { v => createResource(v) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        }
        case Some(content @ JsObject(_))                        => createResource(content).map(created => Ok(Json.obj("created" -> created)))
        case Some(JsString(content)) if content.contains("---") => {
          Source(splitContent(content).toList)
            .flatMapConcat(s => Source(Yaml.parse(s).toList))
            .mapAsync(1) { v => createResource(v) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        }
        case Some(JsString(content))                            =>
          Yaml.parseSafe(content) match {
            case Left(e)     =>
              e.printStackTrace()
              // Yaml.write(env.datastores.globalConfigDataStore.latest().json).debugPrintln
              BadRequest(Json.obj("error" -> "Can't create resources")).vfuture
            case Right(yaml) => createResource(yaml).map(created => Ok(Json.obj("created" -> created)))
          }
        case _                                                  => BadRequest(Json.obj("error" -> "Can't create resources")).vfuture
      }
    }

  private def splitContent(content: String) = {
    var out     = Seq.empty[String]
    var current = Seq.empty[String]
    val lines   = content.split("\n")
    lines.foreach(line => {
      if (line == "---") {
        out = out :+ current.mkString("\n")
        current = Seq.empty[String]
      } else {
        current = current :+ line
      }
    })

    if (current.nonEmpty)
      out = out :+ current.mkString("\n")

    out
  }

  private def createResource(content: JsValue): Future[JsValue] = {
    scala.util.Try {
      val resource = (content \ "spec").asOpt[JsObject] match {
        case None       => content.as[JsObject] - "kind"
        case Some(spec) => spec - "kind"
      }

      val kind = (content \ "kind").as[String]
      (kind match {
        case "DataExporter"      =>
          FastFuture.successful(
            DataExporterConfig
              .fromJsons(
                env.datastores.dataExporterConfigDataStore
                  .template((resource \ "type").asOpt[String])
                  .json
                  .as[JsObject]
                  .deepMerge(resource)
              )
              .json
          )
        case "ServiceDescriptor" =>
          FastFuture.successful(
            ServiceDescriptor
              .fromJsons(
                toJson(env.datastores.serviceDescriptorDataStore.template(env)).as[JsObject].deepMerge(resource)
              )
              .json
          )
        case "ServiceGroup"      =>
          FastFuture.successful(
            ServiceGroup
              .fromJsons(env.datastores.serviceGroupDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "Certificate"       =>
          env.datastores.certificatesDataStore
            .nakedTemplate(env)
            .map(c => Cert.fromJsons(c.json.as[JsObject].deepMerge(resource)).json)
        case "Tenant"            =>
          FastFuture.successful(
            Tenant.fromJsons(env.datastores.tenantDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "Organization"      =>
          FastFuture.successful(
            Tenant.fromJsons(env.datastores.tenantDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "GlobalConfig"      =>
          FastFuture.successful(
            GlobalConfig
              .fromJsons(env.datastores.globalConfigDataStore.template.json.as[JsObject].deepMerge(resource))
              .json
          )
        case "ApiKey"            =>
          FastFuture.successful(
            ApiKey.fromJsons(env.datastores.apiKeyDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "Team"              =>
          FastFuture.successful(
            Team
              .fromJsons(
                env.datastores.teamDataStore
                  .template(TenantId((resource \ "tenant").asOpt[String].getOrElse("default")))
                  .json
                  .as[JsObject]
                  .deepMerge(resource)
              )
              .json
          )
        case "TcpService"        =>
          FastFuture.successful(
            TcpService
              .fromJsons(env.datastores.tcpServiceDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "AuthModule"        =>
          FastFuture.successful(
            AuthModuleConfig
              .fromJsons(
                env.datastores.authConfigsDataStore
                  .template((resource \ "type").asOpt[String], env)
                  .json
                  .as[JsObject]
                  .deepMerge(resource)
              )
              .json
          )
        case "JwtVerifier"       =>
          FastFuture.successful(
            GlobalJwtVerifier
              .fromJsons(env.datastores.globalJwtVerifierDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "Admin"             =>
          FastFuture.successful(
            SimpleOtoroshiAdmin.fmt
              .reads(env.datastores.simpleAdminDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .get
              .json
          )
        case "SimpleAdmin"       =>
          FastFuture.successful(
            SimpleOtoroshiAdmin.fmt
              .reads(env.datastores.simpleAdminDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .get
              .json
          )
        case "Backend"           =>
          val tmpl = env.datastores.backendsDataStore.template(env).json.as[JsObject]
          FastFuture.successful(
            StoredNgBackend.format
              .reads(tmpl.deepMerge(resource))
              .get
              .json
          )
        case "Route"             =>
          FastFuture.successful(
            NgRoute
              .fromJsons(env.datastores.routeDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "RouteComposition"  =>
          FastFuture.successful(
            NgRoute
              .fromJsons(env.datastores.routeCompositionDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "ClientValidator"   =>
          FastFuture.successful(
            ClientCertificateValidator
              .fromJsons(
                env.datastores.clientCertificateValidationDataStore.template.json.as[JsObject].deepMerge(resource)
              )
              .json
          )
        case "Script"            =>
          FastFuture.successful(
            Script.fromJsons(env.datastores.scriptDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "ErrorTemplate"     => FastFuture.successful(ErrorTemplate.fromJsons(resource).toJson.as[JsObject])
      })
        .map(resource => {
          Json.obj(
            "kind"     -> kind,
            "resource" -> resource
          )
        })
    } recover { case error: Throwable =>
      FastFuture.successful(
        Json.obj(
          "error" -> error.getMessage,
          "name"  -> JsString((content \ "name").asOpt[String].getOrElse("Unknown"))
        )
      )
    } get
  }
}
