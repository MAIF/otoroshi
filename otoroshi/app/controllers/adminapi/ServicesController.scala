package controllers.adminapi

import otoroshi.actions.{ApiAction, ApiActionContext}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.{ErrorTemplate, ServiceDescriptor, ServiceDescriptorQuery, Target}
import otoroshi.utils.controllers.{AdminApiHelper, ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SendAuditAndAlert, SeqEntityAndContext}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents, RequestHeader}
import otoroshi.utils.json.JsonPatchHelpers.patchJson
import otoroshi.utils.syntax.implicits._
import play.api.libs.streams.Accumulator
import play.api.mvc.Results.Status

import scala.concurrent.{ExecutionContext, Future}

class ServicesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[ServiceDescriptor, JsValue] with CrudControllerHelper[ServiceDescriptor, JsValue] with AdminApiHelper {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val sourceBodyParser = BodyParser("ServicesController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  lazy val logger = Logger("otoroshi-services-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

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
  }

  def servicesForALine(line: String) = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_SERVICES_FOR_LINES", s"User accessed service list for line $line", None, Json.obj("line" -> line), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ServiceDescriptor) => e.toJson, options) {
      env.datastores.serviceDescriptorDataStore.findByEnv(line).map(_.filter(ctx.canUserRead)).fright[JsonApiError]
    }
  }

  def serviceTargets(serviceId: String) = ApiAction.async { ctx =>
    ctx.canReadService(serviceId) {
      val options = SendAuditAndAlert("ACCESS_SERVICE_TARGETS", "User accessed a service targets", None, Json.obj("serviceId" -> serviceId), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: String) => JsString(e), options) {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
          case None => JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).left[Seq[String]]
          case Some(desc) => desc.targets.map(t => s"${t.scheme}://${t.host}").right[JsonApiError]
        }
      }
    }
  }

  def updateServiceTargets(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
      case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
      case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
    ctx.canReadService(serviceId) {
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
        calls <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
        dataIn <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
        dataOut <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
        rate <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
        duration <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
        overhead <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
        dataInRate <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
        dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
      } yield
        Ok(
          Json.obj(
            "calls" -> calls,
            "dataIn" -> dataIn,
            "dataOut" -> dataOut,
            "rate" -> rate,
            "duration" -> duration,
            "overhead" -> overhead,
            "dataInRate" -> dataInRate,
            "dataOutRate" -> dataOutRate
          )
        )
    }
  }

  def serviceHealth(serviceId: String) = ApiAction.async { ctx =>
    ctx.canReadService(serviceId) {
      val options = SendAuditAndAlert("ACCESS_SERVICE_HEALTH", "User accessed a service descriptor health", None, Json.obj("serviceId" -> serviceId), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: HealthCheckEvent) => e.toJson, options) {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).leftf[Seq[HealthCheckEvent]]
          case Some(desc) => env.datastores.healthCheckDataStore.findAll(desc).fright[JsonApiError]
        }
      }
    }
  }

  def serviceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) if !ctx.canUserRead(desc)=> ctx.fforbidden
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).map {
          case Some(template) => Ok(template.toJson)
          case None           => NotFound(Json.obj("error" -> "template not found"))
        }
      }
    }
  }

  def updateServiceTemplate(serviceId: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val requestBody = Json.parse(bodyRaw.utf8String)
      val body: JsObject = (requestBody \ "serviceId").asOpt[String] match {
        case None => requestBody.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
        case Some(_) => requestBody.as[JsObject]
      }
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
  }

  def createServiceTemplate(serviceId: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val requestBody = Json.parse(bodyRaw.utf8String)
      val body: JsObject = (requestBody \ "serviceId").asOpt[String] match {
        case None => requestBody.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
        case Some(_) => requestBody.as[JsObject]
      }
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
  }

  def deleteServiceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
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
}