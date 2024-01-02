package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import kaleidoscope._
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterString, BetterSyntax}
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class EurekaApp(name: String, instances: Seq[EurekaInstance])

case class EurekaInstance(
    instanceId: String,
    hostname: String,
    app: String,
    ipAddr: String,
    status: String,
    overriddenStatus: String,
    port: Int,
    securePort: Int,
    isSecured: Boolean,
    metadata: Map[String, String],
    homePageUrl: String,
    statusPageUrl: String,
    healthCheckUrl: String,
    vipAddress: String,
    secureVipAddress: String,
    isCoordinatingDiscoveryServer: String,
    lastUpdatedTimestamp: Long,
    lastDirtyTimestamp: Long
)

object EurekaInstance {
  def toTarget(instance: EurekaInstance): NgTarget = {
    NgTarget(
      id = instance.instanceId,
      hostname = instance.hostname,
      tls = instance.isSecured,
      ipAddress = Some(instance.ipAddr),
      port = if (instance.isSecured) instance.securePort else instance.port
    )
  }

  val format: Format[EurekaInstance] = new Format[EurekaInstance] {
    override def writes(o: EurekaInstance): JsValue          = Json.obj(
      "instanceId"                    -> o.instanceId,
      "hostname"                      -> o.hostname,
      "app"                           -> o.app,
      "ipAddr"                        -> o.ipAddr,
      "status"                        -> o.status,
      "overriddenStatus"              -> o.overriddenStatus,
      "port"                          -> o.port,
      "securePort"                    -> o.securePort,
      "isSecured"                     -> o.isSecured,
      "metadata"                      -> o.metadata,
      "homePageUrl"                   -> o.homePageUrl,
      "statusPageUrl"                 -> o.statusPageUrl,
      "healthCheckUrl"                -> o.healthCheckUrl,
      "vipAddress"                    -> o.vipAddress,
      "secureVipAddress"              -> o.secureVipAddress,
      "isCoordinatingDiscoveryServer" -> o.isCoordinatingDiscoveryServer,
      "lastUpdatedTimestamp"          -> o.lastUpdatedTimestamp,
      "lastDirtyTimestamp"            -> o.lastDirtyTimestamp
    )
    override def reads(o: JsValue): JsResult[EurekaInstance] = Try {
      EurekaInstance(
        instanceId = (o \ "instanceId").as[String],
        hostname = (o \ "hostName").as[String],
        app = (o \ "app").as[String],
        ipAddr = (o \ "ipAddr").as[String],
        status = (o \ "status").as[String],
        overriddenStatus = (o \ "overriddenStatus").as[String],
        port = (o \ "port" \ "$").as[Int],
        securePort = (o \ "securePort" \ "$").as[Int],
        isSecured = (o \ "securePort" \ "@enabled").asOpt[Boolean].getOrElse(false),
        metadata = (o \ "metadata").as[Map[String, String]],
        homePageUrl = (o \ "homePageUrl").as[String],
        statusPageUrl = (o \ "statusPageUrl").as[String],
        healthCheckUrl = (o \ "healthCheckUrl").as[String],
        vipAddress = (o \ "vipAddress").as[String],
        secureVipAddress = (o \ "secureVipAddress").as[String],
        isCoordinatingDiscoveryServer = (o \ "isCoordinatingDiscoveryServer").as[String],
        lastUpdatedTimestamp = (o \ "lastUpdatedTimestamp").as[String].toLong,
        lastDirtyTimestamp = (o \ "lastDirtyTimestamp").as[String].toLong
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

object EurekaApp {
  val format: Format[EurekaApp] = new Format[EurekaApp] {
    override def writes(o: EurekaApp): JsValue             = Json.obj(
      "name"      -> o.name,
      "instances" -> o.instances.map(EurekaInstance.format.writes)
    )
    override def reads(json: JsValue): JsResult[EurekaApp] = Try {
      EurekaApp(
        name = (json \ "name").asOpt[String].getOrElse("UNKNOWN_APP"),
        instances = (json \ "instances")
          .as[Seq[JsObject]]
          .map(EurekaInstance.format.reads)
          .collect { case JsSuccess(value, _) => value }
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class EurekaServerConfig(evictionTimeout: Int = 300) extends NgPluginConfig {
  def json: JsValue = EurekaServerConfig.format.writes(this)
}

object EurekaServerConfig {
  val format: Format[EurekaServerConfig] = new Format[EurekaServerConfig] {
    override def writes(o: EurekaServerConfig): JsValue          = Json.obj(
      "evictionTimeout" -> o.evictionTimeout
    )
    override def reads(o: JsValue): JsResult[EurekaServerConfig] = Try {
      EurekaServerConfig(
        evictionTimeout = (o \ "evictionTimeout").asOpt[Int].getOrElse(300)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class EurekaServerSink extends NgBackendCall {

  override def useDelegates: Boolean  = false
  override def multiInstance: Boolean = false
  override def core: Boolean          = false
  override def name: String           = "Eureka instance"

  override def description: Option[String]                 = "Eureka plugin description".some
  override def defaultConfigObject: Option[NgPluginConfig] = EurekaServerConfig().some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.ServiceDiscovery)
  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)

  private def notFoundResponse() = {
    emptyBodyResponse(404, Map("Content-Type" -> "application/json")).vfuture
  }

  private def successfulResponse(body: Seq[JsValue]) = {
    inMemoryBodyResponse(
      200,
      Map("Content-Type" -> "application/xml"),
      otoroshi.utils.xml.Xml.toXml(Json.obj("applications" -> body)).toString().byteString
    ).vfuture
  }

  private def getApps(pluginId: String)(implicit env: Env, ec: ExecutionContext, mat: Materializer) = {

    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap(arr => successfulResponse(arr.map(_.utf8String.parseJson)))
  }

  private def createApp(
      pluginId: String,
      appId: String,
      hasBody: Boolean,
      body: Future[ByteString],
      evictionTimeout: Int
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    if (hasBody)
      body.flatMap { bodyRaw =>
        val instance      = bodyRaw.utf8String.parseJson.as[JsObject]
        val name          = (instance \ "instance" \ "app").asOpt[String].getOrElse("Unknown app")
        val rawInstanceId = (instance \ "instance" \ "instanceId").asOpt[String]

        rawInstanceId match {
          case Some(instanceId) =>
            env.datastores.rawDataStore
              .set(
                s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
                Json
                  .obj(
                    "application" -> Json
                      .obj("name" -> name)
                      .deepMerge(
                        instance
                          .deepMerge(Json.obj("instance" -> Json.obj("last_heartbeat" -> new java.util.Date().getTime)))
                      )
                  )
                  .stringify
                  .byteString,
                evictionTimeout.seconds.toMillis.some
              )
              .flatMap { res =>
                if (res) {
                  emptyBodyResponse(204, Map.empty).vfuture
                } else {
                  inMemoryBodyResponse(
                    400,
                    Map("Content-Type" -> "application/json"),
                    Json
                      .obj("error" -> "an error happened during registration of new app")
                      .stringify
                      .byteString
                  ).vfuture
                }
              }
          case None             =>
            inMemoryBodyResponse(
              400,
              Map("Content-Type" -> "application/json"),
              Json.obj("error" -> "missing instance id").stringify.byteString
            ).vfuture
        }
      }
    else
      inMemoryBodyResponse(
        400,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "missing body or invalid body").stringify.byteString
      ).vfuture
  }

  private def getAppWithId(pluginId: String, appId: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:*")
      .flatMap { instances =>
        if (instances.isEmpty)
          notFoundResponse()
        else
          inMemoryBodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml
              .toXml(
                Json.obj(
                  "application" -> Json.obj(
                    "name"      -> (instances.head.utf8String.parseJson \ "name").as[String],
                    "instances" -> instances.map(instance => {
                      Json.obj("instance" -> (instance.utf8String.parseJson \ "application" \ "instance").as[JsValue])
                    })
                  )
                )
              )
              .toString()
              .byteString
          ).vfuture
      }
  }

  private def getAppWithIdAndInstanceId(pluginId: String, appId: String, instanceId: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
      .flatMap {
        case Some(instance) =>
          inMemoryBodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml.toXml(instance.utf8String.parseJson).toString().byteString
          ).vfuture
        case None           =>
          emptyBodyResponse(404, Map("Content-Type" -> "application/json")).vfuture
      }
  }

  private def getInstanceWithId(pluginId: String, instanceId: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:*:$instanceId")
      .flatMap { instances =>
        if (instances.isEmpty)
          emptyBodyResponse(404, Map("Content-Type" -> "application/json")).vfuture
        else
          inMemoryBodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml.toXml(instances.head.utf8String.parseJson).toString().byteString
          ).vfuture
      }
  }

  private def deleteAppWithId(pluginId: String, appId: String, instanceId: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .del(Seq(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId"))
      .flatMap { _ =>
        inMemoryBodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("deleted" -> "done").stringify.byteString
        ).vfuture
      }
  }

  private def checkHeartbeat(pluginId: String, appId: String, instanceId: String, evictionTimeout: Int)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    val key = s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId"
    env.datastores.rawDataStore
      .get(key)
      .flatMap {
        case None    => emptyBodyResponse(404, Map("Content-Type" -> "application/json")).vfuture
        case Some(v) =>
          env.datastores.rawDataStore
            .set(
              key,
              v.utf8String.parseJson
                .as[JsObject]
                .deepMerge(
                  Json.obj(
                    "application" -> Json.obj(
                      "instance" -> Json.obj("last_heartbeat" -> new java.util.Date().getTime)
                    )
                  )
                )
                .stringify
                .byteString,
              (evictionTimeout * 1000).seconds.toMillis.some
            )
            .flatMap {
              case true  => emptyBodyResponse(200, Map("Content-Type" -> "application/json")).vfuture
              case false => emptyBodyResponse(400, Map.empty).vfuture
            }
      }
  }

  private def takeInstanceOutOfService(
      pluginId: String,
      appId: String,
      instanceId: String,
      status: Option[String]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
      .flatMap {
        case None      => notFoundResponse()
        case Some(app) =>
          val updatedApp = app.utf8String.parseJson
            .as[JsObject]
            .deepMerge(
              Json.obj(
                "application" -> Json.obj(
                  "instance" -> Json.obj(
                    "status" -> status.getOrElse("UP").asInstanceOf[String]
                  )
                )
              )
            )
          env.datastores.rawDataStore
            .set(
              s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
              updatedApp.stringify.byteString,
              120.seconds.toMillis.some
            )
            .flatMap {
              case true  =>
                emptyBodyResponse(200, Map("Content-Type" -> "application/json")).vfuture
              case false =>
                notFoundResponse()
            }

      }
  }

  private def putMetadata(pluginId: String, appId: String, instanceId: String, queryString: Option[String])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    if (queryString.isEmpty)
      inMemoryBodyResponse(
        400,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "missing query string").stringify.byteString
      ).vfuture
    else
      env.datastores.rawDataStore
        .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
        .flatMap {
          case None      => notFoundResponse()
          case Some(app) =>
            val jsonApp    = app.utf8String.parseJson.as[JsObject]
            val updatedApp = jsonApp.deepMerge(
              Json.obj(
                "application" -> Json.obj(
                  "instance" -> Json.obj(
                    "metadata" -> queryString.get
                      .split("&")
                      .map(_.split("="))
                      .foldLeft(Json.obj()) { case (acc, c) =>
                        acc ++ Json.obj(c.head -> c(1))
                      }
                  )
                )
              )
            )
            env.datastores.rawDataStore
              .set(
                s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
                updatedApp.stringify.byteString,
                None
              )
              .flatMap {
                case true  =>
                  emptyBodyResponse(200, Map("Content-Type" -> "application/json")).vfuture
                case false =>
                  notFoundResponse()
              }

        }
  }

  private def getInstancesUnderVipAddress(pluginId: String, vipAddress: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap { arr =>
        val apps = arr
          .map(_.utf8String.parseJson)
          .filter(app => (app \ "application" \ "instance" \ "vipAddress").as[String] == vipAddress)

        if (apps.isEmpty)
          notFoundResponse()
        else
          successfulResponse(apps)
      }
  }

  private def getInstancesUnderSecureVipAddress(pluginId: String, svipAddress: String)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap { arr =>
        val apps = arr
          .map(_.utf8String.parseJson)
          .filter(app => (app \ "application" \ "instance" \ "secureVipAddress").as[String] == svipAddress)

        if (apps.isEmpty)
          notFoundResponse()
        else
          successfulResponse(apps)
      }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val pluginId = ctx.route.id
    val body     = ctx.request.body.runFold(ByteString.empty)(_ ++ _)
    val config   = ctx.cachedConfig(internalName)(EurekaServerConfig.format).getOrElse(EurekaServerConfig())

    (ctx.request.method, ctx.request.path) match {
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/status")   =>
        takeInstanceOutOfService(pluginId, appId, instanceId, ctx.rawRequest.getQueryString("value"))
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/metadata") =>
        putMetadata(pluginId, appId, instanceId, ctx.request.queryString)
      case ("GET", r"/eureka/apps/$appId@(.*)")                           =>
        if (s"$appId".isEmpty)
          getApps(pluginId)
        else
          getAppWithId(pluginId, appId)
      case ("GET", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)")          =>
        getAppWithIdAndInstanceId(pluginId, appId, instanceId)
      case ("GET", r"/eureka/instances/$instanceId@(.*)")                 =>
        getInstanceWithId(pluginId, instanceId)
      case ("GET", r"/eureka/vips/$vipAddress@(.*)")                      =>
        getInstancesUnderVipAddress(pluginId, vipAddress)
      case ("GET", r"/eureka/svips/$svipAddress@(.*)")                    =>
        getInstancesUnderSecureVipAddress(pluginId, svipAddress)
      case ("POST", r"/eureka/apps/$appId@(.*)")                          =>
        createApp(pluginId, appId, ctx.request.hasBody, body, config.evictionTimeout)
      case ("DELETE", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)")       =>
        deleteAppWithId(pluginId, appId, instanceId)
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)")          =>
        checkHeartbeat(pluginId, appId, instanceId, config.evictionTimeout)
      case _                                                              => notFoundResponse()
    }
  }
}

case class EurekaTargetConfig(eurekaServer: Option[String] = None, eurekaApp: Option[String] = None)
    extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "eureka_server" -> eurekaServer,
    "eureka_app"    -> eurekaApp
  )
}

