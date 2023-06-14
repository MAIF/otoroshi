package otoroshi.next.plugins.grpc

import akka.stream.Materializer
import akka.util.ByteString
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage}
import io.grpc.ManagedChannel
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class GrpcConfig(address: String = "",
                      port: Int = 80,
                      secured: Boolean = false,
                      fullServiceName: String = "",
                      methodName: String = "",
                      packageName: String = "",
                      serviceName: String = "",
                      clientKind: GRPCClientKind = GRPCClientKind.AsyncUnary,
                      transcodingRequestToGRPC: Boolean = true) extends NgPluginConfig {
  override def json: JsValue = GrpcConfig.format.writes(this)
}
object GrpcConfig {
  val format  = new Format[GrpcConfig] {
    override def writes(o: GrpcConfig): JsValue             = Json.obj(
      "address" -> o.address,
      "port" -> o.port,
      "secured" -> o.secured,
      "fullServiceName" -> o.fullServiceName,
      "methodName" -> o.methodName,
      "packageName" -> o.packageName,
      "serviceName" -> o.serviceName,
      "clientKind" -> o.clientKind.value,
      "transcodingRequestToGRPC" -> o.transcodingRequestToGRPC
    )
    override def reads(json: JsValue): JsResult[GrpcConfig] = Try {
      GrpcConfig(
        address = json.select("address").asOpt[String].getOrElse(""),
        port = json.select("port").asOpt[Int].getOrElse(80),
        secured = json.select("secured").asOpt[Boolean].getOrElse(false),
        fullServiceName = json.select("fullServiceName").asOpt[String].getOrElse(""),
        methodName = json.select("methodName").asOpt[String].getOrElse(""),
        packageName = json.select("packageName").asOpt[String].getOrElse(""),
        serviceName = json.select("serviceName").asOpt[String].getOrElse(""),
        clientKind = json.select("clientKind").asOpt[Int].map(GRPCClientKind.fromValue)
          .getOrElse(GRPCClientKind.AsyncUnary),
        transcodingRequestToGRPC = json.select("transcodingRequestToGRPC").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e)     => JsError(e.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

object NgGrpcCall {
  val channelsCache: Cache[String, ManagedChannel] = Scaffeine()
    .recordStats()
    .expireAfterWrite(1.days)
    .removalListener((_: String, value: ManagedChannel, cause: RemovalCause) => {
      if (cause == RemovalCause.EXPIRED) {
        value.shutdown()
      }
    })
    .maximumSize(1000)
    .build()
}

class NgGrpcCall extends NgBackendCall {

  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def useDelegates: Boolean                       = false
  override def core: Boolean                               = true
  override def name: String                                = "GRPC caller"
  override def description: Option[String]                 = "GRPC".some
  override def defaultConfigObject: Option[NgPluginConfig] = GrpcConfig().some

  private def transcodeHttpContentToGRPC(request: NgPluginHttpRequest): Either[String, GRPCService] = {
    val path    = request.path
    val method  = request.method
    val headers = request.headers.toSeq

    if (Seq("GET", "PUT", "POST", "DELETE", "PATHC").contains(method.toUpperCase)) {
      Left("Fail to transcoding http method to grpc")
    } else {
      // `GET   /v1/messages/123456`                             | `GetMessage(name: "messages/123456")`
      // `GET   /v1/messages/123456?revision=2&sub.subfield=foo` | `GetMessage(message_id: "123456" revision: 2 sub: SubMessage(subfield: "foo"))`
      // `PATCH /v1/messages/123456 { "text": "Hi!" }`           | `UpdateMessage(message_id: "123456" message { text: "Hi!" })`
      // `PATCH /v1/messages/123456 { "text": "Hi!" }`           | `UpdateMessage(message_id: "123456" text: "Hi!")`
      // `GET   /v1/messages/123456`                             | `GetMessage(message_id: "123456")`
      // `GET   /v1/users/me/messages/123456`                    | `GetMessage(user_id: "me" message_id: "123456")`

      Right(GRPCService("", "", "", ""))
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(GrpcConfig.format)
      .getOrElse(GrpcConfig())

    val channelRef = GRPCChannelRef(config.address, config.port, secured = config.secured)
    val channel = NgGrpcCall.channelsCache.getIfPresent(channelRef.id) match {
      case Some(value) => value
      case None =>
        val newChannel = GRPCChannelRef.convertToManagedChannel(channelRef)
        NgGrpcCall.channelsCache.put(channelRef.id, newChannel)
        newChannel
    }

    val client = new ReflectionClient(channel)
    val messagesPromise = Promise[Either[String, Seq[DynamicMessage]]]()

    val service = if (config.transcodingRequestToGRPC) {
      transcodeHttpContentToGRPC(ctx.request) match {
        case Left(error) => return bodyResponse(
          400,
          Map("Content-Type" -> "application/json"),
          Json.obj("error" -> error)
            .stringify
            .byteString
            .singleSource
        ).vfuture
        case Right(value) => value
      }
    } else {
      GRPCService(config.fullServiceName, config.methodName, config.packageName, config.serviceName)
    }

    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val body = bodyRaw.utf8String
      client.call(service, config.clientKind, if(body.isEmpty) None else Some(body), messagesPromise)

      messagesPromise
        .future
        .flatMap {
          case Left(error) => bodyResponse(
            400,
            Map("Content-Type" -> "application/json"),
            Json.obj("error" -> error)
              .stringify
              .byteString
              .singleSource
          ).vfuture
          case Right(messages) => val jsonMessages: Seq[JsValue] = messages.map(message => Json.parse(JsonFormat.printer().print(message)))
            bodyResponse(
              200,
              Map("Content-Type" -> "application/json"),
              JsArray(jsonMessages)
                .stringify
                .byteString
                .singleSource
            ).vfuture
        };
    }

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
  }
}
