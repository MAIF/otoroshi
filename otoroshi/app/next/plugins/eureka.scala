package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import kaleidoscope._
import otoroshi.utils.syntax.implicits.{BetterByteString, BetterJsValue, BetterString, BetterSyntax}
import play.api.libs.json.{Format, JsArray, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/*case class EurekaPort(`@enabled`: String, `$`: String)
case class EurekaDataCenterInfo(`@class`: String, name: String)
case class EurekaLeaseInfo(renewalIntervalInSecs: Int,
                           durationInSecs: Int,
                           registrationTimestamp: Long,
                           lastRenewalTimestamp: Long,
                           evictionTimestamp: Long,
                           serviceUpTimestamp: Long)
case class EurekaMetadata(`management.port`: Int)

case class EurekaInstance(
   hostName: String,
   app: String,
   ipAddr: String,
   status: String,
   overriddenstatus: String,
   port: EurekaPort,
   securePort: EurekaPort,
   countryId: Int,
   dataCenterInfo: EurekaDataCenterInfo,
   leaseInfo: EurekaLeaseInfo,
   metadata: EurekaMetadata,
   homePageUrl: String,
   statusPageUrl: String,
   healthCheckUrl: String,
   vipAddress: String,
   secureVipAddress: String,
   isCoordinatingDiscoveryServer: Boolean,
   lastUpdatedTimestamp: Long,
   lastDirtyTimestamp: Long,
   actionType: String
  )

case class EurekaApplication(name: String, instance: EurekaInstance)*/

case class EurekaServerTransformerConfig() extends NgPluginConfig {
  def json: JsValue = EurekaServerConfig.format.writes(this)
}

object EurekaServerConfig {
  val format: Format[EurekaServerTransformerConfig] = new Format[EurekaServerTransformerConfig] {
    override def writes(o: EurekaServerTransformerConfig): JsValue             = Json.obj(

    )
    override def reads(json: JsValue): JsResult[EurekaServerTransformerConfig] = Try {
      EurekaServerTransformerConfig()
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class EurekaServerSink extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = false
  override def name: String                                = "Eureka instance"
  override def description: Option[String]                 = "Eureka plugin description".some
  override def defaultConfigObject: Option[NgPluginConfig] = EurekaServerTransformerConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  private def getApps(pluginId: String)(implicit env: Env, ec: ExecutionContext, mat: Materializer) = {

    env.datastores.rawDataStore
      .allMatching(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps*")
      .flatMap { arr =>
        println(s"GET ${arr.length} apps")
        bodyResponse(
          200,
          Map("Content-Type" -> "application/xml"),
          otoroshi.utils.xml.Xml.toXml(Json.obj("applications" -> arr.map(_.utf8String.parseJson))).toString().byteString.singleSource
        ).vfuture
      }
  }

  private def createApp(pluginId: String, appId: String, hasBody: Boolean, body: Future[ByteString])
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
                  "application" -> Json.obj("name" -> name).deepMerge(instance)
                ).stringify.byteString,
                120.seconds.toMillis.some)
              .flatMap { res =>
                if(res) {
                  println("Successful update apps")
                  bodyResponse(204, Map.empty, Source.empty)
                    .vfuture
                } else {
                  println("Failed to update apps")
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
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer)= {
    println(s"GET apps with $appId")
    bodyResponse(
      200,
      Map("Content-Type" -> "application/json"),
      Json.obj("Response" -> "ok").stringify.byteString.singleSource
    ).vfuture
  }

  private def getAppWithIdAndInstanceId(pluginId: String, appId: String, instanceId: String)
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer)= {
    println("GET specific app", appId, instanceId)
    bodyResponse(
      200,
      Map("Content-Type" -> "application/json"),
      Json.obj("Response" -> "ok").stringify.byteString.singleSource
    ).vfuture
  }

  private def getInstanceWithId(pluginId: String, instanceId: String)
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer)= {
    println("GET instance", instanceId)
    bodyResponse(
      200,
      Map("Content-Type" -> "application/json"),
      Json.obj("Response" -> "ok").stringify.byteString.singleSource
    ).vfuture
  }

  private def deleteAppWithId(pluginId: String, appId: String, instanceId: String)
                               (implicit env: Env, ec: ExecutionContext, mat: Materializer)= {
    env.datastores.rawDataStore
      .del(Seq(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId"))
      .flatMap { _ =>
        println("DELETE", appId, instanceId)
        bodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("deleted" -> "done").stringify.byteString.singleSource
        ).vfuture
      }
  }

  private def updateAppWithIdAndInstanceId(pluginId: String, appId: String, instanceId: String)
                                       (implicit env: Env, ec: ExecutionContext, mat: Materializer) = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
      .flatMap {
        case None =>
          println(s"Failed heartbeat : ${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
          bodyResponse(404,
            Map("Content-Type" -> "application/json"), Source.empty).vfuture
        case Some(v) =>
          println(s"Successful heartbeat : ${env.storageRoot}:plugins:eureka-server-$pluginId:apps:$appId:$instanceId")
          bodyResponse(200,
            Map("Content-Type" -> "application/json"),
            Source.empty).vfuture
      }
  }

  private def takeInstanceOutOfService(pluginId: String, appId: String, instanceId: String)
                                      (implicit env: Env, ec: ExecutionContext, mat: Materializer)  = {
    println("PUT status out of service", appId, instanceId)
    bodyResponse(
      200,
      Map("Content-Type" -> "application/json"),
      Json.obj("Response" -> "ok").stringify.byteString.singleSource
    ).vfuture
  }

  private def putMetadata(pluginId: String, appId: String, instanceId: String, key: String, value: String)
                                      (implicit env: Env, ec: ExecutionContext, mat: Materializer)  = {
    println("PUT metadata", appId, instanceId, key, value)
    bodyResponse(
      200,
      Map("Content-Type" -> "application/json"),
      Json.obj("Response" -> "ok").stringify.byteString.singleSource
    ).vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val pluginId = ctx.route.id
    val body = ctx.request.body.runFold(ByteString.empty)(_ ++ _)

    (ctx.request.method, ctx.request.path) match {
      case ("GET", r"/eureka/apps/$appId@(.*)") =>
        if(s"$appId".isEmpty)
          getApps(pluginId)
        else
          getAppWithId(pluginId, appId)
      case ("GET", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        getAppWithIdAndInstanceId(pluginId, appId, instanceId)
      case ("GET", r"/eureka/instances/$instanceId@(.*)") =>
        getInstanceWithId(pluginId, instanceId)
      case ("POST", r"/eureka/apps/$appId@(.*)") =>
        createApp(pluginId, appId, ctx.request.hasBody, body)
      case ("DELETE", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        deleteAppWithId(pluginId, appId, instanceId)
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)") =>
        updateAppWithIdAndInstanceId(pluginId, appId, instanceId)
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/status?value=OUT_OF_SERVICE") =>
        takeInstanceOutOfService(pluginId, appId, instanceId)
      case ("PUT", r"/eureka/apps/$appId@(.*)/$instanceId@(.*)/metadata?$key@(.*)=$value@(.*)") =>
        putMetadata(pluginId, appId, instanceId, key, value)
      case _ => bodyResponse(404,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "resource not found").stringify.byteString.chunks(16 * 1024)
      ).vfuture
    }
  }
}
