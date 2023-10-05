package otoroshi.api

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit}
import otoroshi.models._
import otoroshi.next.models.{NgRoute, NgRouteComposition, StoredNgBackend}
import otoroshi.script.Script
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.tcp.TcpService
import otoroshi.utils.{JsonPathValidator, JsonValidator}
import otoroshi.utils.controllers.GenericAlert
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

case class TweakedGlobalConfig(config: GlobalConfig) extends EntityLocationSupport {
  override def location: EntityLocation         = EntityLocation.default
  override def internalId: String               = config.internalId
  override def json: JsValue                    = config.json
  override def theName: String                  = config.theName
  override def theDescription: String           = config.theDescription
  override def theTags: Seq[String]             = config.theTags
  override def theMetadata: Map[String, String] = config.theMetadata
}

object TweakedGlobalConfig {
  val fmt = new Format[TweakedGlobalConfig] {
    override def writes(o: TweakedGlobalConfig): JsValue             = o.config.json
    override def reads(json: JsValue): JsResult[TweakedGlobalConfig] =
      GlobalConfig._fmt.reads(json).map(c => TweakedGlobalConfig(c))
  }
}

case class ResourceVersion(
    name: String,
    served: Boolean,
    deprecated: Boolean,
    storage: Boolean,
    schema: Option[JsValue] = None
)                                                   {
  def json: JsValue = Json.obj(
    "name"       -> name,
    "served"     -> served,
    "deprecated" -> deprecated,
    "storage"    -> storage
  )
}
case class Resource(
    kind: String,
    pluralName: String,
    singularName: String,
    group: String,
    version: ResourceVersion,
    access: ResourceAccessApi[_]
)                                                   {
  def json: JsValue = Json.obj(
    "kind"          -> kind,
    "plural_name"   -> pluralName,
    "singular_name" -> singularName,
    "group"         -> group,
    "version"       -> version.json
  )
}
trait ResourceAccessApi[T <: EntityLocationSupport] {

  def format: Format[T]
  def key(id: String): String
  def extractId(value: T): String
  def template(version: String): JsValue = Json.obj()

  def canRead: Boolean
  def canCreate: Boolean
  def canUpdate: Boolean
  def canDelete: Boolean
  def canBulk: Boolean

  def validateToJson(json: JsValue, singularName: String, f: => Either[String, Option[BackOfficeUser]])(implicit
      env: Env
  ): JsResult[JsValue] = {
    def readEntity(): JsResult[JsValue] = format.reads(json) match {
      case e: JsError             => e
      case JsSuccess(value, path) => JsSuccess(value.json, path)
    }
    f match {
      case Left(err)         => JsError(err)
      case Right(None)       => readEntity()
      case Right(Some(user)) => {
        val envValidators: Seq[JsonValidator]  =
          env.adminEntityValidators.getOrElse("all", Seq.empty[JsonValidator]) ++ env.adminEntityValidators
            .getOrElse(singularName.toLowerCase, Seq.empty[JsonValidator])
        val userValidators: Seq[JsonValidator] =
          user.adminEntityValidators.getOrElse("all", Seq.empty[JsonValidator]) ++ user.adminEntityValidators
            .getOrElse(singularName.toLowerCase, Seq.empty[JsonValidator])
        val validators                         = envValidators ++ userValidators
        val failedValidators                   = validators.filterNot(_.validate(json))
        if (failedValidators.isEmpty) {
          readEntity()
        } else {
          val errors = failedValidators.flatMap(_.error)
          if (errors.isEmpty) {
            JsError("entity validation failed")
          } else {
            JsError(errors.mkString(". "))
          }
        }
      }
    }
  }