object EurekaTargetConfig {
  val format: Format[EurekaTargetConfig] = new Format[EurekaTargetConfig] {
    override def writes(o: EurekaTargetConfig): JsValue          = Json.obj(
      "eureka_server" -> o.eurekaServer,
      "eureka_app"    -> o.eurekaApp
    )
    override def reads(o: JsValue): JsResult[EurekaTargetConfig] = Try {
      EurekaTargetConfig(
        eurekaServer = (o \ "eureka_server").asOpt[String],
        eurekaApp = (o \ "eureka_app").asOpt[String]
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

object EurekaTarget {
  private val cache: Cache[String, Seq[JsValue]] = Scaffeine()
    .expireAfterWrite(15.seconds)
    .maximumSize(1000)
    .build()
}

class EurekaTarget extends NgPreRouting {

  override def name: String = "Internal Eureka target"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def description: Option[String] = {
    Some(s"""
         | This plugin can be used to used a target that come from an internal Eureka server.
         | If you want to use a target which it locate outside of Otoroshi, you must use the External Eureka Server.
         |""".stripMargin)
  }

  override def defaultConfigObject: Option[NgPluginConfig] = EurekaTargetConfig().some
  override def multiInstance: Boolean                      = false

  private def updatePreExtractedRequestTargetsKey(ctx: NgPreRoutingContext, apps: Seq[JsValue]) = {
    ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey -> apps.map(application => {
      val instance = (application \ "application" \ "instance").as(EurekaInstance.format.reads)
      EurekaInstance.toTarget(instance)
    }))
  }

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val rawConfig = ctx.cachedConfig(internalName)(EurekaTargetConfig.format)
    val pluginId  = ctx.route.id

    rawConfig match {
      case Some(config) =>
        (config.eurekaServer, config.eurekaApp) match {
          case (Some(eurekaServer), Some(eurekaApp)) =>
            val key = s"${env.storageRoot}:plugins:${pluginId}:eureka-server-$eurekaServer:apps:$eurekaApp:*"
            EurekaTarget.cache.getIfPresent(key) match {
              case None       =>
                env.datastores.rawDataStore
                  .allMatching(key)
                  .flatMap(rawApps => {
                    val apps = rawApps.map(a => a.utf8String.parseJson)
                    updatePreExtractedRequestTargetsKey(ctx, apps)
                    EurekaTarget.cache.put(key, apps)
                    Done.right.vfuture
                  })
              case Some(apps) =>
                updatePreExtractedRequestTargetsKey(ctx, apps)
                Done.right.vfuture
            }
          case _                                     =>
            Errors
              .craftResponseResult(
                "An error occured",
                Results.BadRequest,
                ctx.request,
                None,
                Some("errors.eureka.config"),
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => Left(NgPreRoutingErrorWithResult(r)))
        }
      case None         =>
        Errors
          .craftResponseResult(
            "An error occured",
            Results.BadRequest,
            ctx.request,
            None,
            Some("errors.eureka.config"),
            duration = ctx.report.getDurationNow(),
            overhead = ctx.report.getOverheadInNow(),
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => Left(NgPreRoutingErrorWithResult(r)))
    }
  }
}

case class ExternalEurekaTargetConfig(serviceUrlDefaultZone: Option[String] = None, eurekaApp: Option[String] = None)
    extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "service_url_default_zone" -> serviceUrlDefaultZone,
    "eureka_app"               -> eurekaApp
  )
}

