package otoroshi.controllers.adminapi

import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
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

import scala.concurrent.{ExecutionContext, Future}

class TemplatesController(ApiAction: ApiAction, cc: ControllerComponents)(using env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-templates-api")

  def process(json: JsValue, req: RequestHeader): JsValue = {
    val over = req.queryString
      .filterNot(_._1 == "rawPassword")
      .map(t => Json.obj(t._1 -> t._2.head))
      .foldLeft(Json.obj())(_ ++ _)
    json.as[JsObject] ++ over
  }

  def initiateTenant(): Action[AnyContent] =
    ApiAction.async { ctx =>
      Ok(env.datastores.tenantDataStore.template(env).json).future
    }

  def initiateTeam(): Action[AnyContent] =
    ApiAction.async { ctx =>
      Ok(env.datastores.teamDataStore.template(ctx.currentTenant).json).future
    }

  def initiateApiKey(groupId: Option[String]): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        groupId match {
          case Some(gid) =>
            env.datastores.serviceGroupDataStore.findById(gid).map {
              case Some(group) =>
                val finalKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid, env, ctx.some)
                Ok(process(finalKey.toJson, ctx.request))
              case None        => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
            }
          case None      =>
            val finalKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default", env, ctx.some)
            FastFuture.successful(Ok(process(finalKey.toJson, ctx.request)))
        }
      }
    }

  def initiateServiceGroup(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val group = env.datastores.serviceGroupDataStore.initiateNewGroup(env, ctx.some)
        Ok(process(group.toJson, ctx.request)).future
      }
    }

  def initiateService(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val desc = env.datastores.serviceDescriptorDataStore
          .initiateNewDescriptor()
          .copy(location = EntityLocation.ownEntityLocation(ctx.some)(using env))
        Ok(process(desc.toJson, ctx.request)).future
      }
    }

  def initiateTcpService(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val service = env.datastores.tcpServiceDataStore.template(env, ctx.some)
        Ok(
          process(
            service.json,
            ctx.request
          )
        ).future
      }
    }

  def initiateCertificate(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        env.datastores.certificatesDataStore.nakedTemplate(env, ctx.some).map { cert =>
          Ok(process(cert.toJson, ctx.request))
        }
      }
    }

  def initiateGlobalConfig(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        Ok(process(env.datastores.globalConfigDataStore.template.toJson, ctx.request)).future
      }
    }

  def initiateJwtVerifier(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val jwt = env.datastores.globalJwtVerifierDataStore.template(env, ctx.some)
        Ok(
          process(jwt.asJson, ctx.request)
        ).future
      }
    }

  def initiateAuthModule(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val module = env.datastores.authConfigsDataStore
          .template(ctx.request.getQueryString("mod-type"), env, ctx.some)
        Ok(
          process(module.asJson, ctx.request)
        ).future
      }
    }

  def findAllTemplates(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        Ok(
          process(
            Json.obj(
              "templates" -> env.datastores.authConfigsDataStore
                .templates()
                .map(template =>
                  Json.obj(
                    "type"  -> template.`type`,
                    "label" -> template.humanName
                  )
                )
            ),
            ctx.request
          )
        ).future
      }
    }

  def initiateScript(): Action[AnyContent] =
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

  def initiateSimpleAdmin(): Action[AnyContent] =
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

  def initiateWebauthnAdmin(): Action[AnyContent] =
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

  def initiateDataExporterConfig(): Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val module =
          env.datastores.dataExporterConfigDataStore.template(ctx.request.getQueryString("type"), ctx.some)
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

  def createFromTemplate(entity: String): Action[JsValue] =
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
                .applyOn(c =>
                  c.withLocation(c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                )
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
        // In Scala 3, we can't use runtime reflection the same way
        // So we need to either:
        // 1. Use compile-time reflection (macros)
        // 2. Manually define the field specs
        // 3. Use a different approach

        // For now, let's use a manual approach with predefined field specs
        val fieldSpecs = Map(
          "GatewayEvent"                     -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "reqId",
            "parentReqId",
            "protocol",
            "to",
            "target",
            "url",
            "method",
            "from",
            "fromLat",
            "fromLon",
            "headers",
            "cookies",
            "overhead",
            "duration",
            "status",
            "responseHeaders",
            "responseCookies",
            "data",
            "remainingQuotas",
            "viz",
            "err",
            "gwError",
            "userAgentInfo",
            "geolocationInfo",
            "extraInfo",
            "extraInfos",
            "identity",
            "responseChunked",
            "location",
            "host",
            "backendDuration",
            "overheadWoCb",
            "cbDuration",
            "overheads",
            "productConsumption",
            "remainingConsumptions",
            "itemsCount"
          ),
          "MaxConcurrentRequestReachedAlert" -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "limit",
            "current"
          ),
          "CircuitBreakerOpenedAlert"        -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "target",
            "failures",
            "calls"
          ),
          "CircuitBreakerClosedAlert"        -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "target"
          ),
          "SessionDiscardedAlert"            -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "reason"
          ),
          "SessionsDiscardedAlert"           -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "count",
            "reason"
          ),
          "PanicModeAlert"                   -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "reason",
            "enable"
          ),
          "OtoroshiExportAlert"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "export"
          ),
          "U2FAdminDeletedAlert"             -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "date",
            "u2fRegDeletedByAdmin"
          ),
          "BlackListedBackOfficeUserAlert"   -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "date"
          ),
          "AdminLoggedInAlert"               -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "date"
          ),
          "AdminFirstLogin"                  -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "date"
          ),
          "AdminLoggedOutAlert"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "date"
          ),
          "GlobalConfigModification"         -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "modifiedAt",
            "oldConfig",
            "newConfig",
            "diff"
          ),
          "RevokedApiKeyUsageAlert"          -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "apikey",
            "date"
          ),
          "ServiceGroupCreatedAlert"         -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "serviceGroup"
          ),
          "ServiceGroupUpdatedAlert"         -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "serviceGroup",
            "oldServiceGroup",
            "diff"
          ),
          "ServiceGroupDeletedAlert"         -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "serviceGroup"
          ),
          "ServiceCreatedAlert"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "service"
          ),
          "ServiceUpdatedAlert"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "service",
            "oldService",
            "diff"
          ),
          "ServiceDeletedAlert"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "service"
          ),
          "ApiKeyCreatedAlert"               -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "apikey"
          ),
          "ApiKeyUpdatedAlert"               -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "apikey",
            "oldApikey",
            "diff"
          ),
          "ApiKeyDeletedAlert"               -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "from",
            "ua",
            "apikey"
          ),
          "TrafficCaptureEvent"              -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "date",
            "elapsedTime",
            "request",
            "response"
          ),
          "TcpEvent"                         -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "duration",
            "to",
            "flow",
            "from"
          ),
          "HealthCheckEvent"                 -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "service",
            "url",
            "duration",
            "status",
            "error"
          ),
          "RequestBodyEvent"                 -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "snowflake",
            "body",
            "request"
          ),
          "ResponseBodyEvent"                -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "snowflake",
            "body",
            "response"
          ),
          "MirroringEvent"                   -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "duration",
            "done",
            "errors"
          ),
          "BackOfficeEvent"                  -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "action",
            "from",
            "metadata",
            "ua",
            "message",
            "alert",
            "adminApiCall",
            "date"
          ),
          "AdminApiEvent"                    -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "user",
            "action",
            "from",
            "metadata",
            "ua",
            "message",
            "date"
          ),
          "SnowMonkeyOutageRegisteredEvent"  -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "date",
            "descriptorId",
            "descriptorName",
            "duration",
            "until"
          ),
          "CircuitBreakerOpenedEvent"        -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "service",
            "target"
          ),
          "CircuitBreakerClosedEvent"        -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "service",
            "target"
          ),
          "MaxConcurrentRequestReachedEvent" -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env"
          ),
          "JobRunEvent"                      -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "jobName",
            "jobKind",
            "ctx"
          ),
          "JobErrorEvent"                    -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "jobName",
            "jobKind",
            "ctx",
            "err"
          ),
          "JobStoppedEvent"                  -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "jobName",
            "jobKind",
            "ctx"
          ),
          "JobStartedEvent"                  -> Seq(
            "@id",
            "@timestamp",
            "@type",
            "@product",
            "@serviceId",
            "@service",
            "@env",
            "jobName",
            "jobKind",
            "ctx"
          )
        )

        fieldSpecs.get(eventType) match {
          case Some(fields) =>
            Ok(JsArray(fields.map(JsString.apply))).future
          case None         =>
            BadRequest("Event type unknown").future
        }
      }
    }

  def initiateResources(): Action[JsValue] =
    ApiAction.async(parse.json) { ctx =>
      ctx.request.body.select("content").asOpt[JsValue] match {
        case Some(JsArray(values))                              =>
          Source(values.toList)
            .mapAsync(1) { v => createResource(v, ctx.request) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        case Some(content @ JsObject(_))                        =>
          createResource(content, ctx.request).map(created => Ok(Json.obj("created" -> created)))
        case Some(JsString(content)) if content.contains("---") =>
          Source(splitContent(content).toList)
            .flatMapConcat(s => Source(Yaml.parse(s).toList))
            .mapAsync(1) { v => createResource(v, ctx.request) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        case Some(JsString(content))                            =>
          Yaml.parseSafe(content) match {
            case Left(e)     =>
              e.printStackTrace()
              BadRequest(Json.obj("error" -> "Can't create resources")).vfuture
            case Right(yaml) => createResource(yaml, ctx.request).map(created => Ok(Json.obj("created" -> created)))
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

  private def createResource(content: JsValue, request: RequestHeader): Future[JsValue] = {
    scala.util.Try {
      val resource = (content \ "spec").asOpt[JsObject] match {
        case None       => content.as[JsObject] - "kind"
        case Some(spec) => spec - "kind"
      }

      val kind =
        (content \ "kind").asOpt[String].orElse(content.select("spec").select("kind").asOpt[String]).getOrElse("--")
      (kind match {
        case groupKind if kind.contains("/") =>
          val parts = groupKind.split("/")
          val group = parts(0)
          val kind  = parts(1)
          env.allResources.resources.find(r => r.kind == kind && r.group == group) match {
            case None      =>
              Json
                .obj(
                  "error" -> s"resource kind '$kind' unknown",
                  "name"  -> JsString((content \ "name").asOpt[String].getOrElse("Unknown"))
                )
                .vfuture
            case Some(res) =>
              res.access
                .template("v1", request.queryString.view.mapValues(_.last).toMap)
                .as[JsObject]
                .deepMerge(resource)
                .vfuture
          }
        case "DataExporter"                  =>
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
        case "ServiceDescriptor"             =>
          FastFuture.successful(
            ServiceDescriptor
              .fromJsons(
                toJson(env.datastores.serviceDescriptorDataStore.template(env)).as[JsObject].deepMerge(resource)
              )
              .json
          )
        case "ServiceGroup"                  =>
          FastFuture.successful(
            ServiceGroup
              .fromJsons(env.datastores.serviceGroupDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "Certificate"                   =>
          env.datastores.certificatesDataStore
            .nakedTemplate(env)
            .map(c => Cert.fromJsons(c.json.as[JsObject].deepMerge(resource)).json)
        case "Tenant"                        =>
          FastFuture.successful(
            Tenant.fromJsons(env.datastores.tenantDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "Organization"                  =>
          FastFuture.successful(
            Tenant.fromJsons(env.datastores.tenantDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "GlobalConfig"                  =>
          FastFuture.successful(
            GlobalConfig
              .fromJsons(env.datastores.globalConfigDataStore.template.json.as[JsObject].deepMerge(resource))
              .json
          )
        case "ApiKey"                        =>
          FastFuture.successful(
            ApiKey.fromJsons(env.datastores.apiKeyDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "Team"                          =>
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
        case "TcpService"                    =>
          FastFuture.successful(
            TcpService
              .fromJsons(env.datastores.tcpServiceDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "AuthModule"                    =>
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
        case "JwtVerifier"                   =>
          FastFuture.successful(
            GlobalJwtVerifier
              .fromJsons(env.datastores.globalJwtVerifierDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "Admin"                         =>
          FastFuture.successful(
            SimpleOtoroshiAdmin.fmt
              .reads(env.datastores.simpleAdminDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .get
              .json
          )
        case "SimpleAdmin"                   =>
          FastFuture.successful(
            SimpleOtoroshiAdmin.fmt
              .reads(env.datastores.simpleAdminDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .get
              .json
          )
        case "Backend"                       =>
          val tmpl = env.datastores.backendsDataStore.template(env).json.as[JsObject]
          FastFuture.successful(
            StoredNgBackend.format
              .reads(tmpl.deepMerge(resource))
              .get
              .json
          )
        case "Route"                         =>
          NgRoute.fromJsons(NgRoute.default.json.as[JsObject].deepMerge(resource)).json.vfuture
        case "RouteComposition"              =>
          FastFuture.successful(
            NgRoute
              .fromJsons(env.datastores.routeCompositionDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "WasmPlugin"                    =>
          FastFuture.successful(
            WasmPlugin
              .fromJsons(env.datastores.wasmPluginsDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "Draft"                         =>
          FastFuture.successful(
            Draft
              .fromJsons(env.datastores.draftsDataStore.template(env).json.as[JsObject].deepMerge(resource))
              .json
          )
        case "ClientValidator"               =>
          FastFuture.successful(
            ClientCertificateValidator
              .fromJsons(
                env.datastores.clientCertificateValidationDataStore.template.json.as[JsObject].deepMerge(resource)
              )
              .json
          )
        case "Script"                        =>
          FastFuture.successful(
            Script.fromJsons(env.datastores.scriptDataStore.template(env).json.as[JsObject].deepMerge(resource)).json
          )
        case "ErrorTemplate"                 => FastFuture.successful(ErrorTemplate.fromJsons(resource).toJson.as[JsObject])
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
