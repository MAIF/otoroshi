package otoroshi.api

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import otoroshi.actions.ApiAction
import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models.{NgRoute, NgRouteComposition, StoredNgBackend}
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.tcp.TcpService
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

case class ResourceVersion(name: String, served: Boolean, deprecated: Boolean, storage: Boolean, schema: Option[JsValue] = None)
case class Resource(kind: String, pluralName: String, singularName: String, group: String, version: ResourceVersion, access: ResourceAccessApi[_])
trait ResourceAccessApi[T <: EntityLocationSupport] {

  def format: Format[T]
  def key(id: String): String
  def extractId(value: T): String

  def validateToJson(json: JsValue): JsResult[JsValue] = {
    format.reads(json) match {
      case e: JsError => e
      case JsSuccess(value, path) => JsSuccess(value.json, path)
    }
  }

  def create(namespace: String, version: String, singularName: String, id: Option[String], body: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, JsValue]] = {
    val resId = id.getOrElse(s"${singularName}_${IdGenerator.uuid}")
    format.reads(body) match {
      case err @ JsError(_) => Left[JsValue, JsValue](JsError.toJson(err)).vfuture
      case JsSuccess(value, _) => {
        val idKey = "id"
        val finalBody = format.writes(value).asObject.deepMerge(Json.obj(idKey -> resId, "_version" -> version, "_loc" -> Json.obj("tenant" -> namespace)))
        env.datastores.rawDataStore
          .set(key(resId), finalBody.stringify.byteString, None)
          .map { _ =>
            Right(finalBody)
          }
      }
    }
  }

  def findAll(namespace: String, version: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    env.datastores.rawDataStore
      .allMatching(key("*"))
      .map { rawItems =>
        rawItems.map { bytestring =>
          val json = bytestring.utf8String.parseJson
          format.reads(json)
        }
        .collect {
          case JsSuccess(value, _) => value
        }
        .filter { entity =>
          if (namespace == "any") true
          else if (namespace == "all") true
          else if (namespace == "*") true
          else entity.location.tenant.value == namespace
        }
        .map { entity =>
          entity.json
        }
      }
  }

  def deleteAll(namespace: String, version: String, canWrite: JsValue => Boolean)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore
      .allMatching(key("*"))
      .flatMap { rawItems =>
        val keys = rawItems.map { bytestring =>
          val json = bytestring.utf8String.parseJson
          format.reads(json)
        }
        .collect {
          case JsSuccess(value, _) => value
        }
        .filter { entity =>
          if (namespace == "any") true
          else if (namespace == "all") true
          else if (namespace == "*") true
          else entity.location.tenant.value == namespace
        }
        .filter(e => canWrite(e.json))
        .map { entity =>
          key(entity.theId)
        }
        env.datastores.rawDataStore.del(keys)
      }.map(_ => ())
  }

  def deleteOne(namespace: String, version: String, id: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore
      .get(key(id))
      .flatMap {
        case Some(rawItem) =>
          val json = rawItem.utf8String.parseJson
          format.reads(json) match {
            case JsSuccess(entity, _) if namespace == "any" || namespace == "all" || namespace == "*" || entity.location.tenant.value == namespace => {
              val k = key(entity.theId)
              env.datastores.rawDataStore.del(Seq(k)).map(_ => ())
            }
            case _ => ().vfuture
          }
        case None => ().vfuture
      }
  }

  def findOne(namespace: String, version: String, id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] = {
    env.datastores.rawDataStore.get(id)
      .map {
        case None => None
        case Some(item) =>
          val json = item.utf8String.parseJson
          format.reads(json) match {
            case JsSuccess(entity, _) if namespace == "any" || namespace == "all" || namespace == "*" || entity.location.tenant.value == namespace => entity.json.some
            case _ => None
          }
      }
  }
}

case class GenericResourceAccessApi[T <: EntityLocationSupport](format: Format[T], keyf: String => String, extractIdf: T => String) extends ResourceAccessApi[T] {
  override def key(id: String): String = keyf.apply(id)
  override def extractId(value: T): String = value.theId
}