object ExternalEurekaTargetConfig {
  val format: Format[ExternalEurekaTargetConfig] = new Format[ExternalEurekaTargetConfig] {
    override def writes(o: ExternalEurekaTargetConfig): JsValue          = Json.obj(
      "service_url_default_zone" -> o.serviceUrlDefaultZone,
      "eureka_app"               -> o.eurekaApp
    )
    override def reads(o: JsValue): JsResult[ExternalEurekaTargetConfig] = Try {
      ExternalEurekaTargetConfig(
        serviceUrlDefaultZone = (o \ "service_url_default_zone").asOpt[String],
        eurekaApp = (o \ "eureka_app").asOpt[String]
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

object ExternalEurekaTarget {
  private val cache: Cache[String, Seq[NgTarget]] = Scaffeine()
    .expireAfterWrite(15.seconds)
    .maximumSize(1000)
    .build()
}

class ExternalEurekaTarget extends NgPreRouting {

  override def name: String = "External Eureka target"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.ServiceDiscovery)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def description: Option[String] = {
    Some(s"""
         | This plugin can be used to used a target that come from an external Eureka server.
         | If you want to use a target that is directly exposed by an implementation of Eureka by Otoroshi,
         | you must use the Internal Eureka Server.
         |""".stripMargin)
  }

  override def defaultConfigObject: Option[NgPluginConfig] = EurekaTargetConfig().some
  override def multiInstance: Boolean                      = false

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val rawConfig = ctx.cachedConfig(internalName)(ExternalEurekaTargetConfig.format)
    val pluginId  = ctx.route.id

    rawConfig match {
      case Some(config) =>
        (config.serviceUrlDefaultZone, config.eurekaApp) match {
          case (Some(serviceUrlDefaultZone), Some(eurekaApp)) =>
            val key = s"${env.storageRoot}:plugins:${pluginId}:eureka-server:$eurekaApp:*"

            ExternalEurekaTarget.cache.getIfPresent(key) match {
              case None       =>
                env.Ws
                  .url(s"$serviceUrlDefaultZone/apps/$eurekaApp")
                  .withHttpHeaders(Seq("Accept" -> "application/json"): _*)
                  .get()
                  .flatMap { res =>
                    if (res.status == 200) {
                      val instances = (res.body.parseJson
                        .as[JsObject] \ "application" \ "instance")
                        .as[JsArray]
                        .value
                        .map(instance => instance.as(EurekaInstance.format.reads))
                        .map(EurekaInstance.toTarget)

                      ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey -> instances)
                      ExternalEurekaTarget.cache.put(key, instances)

                      Done.right.vfuture
                    } else {
                      Errors
                        .craftResponseResult(
                          "An error occured",
                          Results.BadRequest,
                          ctx.request,
                          None,
                          Some("errors.eureka.config"),
                          duration = ctx.report.getDurationNow(),
                          overhead = ctx.report.getOverheadInNow(),
                          attrs = ctx.attrs
                        )
                    }
                  }
                Done.right.vfuture
              case Some(apps) =>
                ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey -> apps)
                Done.right.vfuture
            }
          case _                                              =>
            Errors
              .craftResponseResult(
                "Wrong config",
                Results.BadRequest,
                ctx.request,
                None,
                Some("errors.eureka.config"),
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => Left(NgPreRoutingErrorWithResult(r)))
        }
      case None         =>
        Errors
          .craftResponseResult(
            "Missing config",
            Results.BadRequest,
            ctx.request,
            None,
            Some("errors.eureka.config"),
            duration = ctx.report.getDurationNow(),
            overhead = ctx.report.getOverheadInNow(),
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => Left(NgPreRoutingErrorWithResult(r)))
    }
  }
}
