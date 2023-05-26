package otoroshi.next.plugins.grpc

import akka.stream.Materializer
import com.google.common.collect.ImmutableList
import com.google.protobuf.{DescriptorProtos, DynamicMessage}
import io.grpc._
import io.grpc.stub.{ClientCalls, StreamObserver}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.io.InputStream
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
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

//    val channel: ManagedChannel = ManagedChannelBuilder.forAddress("localhost", 5000)
////      .usePlaintext()
//      .build()

//    val marshaller = new MethodDescriptor.Marshaller[DynamicMessage]() {
//      def stream(value: DynamicMessage): InputStream = {
//        println("stream", value)
//        null
//      }
//
//      override def parse(stream: InputStream): DynamicMessage = {
//        println("parse", stream)
//        //DynamicMessage.parseFrom(stream)
//        null
//      }
//    }

    val promise = Promise[String]

    new ReflectionClient("127.0.0.1", 5000, false)
      .call(promise)

//    val descriptor: MethodDescriptor[DynamicMessage, DynamicMessage] = io.grpc.MethodDescriptor
//      .newBuilder()
//      .setType(MethodDescriptor.MethodType.UNARY)
//      .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName("UserService", "GetUser"))
//      .setRequestMarshaller(marshaller)
//      .setResponseMarshaller(marshaller)
//      .setSafe(false)
//      .setIdempotent(false)
//      .build()
//
//    val requestDescriptor: ServiceDescriptor = ServiceDescriptor
//      .newBuilder("UserService")
//      .addMethod(descriptor)
//      .build()
//
//    new DynamicGrpcClient(descriptor, channel)
//      .call(
//        requests = ImmutableList.of(DynamicMessage.newBuilder(requestDescriptor)),
//        responseObserver = new StreamObserver[DynamicMessage] {
//          override def onNext(value: DynamicMessage): Unit = {
//            println(value)
//            promise.complete(Try { value.toString } )
//          }
//
//          override def onError(t: Throwable): Unit = {
//            t.printStackTrace()
//            promise.complete(Try { "error" } )
//          }
//
//          override def onCompleted(): Unit = {
//            println("COMPLETED")
//            promise.complete(Try { "completed" } )
//          }
//        },
//        callOptions = CallOptions.DEFAULT.withDeadlineAfter(1234L, TimeUnit.NANOSECONDS)
//      )

    promise
      .future
      .flatMap(str => {
        println(str)

        bodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("content" -> str).stringify.byteString.singleSource
        ).vfuture
      })

//    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
//      val body = bodyRaw.utf8String.parseJson.asObject
//
//    }
  }
}


class DynamicGrpcClient (protoMethodDescriptor: MethodDescriptor[DynamicMessage, DynamicMessage], channel: Channel) {

  def call(requests:  ImmutableList[DynamicMessage], responseObserver: StreamObserver[DynamicMessage], callOptions: CallOptions) = {
    val methodType = getMethodType()
    val numRequests = requests.size

    println(requests)

    if (methodType == io.grpc.MethodDescriptor.MethodType.UNARY) {
      println("Making unary call")
      callUnary(requests.get(0), responseObserver, callOptions)
    }
    else if (methodType == io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING) {
      println("Making server streaming call")
      callServerStreaming(requests.get(0), responseObserver, callOptions)
    }
    else if (methodType == io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING) {
      println("Making client streaming call with " + requests.size + " requests")
      callClientStreaming(requests, responseObserver, callOptions)
    }
    else {
      // Bidi streaming.
      println("Making bidi streaming call with " + requests.size + " requests")
      callBidiStreaming(requests, responseObserver, callOptions)
    }
  }

  private def callBidiStreaming(requests: ImmutableList[DynamicMessage], responseObserver: StreamObserver[DynamicMessage], callOptions: CallOptions) = {
    val doneObserver = new DoneObserver[DynamicMessage]
    val requestObserver = ClientCalls.asyncBidiStreamingCall(createCall(callOptions), CompositeStreamObserver.of(responseObserver, doneObserver))
    requests.forEach(requestObserver.onNext)
    requestObserver.onCompleted
    doneObserver.getCompletionFuture
  }

  private def callClientStreaming(requests: ImmutableList[DynamicMessage], responseObserver: StreamObserver[DynamicMessage], callOptions: CallOptions) = {
    val doneObserver = new DoneObserver[DynamicMessage]
    val requestObserver = ClientCalls.asyncClientStreamingCall(createCall(callOptions), CompositeStreamObserver.of(responseObserver, doneObserver))
    requests.forEach(requestObserver.onNext)
    requestObserver.onCompleted
    doneObserver.getCompletionFuture
  }

  private def callServerStreaming(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage], callOptions: CallOptions) = {
    val doneObserver = new DoneObserver[DynamicMessage]
    ClientCalls.asyncServerStreamingCall(createCall(callOptions), request, CompositeStreamObserver.of(responseObserver, doneObserver))
    doneObserver.getCompletionFuture
  }

  private def callUnary(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage], callOptions: CallOptions) = {
    val doneObserver = new DoneObserver[DynamicMessage]
    ClientCalls.asyncUnaryCall(createCall(callOptions), request, CompositeStreamObserver.of(responseObserver, doneObserver))
    doneObserver.getCompletionFuture
  }

  private def createCall(callOptions: CallOptions) = channel.newCall(createGrpcMethodDescriptor, callOptions)

  private def createGrpcMethodDescriptor = {
    io.grpc.MethodDescriptor.create[DynamicMessage, DynamicMessage](
      getMethodType,
      getFullMethodName,
      protoMethodDescriptor.getRequestMarshaller,
      protoMethodDescriptor.getResponseMarshaller
    )
//      new DynamicMessageMarshaller(protoMethodDescriptor.getInputType),
//      new DynamicMessageMarshaller(protoMethodDescriptor.getOutputType))
  }

  private def getFullMethodName() = {
    val serviceName = protoMethodDescriptor.getServiceName
    val methodName = protoMethodDescriptor.getFullMethodName
    io.grpc.MethodDescriptor.generateFullMethodName(serviceName, methodName)
  }

  /** Returns the appropriate method type based on whether the client or server expect streams. */
  private def getMethodType() = {
    protoMethodDescriptor.getType
    // MethodType.UNARY
//    val clientStreaming = protoMethodDescriptor.
//    val serverStreaming = protoMethodDescriptor.toProto.getServerStreaming
//    if (!clientStreaming && !serverStreaming) MethodType.UNARY
//    else if (!clientStreaming && serverStreaming) MethodType.SERVER_STREAMING
//    else if (clientStreaming && !serverStreaming) MethodType.CLIENT_STREAMING
//    else MethodType.BIDI_STREAMING
  }
}
