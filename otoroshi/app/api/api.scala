package otoroshi.api

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import org.apache.commons.lang3.math.NumberUtils
import org.joda.time.DateTime
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.auth.AuthModuleConfig
import otoroshi.controllers.HealthController
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit}
import otoroshi.jobs.updates.SoftwareUpdatesJobs
import otoroshi.models._
import otoroshi.next.models.{NgRoute, NgRouteComposition, StoredNgBackend}
import otoroshi.script.Script
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.tcp.TcpService
import otoroshi.utils.JsonValidator
import otoroshi.utils.controllers.GenericAlert
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.json.{JsonOperationsHelper, JsonPatchHelpers}
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
    "storage"    -> storage,
    "schema"     -> schema
  )

  def jsonWithSchema(kind: String, clazz: Class[_])(implicit env: Env): JsValue = Json.obj(
    "name"       -> name,
    "served"     -> served,
    "deprecated" -> deprecated,
    "storage"    -> storage,
    "schema"     -> finalSchema(kind, clazz)
  )

  def finalSchema(kind: String, clazz: Class[_])(implicit env: Env): JsValue = {
    schema.getOrElse {
      Try(
        Json.parse(
          org.json4s.jackson.JsonMethods.pretty(fi.oph.scalaschema.SchemaFactory.default.createSchema(clazz).toJson)
        )
      ) match {
        case Failure(e) => {
          env.logger.error(s"failing reflection on '${kind}'", e)
          Json.obj("type" -> "object", "description" -> s"A resource of kind ${kind}")
        }
        case Success(s) => s
      }
    }
  }
}

object Resource {
  val unknown = Resource(
    kind = "Unknown",
    pluralName = "unknowns",
    singularName = "unknown",
    group = "proxy.otoroshi.io",
    version =  ResourceVersion("v1", true, false, true),
    access = null,
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
  lazy val groupKind = s"${group}/${kind}"
  def json: JsValue  = Json.obj(
    "kind"          -> kind,
    "plural_name"   -> pluralName,
    "singular_name" -> singularName,
    "group"         -> group,
    "version"       -> version.json
  )

  def jsonWithSchema(implicit env: Env): JsValue = Json.obj(
    "kind"          -> kind,
    "plural_name"   -> pluralName,
    "singular_name" -> singularName,
    "group"         -> group,
    "version"       -> version.jsonWithSchema(kind, access.clazz)
  )
}

sealed trait WriteAction
object WriteAction {
  case object Create extends WriteAction
  case object Update extends WriteAction
}

sealed trait ReadAction
object ReadAction {
  case object ReadOne extends ReadAction
  case object ReadAll extends ReadAction
}

sealed trait DeleteAction
object DeleteAction {
  case object DeleteOne extends DeleteAction
  case object DeleteAll extends DeleteAction
}

trait ResourceAccessApi[T <: EntityLocationSupport] {

  def clazz: Class[T]
  def format: Format[T]
  def key(id: String): String
  def extractId(value: T): String
  def extractIdJson(value: JsValue): String
  def idFieldName(): String
  def template(version: String, params: Map[String, String]): JsValue = Json.obj()

  def canRead: Boolean
  def canCreate: Boolean
  def canUpdate: Boolean
  def canDelete: Boolean
  def canBulk: Boolean

  def writeValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: WriteAction, env: Env): Future[Either[JsValue, T]]
  //def deleteValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: DeleteAction): Future[Either[JsValue, T]]
  //def readValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: ReadAction): Future[Either[JsValue, T]]

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

  def create(version: String, singularName: String, id: Option[String], body: JsValue, action: WriteAction)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsValue]] = {
    val dev   = if (env.isDev) "_dev" else ""
    val resId = id
      .orElse(Try(extractIdJson(body)).toOption)
      .getOrElse(s"${singularName}${dev}_${IdGenerator.uuid}")
    format.reads(body) match {
      case err @ JsError(_)    => Left[JsValue, JsValue](JsError.toJson(err)).vfuture
      case JsSuccess(_value, _) => {
        writeValidation(_value, body, singularName, id, action, env).flatMap {
          case Left(err) => err.leftf
          case Right(value) => {
            val idKey     = idFieldName()
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
            //entity.json
            format.writes(entity)
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
              //entity.json.some
              format.writes(entity).some
            case _                    => None
          }
      }
  }

  def allJson(): Seq[JsValue]                = all().map(v => format.writes(v))   //.map(_.json)
  def oneJson(id: String): Option[JsValue]   = one(id).map(v => format.writes(v)) //.map(_.json)
  def updateJson(values: Seq[JsValue]): Unit = update(
    values.map(v => format.reads(v)).collect { case JsSuccess(v, _) => v }
  )
  def all(): Seq[T]
  def one(id: String): Option[T]
  def update(values: Seq[T]): Unit
}

case class GenericResourceAccessApii[T <: EntityLocationSupport](
    format: Format[T],
    clazz: Class[T],
    keyf: String => String,
    extractIdf: T => String,
    extractIdJsonf: JsValue => String,
    idFieldNamef: () => String,
    tmpl: (String, Map[String, String]) => JsValue = (v, p) => Json.obj(),
    canRead: Boolean = true,
    canCreate: Boolean = true,
    canUpdate: Boolean = true,
    canDelete: Boolean = true,
    canBulk: Boolean = true,
) extends ResourceAccessApi[T] {
  override def key(id: String): String                                           = keyf.apply(id)
  override def extractId(value: T): String                                       = value.theId
  override def extractIdJson(value: JsValue): String                             = extractIdJsonf(value)
  override def idFieldName(): String                                             = idFieldNamef()
  override def template(version: String, template: Map[String, String]): JsValue = tmpl(version, template)
  override def all(): Seq[T]                                                     = throw new UnsupportedOperationException()
  override def one(id: String): Option[T]                                        = throw new UnsupportedOperationException()
  override def update(values: Seq[T]): Unit                                      = throw new UnsupportedOperationException()
  override def writeValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: WriteAction, env: Env): Future[Either[JsValue, T]] = entity.rightf
}

