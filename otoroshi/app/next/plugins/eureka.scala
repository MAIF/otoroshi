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

case class EurekaApp(
    name: String,
    instances: Seq[EurekaInstance])

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
      port = if(instance.isSecured) instance.securePort else instance.port
    )
  }

  val format: Format[EurekaInstance] = new Format[EurekaInstance] {
    override def writes(o: EurekaInstance): JsValue             = Json.obj(
      "instanceId" -> o.instanceId,
      "hostname" -> o.hostname,
      "app" -> o.app,
      "ipAddr" -> o.ipAddr,
      "status" -> o.status,
      "overriddenStatus" -> o.overriddenStatus,
      "port" -> o.port,
      "securePort" -> o.securePort,
      "isSecured" -> o.isSecured,
      "metadata" -> o.metadata,
      "homePageUrl" -> o.homePageUrl,
      "statusPageUrl" -> o.statusPageUrl,
      "healthCheckUrl" -> o.healthCheckUrl,
      "vipAddress" -> o.vipAddress,
      "secureVipAddress" -> o.secureVipAddress,
      "isCoordinatingDiscoveryServer" -> o.isCoordinatingDiscoveryServer,
      "lastUpdatedTimestamp" -> o.lastUpdatedTimestamp,
      "lastDirtyTimestamp" -> o.lastDirtyTimestamp
    )
    override def reads(o: JsValue): JsResult[EurekaInstance] = Try {
      EurekaInstance(
        instanceId = (o \ "instanceId").as[String],
        hostname = (o \ "hostName").as[String],
        app = (o \ "app").as[String],
        ipAddr = (o \ "ipAddr").as[String],
        status = (o \ "status").as[String],
        overriddenStatus  = (o \ "overriddenStatus").as[String],
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
      "name" -> o.name,
      "instances" -> o.instances.map(EurekaInstance.format.writes)
    )
    override def reads(json: JsValue): JsResult[EurekaApp] = Try {
      EurekaApp(
        name = (json \ "name").asOpt[String].getOrElse("UNKNOWN_APP"),
        instances = (json \ "instances").as[Seq[JsObject]]
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
    override def writes(o: EurekaServerConfig): JsValue             = Json.obj(
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

  override def useDelegates: Boolean = false
  override def multiInstance: Boolean = false
  override def core: Boolean = false
  override def name: String = "Eureka instance"

  override def description: Option[String] = "Eureka plugin description".some
  override def defaultConfigObject: Option[NgPluginConfig] = EurekaServerConfig().some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  private def notFoundResponse() = {
    bodyResponse(404,
      Map("Content-Type" -> "application/json"), Source.empty).vfuture
  }

  private def successfulResponse(body: Seq[JsValue]) = {
    bodyResponse(
      200,
      Map("Content-Type" -> "application/xml"),
      otoroshi.utils.xml.Xml.toXml(Json.obj("applications" -> body)).toString().byteString.singleSource
    ).vfuture
  }

  private def getApps(pluginId: String)(implicit env: Env, ec: ExecutionContext, mat: Materializer) = {

    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap(arr => successfulResponse(arr.map(_.utf8String.parseJson)))
  }

  private def createApp(pluginId: String, appId: String, hasBody: Boolean, body: Future[ByteString], evictionTimeout: Int)
                       (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    if (hasBody)
      body.flatMap { bodyRaw =>
        val instance = bodyRaw.utf8String.parseJson.as[JsObject]
        val name = (instance \ "instance" \ "app").asOpt[String].getOrElse("Unknown app")
        val rawInstanceId = (instance \ "instance" \ "instanceId").asOpt[String]

        rawInstanceId match {
          case Some(instanceId) =>
            env.datastores.rawDataStore
              .set(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
                Json.obj(
                  "application" -> Json.obj("name" -> name).deepMerge(instance.deepMerge(
                    Json.obj("instance" -> Json.obj("last_heartbeat" -> new java.util.Date().getTime))))
                ).stringify.byteString,
                evictionTimeout.seconds.toMillis.some)
              .flatMap { res =>
                if (res) {
                  bodyResponse(204, Map.empty, Source.empty).vfuture
                } else {
                  bodyResponse(400,
                    Map("Content-Type" -> "application/json"),
                    Json.obj("error" -> "an error happened during registration of new app").stringify.byteString.singleSource
                  ).vfuture
                }
              }
          case None =>
            bodyResponse(400,
              Map("Content-Type" -> "application/json"),
              Json.obj("error" -> "missing instance id").stringify.byteString.singleSource
            ).vfuture
        }
      }
    else
      bodyResponse(400,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "missing body or invalid body").stringify.byteString.singleSource
      ).vfuture
  }

  private def getAppWithId(pluginId: String, appId: String)
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:*")
      .flatMap { instances =>
        if (instances.isEmpty)
          notFoundResponse()
        else
          bodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml.toXml(Json.obj(
              "application" -> Json.obj(
                "name" -> (instances.head.utf8String.parseJson \ "name").as[String],
                "instances" -> instances.map(instance => {
                  Json.obj("instance" -> (instance.utf8String.parseJson \ "application" \ "instance").as[JsValue])
                })
              )
            ))
              .toString()
              .byteString
              .singleSource
          ).vfuture
      }
  }

  private def getAppWithIdAndInstanceId(pluginId: String, appId: String, instanceId: String)
                                       (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
      .flatMap {
        case Some(instance) =>
          bodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml.toXml(instance.utf8String.parseJson).toString().byteString.singleSource
          ).vfuture
        case None =>
          bodyResponse(404,
            Map("Content-Type" -> "application/json"), Source.empty).vfuture
      }
  }

  private def getInstanceWithId(pluginId: String, instanceId: String)
                               (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:*:$instanceId")
      .flatMap { instances =>
        if (instances.isEmpty)
          bodyResponse(404,
            Map("Content-Type" -> "application/json"), Source.empty).vfuture
        else
          bodyResponse(
            200,
            Map("Content-Type" -> "application/xml"),
            otoroshi.utils.xml.Xml.toXml(instances.head.utf8String.parseJson).toString().byteString.singleSource
          ).vfuture
      }
  }

  private def deleteAppWithId(pluginId: String, appId: String, instanceId: String)
                             (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .del(Seq(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId"))
      .flatMap { _ =>
        bodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("deleted" -> "done").stringify.byteString.singleSource
        ).vfuture
      }
  }

  private def checkHeartbeat(pluginId: String, appId: String, instanceId: String, evictionTimeout: Int)
                            (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    val key = s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId"
    env.datastores.rawDataStore
      .get(key)
      .flatMap {
        case None => bodyResponse(404, Map("Content-Type" -> "application/json"), Source.empty)
          .vfuture
        case Some(v) =>
          env.datastores.rawDataStore
            .set(
              key,
              v.utf8String.parseJson.as[JsObject].deepMerge(
                Json.obj("application" -> Json.obj(
                  "instance" -> Json.obj("last_heartbeat" -> new java.util.Date().getTime)
                )))
                .stringify.byteString,
              (evictionTimeout * 1000).seconds.toMillis.some)
            .flatMap {
              case true => bodyResponse(200, Map("Content-Type" -> "application/json"), Source.empty)
                .vfuture
              case false => bodyResponse(400, Map.empty, Source.empty)
                  .vfuture
            }
      }
  }

  private def takeInstanceOutOfService(pluginId: String, appId: String, instanceId: String, status: Option[String])
                                      (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
      .flatMap {
        case None => notFoundResponse()
        case Some(app) =>
          val updatedApp = app.utf8String.parseJson.as[JsObject].deepMerge(
            Json.obj("application" -> Json.obj(
              "instance" -> Json.obj(
                "status" -> status.getOrElse("UP").asInstanceOf[String]
              )))
          )
          env.datastores.rawDataStore
            .set(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
              updatedApp.stringify.byteString,
              120.seconds.toMillis.some)
            .flatMap {
              case true =>
                bodyResponse(200,
                  Map("Content-Type" -> "application/json"),
                  Source.empty).vfuture
              case false =>
                notFoundResponse()
            }

      }
  }

  private def putMetadata(pluginId: String, appId: String, instanceId: String, queryString: Option[String])
                         (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    if (queryString.isEmpty)
      bodyResponse(400,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "missing query string").stringify.byteString.singleSource).vfuture
    else
      env.datastores.rawDataStore
        .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
        .flatMap {
          case None => notFoundResponse()
          case Some(app) =>
            val jsonApp = app.utf8String.parseJson.as[JsObject]
            val updatedApp = jsonApp.deepMerge(
              Json.obj("application" -> Json.obj(
                "instance" -> Json.obj(
                  "metadata" -> queryString.get.split("&")
                    .map(_.split("="))
                    .foldLeft(Json.obj()) { case (acc, c) =>
                      acc ++ Json.obj(c.head -> c(1))
                    }
                )))
            )
            env.datastores.rawDataStore
              .set(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId",
                updatedApp.stringify.byteString,
                None)
              .flatMap {
                case true =>
                  bodyResponse(200,
                    Map("Content-Type" -> "application/json"),
                    Source.empty).vfuture
                case false =>
                  notFoundResponse()
              }

        }
  }


  private def getInstancesUnderVipAddress(pluginId: String, vipAddress: String)
                                         (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap { arr =>
        val apps = arr.map(_.utf8String.parseJson)
          .filter(app => (app \ "application" \ "instance" \ "vipAddress").as[String] == vipAddress)

        if (apps.isEmpty)
          notFoundResponse()
        else
          successfulResponse(apps)
      }
  }

  private def getInstancesUnderSecureVipAddress(pluginId: String, svipAddress: String)
                                               (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap { arr =>
        val apps = arr.map(_.utf8String.parseJson)
          .filter(app => (app \ "application" \ "instance" \ "secureVipAddress").as[String] == svipAddress)

        if (apps.isEmpty)
          notFoundResponse()
        else
          successfulResponse(apps)
      }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val pluginId = ctx.route.id
    val body = ctx.request.body.runFold(ByteString.empty)(_ ++ _)
    val config = ctx.cachedConfig(internalName)(EurekaServerConfig.format).getOrElse(EurekaServerConfig())

    (ctx.request.method, ctx.request.path) match {
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/status") =>
        takeInstanceOutOfService(pluginId, appId, instanceId, ctx.rawRequest.getQueryString("value"))
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/metadata") =>
        putMetadata(pluginId, appId, instanceId, ctx.request.queryString)
      case ("GET", r"/eureka/apps/$appId@(.*)") =>
        if (s"$appId".isEmpty)
          getApps(pluginId)
        else
          getAppWithId(pluginId, appId)
      case ("GET", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        getAppWithIdAndInstanceId(pluginId, appId, instanceId)
      case ("GET", r"/eureka/instances/$instanceId@(.*)") =>
        getInstanceWithId(pluginId, instanceId)
      case ("GET", r"/eureka/vips/$vipAddress@(.*)") =>
        getInstancesUnderVipAddress(pluginId, vipAddress)
      case ("GET", r"/eureka/svips/$svipAddress@(.*)") =>
        getInstancesUnderSecureVipAddress(pluginId, svipAddress)
      case ("POST", r"/eureka/apps/$appId@(.*)") =>
        createApp(pluginId, appId, ctx.request.hasBody, body, config.evictionTimeout)
      case ("DELETE", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        deleteAppWithId(pluginId, appId, instanceId)
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        checkHeartbeat(pluginId, appId, instanceId, config.evictionTimeout)
      case _ => notFoundResponse()
    }
  }
}

case class EurekaTargetConfig(eurekaServer: Option[String] = None, eurekaApp: Option[String] = None)
  extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "eureka_server" -> eurekaServer,
    "eureka_app" -> eurekaApp,
  )
}

