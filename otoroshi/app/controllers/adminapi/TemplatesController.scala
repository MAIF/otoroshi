package otoroshi.controllers.adminapi

import org.apache.pekko.http.scaladsl.util.FastFuture
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
                val finalKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid, env, ctx.some)
//                val finalKey = apiKey
//                  .copy(location = apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
                Ok(process(finalKey.toJson, ctx.request))
              }
              case None        => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
            }
          }
          case None      => {
            val finalKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default", env, ctx.some)
//            val finalKey = apiKey.copy(location =
//              apiKey.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))
//            )
            FastFuture.successful(Ok(process(finalKey.toJson, ctx.request)))
          }
        }
      }
    }

  def initiateServiceGroup() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val group = env.datastores.serviceGroupDataStore.initiateNewGroup(env, ctx.some)
//        val finalGroup =
//          group.copy(location = group.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(process(group.toJson, ctx.request)).future
      }
    }

  def initiateService() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val desc = env.datastores.serviceDescriptorDataStore
          .initiateNewDescriptor()
          .copy(location = EntityLocation.ownEntityLocation(ctx.some)(env))
//        val finaldesc =
//          desc.copy(location = desc.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(process(desc.toJson, ctx.request)).future
      }
    }

  def initiateTcpService() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val service = env.datastores.tcpServiceDataStore.template(env, ctx.some)
//        val finalService =
//          service.copy(location = service.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(
          process(
            service.json,
            ctx.request
          )
        ).future
      }
    }

  def initiateCertificate() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        env.datastores.certificatesDataStore.nakedTemplate(env, ctx.some).map { cert =>
//          val finalCert =
//            cert.copy(location = cert.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
          Ok(process(cert.toJson, ctx.request))
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
        val jwt = env.datastores.globalJwtVerifierDataStore.template(env, ctx.some)
//        val finalJwt =
//          jwt.copy(location = jwt.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
        Ok(
          process(jwt.asJson, ctx.request)
        ).future
      }
    }

  def initiateAuthModule() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.Anyone) {
        val module = env.datastores.authConfigsDataStore
          .template(ctx.request.getQueryString("mod-type"), env, ctx.some)
//          .applyOn(c => c.withLocation(c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam))))
        Ok(
          process(module.asJson, ctx.request)
        ).future
      }
    }

  def findAllTemplates() =
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
          env.datastores.dataExporterConfigDataStore.template(ctx.request.getQueryString("type"), ctx.some)