case class GenericResourceAccessApiWithState[T <: EntityLocationSupport](
    format: Format[T],
    clazz: Class[T],
    keyf: String => String,
    extractIdf: T => String,
    extractIdJsonf: JsValue => String,
    idFieldNamef: () => String,
    tmpl: (String, Map[String, String]) => JsValue = (v, p) => Json.obj(),
    canRead: Boolean = true,
    canCreate: Boolean = true,
    canUpdate: Boolean = true,
    canDelete: Boolean = true,
    canBulk: Boolean = true,
    stateAll: () => Seq[T],
    stateOne: (String) => Option[T],
    stateUpdate: (Seq[T]) => Unit,
) extends ResourceAccessApi[T] {
  override def key(id: String): String                                           = keyf.apply(id)
  override def extractId(value: T): String                                       = value.theId
  override def extractIdJson(value: JsValue): String                             = extractIdJsonf(value)
  override def idFieldName(): String                                             = idFieldNamef()
  override def template(version: String, template: Map[String, String]): JsValue = tmpl(version, template)
  override def all(): Seq[T]                                                     = stateAll()
  override def one(id: String): Option[T]                                        = stateOne(id)
  override def update(values: Seq[T]): Unit                                      = stateUpdate(values)
  override def writeValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: WriteAction, env: Env): Future[Either[JsValue, T]] = entity.rightf
}