  def create(version: String, singularName: String, id: Option[String], body: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsValue]] = {
    val dev   = if (env.isDev) "_dev" else ""
    val resId = id
      .orElse(body.select("client_id").asOpt[String])
      .orElse(body.select("clientId").asOpt[String])
      .orElse(body.select("id").asOpt[String])
      .getOrElse(s"${singularName}${dev}_${IdGenerator.uuid}")
    format.reads(body) match {
      case err @ JsError(_)    => Left[JsValue, JsValue](JsError.toJson(err)).vfuture
      case JsSuccess(value, _) => {
        val idKey     = "id"
        val updateKey = if (id.isDefined) "updated_at" else "created_at"
        val finalBody = format
          .writes(value)
          .asObject
          .deepMerge(
            Json.obj(
              idKey      -> resId,
              "metadata" -> Json.obj(updateKey -> DateTime.now().toString())
            )
          )
        env.datastores.rawDataStore
          .set(key(resId), finalBody.stringify.byteString, None)
          .map { _ =>
            Right(finalBody)
          }
      }
    }
  }

  def findAll(version: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    env.datastores.rawDataStore
      .allMatching(key("*"))
      .map { rawItems =>
        rawItems
          .map { bytestring =>
            val json = bytestring.utf8String.parseJson
            format.reads(json)
          }
          .collect { case JsSuccess(value, _) =>
            value
          }
          // .filter { entity =>
          //   if (namespace == "any") true
          //   else if (namespace == "all") true
          //   else if (namespace == "*") true
          //   else entity.location.tenant.value == namespace
          // }
          .map { entity =>
            entity.json
          }
      }
  }

  def deleteAll(version: String, canWrite: JsValue => Boolean)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    env.datastores.rawDataStore
      .allMatching(key("*"))
      .flatMap { rawItems =>
        val keys = rawItems
          .map { bytestring =>
            val json = bytestring.utf8String.parseJson
            format.reads(json)
          }
          .collect { case JsSuccess(value, _) =>
            value
          }
          // .filter { entity =>
          //   if (namespace == "any") true
          //   else if (namespace == "all") true
          //   else if (namespace == "*") true
          //   else entity.location.tenant.value == namespace
          // }
          .filter(e => canWrite(e.json))
          .map { entity =>
            key(entity.theId)
          }
        env.datastores.rawDataStore.del(keys)
      }
      .map(_ => ())
  }

  def deleteOne(version: String, id: String)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    env.datastores.rawDataStore
      .get(key(id))
      .flatMap {
        case Some(rawItem) =>
          val json = rawItem.utf8String.parseJson
          format.reads(json) match {
            case JsSuccess(entity, _) => {
              //  if namespace == "any" || namespace == "all" || namespace == "*" || entity.location.tenant.value == namespace => {
              val k = key(entity.theId)
              env.datastores.rawDataStore.del(Seq(k)).map(_ => ())
            }
            case _                    => ().vfuture
          }
        case None          => ().vfuture
      }
  }

  def deleteMany(version: String, ids: Seq[String])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    if (ids.nonEmpty) {
      env.datastores.rawDataStore.del(ids.map(id => key(id))).map(_ => ())
    } else {
      ().vfuture
    }
  }

  def findOne(version: String, id: String)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[JsValue]] = {
    env.datastores.rawDataStore
      .get(key(id))
      .map {
        case None       => None
        case Some(item) =>
          val json = item.utf8String.parseJson
          format.reads(json) match {
            case JsSuccess(entity, _) =>
              //if namespace == "any" || namespace == "all" || namespace == "*" || entity.location.tenant.value == namespace =>
              entity.json.some
            case _                    => None
          }
      }
  }

  def allJson(): Seq[JsValue]                = all().map(_.json)
  def oneJson(id: String): Option[JsValue]   = one(id).map(_.json)
  def updateJson(values: Seq[JsValue]): Unit = update(
    values.map(v => format.reads(v)).collect { case JsSuccess(v, _) => v }
  )
  def all(): Seq[T]
  def one(id: String): Option[T]
  def update(values: Seq[T]): Unit
}

case class GenericResourceAccessApi[T <: EntityLocationSupport](
    format: Format[T],
    keyf: String => String,
    extractIdf: T => String,
    tmpl: () => JsValue = () => Json.obj(),
    canRead: Boolean = true,
    canCreate: Boolean = true,
    canUpdate: Boolean = true,
    canDelete: Boolean = true,
    canBulk: Boolean = true
) extends ResourceAccessApi[T] {
  override def key(id: String): String            = keyf.apply(id)
  override def extractId(value: T): String        = value.theId
  override def template(version: String): JsValue = tmpl()
  override def all(): Seq[T]                      = throw new UnsupportedOperationException()
  override def one(id: String): Option[T]         = throw new UnsupportedOperationException()
  override def update(values: Seq[T]): Unit       = throw new UnsupportedOperationException()
}

case class GenericResourceAccessApiWithState[T <: EntityLocationSupport](
    format: Format[T],
    keyf: String => String,
    extractIdf: T => String,
    tmpl: () => JsValue = () => Json.obj(),
    canRead: Boolean = true,
    canCreate: Boolean = true,
    canUpdate: Boolean = true,
    canDelete: Boolean = true,
    canBulk: Boolean = true,
    stateAll: () => Seq[T],
    stateOne: (String) => Option[T],
    stateUpdate: (Seq[T]) => Unit
) extends ResourceAccessApi[T] {
  override def key(id: String): String            = keyf.apply(id)
  override def extractId(value: T): String        = value.theId
  override def template(version: String): JsValue = tmpl()
  override def all(): Seq[T]                      = stateAll()
  override def one(id: String): Option[T]         = stateOne(id)
  override def update(values: Seq[T]): Unit       = stateUpdate(values)
}