object EurekaTargetConfig {
  val format: Format[EurekaTargetConfig] = new Format[EurekaTargetConfig] {
    override def writes(o: EurekaTargetConfig): JsValue             = Json.obj(
      "eureka_server" -> o.eurekaServer,
      "eureka_app" -> o.eurekaApp,
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

  override def name: String = "Eureka target"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def description: Option[String] = {
    Some(s"""Description of the eureka target""".stripMargin)
  }

  override def defaultConfigObject: Option[NgPluginConfig] = EurekaTargetConfig().some
  override def multiInstance: Boolean = true

  private def updatePreExtractedRequestTargetsKey(ctx: NgPreRoutingContext, apps: Seq[JsValue]) = {
    ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey -> apps.map(application => {
      val instance = (application \ "application" \ "instance").as(EurekaInstance.format.reads)
      EurekaInstance.toTarget(instance)
    }))
  }

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val rawConfig = ctx.cachedConfig(internalName)(EurekaTargetConfig.format)

    rawConfig match {
      case Some(config) =>
        (config.eurekaServer, config.eurekaApp) match {
          case (Some(eurekaServer), Some(eurekaApp)) =>
            val key = s"${env.storageRoot}:plugins:eureka-server-$eurekaServer:apps:$eurekaApp:*"
            EurekaTarget.cache.getIfPresent(key) match {
              case None =>
                env.datastores.rawDataStore
                  .allMatching(s"${env.storageRoot}:plugins:eureka-server-$eurekaServer:apps:$eurekaApp:*")
                  .flatMap(rawApps => {
                    val apps = rawApps.map(a => a.utf8String.parseJson)
                    updatePreExtractedRequestTargetsKey(ctx, apps)
                    EurekaTarget.cache.put(key, apps)
                    Done
                      .right
                      .vfuture
                  })
              case Some(apps) =>
                updatePreExtractedRequestTargetsKey(ctx, apps)
                Done
                  .right
                  .vfuture
            }
          case _ =>
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
      case None =>
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









