//            .applyOn { c =>
//            c.copy(location = c.location.copy(tenant = ctx.currentTenant, teams = Seq(ctx.oneAuthorizedTeam)))
//          }
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

        // Scala 3 has no runtime `scala.reflect.universe`, so the dotted field paths of an event case
        // class are derived with plain Java reflection: a Scala case class implements `scala.Product`
        // and exposes its constructor params as declared fields. We recurse into nested case classes,
        // unwrapping the first type argument of parameterized fields (Option[X], Seq[X], Map[K, _]…)
        // and stopping at non-Product leaf types. A visited set guards against cyclic types.
        def elementClass(t: java.lang.reflect.Type): Class[?] = t match {
          case pt: java.lang.reflect.ParameterizedType =>
            pt.getActualTypeArguments.headOption match {
              case Some(c: Class[?])                                 => c
              case Some(inner: java.lang.reflect.ParameterizedType) => inner.getRawType.asInstanceOf[Class[?]]
              case _                                                 => pt.getRawType.asInstanceOf[Class[?]]
            }
          case c: Class[?]                             => c
          case _                                       => classOf[AnyRef]
        }

        def rec(clazz: Class[?], visited: Set[Class[?]]): List[List[String]] = {
          if (!classOf[Product].isAssignableFrom(clazz) || visited.contains(clazz)) {
            List(Nil)
          } else {
            val fields = clazz.getDeclaredFields.toList
              .filterNot(f => f.isSynthetic || java.lang.reflect.Modifier.isStatic(f.getModifiers))
            if (fields.isEmpty) List(Nil)
            else
              fields.flatMap { f =>
                rec(elementClass(f.getGenericType), visited + clazz).map(f.getName :: _)
              }
          }
        }

        val map: Map[String, Class[?]] = Map(
          "GatewayEvent"                     -> classOf[GatewayEvent],
          "MaxConcurrentRequestReachedAlert" -> classOf[MaxConcurrentRequestReachedAlert],
          "CircuitBreakerOpenedAlert"        -> classOf[CircuitBreakerOpenedAlert],
          "CircuitBreakerClosedAlert"        -> classOf[CircuitBreakerClosedAlert],
          "SessionDiscardedAlert"            -> classOf[SessionDiscardedAlert],
          "SessionsDiscardedAlert"           -> classOf[SessionsDiscardedAlert],
          "PanicModeAlert"                   -> classOf[PanicModeAlert],
          "OtoroshiExportAlert"              -> classOf[OtoroshiExportAlert],
          "U2FAdminDeletedAlert"             -> classOf[U2FAdminDeletedAlert],
          "BlackListedBackOfficeUserAlert"   -> classOf[BlackListedBackOfficeUserAlert],
          "AdminLoggedInAlert"               -> classOf[AdminLoggedInAlert],
          "AdminFirstLogin"                  -> classOf[AdminFirstLogin],
          "AdminLoggedOutAlert"              -> classOf[AdminLoggedOutAlert],
          "GlobalConfigModification"         -> classOf[GlobalConfigModification],
          "RevokedApiKeyUsageAlert"          -> classOf[RevokedApiKeyUsageAlert],
          "ServiceGroupCreatedAlert"         -> classOf[ServiceGroupCreatedAlert],
          "ServiceGroupUpdatedAlert"         -> classOf[ServiceGroupUpdatedAlert],
          "ServiceGroupDeletedAlert"         -> classOf[ServiceGroupDeletedAlert],
          "ServiceCreatedAlert"              -> classOf[ServiceCreatedAlert],
          "ServiceUpdatedAlert"              -> classOf[ServiceUpdatedAlert],
          "ServiceDeletedAlert"              -> classOf[ServiceDeletedAlert],
          "ApiKeyCreatedAlert"               -> classOf[ApiKeyCreatedAlert],
          "ApiKeyUpdatedAlert"               -> classOf[ApiKeyUpdatedAlert],
          "ApiKeyDeletedAlert"               -> classOf[ApiKeyDeletedAlert],
          "TrafficCaptureEvent"              -> classOf[TrafficCaptureEvent],
          "TcpEvent"                         -> classOf[TcpEvent],
          "HealthCheckEvent"                 -> classOf[HealthCheckEvent],
          "RequestBodyEvent"                 -> classOf[RequestBodyEvent],
          "ResponseBodyEvent"                -> classOf[ResponseBodyEvent],
          "MirroringEvent"                   -> classOf[MirroringEvent],
          "BackOfficeEvent"                  -> classOf[BackOfficeEvent],
          "AdminApiEvent"                    -> classOf[AdminApiEvent],
          "SnowMonkeyOutageRegisteredEvent"  -> classOf[SnowMonkeyOutageRegisteredEvent],
          "CircuitBreakerOpenedEvent"        -> classOf[CircuitBreakerOpenedEvent],
          "CircuitBreakerClosedEvent"        -> classOf[CircuitBreakerClosedEvent],
          "MaxConcurrentRequestReachedEvent" -> classOf[MaxConcurrentRequestReachedEvent],
          "JobRunEvent"                      -> classOf[JobRunEvent],
          "JobErrorEvent"                    -> classOf[JobErrorEvent],
          "JobStoppedEvent"                  -> classOf[JobStoppedEvent],
          "JobStartedEvent"                  -> classOf[JobStartedEvent]
        )

        map.get(eventType) match {
          case Some(value) =>
            val fields: Seq[String] = rec(value, Set.empty[Class[?]])
              .map(_.mkString("."))
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
            .mapAsync(1) { v => createResource(v, ctx.request) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        }
        case Some(content @ JsObject(_))                        =>
          createResource(content, ctx.request).map(created => Ok(Json.obj("created" -> created)))
        case Some(JsString(content)) if content.contains("---") => {
          Source(splitContent(content).toList)
            .flatMapConcat(s => Source(Yaml.parse(s).toList))
            .mapAsync(1) { v => createResource(v, ctx.request) }
            .runWith(Sink.seq)
            .map(created => Ok(Json.obj("created" -> JsArray(created))))
        }
        case Some(JsString(content))                            =>
          Yaml.parseSafe(content) match {
            case Left(e)     =>
              e.printStackTrace()
              // Yaml.write(env.datastores.globalConfigDataStore.latest().json).debugPrintln
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
        case groupKind if kind.contains("/") => {
          val parts = groupKind.split("/")
          val group = parts(0)
          val kind  = parts(1)
          env.allResources.resources.find(r => r.kind == kind && r.group == group) match {
            case None      => {
              Json
                .obj(
                  "error" -> s"resource kind '${kind}' unknown",
                  "name"  -> JsString((content \ "name").asOpt[String].getOrElse("Unknown"))
                )
                .vfuture
            }
            case Some(res) => {
              res.access
                .template("v1", request.queryString.mapValues(_.last))
                .as[JsObject]
                .deepMerge(resource)
                .vfuture
            }
          }
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