class OtoroshiResources(env: Env) {
  lazy val resources = Seq(
    ///////
    Resource(
      "Route",
      "routes",
      "route",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[NgRoute](
        NgRoute.fmt,
        env.datastores.routeDataStore.key,
        env.datastores.routeDataStore.extractId
      )
    ),
    Resource(
      "Backend",
      "backends",
      "backend",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[StoredNgBackend](
        StoredNgBackend.format,
        env.datastores.backendsDataStore.key,
        env.datastores.backendsDataStore.extractId
      )
    ),
    Resource(
      "RouteComposition",
      "route-compositions",
      "route-composition",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[NgRouteComposition](
        NgRouteComposition.fmt,
        env.datastores.routeCompositionDataStore.key,
        env.datastores.routeCompositionDataStore.extractId
      )
    ),
    Resource(
      "ServiceDescriptor",
      "service-descriptors",
      "service-descriptor",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[ServiceDescriptor](
        ServiceDescriptor._fmt,
        env.datastores.serviceDescriptorDataStore.key,
        env.datastores.serviceDescriptorDataStore.extractId
      )
    ),
    Resource(
      "TcpService",
      "tcp-services",
      "tcp-service",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[TcpService](
        TcpService.fmt,
        env.datastores.tcpServiceDataStore.key,
        env.datastores.tcpServiceDataStore.extractId
      )
    ),
    Resource(
      "ErrorTemplate",
      "error-templates",
      "error-templates",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[ErrorTemplate](
        ErrorTemplate.fmt,
        env.datastores.errorTemplateDataStore.key,
        env.datastores.errorTemplateDataStore.extractId
      )
    ),
    //////
    Resource(
      "Apikey",
      "apikeys",
      "apikey",
      "apim.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[ApiKey](
        ApiKey._fmt,
        env.datastores.apiKeyDataStore.key,
        env.datastores.apiKeyDataStore.extractId
      )
    ),
    //////
    Resource(
      "Certificate",
      "certificates",
      "certificate",
      "pki.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[Cert](
        Cert._fmt,
        env.datastores.certificatesDataStore.key,
        env.datastores.certificatesDataStore.extractId
      )
    ),
    //////
    Resource(
      "JwtVerifier",
      "jwt-verifiers",
      "jwt-verifier",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[GlobalJwtVerifier](
        GlobalJwtVerifier._fmt,
        env.datastores.globalJwtVerifierDataStore.key,
        env.datastores.globalJwtVerifierDataStore.extractId
      )
    ),
    Resource(
      "AuthModule",
      "auth-modules",
      "auth-module",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[AuthModuleConfig](
        AuthModuleConfig._fmt(env),
        env.datastores.authConfigsDataStore.key,
        env.datastores.authConfigsDataStore.extractId
      )
    ),
    Resource(
      "AdminSession",
      "admin-sessions",
      "admin-session",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[BackOfficeUser](
        BackOfficeUser.fmt,
        env.datastores.backOfficeUserDataStore.key,
        env.datastores.backOfficeUserDataStore.extractId,
        canCreate = false,
        canUpdate = false
      )
    ),
    Resource(
      "SimpleAdminUser",
      "simple-admin-users",
      "simple-admin-user",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[SimpleOtoroshiAdmin](
        SimpleOtoroshiAdmin.fmt,
        env.datastores.simpleAdminDataStore.key,
        env.datastores.simpleAdminDataStore.extractId,
        canCreate = true,
        canUpdate = false,
        canDelete = true
      )
    ),
    Resource(
      "AuthModuleUser",
      "auth-module-users",
      "auth-module-user",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[PrivateAppsUser](
        PrivateAppsUser.fmt,
        env.datastores.privateAppsUserDataStore.key,
        env.datastores.privateAppsUserDataStore.extractId,
        canCreate = false,
        canUpdate = false
      )
    ),
    //////
    Resource(
      "ServiceGroup",
      "service-groups",
      "service-group",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[ServiceGroup](
        ServiceGroup._fmt,
        env.datastores.serviceGroupDataStore.key,
        env.datastores.serviceGroupDataStore.extractId
      )
    ),
    Resource(
      "Organization",
      "organizations",
      "organization",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[Tenant](
        Tenant.format,
        env.datastores.tenantDataStore.key,
        env.datastores.tenantDataStore.extractId
      )
    ),
    Resource(
      "Tenant",
      "tenants",
      "tenant",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[Tenant](
        Tenant.format,
        env.datastores.tenantDataStore.key,
        env.datastores.tenantDataStore.extractId
      )
    ),
    Resource(
      "Team",
      "teams",
      "team",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[Team](
        Team.format,
        env.datastores.teamDataStore.key,
        env.datastores.teamDataStore.extractId
      )
    ),
    //////
    Resource(
      "DateExporter",
      "data-exporters",
      "data-exporter",
      "events.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[DataExporterConfig](
        DataExporterConfig.format,
        env.datastores.dataExporterConfigDataStore.key,
        env.datastores.dataExporterConfigDataStore.extractId
      )
    ),
    //////
    Resource(
      "Script",
      "scripts",
      "script",
      "plugins.otoroshi.io",
      ResourceVersion("v1", served = true, deprecated = true, storage = true),
      GenericResourceAccessApi[Script](
        Script._fmt,
        env.datastores.scriptDataStore.key,
        env.datastores.scriptDataStore.extractId
      )
    ),
    Resource(
      "WasmPlugin",
      "wasm-plugins",
      "wasm-plugin",
      "plugins.otoroshi.io",
      ResourceVersion("v1", served = true, deprecated = true, storage = true),
      GenericResourceAccessApi[WasmPlugin](
        WasmPlugin.format,
        env.datastores.wasmPluginsDataStore.key,
        env.datastores.wasmPluginsDataStore.extractId,
        () => env.datastores.wasmPluginsDataStore.template(env).json
      )
    ),
    //////
    Resource(
      "GlobalConfig",
      "global-configs",
      "global-config",
      "config.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApi[TweakedGlobalConfig](
        TweakedGlobalConfig.fmt,
        id => env.datastores.globalConfigDataStore.key(id),
        c => env.datastores.globalConfigDataStore.extractId(c.config),
        canCreate = false,
        canDelete = false,
        canBulk = false
      )
    )
  ) ++ env.adminExtensions.resources()
}

class GenericApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  private val sourceBodyParser = BodyParser("GenericApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  private implicit val ec = env.otoroshiExecutionContext

  private implicit val mat = env.otoroshiMaterializer

  private def filterPrefix: Option[String] = "filter.".some

  private def adminApiEvent(
      ctx: ApiActionContext[_],
      action: String,
      message: String,
      meta: JsValue,
      alert: Option[String]
  )(implicit env: Env): Unit = {
    val event: AdminApiEvent = AdminApiEvent(
      env.snowflakeGenerator.nextIdStr(),
      env.env,
      Some(ctx.apiKey),
      ctx.user,
      action,
      message,
      ctx.from,
      ctx.ua,
      meta
    )
    Audit.send(event)
    alert.foreach { a =>
      Alerts.send(
        GenericAlert(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user.getOrElse(ctx.apiKey.toJson),
          a,
          event,
          ctx.from,
          ctx.ua
        )
      )
    }
  }

  private def notFoundBody: JsValue = Json.obj("error" -> "not_found", "error_description" -> "resource not found")

  private def extractIdSafe(entity: JsValue): Option[String] = {
    entity
      .select("client_id")
      .asOpt[String]
      .orElse(entity.select("clientId").asOpt[String])
      .orElse(entity.select("id").asOpt[String])
  }

  private def extractId(entity: JsValue): String = extractIdSafe(entity).get

  private def bodyIn(request: Request[Source[ByteString, _]]): Future[Either[JsValue, JsValue]] = {
    Option(request.body) match {
      case Some(body) if request.contentType.contains("application/yaml") => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          // TODO: read as k8s resource too
          Yaml.parse(bodyRaw.utf8String) match {
            case None      => Left(Json.obj("error" -> "bad_request", "error_description" -> "error while parsing yaml"))
            case Some(yml) => Right(yml)
          }
        }
      }
      case Some(body) if request.contentType.contains("application/json") => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          Right(Json.parse(bodyRaw.utf8String))
        }
      }
      case _                                                              => Left(Json.obj("error" -> "bad_request", "error_description" -> "bad content type")).vfuture
    }
  }

  private def filterEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    _entity match {
      case arr @ JsArray(_) => {
        val prefix     = filterPrefix
        val filters    = request.queryString
          .mapValues(_.last)
          .collect {
            case v if prefix.isEmpty                                  => v
            case v if prefix.isDefined && v._1.startsWith(prefix.get) => (v._1.replace(prefix.get, ""), v._2)
          }
          .filterNot(a => a._1 == "page" || a._1 == "pageSize" || a._1 == "fields")
        val filtered   = request
          .getQueryString("filtered")
          .map(
            _.split(",")
              .map(r => {
                val field = r.split(":")
                (field.head, field.last)
              })
              .toSeq
          )
          .getOrElse(Seq.empty[(String, String)])
        val hasFilters = filters.nonEmpty
        if (hasFilters) {
          val reducedItems  = if (hasFilters) {
            val items: Seq[JsValue] = arr.value.filter { elem =>
              filters.forall { case (key, value) =>
                (elem \ key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
            }
            items
          } else {
            arr.value
          }
          val filteredItems = if (filtered.nonEmpty) {
            val items: Seq[JsValue] = reducedItems.filter { elem =>
              filtered.forall { case (key, value) =>
                JsonOperationsHelper.getValueAtPath(key.toLowerCase(), elem)._2.asOpt[JsValue] match {
                  case Some(v) =>
                    v match {
                      case JsString(v)              => v.toLowerCase().indexOf(value) != -1
                      case JsBoolean(v)             => v == value.toBoolean
                      case JsNumber(v)              => v.toDouble == value.toDouble
                      case JsArray(values)          => values.contains(JsString(value))
                      case JsObject(v) if v.isEmpty =>
                        JsonOperationsHelper.getValueAtPath(key, elem)._2.asOpt[JsValue] match {
                          case Some(v) =>
                            v match {
                              case JsString(v)     => v.toLowerCase().indexOf(value) != -1
                              case JsBoolean(v)    => v == value.toBoolean
                              case JsNumber(v)     => v.toDouble == value.toDouble
                              case JsArray(values) => values.contains(JsString(value))
                              case _               => false
                            }
                          case _       => false
                        }
                      case _                        => false
                    }
                  case _       =>
                    false
                }
              }
            }
            items
          } else {
            reducedItems
          }
          JsArray(filteredItems).some
        } else {
          arr.some
        }
      }
      case _                => _entity.some
    }
  }

  private def sortEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    _entity match {
      case arr @ JsArray(_) => {
        val sorted    = request
          .getQueryString("sorted")
          .map(
            _.split(",")
              .map(r => {
                val field = r.split(":")
                (field.head, field.last.toBoolean)
              })
              .toSeq
          )
          .getOrElse(Seq.empty[(String, Boolean)])
        val hasSorted = sorted.nonEmpty
        if (hasSorted) {
          JsArray(sorted.foldLeft(arr.value) {
            case (sortedArray, sort) => {
              val out = sortedArray
                .sortBy { r =>
                  String.valueOf(JsonOperationsHelper.getValueAtPath(sort._1.toLowerCase(), r)._2)
                }(Ordering[String].reverse)
              if (sort._2) {
                out.reverse
              } else {
                out
              }
            }
          }).some
        } else {
          arr.some
        }
      }
      case _                => _entity.some
    }
  }

  private def paginateEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    _entity match {
      case arr @ JsArray(_) => {
        val paginationPage: Int     =
          request.queryString
            .get("page")
            .flatMap(_.headOption)
            .map(_.toInt)
            .getOrElse(1)
        val paginationPageSize: Int =
          request.queryString
            .get("pageSize")
            .flatMap(_.headOption)
            .map(_.toInt)
            .getOrElse(Int.MaxValue)
        val paginationPosition      = (paginationPage - 1) * paginationPageSize
        JsArray(arr.value.slice(paginationPosition, paginationPosition + paginationPageSize)).some
      }
      case _                => _entity.some
    }
  }

  private def projectedEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    val fields    = request.getQueryString("fields").map(_.split(",").toSeq).getOrElse(Seq.empty[String])
    val hasFields = fields.nonEmpty
    if (hasFields) {
      _entity match {
        case arr @ JsArray(_)  =>
          JsArray(arr.value.map { item =>
            JsonOperationsHelper.filterJson(item.asObject, fields)
          }).some
        case obj @ JsObject(_) => JsonOperationsHelper.filterJson(obj, fields).some
        case _                 => _entity.some
      }
    } else {
      _entity.some
    }
  }

  private def result(
      res: Results.Status,
      _entity: JsValue,
      request: RequestHeader,
      resEntity: Option[Resource]
  ): Result = {
    val entity = if (request.method == "GET") {
      (for {
        filtered  <- filterEntity(_entity, request)
        sorted    <- sortEntity(filtered, request)
        paginated <- paginateEntity(sorted, request)
        projected <- projectedEntity(paginated, request)
      } yield projected).get
    } else {
      _entity
    }
    entity match {
      case JsArray(seq) if !request.accepts("application/json") && request.accepts("application/x-ndjson") => {
        res
          .sendEntity(
            HttpEntity.Streamed(
              data = Source(seq.toList.map(_.stringify.byteString)),
              contentLength = None,
              contentType = "application/x-ndjson".some
            )
          )
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
      }
      case _ if !request.accepts("application/json") && request.accepts("application/yaml")                =>
        res(Yaml.write(entity)) // TODO: writes a k8s resource ?
          .as("application/yaml")
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
      case _                                                                                               =>
        res(entity).applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
          r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
        }
    }
  }

  private def withResource(
      group: String,
      version: String,
      entity: String,
      request: RequestHeader,
      bulk: Boolean = false
  )(f: Resource => Future[Result]): Future[Result] = {
    env.allResources.resources
      .filter(_.version.served)
      .find(r =>
        (group == "any" || r.group == group) && (version == "any" || r.version.name == version) && r.pluralName == entity
      ) match {
      case None                                               => result(Results.NotFound, notFoundBody, request, None).vfuture
      case Some(resource) if !resource.access.canBulk && bulk =>
        result(
          Results.Unauthorized,
          Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
          request,
          resource.some
        ).vfuture
      case Some(resource)                                     => {
        val read   = request.method == "GET"
        val create = request.method == "POST"
        val update = request.method == "PUT" || request.method == "PATCH"
        val delete = request.method == "DELETE"
        if (read && !resource.access.canRead) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          ).vfuture
        } else if (create && !resource.access.canCreate) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          ).vfuture
        } else if (update && !resource.access.canUpdate) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          ).vfuture
        } else if (delete && !resource.access.canDelete) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          ).vfuture
        } else {
          f(resource)
        }
      }
    }
  }

  // GET /apis/entities
  def entities() = ApiAction { ctx =>
    Ok(
      Json.obj(
        "version"   -> env.otoroshiVersion,
        "resources" -> JsArray(env.allResources.resources.map(_.json))
      )
    )
  }

  // PATCH /apis/:group/:version/:entity/_bulk
  def bulkPatch(group: String, version: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    import otoroshi.utils.json.JsonPatchHelpers.patchJson
    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") =>
        withResource(group, version, entity, ctx.request, bulk = true) { resource =>
          val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
          val src      = ctx.request.body
            .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
            .map(bs => Try(Json.parse(bs.utf8String)))
            .collect { case Success(e) => e }
            .mapAsync(1) { e =>
              resource.access.findOne(version, extractId(e)).map(ee => (e.select("patch").asValue, ee))
            }
            .map {
              case (e, None)    => Left((Json.obj("error" -> "entity not found"), e))
              case (_, Some(e)) => Right(("--", e))
            }
            .filter {
              case Left(_)            => true
              case Right((_, entity)) => ctx.canUserWriteJson(entity)
            }
            .mapAsync(grouping) {
              case Left((error, json))        =>
                Json
                  .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                  .stringify
                  .byteString
                  .future
              case Right((patchBody, entity)) => {
                val patchedEntity = patchJson(Json.parse(patchBody), entity)
                resource.access.validateToJson(patchedEntity, resource.singularName, ctx.backOfficeUser) match {
                  case JsError(errs)   =>
                    Json
                      .obj(
                        "status"            -> 400,
                        "error"             -> "bad_request",
                        "error_description" -> JsArray(errs.flatMap(_._2).flatMap(_.messages).map(JsString.apply)),
                        "entity"            -> entity
                      )
                      .stringify
                      .byteString
                      .vfuture
                  case JsSuccess(_, _) =>
                    resource.access
                      .create(version, resource.singularName, extractId(patchedEntity).some, patchedEntity)
                      .map {
                        case Left(error)          =>
                          error.stringify.byteString
                        case Right(createdEntity) =>
                          adminApiEvent(
                            ctx,
                            s"BULK_PATCH_${resource.singularName.toUpperCase()}",
                            s"User bulk patched a ${resource.singularName}",
                            createdEntity,
                            s"${resource.singularName}Patched".some
                          )
                          Json
                            .obj("status" -> 200, "updated" -> true, "id" -> extractId(createdEntity))
                            .stringify
                            .byteString
                      }
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
      case _                            =>
        result(
          Results.BadRequest,
          Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"),
          ctx.request,
          None
        ).vfuture
    }
  }

  // POST /apis/:group/:version/:entity/_bulk
  def bulkCreate(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.request.headers.get("Content-Type") match {
        case Some("application/x-ndjson") =>
          withResource(group, version, entity, ctx.request, bulk = true) { resource =>
            val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
            val src      = ctx.request.body
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
              .map(bs => Try(Json.parse(bs.utf8String)))
              .collect { case Success(e) => e }
              .map(e =>
                resource.access.format.reads(e) match {
                  case JsError(err)    => Left((JsError.toJson(err), e))
                  case JsSuccess(_, _) => Right(("--", e))
                }
              )
              .filter {
                case Left(_)            => true
                case Right((_, entity)) => ctx.canUserWriteJson(entity)
              }
              .mapAsync(grouping) {
                case Left((error, json)) =>
                  Json
                    .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                    .stringify
                    .byteString
                    .future
                case Right((_, entity))  => {
                  val id = extractId(entity)
                  resource.access.findOne(version, id).flatMap {
                    case Some(_) =>
                      Json
                        .obj(
                          "status"            -> 400,
                          "error"             -> "bad_entity",
                          "error_description" -> "entity already exists",
                          "entity"            -> entity
                        )
                        .stringify
                        .byteString
                        .future
                    case None    => {
                      resource.access.validateToJson(entity, resource.singularName, ctx.backOfficeUser) match {
                        case JsError(errs)   =>
                          Json
                            .obj(
                              "status"            -> 400,
                              "error"             -> "bad_request",
                              "error_description" -> JsArray(
                                errs.flatMap(_._2).flatMap(_.messages).map(JsString.apply)
                              ),
                              "entity"            -> entity
                            )
                            .stringify
                            .byteString
                            .vfuture
                        case JsSuccess(_, _) =>
                          resource.access.create(version, resource.singularName, None, entity).map {
                            case Left(error)          =>
                              error.stringify.byteString
                            case Right(createdEntity) =>
                              adminApiEvent(
                                ctx,
                                s"BULK_CREATE_${resource.singularName.toUpperCase()}",
                                s"User bulk created a ${resource.singularName}",
                                createdEntity,
                                s"${resource.singularName}Created".some
                              )
                              Json
                                .obj("status" -> 201, "created" -> true, "id" -> extractId(createdEntity))
                                .stringify
                                .byteString
                          }
                      }
                    }
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
        case _                            =>
          result(
            Results.BadRequest,
            Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"),
            ctx.request,
            None
          ).vfuture
      }
    }

  // PUT /apis/:group/:version/:entity/_bulk
  def bulkUpdate(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.request.headers.get("Content-Type") match {
        case Some("application/x-ndjson") =>
          withResource(group, version, entity, ctx.request, bulk = true) { resource =>
            val grouping                   = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
            val src: Source[ByteString, _] = ctx.request.body
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
              .map(bs => Try(Json.parse(bs.utf8String)))
              .collect { case Success(e) => e }
              .map(e =>
                resource.access.format.reads(e) match {
                  case JsError(err)    => Left((JsError.toJson(err), e))
                  case JsSuccess(_, _) => Right(("--", e))
                }
              )
              .filter {
                case Left(_)            => true
                case Right((_, entity)) => ctx.canUserWriteJson(entity)
              }
              .mapAsync(grouping) {
                case Left((error, json)) =>
                  Json
                    .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                    .stringify
                    .byteString
                    .future
                case Right((_, entity))  => {
                  val id = extractId(entity)
                  resource.access.findOne(version, id).flatMap {
                    case None                                                =>
                      Json
                        .obj(
                          "status"            -> 404,
                          "error"             -> "bad_entity",
                          "error_description" -> "entity does not exists",
                          "entity"            -> entity
                        )
                        .stringify
                        .byteString
                        .future
                    case Some(oldEntity) if !ctx.canUserWriteJson(oldEntity) =>
                      Json
                        .obj(
                          "status"            -> 400,
                          "error"             -> "bad_entity",
                          "error_description" -> "you cannot access this resource",
                          "entity"            -> entity
                        )
                        .stringify
                        .byteString
                        .future
                    case Some(oldEntity)                                     => {
                      resource.access.validateToJson(entity, resource.singularName, ctx.backOfficeUser) match {
                        case JsError(errs)   =>
                          Json
                            .obj(
                              "status"            -> 400,
                              "error"             -> "bad_request",
                              "error_description" -> JsArray(
                                errs.flatMap(_._2).flatMap(_.messages).map(JsString.apply)
                              ),
                              "entity"            -> entity
                            )
                            .stringify
                            .byteString
                            .vfuture
                        case JsSuccess(_, _) =>
                          resource.access
                            .create(version, resource.singularName, extractId(entity).some, entity)
                            .map {
                              case Left(error)          =>
                                error.stringify.byteString
                              case Right(createdEntity) =>
                                adminApiEvent(
                                  ctx,
                                  s"BULK_UPDATE_${resource.singularName.toUpperCase()}",
                                  s"User bulk updated a ${resource.singularName}",
                                  createdEntity,
                                  s"${resource.singularName}Updated".some
                                )
                                Json
                                  .obj("status" -> 200, "updated" -> true, "id" -> extractId(createdEntity))
                                  .stringify
                                  .byteString
                            }

                      }
                    }
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
        case _                            =>
          result(
            Results.BadRequest,
            Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"),
            ctx.request,
            None
          ).vfuture
      }
    }

  // DELETE /apis/:group/:version/:entity/_bulk
  def bulkDelete(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.request.headers.get("Content-Type") match {
        case Some("application/x-ndjson") =>
          withResource(group, version, entity, ctx.request, bulk = true) { resource =>
            val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
            val src      = ctx.request.body
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
              .map(bs => Try(Json.parse(bs.utf8String)))
              .collect { case Success(e) => e }
              .mapAsync(1) { e =>
                resource.access.findOne(version, extractId(e)).map(ee => (e, ee))
              }
              .map {
                case (e, None)    => Left((Json.obj("error" -> "entity not found"), e))
                case (_, Some(e)) => Right(("--", e))
              }
              .filter {
                case Left(_)            => true
                case Right((_, entity)) => ctx.canUserWriteJson(entity)
              }
              .mapAsync(grouping) {
                case Left((error, json)) =>
                  Json
                    .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                    .stringify
                    .byteString
                    .future
                case Right((_, entity))  => {
                  adminApiEvent(
                    ctx,
                    s"BULK_DELETED_${resource.singularName.toUpperCase()}",
                    s"User bulk deleted a ${resource.singularName}",
                    Json.obj("id" -> extractId(entity)),
                    s"${resource.singularName}Deleted".some
                  )
                  resource.access.deleteOne(version, extractId(entity)).map { _ =>
                    Json.obj("status" -> 200, "deleted" -> true, "id" -> extractId(entity)).stringify.byteString
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
        case _                            =>
          result(
            Results.BadRequest,
            Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type"),
            ctx.request,
            None
          ).vfuture
      }
    }

  // GET /apis/:group/:version/:entity/_count
  def countAll(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findAll(version).map { entities =>
        adminApiEvent(
          ctx,
          s"COUNT_ALL_${resource.pluralName.toUpperCase()}",
          s"User bulk count all ${resource.pluralName}",
          Json.obj(),
          None
        )
        result(Results.Ok, Json.obj("count" -> entities.count(e => ctx.canUserReadJson(e))), ctx.request, resource.some)
      }
    }
  }

  // GET /apis/:group/:version/:entity
  def findAll(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findAll(version).map { entities =>
        adminApiEvent(
          ctx,
          s"READ_ALL_${resource.pluralName.toUpperCase()}",
          s"User bulk read all ${resource.pluralName}",
          Json.obj(),
          None
        )
        result(Results.Ok, JsArray(entities.filter(e => ctx.canUserReadJson(e))), ctx.request, resource.some)
      }
    }
  }

  // POST /apis/:group/:version/:entity
  def create(group: String, version: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) flatMap {
        case Left(err)                                  => result(Results.BadRequest, err, ctx.request, resource.some).vfuture
        case Right(body) if !ctx.canUserWriteJson(body) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          ).vfuture
        case Right(body)                                => {
          val dev = if (env.isDev) "_dev" else ""
          val id  = extractIdSafe(body)
            .getOrElse(s"${resource.singularName}${dev}_${IdGenerator.uuid}")
          resource.access.findOne(version, id).flatMap {
            case Some(oldEntity) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "resource already exists"),
                ctx.request,
                resource.some
              ).vfuture
            case None            => {
              resource.access.validateToJson(body, resource.singularName, ctx.backOfficeUser) match {
                case JsError(errs)   =>
                  result(
                    Results.BadRequest,
                    Json.obj(
                      "error"             -> "bad_request",
                      "error_description" -> JsArray(errs.flatMap(_._2).flatMap(_.messages).map(JsString.apply))
                    ),
                    ctx.request,
                    resource.some
                  ).vfuture
                case JsSuccess(_, _) =>
                  resource.access.create(version, resource.singularName, None, body).map {
                    case Left(err)  => result(Results.InternalServerError, err, ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"CREATE_${resource.singularName.toUpperCase()}",
                        s"User bulk created a ${resource.singularName}",
                        body,
                        s"${resource.singularName}Created".some
                      )
                      result(Results.Created, res, ctx.request, resource.some)
                  }
              }
            }
          }
        }
      }
    }
  }

  // DELETE /apis/:group/:version/:entity
  def deleteAll(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.deleteAll(version, e => ctx.canUserWriteJson(e)).map { _ =>
        adminApiEvent(
          ctx,
          s"DELETE_ALL_${resource.pluralName.toUpperCase()}",
          s"User bulk deleted all ${resource.pluralName}",
          Json.obj(),
          s"All${resource.singularName}Deleted".some
        )
        NoContent
      }
    }
  }

  // GET /apis/:group/:version/:entity/:id/_template
  def template(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      Ok(resource.access.template(version)).vfuture
    }
  }

  // GET /apis/:group/:version/:entity/:id
  def findOne(group: String, version: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findOne(version, id).map {
        case None                                         => result(Results.NotFound, notFoundBody, ctx.request, resource.some)
        case Some(entity) if !ctx.canUserReadJson(entity) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          )
        case Some(entity)                                 =>
          adminApiEvent(
            ctx,
            s"READ_${resource.singularName.toUpperCase()}",
            s"User bulk read a ${resource.singularName}",
            Json.obj("id" -> id),
            None
          )
          result(Results.Ok, entity, ctx.request, resource.some)
      }
    }
  }

  // DELETE /apis/:group/:version/:entity/:id
  def delete(group: String, version: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findOne(version, id).flatMap {
        case None                                          => result(Results.NotFound, notFoundBody, ctx.request, resource.some).vfuture
        case Some(entity) if !ctx.canUserWriteJson(entity) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          ).vfuture
        case Some(entity)                                  =>
          adminApiEvent(
            ctx,
            s"DELETE_${resource.singularName.toUpperCase()}",
            s"User bulk deleted a ${resource.singularName}",
            Json.obj("id" -> id),
            s"${resource.singularName}Deleted".some
          )
          resource.access.deleteOne(version, id).map { _ =>
            result(Results.Ok, entity, ctx.request, resource.some)
          }
      }
    }
  }

  // POST /apis/:group/:version/:entity/:id
  def upsert(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) flatMap {
        case Left(err)     => result(Results.BadRequest, err, ctx.request, resource.some).vfuture
        case Right(__body) => {
          val _body = __body.asObject ++ Json.obj("id" -> id, "client_id" -> id, "clientId" -> id)
          resource.access.findOne(version, id).flatMap {
            case None                                                =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "resource does not exists"),
                ctx.request,
                resource.some
              ).vfuture
            case Some(oldEntity) if !ctx.canUserWriteJson(oldEntity) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                ctx.request,
                resource.some
              ).vfuture
            case Some(oldEntity)                                     => {
              resource.access.validateToJson(_body, resource.singularName, ctx.backOfficeUser) match {
                case err @ JsError(_)                                =>
                  result(Results.BadRequest, JsError.toJson(err), ctx.request, resource.some).vfuture
                case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) =>
                  result(
                    Results.Unauthorized,
                    Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                    ctx.request,
                    resource.some
                  ).vfuture
                case JsSuccess(body, _)                              => {
                  resource.access.findOne(version, id).flatMap {
                    case None    =>
                      resource.access.create(version, resource.singularName, None, body).map {
                        case Left(err)  => result(Results.InternalServerError, err, ctx.request, resource.some)
                        case Right(res) =>
                          adminApiEvent(
                            ctx,
                            s"CREATE_${resource.singularName.toUpperCase()}",
                            s"User bulk created a ${resource.singularName}",
                            body,
                            s"${resource.singularName}Created".some
                          )
                          result(Results.Ok, res, ctx.request, resource.some)
                      }
                    case Some(_) =>
                      resource.access.create(version, resource.singularName, id.some, body).map {
                        case Left(err)  => result(Results.InternalServerError, err, ctx.request, resource.some)
                        case Right(res) =>
                          adminApiEvent(
                            ctx,
                            s"UPDATE_${resource.singularName.toUpperCase()}",
                            s"User bulk updated a ${resource.singularName}",
                            body,
                            s"${resource.singularName}Updated".some
                          )
                          result(Results.Ok, res, ctx.request, resource.some)
                      }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // PUT /apis/:group/:version/:entity/:id
  def update(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) flatMap {
        case Left(err)     => result(Results.BadRequest, err, ctx.request, resource.some).vfuture
        case Right(__body) => {
          val _body = __body.asObject ++ Json.obj("id" -> id, "client_id" -> id, "clientId" -> id)
          resource.access.findOne(version, id).flatMap {
            case None                                                =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "resource does not exists"),
                ctx.request,
                resource.some
              ).vfuture
            case Some(oldEntity) if !ctx.canUserWriteJson(oldEntity) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                ctx.request,
                resource.some
              ).vfuture
            case Some(oldEntity)                                     => {
              resource.access.validateToJson(_body, resource.singularName, ctx.backOfficeUser) match {
                case err @ JsError(_)                                =>
                  result(Results.BadRequest, JsError.toJson(err), ctx.request, resource.some).vfuture
                case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) =>
                  result(
                    Results.Unauthorized,
                    Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                    ctx.request,
                    resource.some
                  ).vfuture
                case JsSuccess(body, _)                              => {
                  resource.access.findOne(version, id).flatMap {
                    case None    => result(Results.NotFound, notFoundBody, ctx.request, resource.some).vfuture
                    case Some(_) =>
                      resource.access.create(version, resource.singularName, id.some, body).map {
                        case Left(err)  => result(Results.InternalServerError, err, ctx.request, resource.some)
                        case Right(res) =>
                          adminApiEvent(
                            ctx,
                            s"UPDATE_${resource.singularName.toUpperCase()}",
                            s"User bulk updated a ${resource.singularName}",
                            body,
                            s"${resource.singularName}Updated".some
                          )
                          result(Results.Ok, res, ctx.request, resource.some)
                      }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // PATCH /apis/:group/:version/:entity/:id
  def patch(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx =>
    import otoroshi.utils.json.JsonPatchHelpers.patchJson
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request) flatMap {
        case Left(err)   => result(Results.BadRequest, err, ctx.request, resource.some).vfuture
        case Right(body) => {
          resource.access.findOne(version, id).flatMap {
            case None                                            => result(Results.NotFound, notFoundBody, ctx.request, resource.some).vfuture
            case Some(current) if !ctx.canUserWriteJson(current) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                ctx.request,
                resource.some
              ).vfuture
            case Some(current)                                   => {
              val _patchedBody = patchJson(body, current)
              val patchedBody  = _patchedBody.asObject ++ Json.obj("id" -> id, "client_id" -> id, "clientId" -> id)
              resource.access.validateToJson(patchedBody, resource.singularName, ctx.backOfficeUser) match {
                case JsError(errs)   =>
                  result(
                    Results.BadRequest,
                    Json.obj(
                      "error"             -> "bad_request",
                      "error_description" -> JsArray(errs.flatMap(_._2).flatMap(_.messages).map(JsString.apply))
                    ),
                    ctx.request,
                    resource.some
                  ).vfuture
                case JsSuccess(_, _) =>
                  resource.access.create(version, resource.singularName, id.some, patchedBody).map {
                    case Left(err)  => result(Results.InternalServerError, err, ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"PATCHED_${resource.singularName.toUpperCase()}",
                        s"User bulk patched a ${resource.singularName}",
                        body,
                        s"${resource.singularName}Patched".some
                      )
                      result(Results.Ok, res, ctx.request, resource.some)
                  }
              }
            }
          }
        }
      }
    }
  }
}