// TODO: handle AdminApiEvent
class GenericApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  private val sourceBodyParser = BodyParser("GenericApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  private implicit val ec = env.otoroshiExecutionContext

  private implicit val mat = env.otoroshiMaterializer

  private val resources = Seq(
    Resource("Route", "routes", "route", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[NgRoute](NgRoute.fmt, env.datastores.routeDataStore.key, env.datastores.routeDataStore.extractId)),
    Resource("Backend", "backends", "backend", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[StoredNgBackend](StoredNgBackend.format, env.datastores.backendsDataStore.key, env.datastores.backendsDataStore.extractId)),
    Resource("RouteComposition", "route-compositions", "route-composition", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[NgRouteComposition](NgRouteComposition.fmt, env.datastores.routeCompositionDataStore.key, env.datastores.routeCompositionDataStore.extractId)),
    Resource("ServiceDescriptor", "service-descriptors", "service-descriptor", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[ServiceDescriptor](ServiceDescriptor._fmt, env.datastores.serviceDescriptorDataStore.key, env.datastores.serviceDescriptorDataStore.extractId)),
    Resource("TcpService", "tcp-services", "tcp-service", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[TcpService](TcpService.fmt, env.datastores.tcpServiceDataStore.key, env.datastores.tcpServiceDataStore.extractId)),
    Resource("ErrorTemplate", "error-templates", "error-templates", "proxy.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[ErrorTemplate](ErrorTemplate.fmt, env.datastores.errorTemplateDataStore.key, env.datastores.errorTemplateDataStore.extractId)),
    //////
    Resource("Apikey", "apikeys", "apikey", "apim.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[ApiKey](ApiKey._fmt, env.datastores.apiKeyDataStore.key, env.datastores.apiKeyDataStore.extractId)),
    //////
    Resource("Certificate", "certificates", "certificate", "pki.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[Cert](Cert._fmt, env.datastores.certificatesDataStore.key, env.datastores.certificatesDataStore.extractId)),
    //////
    Resource("JwtVerifier", "jwt-verifiers", "jwt-verifier", "security.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[GlobalJwtVerifier](GlobalJwtVerifier._fmt, env.datastores.globalJwtVerifierDataStore.key, env.datastores.globalJwtVerifierDataStore.extractId)),
    Resource("AuthModule", "auth-modules", "auth-module", "security.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[AuthModuleConfig](AuthModuleConfig._fmt, env.datastores.authConfigsDataStore.key, env.datastores.authConfigsDataStore.extractId)),
    //////
    Resource("ServiceGroup", "service-groups", "service-group", "organize.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[ServiceGroup](ServiceGroup._fmt, env.datastores.serviceGroupDataStore.key, env.datastores.serviceGroupDataStore.extractId)),
    Resource("Organization", "organizations", "organization", "organize.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[Tenant](Tenant.format, env.datastores.tenantDataStore.key, env.datastores.tenantDataStore.extractId)),
    Resource("Team", "teams", "team", "organize.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[Team](Team.format, env.datastores.teamDataStore.key, env.datastores.teamDataStore.extractId)),
    //////
    Resource("DateExporter", "data-exporters", "data-exporter", "events.otoroshi.io", ResourceVersion("v1", true, false, true), GenericResourceAccessApi[DataExporterConfig](DataExporterConfig.format, env.datastores.dataExporterConfigDataStore.key, env.datastores.dataExporterConfigDataStore.extractId)),
  )

  private def notFoundBody: JsValue = Json.obj("error" -> "not_found", "error_description" -> "resource not found")

  private def extractId(entity: JsValue): String = entity.select("id").asOpt[String].getOrElse(entity.select("clientId").asString)

  private def bodyIn(request: Request[AnyContent]): Either[JsValue, JsValue] = {
    request.body.asText match {
      case Some(body) if request.contentType.contains("application/yaml") => Yaml.parse(body) match {
        case None => Left(Json.obj("error" -> "bad_request", "error_description" -> "error while parsing yaml"))
        case Some(yml) => Right(yml)
      }
      case Some(body) if request.contentType.contains("application/json") => Right(Json.parse(body))
      case _ => Left(Json.obj("error" -> "bad_request", "error_description" -> "bad content type"))
    }
  }

  private def result(res: Results.Status, _entity: JsValue, request: RequestHeader): Result = {
    // TODO: handle field extraction
    // TODO: handle sort
    // TODO: handle pagination
    // TODO: handle filtering
    val entity = _entity
    entity match {
      case JsArray(seq) if !request.accepts("application/json") && request.accepts("application/x-ndjson") => {
        res.sendEntity(
          HttpEntity.Streamed(
            data = Source(seq.toList.map(_.stringify.byteString)),
            contentLength = None,
            contentType = "application/x-ndjson".some
          )
        )
      }
      case _ if !request.accepts("application/json") && request.accepts("application/yaml") =>
        res(Yaml.write(entity)).as("application/yaml")
      case _ =>
        res(entity)
    }
  }

  private def withResource(group: String, version: String, entity: String, request: RequestHeader)(f: Resource => Future[Result]): Future[Result] = {
    resources
      .filter(_.version.served)
      .find(r => (group == "any" || r.group == group) && (version == "any" || r.version.name == version) && r.pluralName == entity) match {
        case None => result(Results.NotFound, notFoundBody, request).vfuture
        case Some(resource) => f(resource)
      }
  }

  // PATCH /apis/:group/:version/namespaces/:namespace/:entity/_bulk
  def bulkPatch(group: String, version: String, namespace: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    import otoroshi.utils.json.JsonPatchHelpers.patchJson
    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => withResource(group, version, entity, ctx.request) { resource =>
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .mapAsync(1) { e =>
            resource.access.findOne(namespace, version, extractId(e)).map(ee => (e.select("patch").asValue, ee))
          }
          .map {
            case (e, None) => Left((Json.obj("error" -> "entity not found"), e))
            case (_, Some(e)) => Right(("--", e))
          }
          .filter {
            case Left(_) => true
            case Right((_, entity)) => ctx.canUserWriteJson(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((patchBody, entity)) => {
              val patchedEntity =  patchJson(Json.parse(patchBody), entity)
              resource.access.create(namespace, version, resource.singularName, extractId(patchedEntity).some, patchedEntity).map {
                case Left(error) =>
                  error.stringify.byteString
                case Right(createdEntity) =>
                  Json.obj("status" -> 201, "created" -> true, "id" -> extractId(createdEntity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _ => result(Results.BadRequest, Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"), ctx.request).vfuture
    }
  }

  // POST /apis/:group/:version/namespaces/:namespace/:entity/_bulk
  def bulkCreate(group: String, version: String, namespace: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => withResource(group, version, entity, ctx.request) { resource =>
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .map(e =>
            resource.access.format.reads(e) match {
              case JsError(err) => Left((JsError.toJson(err), e))
              case JsSuccess(_, _) => Right(("--", e))
            }
          )
          .filter {
            case Left(_) => true
            case Right((_, entity)) => ctx.canUserWriteJson(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_, entity)) => {
              resource.access.create(namespace, version, resource.singularName, None, entity).map {
                case Left(error) =>
                  error.stringify.byteString
                case Right(createdEntity) =>
                  Json.obj("status" -> 201, "created" -> true, "id" -> extractId(createdEntity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _ => result(Results.BadRequest, Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"), ctx.request).vfuture
    }
  }

  // PUT /apis/:group/:version/namespaces/:namespace/:entity/_bulk
  def bulkUpdate(group: String, version: String, namespace: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => withResource(group, version, entity, ctx.request) { resource =>
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .map(e =>
            resource.access.format.reads(e) match {
              case JsError(err) => Left((JsError.toJson(err), e))
              case JsSuccess(_, _) => Right(("--", e))
            }
          )
          .filter {
            case Left(_) => true
            case Right((_, entity)) => ctx.canUserWriteJson(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_, entity)) => {
              resource.access.create(namespace, version, resource.singularName, extractId(entity).some, entity).map {
                case Left(error) =>
                  error.stringify.byteString
                case Right(createdEntity) =>
                  Json.obj("status" -> 201, "created" -> true, "id" -> extractId(createdEntity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _ => result(Results.BadRequest, Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"), ctx.request).vfuture
    }
  }

  // DELETE /apis/:group/:version/namespaces/:namespace/:entity/_bulk
  def bulkDelete(group: String, version: String, namespace: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => withResource(group, version, entity, ctx.request) { resource =>
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .mapAsync(1) { e =>
            resource.access.findOne(namespace, version, extractId(e)).map(ee => (e, ee))
          }
          .map {
            case (e, None) => Left((Json.obj("error" -> "entity not found"), e))
            case (_, Some(e)) => Right(("--", e))
          }
          .filter {
            case Left(_) => true
            case Right((_, entity)) => ctx.canUserWriteJson(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_, entity)) => {
              resource.access.deleteOne(namespace, version, extractId(entity)).map { _ =>
                  Json.obj("status" -> 201, "created" -> true, "id" -> extractId(entity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _ => result(Results.BadRequest, Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"), ctx.request).vfuture
    }
  }

  // GET /apis/:group/:version/namespaces/:namespace/:entity/_count
  def countAll(group: String, version: String, namespace: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findAll(namespace, version).map { entities =>
        result(Results.Ok, Json.obj("count" -> entities.count(e => ctx.canUserReadJson(e))), ctx.request)
      }
    }
  }

  // GET /apis/:group/:version/namespaces/:namespace/:entity
  def findAll(group: String, version: String, namespace: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findAll(namespace, version).map { entities =>
        result(Results.Ok, JsArray(entities.filter(e => ctx.canUserReadJson(e))), ctx.request)
      }
    }
  }

  // POST /apis/:group/:version/namespaces/:namespace/:entity
  def create(group: String, version: String, namespace: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) match {
        case Left(err) => result(Results.BadRequest, err, ctx.request).vfuture
        case Right(body) if !ctx.canUserWriteJson(body) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request).vfuture
        case Right(body) => {
          resource.access.create(namespace, version, resource.singularName, None, body).map {
            case Left(err) => result(Results.InternalServerError, err, ctx.request)
            case Right(res) => result(Results.Created, res, ctx.request)
          }
        }
      }
    }
  }

  // DELETE /apis/:group/:version/namespaces/:namespace/:entity
  def deleteAll(group: String, version: String, namespace: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.deleteAll(namespace, version, e => ctx.canUserWriteJson(e)).map { _ =>
        NoContent
      }
    }
  }

  // GET /apis/:group/:version/namespaces/:namespace/:entity/:id
  def findOne(group: String, version: String, namespace: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findOne(namespace, version, id).map {
        case None => result(Results.NotFound, notFoundBody, ctx.request)
        case Some(entity) if !ctx.canUserReadJson(entity) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request)
        case Some(entity) => result(Results.Ok, entity, ctx.request)
      }
    }
  }

  // DELETE /apis/:group/:version/namespaces/:namespace/:entity/:id
  def delete(group: String, version: String, namespace: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findOne(namespace, version, id).flatMap {
        case None => result(Results.NotFound, notFoundBody, ctx.request).vfuture
        case Some(entity) if !ctx.canUserWriteJson(entity) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request).vfuture
        case Some(entity) => resource.access.deleteOne(namespace, version, id).map { _ =>
          result(Results.Ok, entity, ctx.request)
        }
      }
    }
  }

  // POST /apis/:group/:version/namespaces/:namespace/:entity/:id
  def upsert(group: String, version: String, namespace: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) match {
        case Left(err) => result(Results.BadRequest, err, ctx.request).vfuture
        case Right(_body) => {
          resource.access.validateToJson(_body) match {
            case err @ JsError(_) => result(Results.BadRequest, JsError.toJson(err), ctx.request).vfuture
            case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request).vfuture
            case JsSuccess(body, _) => {
              resource.access.findOne(namespace, version, id).flatMap {
                case None => resource.access.create(namespace, version, resource.singularName, None, body).map {
                  case Left(err) => result(Results.InternalServerError, err, ctx.request)
                  case Right(res) => result(Results.Created, res, ctx.request)
                }
                case Some(_) => resource.access.create(namespace, version, resource.singularName, id.some, body).map {
                  case Left(err) => result(Results.InternalServerError, err, ctx.request)
                  case Right(res) => result(Results.Created, res, ctx.request)
                }
              }
            }
          }
        }
      }
    }
  }

  // PUT /apis/:group/:version/namespaces/:namespace/:entity/:id
  def update(group: String, version: String, namespace: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) match {
        case Left(err) => result(Results.BadRequest, err, ctx.request).vfuture
        case Right(_body) => {
          resource.access.validateToJson(_body) match {
            case err@JsError(_) => result(Results.BadRequest, JsError.toJson(err), ctx.request).vfuture
            case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request).vfuture
            case JsSuccess(body, _) => {
              resource.access.findOne(namespace, version, id).flatMap {
                case None => result(Results.NotFound, notFoundBody, ctx.request).vfuture
                case Some(_) => resource.access.create(namespace, version, resource.singularName, id.some, body).map {
                  case Left(err) => result(Results.InternalServerError, err, ctx.request)
                  case Right(res) => result(Results.Created, res, ctx.request)
                }
              }
            }
          }
        }
      }
    }
  }

  // PATCH /apis/:group/:version/namespaces/:namespace/:entity/:id
  def patch(group: String, version: String, namespace: String, entity: String, id: String) = ApiAction.async { ctx =>
    import otoroshi.utils.json.JsonPatchHelpers.patchJson
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) match {
        case Left(err) => result(Results.BadRequest, err, ctx.request).vfuture
        case Right(body) => {
          resource.access.findOne(namespace, version, id).flatMap {
            case None => result(Results.NotFound, notFoundBody, ctx.request).vfuture
            case Some(current) if !ctx.canUserWriteJson(current) => result(Results.Unauthorized, Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"), ctx.request).vfuture
            case Some(current) => {
              val patchedBody = patchJson(body, current)
              resource.access.create(namespace, version, resource.singularName, id.some, patchedBody).map {
                case Left(err) => result(Results.InternalServerError, err, ctx.request)
                case Right(res) => result(Results.Created, res, ctx.request)
              }
            }
          }
        }
      }
    }
  }
}