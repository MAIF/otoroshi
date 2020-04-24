package controllers.adminapi

import actions.{ApiAction, ApiActionContext}
import env.Env
import events._
import models.{ErrorTemplate, ServiceDescriptor, ServiceDescriptorQuery, Target}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils.JsonPatchHelpers.patchJson
import utils._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results.Status

import scala.concurrent.{ExecutionContext, Future}

class ServicesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[ServiceDescriptor, JsValue] with CrudControllerHelper[ServiceDescriptor, JsValue] with AdminApiHelper {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-services-api")

  override def extractId(entity: ServiceDescriptor): String = entity.id

  override def readEntity(json: JsValue): Either[String, ServiceDescriptor] = ServiceDescriptor._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: ServiceDescriptor): JsValue = ServiceDescriptor._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ServiceDescriptor]]] = {
    env.datastores.serviceDescriptorDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_SERVICE_DESCRIPTOR",
        message = "User accessed a service descriptor",
        metadata = Json.obj("ServiceDescriptorId" -> id),
        alert = "ServiceDescriptorAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[ServiceDescriptor]]] = {
    env.datastores.serviceDescriptorDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_SERVICE_DESCRIPTORS",
        message = "User accessed all service descriptors",
        metadata = Json.obj(),
        alert = "ServiceDescriptorsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: ServiceDescriptor)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceDescriptor]]] = {
    env.datastores.serviceDescriptorDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_SERVICE_DESCRIPTOR",
          message = "User created a service descriptor",
          metadata = entity.toJson.as[JsObject],
          alert = "ServiceDescriptorCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "service descriptor not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: ServiceDescriptor)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceDescriptor]]] = {
    env.datastores.serviceDescriptorDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_SERVICE_DESCRIPTOR",
          message = "User updated a service descriptor",
          metadata = entity.toJson.as[JsObject],
          alert = "ServiceDescriptorUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "service descriptor not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[ServiceDescriptor]]] = {
    env.datastores.serviceDescriptorDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_SERVICE_DESCRIPTOR",
          message = "User deleted a service descriptor",
          metadata = Json.obj("ServiceDescriptorId" -> id),
          alert = "ServiceDescriptorDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "service descriptor not deleted ...")
        ))
      }
    }
  }

  def allLines() = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_ALL_LINES", s"User accessed all lines", None, Json.obj(), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: String) => JsString(e), options) {
      env.datastores.globalConfigDataStore.allEnv().map(_.toSeq).fright[JsonApiError]
    }
    // Audit.send(
    //   AdminApiEvent(
    //     env.snowflakeGenerator.nextIdStr(),
    //     env.env,
    //     Some(ctx.apiKey),
    //     ctx.user,
    //     "ACCESS_ALL_LINES",
    //     "User accessed all lines",
    //     ctx.from,
    //     ctx.ua
    //   )
    // )
    // env.datastores.globalConfigDataStore.allEnv().map {
    //   case lines => Ok(JsArray(lines.toSeq.map(l => JsString(l))))
    // }
  }

  def servicesForALine(line: String) = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_SERVICES_FOR_LINES", s"User accessed service list for line $line", None, Json.obj("line" -> line), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ServiceDescriptor) => e.toJson, options) {
      env.datastores.serviceDescriptorDataStore.findByEnv(line).fright[JsonApiError]
    }
    // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    // val paginationPageSize: Int =
    //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    // val paginationPosition = (paginationPage - 1) * paginationPageSize
    // Audit.send(
    //   AdminApiEvent(
    //     env.snowflakeGenerator.nextIdStr(),
    //     env.env,
    //     Some(ctx.apiKey),
    //     ctx.user,
    //     "ACCESS_SERVICES_FOR_LINES",
    //     s"User accessed service list for line $line",
    //     ctx.from,
    //     ctx.ua,
    //     Json.obj("line" -> line)
    //   )
    // )
    // env.datastores.serviceDescriptorDataStore.findByEnv(line).map {
    //   case descriptors => Ok(JsArray(descriptors.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
    // }
  }

  def serviceTargets(serviceId: String) = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_SERVICE_TARGETS", "User accessed a service targets", None, Json.obj("serviceId" -> serviceId), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: String) => JsString(e), options) {
      env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
        case None => JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).left[Seq[String]]
        case Some(desc) => desc.targets.map(t => s"${t.scheme}://${t.host}").right[JsonApiError]
      }
    }
    // env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
    //   case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
    //   case Some(desc) => {
    //     Audit.send(
    //       AdminApiEvent(
    //         env.snowflakeGenerator.nextIdStr(),
    //         env.env,
    //         Some(ctx.apiKey),
    //         ctx.user,
    //         "ACCESS_SERVICE_TARGETS",
    //         s"User accessed a service targets",
    //         ctx.from,
    //         ctx.ua,
    //         Json.obj("serviceId" -> serviceId)
    //       )
    //     )
    //     Ok(JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}"))))
    //   }
    // }
  }

  def updateServiceTargets(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val actualTargets = JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}")))
        val newTargets = patchJson(body, actualTargets)
          .as[JsArray]
          .value
          .map(_.as[String])
          .map(s => s.split("://"))
          .map(arr => Target(scheme = arr(0), host = arr(1)))
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceAddTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets
            else
              desc.targets :+ tgt
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceDeleteTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "DELETE_SERVICE_TARGET",
          s"User deleted a service target",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets.filterNot(_ == tgt)
            else
              desc.targets
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
            env.env,
            ctx.user.getOrElse(ctx.apiKey.toJson),
            event,
            ctx.from,
            ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceLiveStats(serviceId: String) = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_LIVESTATS",
        s"User accessed a service descriptor livestats",
        ctx.from,
        ctx.ua,
        Json.obj("serviceId" -> serviceId)
      )
    )
    for {
      calls       <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
      dataIn      <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
      dataOut     <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
      rate        <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
      duration    <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
      overhead    <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
      dataInRate  <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
      dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
    } yield
      Ok(
        Json.obj(
          "calls"       -> calls,
          "dataIn"      -> dataIn,
          "dataOut"     -> dataOut,
          "rate"        -> rate,
          "duration"    -> duration,
          "overhead"    -> overhead,
          "dataInRate"  -> dataInRate,
          "dataOutRate" -> dataOutRate
        )
      )
  }

  def serviceHealth(serviceId: String) = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_SERVICE_HEALTH", "User accessed a service descriptor health", None, Json.obj("serviceId" -> serviceId), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: HealthCheckEvent) => e.toJson, options) {
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None => JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).leftf[Seq[HealthCheckEvent]]
        case Some(desc) => env.datastores.healthCheckDataStore.findAll(desc).fright[JsonApiError]
      }
    }
    // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    // val paginationPageSize: Int =
    //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    // val paginationPosition = (paginationPage - 1) * paginationPageSize
    // env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
    //   case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
    //   case Some(desc) => {
    //     Audit.send(
    //       AdminApiEvent(
    //         env.snowflakeGenerator.nextIdStr(),
    //         env.env,
    //         Some(ctx.apiKey),
    //         ctx.user,
    //         "ACCESS_SERVICE_HEALTH",
    //         s"User accessed a service descriptor helth",
    //         ctx.from,
    //         ctx.ua,
    //         Json.obj("serviceId" -> serviceId)
    //       )
    //     )
    //     env.datastores.healthCheckDataStore
    //       .findAll(desc)
    //       .map(evts => Ok(JsArray(evts.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))) // .map(_.toEnrichedJson))))
    //   }
    // }
  }

  def serviceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).map {
          case Some(template) => Ok(template.toJson)
          case None           => NotFound(Json.obj("error" -> "template not found"))
        }
      }
    }
  }

  def updateServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ErrorTemplate format")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_ERROR_TEMPLATE",
                  s"User updated an error template",
                  ctx.from,
                  ctx.ua,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def createServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> s"Bad ErrorTemplate format $e")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_ERROR_TEMPLATE",
                  s"User created an error template",
                  ctx.from,
                  ctx.ua,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def deleteServiceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).flatMap {
          case None => NotFound(Json.obj("error" -> "template not found")).asFuture
          case Some(errorTemplate) =>
            env.datastores.errorTemplateDataStore.delete(desc.id).map { _ =>
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "DELETE_ERROR_TEMPLATE",
                s"User deleted an error template",
                ctx.from,
                ctx.ua,
                errorTemplate.toJson
              )
              Audit.send(event)
              Ok(Json.obj("done" -> true))
            }
        }
      }
    }
  }

  /*s

  def createService() = ApiAction.async(parse.json) { ctx =>
    val rawBody = ctx.request.body.as[JsObject]
    env.datastores.globalConfigDataStore.findById("global").map(_.get).flatMap { globalConfig =>
      val body: JsObject = ((rawBody \ "id").asOpt[String] match {
        case None    => rawBody ++ Json.obj("id" -> IdGenerator.token(64))
        case Some(b) => rawBody
      }) ++ ((rawBody \ "groupId").asOpt[String] match {
        case None if globalConfig.autoLinkToDefaultGroup => rawBody ++ Json.obj("groupId" -> "default")
        case Some(b)                                     => rawBody
      })
      ServiceDescriptor.fromJsonSafe(body) match {
        case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
        case JsSuccess(desc, _) =>
          desc.save().map {
            case false => InternalServerError(Json.obj("error" -> "ServiceDescriptor not stored ..."))
            case true => {
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "CREATE_SERVICE",
                s"User created a service",
                ctx.from,
                ctx.ua,
                desc.toJson
              )
              Audit.send(event)
              Alerts.send(
                ServiceCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  ctx.user.getOrElse(ctx.apiKey.toJson),
                  event,
                  ctx.from,
                  ctx.ua)
              )
              ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).addServices(Seq(desc))
              Ok(desc.toJson)
            }
          }
      }
    }
  }

  def updateService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        ServiceDescriptor.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              ctx.ua,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            if (desc.groupId != newDesc.groupId) {
              env.datastores.apiKeyDataStore.clearFastLookupByService(newDesc.id)
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def patchService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        val currentDescJson = desc.toJson
        val newDescJson     = patchJson(body, currentDescJson)
        ServiceDescriptor.fromJsonSafe(newDescJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              ctx.ua,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            if (desc.groupId != newDesc.groupId) {
              env.datastores.apiKeyDataStore.clearFastLookupByService(newDesc.id)
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def deleteService(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        desc.delete().map { res =>
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE",
            s"User deleted a service",
            ctx.from,
            ctx.ua,
            desc.toJson
          )
          Audit.send(admEvt)
          Alerts.send(
            ServiceDeletedAlert(env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user.getOrElse(ctx.apiKey.toJson),
              admEvt,
              ctx.from,
              ctx.ua)
          )
          env.datastores.canaryDataStore.destroyCanarySession(desc.id)
          ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  def allServices() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition            = (paginationPage - 1) * paginationPageSize
    val _env: Option[String]          = ctx.request.queryString.get("env").flatMap(_.headOption)
    val group: Option[String]         = ctx.request.queryString.get("group").flatMap(_.headOption)
    val id: Option[String]            = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String]          = ctx.request.queryString.get("name").flatMap(_.headOption)
    val target: Option[String]        = ctx.request.queryString.get("target").flatMap(_.headOption)
    val exposedDomain: Option[String] = ctx.request.queryString.get("exposedDomain").flatMap(_.headOption)
    val domain: Option[String]        = ctx.request.queryString.get("domain").flatMap(_.headOption)
    val subdomain: Option[String]     = ctx.request.queryString.get("subdomain").flatMap(_.headOption)
    val hasFilters = _env
      .orElse(group)
      .orElse(id)
      .orElse(name)
      .orElse(target)
      .orElse(exposedDomain)
      .orElse(domain)
      .orElse(subdomain)
      .isDefined
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES",
        s"User accessed all service descriptors",
        ctx.from,
        ctx.ua,
        Json.obj(
          "env"   -> JsString(_env.getOrElse("--")),
          "group" -> JsString(_env.getOrElse("--"))
        )
      )
    )
    env.datastores.serviceDescriptorDataStore
      .streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize)
      .map { descs =>
        if (hasFilters) {
          Ok(
            JsArray(
              descs
                .filter {
                  case desc if _env.isDefined && desc.env == _env.get                                 => true
                  case desc if group.isDefined && desc.groupId == group.get                           => true
                  case desc if id.isDefined && desc.id == id.get                                      => true
                  case desc if name.isDefined && desc.name == name.get                                => true
                  case desc if target.isDefined && desc.targets.find(_.asUrl == target.get).isDefined => true
                  case desc if exposedDomain.isDefined && desc.exposedDomain == exposedDomain.get     => true
                  case desc if domain.isDefined && desc.domain == domain.get                          => true
                  case desc if subdomain.isDefined && desc.subdomain == subdomain.get                 => true
                  case _                                                                              => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(descs.map(_.toJson)))
        }
      }
  }

  def service(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE",
            s"User accessed a service descriptor",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        Ok(desc.toJson)
      }
    }
  }
   */
}