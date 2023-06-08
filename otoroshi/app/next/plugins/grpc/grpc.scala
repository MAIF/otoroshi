package otoroshi.next.plugins.grpc

import akka.stream.Materializer
import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class GrpcConfig(

) extends NgPluginConfig {
  override def json: JsValue = GrpcConfig.format.writes(this)
}
object GrpcConfig {
  val format  = new Format[GrpcConfig] {
    override def writes(o: GrpcConfig): JsValue             = Json.obj(

    )
    override def reads(json: JsValue): JsResult[GrpcConfig] = Try {
      GrpcConfig(

      )
    } match {
      case Failure(e)     => JsError(e.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class NgGrpcCall extends NgBackendCall {

  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def useDelegates: Boolean                       = false
  override def core: Boolean                               = true
  override def name: String                                = "Grpc caller"
  override def description: Option[String]                 = "grpc".some
  override def defaultConfigObject: Option[NgPluginConfig] = GrpcConfig().some

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(GrpcConfig.format)
      .getOrElse(GrpcConfig())

    val path = ctx.request.path
    val method = ctx.request.method
    val headers = ctx.request.headers.toSeq

    val client = new ReflectionClient("127.0.0.1", 5000, false)
    val test = Promise[DynamicMessage]()

    // client.call("UserService", "GetUser", "", "UserService", "", test)
    client.call("UserService", "SetUser", "", "UserService",
      Json.stringify(Json.obj(
        "name" -> "Etienne",
        "age" -> 132
      )), test)

    test
      .future
      .flatMap(res => {
        println(res.toString)
        bodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("result" -> "done").stringify.byteString.singleSource
        ).vfuture
      });

    //client.listService(servicesPromise)

//    servicesPromise
//      .future
//      .flatMap(services => {
//        println(s"Services: $services")
//
//        val filePromise = Promise[DescriptorProtos.FileDescriptorProto]()
//        client.getFile(filePromise, services.toSeq.head.replace(".", ""))
//
//        filePromise
//          .future
//          .map(file => {
//            val methods = file.getServiceList.asScala.head.getMethodList.asScala
//
//            val method = methods.head
//
//            // file.getMessageTypeList
//            client.callMethod(method)
//          })
//
//        bodyResponse(
//          200,
//          Map("Content-Type" -> "application/json"),
//          Json.obj().stringify.byteString.singleSource
//        ).vfuture
//      })

//    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
//      val body = bodyRaw.utf8String.parseJson.asObject
//
//    }
  }
}