case class GenericResourceAccessApiWithStateAndWriteValidation[T <: EntityLocationSupport](
  format: Format[T],
  clazz: Class[T],
  keyf: String => String,
  extractIdf: T => String,
  extractIdJsonf: JsValue => String,
  idFieldNamef: () => String,
  tmpl: (String, Map[String, String]) => JsValue = (v, p) => Json.obj(),
  canRead: Boolean = true,
  canCreate: Boolean = true,
  canUpdate: Boolean = true,
  canDelete: Boolean = true,
  canBulk: Boolean = true,
  stateAll: () => Seq[T],
  stateOne: (String) => Option[T],
  stateUpdate: (Seq[T]) => Unit,
  writeValidator: Function6[T, JsValue, String, Option[String], WriteAction, Env, Future[Either[JsValue, T]]] = (ent: T, _: JsValue, _: String, _: Option[String], _: WriteAction, _: Env) => ent.rightf,
) extends ResourceAccessApi[T] {
  override def key(id: String): String                                           = keyf.apply(id)
  override def extractId(value: T): String                                       = value.theId
  override def extractIdJson(value: JsValue): String                             = extractIdJsonf(value)
  override def idFieldName(): String                                             = idFieldNamef()
  override def template(version: String, template: Map[String, String]): JsValue = tmpl(version, template)
  override def all(): Seq[T]                                                     = stateAll()
  override def one(id: String): Option[T]                                        = stateOne(id)
  override def update(values: Seq[T]): Unit                                      = stateUpdate(values)
  override def writeValidation(entity: T, body: JsValue, singularName: String, id: Option[String], action: WriteAction, env: Env): Future[Either[JsValue, T]] = {
    writeValidator.apply(entity, body, singularName, id, action, env)
  }
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
      GenericResourceAccessApiWithState[NgRoute](
        NgRoute.fmt,
        classOf[NgRoute],
        env.datastores.routeDataStore.key,
        env.datastores.routeDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.routeDataStore.template(env).json,
        stateAll = () => env.proxyState.allRawRoutes(),
        stateOne = id => env.proxyState.rawRoute(id),
        stateUpdate = seq => env.proxyState.updateRawRoutes(seq)
      )
    ),
    Resource(
      "Backend",
      "backends",
      "backend",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[StoredNgBackend](
        StoredNgBackend.format,
        classOf[StoredNgBackend],
        env.datastores.backendsDataStore.key,
        env.datastores.backendsDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.backendsDataStore.template(env).json,
        stateAll = () => env.proxyState.allStoredBackends(),
        stateOne = id => env.proxyState.storedBackend(id),
        stateUpdate = seq => env.proxyState.updateBackends(seq)
      )
    ),
    Resource(
      "RouteComposition",
      "route-compositions",
      "route-composition",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[NgRouteComposition](
        NgRouteComposition.fmt,
        classOf[NgRouteComposition],
        env.datastores.routeCompositionDataStore.key,
        env.datastores.routeCompositionDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.routeCompositionDataStore.template(env).json,
        stateAll = () => env.proxyState.allRouteCompositions(),
        stateOne = id => env.proxyState.routeComposition(id),
        stateUpdate = seq => env.proxyState.updateNgSRouteCompositions(seq)
      )
    ),
    Resource(
      "ServiceDescriptor",
      "service-descriptors",
      "service-descriptor",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ServiceDescriptor](
        ServiceDescriptor._fmt,
        classOf[ServiceDescriptor],
        env.datastores.serviceDescriptorDataStore.key,
        env.datastores.serviceDescriptorDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.serviceDescriptorDataStore.template(env).json,
        stateAll = () => env.proxyState.allServices(),
        stateOne = id => env.proxyState.service(id),
        stateUpdate = seq => env.proxyState.updateServices(seq)
      )
    ),
    Resource(
      "TcpService",
      "tcp-services",
      "tcp-service",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[TcpService](
        TcpService.fmt,
        classOf[TcpService],
        env.datastores.tcpServiceDataStore.key,
        env.datastores.tcpServiceDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.tcpServiceDataStore.template(env).json,
        stateAll = () => env.proxyState.allTcpServices(),
        stateOne = id => env.proxyState.tcpService(id),
        stateUpdate = seq => env.proxyState.updateTcpServices(seq)
      )
    ),
    Resource(
      "ErrorTemplate",
      "error-templates",
      "error-templates",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApii[ErrorTemplate](
        ErrorTemplate.fmt,
        classOf[ErrorTemplate],
        env.datastores.errorTemplateDataStore.key,
        env.datastores.errorTemplateDataStore.extractId,
        json => json.select("serviceId").asString,
        () => "serviceId"
      )
    ),
    //////
    Resource(
      "Apikey",
      "apikeys",
      "apikey",
      "apim.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ApiKey](
        new Format[ApiKey] {
          override def reads(json: JsValue): JsResult[ApiKey] = {
            ApiKey._fmt.reads(json)
          }
          override def writes(o: ApiKey): JsValue = {
            var base = Json.obj("bearer" -> o.toBearer())
            if (o.rotation.enabled && o.rotation.nextSecret.isDefined) {
              base = base ++ Json.obj("rotation" -> Json.obj("bearer" -> o.toNextBearer()))
            }
            ApiKey._fmt.writes(o).asObject.deepMerge(base)
          }
        },
        classOf[ApiKey],
        env.datastores.apiKeyDataStore.key,
        env.datastores.apiKeyDataStore.extractId,
        json => json.select("clientId").asString,
        () => "clientId",
        (v, p) => env.datastores.apiKeyDataStore.template(env).json,
        stateAll = () => env.proxyState.allApikeys(),
        stateOne = id => env.proxyState.apikey(id),
        stateUpdate = seq => env.proxyState.updateApikeys(seq)
      )
    ),
    //////
    Resource(
      "Certificate",
      "certificates",
      "certificate",
      "pki.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Cert](
        Cert._fmt,
        classOf[Cert],
        env.datastores.certificatesDataStore.key,
        env.datastores.certificatesDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) =>
          env.datastores.certificatesDataStore
            .template(env.otoroshiExecutionContext, env)
            .awaitf(10.seconds)(env.otoroshiExecutionContext)
            .json,
        stateAll = () => env.proxyState.allCertificates(),
        stateOne = id => env.proxyState.certificate(id),
        stateUpdate = seq => env.proxyState.updateCertificates(seq)
      )
    ),
    //////
    Resource(
      "JwtVerifier",
      "jwt-verifiers",
      "jwt-verifier",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[GlobalJwtVerifier](
        GlobalJwtVerifier._fmt,
        classOf[GlobalJwtVerifier],
        env.datastores.globalJwtVerifierDataStore.key,
        env.datastores.globalJwtVerifierDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.globalJwtVerifierDataStore.template(env).json,
        stateAll = () => env.proxyState.allJwtVerifiers(),
        stateOne = id => env.proxyState.jwtVerifier(id),
        stateUpdate = seq => env.proxyState.updateJwtVerifiers(seq)
      )
    ),
    Resource(
      "AuthModule",
      "auth-modules",
      "auth-module",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AuthModuleConfig](
        AuthModuleConfig._fmt(env),
        classOf[AuthModuleConfig],
        env.datastores.authConfigsDataStore.key,
        env.datastores.authConfigsDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.authConfigsDataStore.template(p.get("type"), env)(env.otoroshiExecutionContext).json,
        stateAll = () => env.proxyState.allAuthModules(),
        stateOne = id => env.proxyState.authModule(id),
        stateUpdate = seq => env.proxyState.updateAuthModules(seq)
      )
    ),
    Resource(
      "AdminSession",
      "admin-sessions",
      "admin-session",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[BackOfficeUser](
        BackOfficeUser.fmt,
        classOf[BackOfficeUser],
        env.datastores.backOfficeUserDataStore.key,
        env.datastores.backOfficeUserDataStore.extractId,
        json => json.select("randomId").asString,
        () => "randomId",
        canCreate = false,
        canUpdate = false,
        stateAll = () => env.proxyState.allBackofficeSessions(),
        stateOne = id => env.proxyState.backofficeSession(id),
        stateUpdate = seq => throw new UnsupportedOperationException("...")
      )
    ),
    Resource(
      "SimpleAdminUser",
      "admins", //"simple-admin-users",
      "admins", //"simple-admin-user",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[SimpleOtoroshiAdmin](
        SimpleOtoroshiAdmin.fmt,
        classOf[SimpleOtoroshiAdmin],
        env.datastores.simpleAdminDataStore.key,
        env.datastores.simpleAdminDataStore.extractId,
        json => json.select("username").asString,
        () => "username",
        canCreate = true,
        canUpdate = false,
        canDelete = true,
        stateAll = () =>
          env.proxyState.allOtoroshiAdmins().collect { case u: SimpleOtoroshiAdmin =>
            u
          },
        stateOne = id =>
          env.proxyState.otoroshiAdmin(id).collect { case u: SimpleOtoroshiAdmin =>
            u
          },
        stateUpdate = seq => throw new UnsupportedOperationException("...")
      )
    ),
    Resource(
      "AuthModuleUser",
      "auth-module-users",
      "auth-module-user",
      "security.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[PrivateAppsUser](
        PrivateAppsUser.fmt,
        classOf[PrivateAppsUser],
        env.datastores.privateAppsUserDataStore.key,
        env.datastores.privateAppsUserDataStore.extractId,
        json => json.select("randomId").asString,
        () => "randomId",
        canCreate = false,
        canUpdate = false,
        stateAll = () => env.proxyState.allPrivateAppsSessions(),
        stateOne = id => env.proxyState.privateAppsSession(id),
        stateUpdate = seq => throw new UnsupportedOperationException("...")
      )
    ),
    //////
    Resource(
      "ServiceGroup",
      "service-groups",
      "service-group",
      "organize.otoroshi.io",
      ResourceVersion(
        "v1",
        true,
        false,
        true,
        Some(
          Json.obj(
            "type"        -> "object",
            "description" -> "The otoroshi model for a group of services",
            "properties"  -> Json.obj(
              "id"          -> Json.obj(
                "type"        -> "string",
                "description" -> "A unique random string to identify your service"
              ),
              "_loc"        -> Json.obj(
                "$ref"        -> "#/components/schemas/otoroshi.models.EntityLocation",
                "description" -> "Entity location"
              ),
              "name"        -> Json.obj(
                "type"        -> "string",
                "description" -> "The name of your service. Only for debug and human readability purposes"
              ),
              "metadata"    -> Json.obj(
                "type"                 -> "object",
                "additionalProperties" -> Json.obj(
                  "type" -> "string"
                ),
                "description"          -> "Just a bunch of random properties"
              ),
              "description" -> Json.obj(
                "type"        -> "string",
                "description" -> "Entity description"
              ),
              "tags"        -> Json.obj(
                "type"        -> "array",
                "items"       -> Json.obj(
                  "type" -> "string"
                ),
                "description" -> "Entity tags"
              )
            )
          )
        )
      ),
      GenericResourceAccessApiWithState[ServiceGroup](
        ServiceGroup._fmt,
        classOf[ServiceGroup],
        env.datastores.serviceGroupDataStore.key,
        env.datastores.serviceGroupDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.serviceGroupDataStore.template(env).json,
        stateAll = () => env.proxyState.allServiceGroups(),
        stateOne = id => env.proxyState.serviceGroup(id),
        stateUpdate = seq => env.proxyState.updateServiceGroups(seq)
      )
    ),
    Resource(
      "Organization",
      "organizations",
      "organization",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Tenant](
        Tenant.format,
        classOf[Tenant],
        env.datastores.tenantDataStore.key,
        env.datastores.tenantDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.tenantDataStore.template(env).json,
        stateAll = () => env.proxyState.allTenants(),
        stateOne = id => env.proxyState.tenant(id),
        stateUpdate = seq => env.proxyState.updateTenants(seq)
      )
    ),
    Resource(
      "Tenant",
      "tenants",
      "tenant",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Tenant](
        Tenant.format,
        classOf[Tenant],
        env.datastores.tenantDataStore.key,
        env.datastores.tenantDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.tenantDataStore.template(env).json,
        stateAll = () => env.proxyState.allTenants(),
        stateOne = id => env.proxyState.tenant(id),
        stateUpdate = seq => env.proxyState.updateTenants(seq)
      )
    ),
    Resource(
      "Team",
      "teams",
      "team",
      "organize.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Team](
        Team.format,
        classOf[Team],
        env.datastores.teamDataStore.key,
        env.datastores.teamDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.teamDataStore.template(TenantId.default).json,
        stateAll = () => env.proxyState.allTeams(),
        stateOne = id => env.proxyState.team(id),
        stateUpdate = seq => env.proxyState.updateTeams(seq)
      )
    ),
    //////
    Resource(
      "DataExporter",
      "data-exporters",
      "data-exporter",
      "events.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[DataExporterConfig](
        DataExporterConfig.format,
        classOf[DataExporterConfig],
        env.datastores.dataExporterConfigDataStore.key,
        env.datastores.dataExporterConfigDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.dataExporterConfigDataStore.template(p.get("type")).json,
        stateAll = () => env.proxyState.allDataExporters(),
        stateOne = id => env.proxyState.dataExporter(id),
        stateUpdate = seq => env.proxyState.updateDataExporters(seq)
      )
    ),
    //////
    Resource(
      "Script",
      "scripts",
      "script",
      "plugins.otoroshi.io",
      ResourceVersion("v1", served = true, deprecated = true, storage = true),
      GenericResourceAccessApiWithState[Script](
        Script._fmt,
        classOf[Script],
        env.datastores.scriptDataStore.key,
        env.datastores.scriptDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.scriptDataStore.template(env).json,
        stateAll = () => env.proxyState.allScripts(),
        stateOne = id => env.proxyState.script(id),
        stateUpdate = seq => env.proxyState.updateScripts(seq)
      )
    ),
    Resource(
      "WasmPlugin",
      "wasm-plugins",
      "wasm-plugin",
      "plugins.otoroshi.io",
      ResourceVersion("v1", served = true, deprecated = true, storage = true),
      GenericResourceAccessApiWithState[WasmPlugin](
        WasmPlugin.format,
        classOf[WasmPlugin],
        env.datastores.wasmPluginsDataStore.key,
        env.datastores.wasmPluginsDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (v, p) => env.datastores.wasmPluginsDataStore.template(env).json,
        stateAll = () => env.proxyState.allWasmPlugins(),
        stateOne = id => env.proxyState.wasmPlugin(id),
        stateUpdate = seq => env.proxyState.updateWasmPlugins(seq)
      )
    ),
    //////
    Resource(
      "GlobalConfig",
      "global-configs",
      "global-config",
      "config.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[TweakedGlobalConfig](
        TweakedGlobalConfig.fmt,
        classOf[TweakedGlobalConfig],
        id => env.datastores.globalConfigDataStore.key(id),
        c => env.datastores.globalConfigDataStore.extractId(c.config),
        json => "global",
        () => "id",
        (v, p) => env.datastores.globalConfigDataStore.template.json,
        canCreate = false,
        canDelete = false,
        canBulk = false,
        stateAll = () => env.proxyState.globalConfig().map(TweakedGlobalConfig.apply).toSeq,
        stateOne = id => env.proxyState.globalConfig().map(TweakedGlobalConfig.apply),
        stateUpdate = seq => throw new UnsupportedOperationException("...")
      )
    ),
    //////
    Resource(
      "Draft",
      "drafts",
      "draft",
      "proxy.otoroshi.io",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Draft](
        Draft.format,
        classOf[Draft],
        env.datastores.draftsDataStore.key,
        env.datastores.draftsDataStore.extractId,
        json => json.select("id").asString,
        () => "id",
        (_v, _p) => env.datastores.draftsDataStore.template(env).json,
        stateAll = () => env.proxyState.allDrafts(),
        stateOne = id => env.proxyState.draft(id),
        stateUpdate = seq => env.proxyState.updateDrafts(seq)
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

  private lazy val commitVersion = Option(System.getenv("COMMIT_ID")).getOrElse(env.otoroshiVersion)

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

  private def bodyIn(
      request: Request[Source[ByteString, _]],
      resource: Resource,
      version: String,
      defaultEntity: Option[JsObject] = None
  ): Future[Either[JsValue, JsValue]] = {
    Option(request.body) match {
      case Some(body) if request.contentType.contains("application/yaml")                  => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          Yaml.parse(bodyRaw.utf8String) match {
            case None      => Left(Json.obj("error" -> "bad_request", "error_description" -> "error while parsing yaml"))
            case Some(yml) => {
              val isKubeArmored = yml.select("apiVersion").isDefined && yml.select("spec").isDefined
              if (isKubeArmored) {
                val specName     = yml.select("spec").select("name").asOpt[String]
                val metaName     = yml.select("metadata").select("name").asOpt[String]
                val name: String = specName.orElse(metaName).getOrElse("no name")
                val kind         = yml.select("kind").asOpt[String].getOrElse("nokind")
                Right(
                  yml.select("spec").asObject ++ Json.obj(
                    "name" -> name,
                    "kind" -> kind
                  )
                )
              } else {
                Right(yml)
              }
            }
          }
        }
      }
      case Some(body) if request.contentType.contains("application/json")                  => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          Right(Json.parse(bodyRaw.utf8String))
        }
      }
      case Some(body) if request.contentType.contains("application/json+oto-patch")        => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          val values: Seq[JsObject]            = Json.parse(bodyRaw.utf8String).asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          val default                          =
            defaultEntity.orElse(resource.access.template(version, Map.empty).asOpt[JsObject]).getOrElse(Json.obj())
          val jsonValues: Map[String, JsValue] = values
            .map(obj => (obj.select("path").asString, obj.select("value").asValue))
            .map {
              case (key, JsString(str)) =>
                (
                  key,
                  str match {
                    case str if str == "null"                                     => JsNull
                    case str if str == "true"                                     => JsBoolean(true)
                    case str if str == "false"                                    => JsBoolean(false)
                    case str if str.startsWith("{") && str.endsWith("}")          => Json.parse(str).asObject
                    case str if str.startsWith("[") && str.endsWith("]")          => Json.parse(str).asArray
                    case str if NumberUtils.isCreatable(str) && str.contains(".") => JsNumber(BigDecimal(str))
                    case str if NumberUtils.isCreatable(str)                      => JsNumber(BigDecimal(str))
                    case str                                                      => JsString(str)
                  }
                )
              case (key, value)         => (key, value)
            }
            .toMap
          Right(jsonValues.toSeq.foldLeft(default) {
            case (obj, (key, value)) if key.contains(".") => {
              val pointer = if (key.startsWith("/")) s"${key.replace(".", "/")}" else s"/${key.replace(".", "/")}"
              val parts   = key.split("\\.").toSeq
              val newObj  = JsonOperationsHelper.genericInsertAtPath(obj, parts, Json.obj())
              val ops     =
                if (obj.atPointer(pointer).isDefined)
                  Seq(
                    Json.obj("op" -> "remove", "path" -> pointer),
                    Json.obj("op" -> "add", "path"    -> pointer, "value" -> value)
                  )
                else
                  Seq(
                    Json.obj("op" -> "add", "path" -> pointer, "value" -> value)
                  )
              JsonPatchHelpers.patchJson(JsArray(ops), newObj).asObject
            }
            case (obj, (key, value))                      => obj ++ Json.obj(key -> value)
          })
        }
      }
      case Some(body) if request.contentType.contains("application/x-www-form-urlencoded") => {
        body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          val values: Map[String, String]      = FormUrlEncodedParser.parse(bodyRaw.utf8String).mapValues(_.last)
          val default                          =
            defaultEntity.orElse(resource.access.template(version, Map.empty).asOpt[JsObject]).getOrElse(Json.obj())
          val jsonValues: Map[String, JsValue] = values.mapValues {
            case str if str == "null"                                     => JsNull
            case str if str == "true"                                     => JsBoolean(true)
            case str if str == "false"                                    => JsBoolean(false)
            case str if str.startsWith("{") && str.endsWith("}")          => Json.parse(str).asObject
            case str if str.startsWith("[") && str.endsWith("]")          => Json.parse(str).asArray
            case str if NumberUtils.isCreatable(str) && str.contains(".") => JsNumber(BigDecimal(str))
            case str if NumberUtils.isCreatable(str)                      => JsNumber(BigDecimal(str))
            case str                                                      => JsString(str)
          }
          Right(jsonValues.toSeq.foldLeft(default) {
            case (obj, (key, value)) if key.contains(".") => {
              val pointer = if (key.startsWith("/")) s"${key.replace(".", "/")}" else s"/${key.replace(".", "/")}"
              val parts   = key.split("\\.").toSeq
              val newObj  = JsonOperationsHelper.genericInsertAtPath(obj, parts, Json.obj())
              val ops     =
                if (obj.atPointer(pointer).isDefined)
                  Seq(
                    Json.obj("op" -> "remove", "path" -> pointer),
                    Json.obj("op" -> "add", "path"    -> pointer, "value" -> value)
                  )
                else
                  Seq(
                    Json.obj("op" -> "add", "path" -> pointer, "value" -> value)
                  )
              JsonPatchHelpers.patchJson(JsArray(ops), newObj).asObject
            }
            case (obj, (key, value))                      => obj ++ Json.obj(key -> value)
          })
        }
      }
      case _                                                                               => Left(Json.obj("error" -> "bad_request", "error_description" -> "bad content type")).vfuture
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

        val reducedItems = if (hasFilters) {
          val items: Seq[JsValue] = arr.value.filter { elem =>
            filters.forall {
              case (key, value) if key.startsWith("$") && key.contains(".") => {
                elem.atPath(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value) if key.contains(".")                        => {
                elem.at(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value) if key.contains("/")                        => {
                elem.atPointer(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value)                                             => {
                (elem \ key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
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
                .sortBy { r => String.valueOf(JsonOperationsHelper.getValueAtPath(sort._1.toLowerCase(), r)._2) }(
                  Ordering[String].reverse
                )

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

  case class PaginatedContent(pages: Int = -1, content: JsValue)

  private def paginateEntity(_entity: JsValue, request: RequestHeader): Option[PaginatedContent] = {
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

        val content = arr.value.slice(paginationPosition, paginationPosition + paginationPageSize)
        PaginatedContent(
          pages = Math.ceil(arr.value.size.toFloat / paginationPageSize).toInt,
          content = JsArray(content)
        ).some
      }
      case _                =>
        PaginatedContent(
          content = _entity
        ).some
    }
  }

  private def projectedEntity(_entity: PaginatedContent, request: RequestHeader): Option[PaginatedContent] = {
    val fields    = request.getQueryString("fields").map(_.split(",").toSeq).getOrElse(Seq.empty[String])
    val hasFields = fields.nonEmpty
    if (hasFields) {
      val content = _entity.content match {
        case arr @ JsArray(_)  =>
          JsArray(arr.value.map { item =>
            JsonOperationsHelper.filterJson(item.asObject, fields)
          })
        case obj @ JsObject(_) => JsonOperationsHelper.filterJson(obj, fields)
        case _                 => return _entity.some
      }
      _entity.copy(content = content).some
    } else {
      _entity.some
    }
  }

  private def result(
      res: Results.Status,
      _entity: JsValue,
      request: RequestHeader,
      _resEntity: Option[Resource],
      addHeaders: Map[String, String] = Map.empty
  ): Future[Result] = {
    val resEntity = _resEntity match {
      case Some(r) => Some(r)
      case None => Resource.unknown.some
    }
    val gzipConfig = GzipConfig(
      enabled = true,
      whiteList =
        Seq("application/json", "application/yaml", "application/yml", "application/yaml+k8s", "application/yml+k8s"),
      blackList = Seq("application/x-ndjson"),
      compressionLevel = 5
    )
    val entity     = if (request.method == "GET") {
      (for {
        filtered  <- filterEntity(_entity, request)
        sorted    <- sortEntity(filtered, request)
        paginated <- paginateEntity(sorted, request)
        projected <- projectedEntity(paginated, request)
      } yield projected).get
    } else {
      PaginatedContent(content = _entity)
    }
    entity.content match {
      case JsArray(seq) if !request.accepts("application/json") && request.accepts("application/x-ndjson") => {
        res
          .sendEntity(
            HttpEntity.Streamed(
              data = Source(
                seq
                  .map(o => o.asObject ++ Json.obj("kind" -> resEntity.get.groupKind))
                  .toList
                  .map(_.stringify.byteString)
              ),
              contentLength = None,
              contentType = "application/x-ndjson".some
            )
          )
          .withHeaders("X-Pages" -> entity.pages.toString)
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      }
      case JsArray(arr)
          if !request.accepts("application/json") && (request
            .accepts("application/yaml") || request.accepts("application/yml")) =>
        res(Yaml.write(JsArray(arr.map(o => o.asObject ++ Json.obj("kind" -> resEntity.get.groupKind)))))
          .as("application/yaml")
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      case _
          if !request.accepts("application/json") && (request
            .accepts("application/yaml") || request.accepts("application/yml")) =>
        res(Yaml.write(entity.content.asObject ++ Json.obj("kind" -> resEntity.get.groupKind)))
          .as("application/yaml")
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      case JsArray(arr)
          if !request.accepts("application/json") && (request
            .accepts("application/yaml+k8s") || request.accepts("application/yml+k8s")) =>
        res(
          Yaml.write(
            JsArray(
              arr.map(o =>
                Json.obj(
                  "apiVersion" -> "proxy.otoroshi.io/v1",
                  "kind"       -> resEntity.get.kind,
                  "metadata"   -> Json.obj(
                    "name" -> o.select("name").asOpt[String].getOrElse("no name").asInstanceOf[String]
                  ),
                  "spec"       -> (o.asObject ++ Json.obj("kind" -> resEntity.get.groupKind))
                )
              )
            )
          )
        )
          .as("application/yaml")
          .withHeaders("X-Pages" -> entity.pages.toString)
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      case _
          if !request.accepts("application/json") && (request
            .accepts("application/yaml+k8s") || request.accepts("application/yml+k8s")) =>
        res(
          Yaml.write(
            Json.obj(
              "apiVersion" -> "proxy.otoroshi.io/v1",
              "kind"       -> resEntity.get.kind,
              "metadata"   -> Json.obj(
                "name" -> entity.content.select("name").asOpt[String].getOrElse("no name").asInstanceOf[String]
              ),
              "spec"       -> (entity.content.asObject ++ Json.obj("kind" -> resEntity.get.groupKind))
            )
          )
        )
          .as("application/yaml")
          .withHeaders("X-Pages" -> entity.pages.toString)
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      case JsArray(arr)                                                                                    =>
        val envelope       = request.getQueryString("envelope").map(_.toLowerCase()).contains("true")
        val prettyQuery    = request.getQueryString("pretty").map(_.toLowerCase())
        val pretty         = prettyQuery match {
          case Some("true")  => true
          case Some("false") => false
          case _             => env.defaultPrettyAdminApi
        }
        val entityWithKind = JsArray(arr.map(o => o.asObject ++ Json.obj("kind" -> resEntity.get.groupKind)))
        val finalEntity    = if (envelope) Json.obj("data" -> entityWithKind) else entityWithKind
        val entityStr      = if (pretty) finalEntity.prettify else finalEntity.stringify
        res(entityStr)
          .as("application/json")
          .withHeaders("X-Pages" -> entity.pages.toString)
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
      case _                                                                                               =>
        val envelope       = request.getQueryString("envelope").map(_.toLowerCase()).contains("true")
        val prettyQuery    = request.getQueryString("pretty").map(_.toLowerCase())
        val pretty         = prettyQuery match {
          case Some("true")  => true
          case Some("false") => false
          case _             => env.defaultPrettyAdminApi
        }
        val entityWithKind = entity.content.asObject ++ Json.obj("kind" -> resEntity.get.groupKind)
        val finalEntity    = if (envelope) Json.obj("data" -> entityWithKind) else entityWithKind
        val entityStr      = if (pretty) finalEntity.prettify else finalEntity.stringify
        res(entityStr)
          .as("application/json")
          .withHeaders("X-Pages" -> entity.pages.toString)
          .applyOnIf(addHeaders.nonEmpty) { r =>
            r.withHeaders(addHeaders.toSeq: _*)
          }
          .applyOnIf(resEntity.nonEmpty && resEntity.get.version.deprecated) { r =>
            r.withHeaders("Otoroshi-Api-Deprecated" -> "yes")
          }
          .applyOn(rez => gzipConfig.handleResult(request, rez))
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
        (group == "any" || group == "all" || r.group == group) && (version == "any" || version == "all" || r.version.name == version) && (r.pluralName == entity || r.kind == entity)
      ) match {
      case None                                               => result(Results.NotFound, notFoundBody, request, None)
      case Some(resource) if !resource.access.canBulk && bulk =>
        result(
          Results.Unauthorized,
          Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
          request,
          resource.some
        )
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
          )
        } else if (create && !resource.access.canCreate) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          )
        } else if (update && !resource.access.canUpdate) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          )
        } else if (delete && !resource.access.canDelete) {
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot do that"),
            request,
            resource.some
          )
        } else {
          f(resource)
        }
      }
    }
  }

  // GET /apis/health
  def health() = ApiAction.async {
    HealthController.fetchHealth().map {
      case Left(payload)  => ServiceUnavailable(payload)
      case Right(payload) => Ok(payload)
    }
  }

  // GET /apis/metrics
  def metrics() = ApiAction { ctx =>
    val format      = ctx.request.getQueryString("format")
    val filter      = ctx.request.getQueryString("filter")
    val acceptsJson = ctx.request.accepts("application/json")
    val acceptsProm = ctx.request.accepts("application/prometheus")
    if (env.metricsEnabled) {
      HealthController.fetchMetrics(format, acceptsJson, acceptsProm, filter)
    } else {
      NotFound(Json.obj("error" -> "metrics not enabled"))
    }
  }

  // GET /apis/entities
  def entities() = ApiAction { ctx =>
    if (ctx.request.getQueryString("schema").contains("false")) {
      Ok(
        Json.obj(
          "version"   -> env.otoroshiVersion,
          "resources" -> JsArray(env.allResources.resources.map(_.json))
        )
      )
    } else {
      Ok(
        Json.obj(
          "version"   -> env.otoroshiVersion,
          "resources" -> JsArray(env.allResources.resources.map(_.jsonWithSchema))
        )
      )
    }
  }

  // PATCH /apis/:group/:version/:entity/_bulk
  def bulkPatch(group: String, version: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
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
              resource.access
                .findOne(version, resource.access.extractIdJson(e))
                .map(ee => (e.select("patch").asValue, ee))
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
                      .create(
                        version,
                        resource.singularName,
                        resource.access.extractIdJson(patchedEntity).some,
                        patchedEntity,
                        WriteAction.Update
                      )
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
                            .obj(
                              "status"   -> 200,
                              "updated"  -> true,
                              "id"       -> resource.access.extractIdJson(createdEntity),
                              "id_field" -> resource.access.idFieldName()
                            )
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
        )
    }
  }

  // POST /apis/:group/:version/:entity/_bulk
  def bulkCreate(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
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
                case Right((_, _entity)) => {
                  val id     = resource.access.extractIdJson(_entity)
                  val entity = _entity.asObject ++ Json.obj(resource.access.idFieldName() -> id)
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
                          resource.access.create(version, resource.singularName, None, entity, WriteAction.Create).map {
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
                                .obj(
                                  "status"   -> 201,
                                  "created"  -> true,
                                  "id"       -> resource.access.extractIdJson(createdEntity),
                                  "id_field" -> resource.access.idFieldName()
                                )
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
          )
      }
    }

  // PUT /apis/:group/:version/:entity/_bulk
  def bulkUpdate(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
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
                  val id = resource.access.extractIdJson(entity)
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
                            .create(version, resource.singularName, resource.access.extractIdJson(entity).some, entity, WriteAction.Update)
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
                                  .obj(
                                    "status"   -> 200,
                                    "updated"  -> true,
                                    "id"       -> resource.access.extractIdJson(createdEntity),
                                    "id_field" -> resource.access.idFieldName()
                                  )
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
          )
      }
    }

  // DELETE /apis/:group/:version/:entity/_bulk
  def bulkDelete(group: String, version: String, entity: String) =
    ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
      ctx.request.headers.get("Content-Type") match {
        case Some("application/x-ndjson") =>
          withResource(group, version, entity, ctx.request, bulk = true) { resource =>
            val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
            val src      = ctx.request.body
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
              .map(bs => Try(Json.parse(bs.utf8String)))
              .collect { case Success(e) => e }
              .mapAsync(1) { e =>
                resource.access.findOne(version, resource.access.extractIdJson(e)).map(ee => (e, ee))
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
                    Json.obj("id" -> resource.access.extractIdJson(entity)),
                    s"${resource.singularName}Deleted".some
                  )
                  resource.access.deleteOne(version, resource.access.extractIdJson(entity)).map { _ =>
                    Json
                      .obj(
                        "status"   -> 200,
                        "deleted"  -> true,
                        "id"       -> resource.access.extractIdJson(entity),
                        "id_field" -> resource.access.idFieldName()
                      )
                      .stringify
                      .byteString
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
          )
      }
    }

  // GET /apis/:group/:version/:entity/_count
  def countAll(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      val fuEntities = if (ctx.request.getQueryString("in_mem").contains("true")) {
        resource.access.allJson().vfuture
      } else {
        resource.access.findAll(version)
      }
      fuEntities.flatMap { entities =>
        adminApiEvent(
          ctx,
          s"COUNT_ALL_${resource.pluralName.toUpperCase()}",
          s"User count all ${resource.pluralName}",
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
      val fuEntities = if (ctx.request.getQueryString("in_mem").contains("true")) {
        resource.access.allJson().vfuture
      } else {
        resource.access.findAll(version)
      }
      fuEntities.flatMap { entities =>
        adminApiEvent(
          ctx,
          s"READ_ALL_${resource.pluralName.toUpperCase()}",
          s"User read all ${resource.pluralName}",
          Json.obj(),
          None
        )
        result(Results.Ok, JsArray(entities.filter(e => ctx.canUserReadJson(e))), ctx.request, resource.some)
      }
    }
  }

  private def getStatus(err: JsValue): Results.Status = {
    err.select("http_status_code").asOptInt match {
      case None => Results.InternalServerError
      case Some(code) => Results.Status(code)
    }
  }

  private def cleanError(err: JsValue): JsValue = {
    err match {
      case obj: JsObject => obj - "http_status_code"
      case _ => err
    }
  }

  // POST /apis/:group/:version/:entity
  def create(group: String, version: String, entity: String) = ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request, resource, version) flatMap {
        case Left(err)                                  => result(Results.BadRequest, err, ctx.request, resource.some)
        case Right(body) if !ctx.canUserWriteJson(body) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          )
        case Right(_body)                               => {
          val dev  = if (env.isDev) "_dev" else ""
          val id   = Try(resource.access.extractIdJson(_body))
            .getOrElse(s"${resource.singularName}${dev}_${IdGenerator.uuid}")
          val body = _body.asObject ++ Json.obj(resource.access.idFieldName() -> id)
          resource.access.findOne(version, id).flatMap {
            case Some(oldEntity) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "resource already exists"),
                ctx.request,
                resource.some
              )
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
                  )
                case JsSuccess(_, _) =>
                  resource.access.create(version, resource.singularName, None, body, WriteAction.Create).flatMap {
                    case Left(err) => result(getStatus(err), cleanError(err), ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"CREATE_${resource.singularName.toUpperCase()}",
                        s"User created a ${resource.singularName}",
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
          s"User deleted all ${resource.pluralName}",
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
      val templ = resource.access.template(version, ctx.request.queryString.mapValues(_.last))
      if (templ == Json.obj()) {
        NotFound(Json.obj("error" -> "template not found !")).vfuture
      } else {
        Ok(templ).vfuture
      }
    }
  }

  // GET /apis/:group/:version/:entity/:id/_template
  def schema(group: String, version: String, entity: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      val schema = resource.version.finalSchema(resource.kind, resource.access.clazz)
      Ok(schema).vfuture
    }
  }

  // GET /apis/:group/:version/:entity/:id
  def findOne(group: String, version: String, entity: String, id: String) = ApiAction.async { ctx =>
    withResource(group, version, entity, ctx.request) { resource =>
      val fuOptEntity = if (ctx.request.getQueryString("in_mem").contains("true")) {
        resource.access.oneJson(id).vfuture
      } else {
        resource.access.findOne(version, id)
      }
      fuOptEntity.flatMap {
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
        case None                                          => result(Results.NotFound, notFoundBody, ctx.request, resource.some)
        case Some(entity) if !ctx.canUserWriteJson(entity) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          )
        case Some(entity)                                  =>
          adminApiEvent(
            ctx,
            s"DELETE_${resource.singularName.toUpperCase()}",
            s"User deleted a ${resource.singularName}",
            Json.obj("id" -> id),
            s"${resource.singularName}Deleted".some
          )
          resource.access.deleteOne(version, id).flatMap { _ =>
            result(Results.Ok, entity, ctx.request, resource.some)
          }
      }
    }
  }

  // POST /apis/:group/:version/:entity/:id
  def upsert(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request, resource, version) flatMap {
        case Left(err)     => result(Results.BadRequest, err, ctx.request, resource.some)
        case Right(__body) => {
          val _body = __body.asObject ++ Json.obj(resource.access.idFieldName() -> id)
          //resource.access.findOne(version, id).flatMap {
          //  case None                                                =>
          //    result(
          //      Results.Unauthorized,
          //      Json.obj("error" -> "unauthorized", "error_description" -> "resource does not exists"),
          //      ctx.request,
          //      resource.some
          //    ).vfuture
          //  case Some(oldEntity) if !ctx.canUserWriteJson(oldEntity) =>
          //    result(
          //      Results.Unauthorized,
          //      Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
          //      ctx.request,
          //      resource.some
          //    ).vfuture
          //  case Some(oldEntity)                                     => {
          resource.access.validateToJson(_body, resource.singularName, ctx.backOfficeUser) match {
            case err @ JsError(_)                                =>
              result(Results.BadRequest, JsError.toJson(err), ctx.request, resource.some)
            case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                ctx.request,
                resource.some
              )
            case JsSuccess(body, _)                              => {
              resource.access.findOne(version, id).flatMap {
                case None      =>
                  resource.access.create(version, resource.singularName, Some(id), body, WriteAction.Create).flatMap {
                    case Left(err) => result(getStatus(err), cleanError(err), ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"CREATE_${resource.singularName.toUpperCase()}",
                        s"User created a ${resource.singularName}",
                        body,
                        s"${resource.singularName}Created".some
                      )
                      result(Results.Created, res, ctx.request, resource.some)
                  }
                case Some(old) =>
                  val oldEntity  = resource.access.format.reads(old).get
                  val newEntity  = resource.access.format.reads(body).get
                  val hasChanged = oldEntity == newEntity
                  resource.access.create(version, resource.singularName, id.some, body, WriteAction.Update).flatMap {
                    case Left(err) => result(getStatus(err), cleanError(err), ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"UPDATE_${resource.singularName.toUpperCase()}",
                        s"User updated a ${resource.singularName}",
                        body,
                        s"${resource.singularName}Updated".some
                      )
                      result(
                        Results.Ok,
                        res,
                        ctx.request,
                        resource.some,
                        Map("Otoroshi-Entity-Updated" -> hasChanged.toString)
                      )
                  }
              }
            }
            //  }
            //}
          }
        }
      }
    }
  }

  // PUT /apis/:group/:version/:entity/:id
  def update(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
    withResource(group, version, entity, ctx.request) { resource =>
      bodyIn(ctx.request, resource, version) flatMap {
        case Left(err)     => result(Results.BadRequest, err, ctx.request, resource.some)
        case Right(__body) => {
          val _body = __body.asObject ++ Json.obj(resource.access.idFieldName() -> id)
          resource.access.findOne(version, id).flatMap {
            case None                                                =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "resource does not exists"),
                ctx.request,
                resource.some
              )
            case Some(oldEntity) if !ctx.canUserWriteJson(oldEntity) =>
              result(
                Results.Unauthorized,
                Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                ctx.request,
                resource.some
              )
            case Some(oldEntity)                                     => {
              resource.access.validateToJson(_body, resource.singularName, ctx.backOfficeUser) match {
                case err @ JsError(_)                                =>
                  result(Results.BadRequest, JsError.toJson(err), ctx.request, resource.some)
                case JsSuccess(_, _) if !ctx.canUserWriteJson(_body) =>
                  result(
                    Results.Unauthorized,
                    Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
                    ctx.request,
                    resource.some
                  )
                case JsSuccess(body, _)                              => {
                  resource.access.findOne(version, id).flatMap {
                    case None    => result(Results.NotFound, notFoundBody, ctx.request, resource.some)
                    case Some(_) =>
                      resource.access.create(version, resource.singularName, id.some, body, WriteAction.Update).flatMap {
                        case Left(err) => result(getStatus(err), cleanError(err), ctx.request, resource.some)
                        case Right(res) =>
                          adminApiEvent(
                            ctx,
                            s"UPDATE_${resource.singularName.toUpperCase()}",
                            s"User updated a ${resource.singularName}",
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
  def patch(group: String, version: String, entity: String, id: String) = ApiAction.async(sourceBodyParser) { ctx: ApiActionContext[Source[ByteString, _]] =>
    import otoroshi.utils.json.JsonPatchHelpers.patchJson
    withResource(group, version, entity, ctx.request) { resource =>
      resource.access.findOne(version, id).flatMap {
        case None                                            => result(Results.NotFound, notFoundBody, ctx.request, resource.some)
        case Some(current) if !ctx.canUserWriteJson(current) =>
          result(
            Results.Unauthorized,
            Json.obj("error" -> "unauthorized", "error_description" -> "you cannot access this resource"),
            ctx.request,
            resource.some
          )
        case Some(current)                                   => {
          val isFormDataBody                  = ctx.request.contentType.contains(
            "application/x-www-form-urlencoded"
          ) || ctx.request.contentType.contains("application/json+oto-patch")
          val defaultEntity: Option[JsObject] = if (isFormDataBody) Some(current.asObject) else None
          bodyIn(ctx.request, resource, version, defaultEntity) flatMap {
            case Left(err)   => result(Results.BadRequest, err, ctx.request, resource.some)
            case Right(body) => {
              val _patchedBody = if (isFormDataBody) body else patchJson(body, current)
              val patchedBody  = _patchedBody.asObject ++ Json.obj(resource.access.idFieldName() -> id)
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
                  )
                case JsSuccess(_, _) =>
                  resource.access.create(version, resource.singularName, id.some, patchedBody, WriteAction.Update).flatMap {
                    case Left(err) => result(getStatus(err), cleanError(err), ctx.request, resource.some)
                    case Right(res) =>
                      adminApiEvent(
                        ctx,
                        s"PATCHED_${resource.singularName.toUpperCase()}",
                        s"User patched a ${resource.singularName}",
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

  def openapi() = Action { req =>
    val body = otoroshi.api.OpenApi.generate(env, req.getQueryString("version"))
    Ok(body).as("application/json").withHeaders("Access-Control-Allow-Origin" -> "*")
  }
}
