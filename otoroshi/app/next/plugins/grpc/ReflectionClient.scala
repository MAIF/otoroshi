package otoroshi.next.plugins.grpc

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf._
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc._
import otoroshi.env.Env
import otoroshi.ssl.DynamicSSLEngineProvider
import play.api.Logger

import java.nio.charset.StandardCharsets
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._
import scala.util.Try

sealed trait GRPCClientKind {
  def value: Int
}

object GRPCClientKind {
  case object AsyncUnary           extends GRPCClientKind {
    def value: Int = 1
  }
  case object BlockingUnary        extends GRPCClientKind {
    def value: Int = 2
  }
  case object AsyncBidiStreaming   extends GRPCClientKind {
    def value: Int = 3
  }
  case object AsyncClientStreaming extends GRPCClientKind {
    def value: Int = 4
  }

  def fromValue(n: Int): GRPCClientKind = n match {
    case 1 => GRPCClientKind.AsyncUnary
    case 2 => GRPCClientKind.BlockingUnary
    case 3 => GRPCClientKind.AsyncClientStreaming
    case 4 => GRPCClientKind.AsyncBidiStreaming
    case _ => GRPCClientKind.AsyncUnary
  }
}

case class GRPCService(fullServiceName: String, methodName: String, packageName: String, serviceName: String)

case class GRPCChannelRef(id: String, address: String, port: Int, secured: Boolean)

object GRPCChannelRef {
  def apply(address: String, port: Int, secured: Boolean): GRPCChannelRef = {
    GRPCChannelRef(
      id = s"$address:$port:$secured",
      address,
      port,
      secured)
  }

  def convertToManagedChannel(channelRef: GRPCChannelRef)(implicit env: Env): ManagedChannel = {
    if (channelRef.secured) {
//      ManagedChannelBuilder.forAddress(channelRef.address, channelRef.port).build()
      // TODO - add list of trusted certificates
      val creds = TlsChannelCredentials.newBuilder()
        .trustManager(DynamicSSLEngineProvider.currentServerTrustManager)
        .build()
      Grpc.newChannelBuilderForAddress(
        channelRef.address, channelRef.port, creds
      )
        .build()
    } else {
      Grpc.newChannelBuilderForAddress(
        channelRef.address, channelRef.port, InsecureChannelCredentials.create()
      )
        .build()
    }
  }
}

class ReflectionClient(channel: ManagedChannel) {
  private val logger = Logger("otoroshi-next-plugins-grpc-client")

  private val reflectionStub = ServerReflectionGrpc.newStub(channel)

  def call(
      service: GRPCService,
      clientKind: GRPCClientKind,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]]
  ) = {
    val observer = new StreamObserver[ServerReflectionResponse]() {
      override def onNext(response: ServerReflectionResponse) {
        if (response.getMessageResponseCase == ServerReflectionResponse.MessageResponseCase.FILE_DESCRIPTOR_RESPONSE) {
          handleResponse(
            response.getFileDescriptorResponse.getFileDescriptorProtoList,
            service,
            clientKind,
            requestContent,
            promise
          )
        } else {
          println(response.getMessageResponseCase)
        }
      }

      override def onError(t: Throwable): Unit = logger.error("Error while using reflection service", t)

      override def onCompleted(): Unit = {}
    }

    val requestStreamObserver = reflectionStub.serverReflectionInfo(observer)

    val getFileContainingSymbolRequest = ServerReflectionRequest
      .newBuilder()
      .setFileContainingSymbol(service.fullServiceName)
      .build()
    requestStreamObserver.onNext(getFileContainingSymbolRequest)
    requestStreamObserver.onCompleted()
  }

  def handleResponse(
      fileDescriptorProtoList: java.util.List[ByteString],
      service: GRPCService,
      clientKind: GRPCClientKind,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]]
  ): Unit = {
    val fileDescriptor: Descriptors.FileDescriptor =
      getFileDescriptor(fileDescriptorProtoList, service.packageName, service.serviceName)

    val serviceDescriptor = fileDescriptor.getFile.findServiceByName(service.serviceName)
    val methodDescriptor  = serviceDescriptor.findMethodByName(service.methodName)

    clientKind match {
      case GRPCClientKind.AsyncUnary           => asyncUnaryCall(channel, fileDescriptor, methodDescriptor, requestContent, promise)
      case GRPCClientKind.BlockingUnary        =>
        blockingUnaryCall(channel, fileDescriptor, methodDescriptor, requestContent, promise)
      case GRPCClientKind.AsyncBidiStreaming   =>
        asyncBidiStreamingCall(
          channel,
          fileDescriptor,
          methodDescriptor,
          requestContent,
          promise,
          streamingResponseObserver(promise)
        )
      case GRPCClientKind.AsyncClientStreaming =>
        asyncClientStreamingCall(channel, fileDescriptor, methodDescriptor, requestContent, promise)
    }
  }

  def getFileDescriptor(
      fileDescriptorProtoList: java.util.List[ByteString],
      packageName: String,
      serviceName: String
  ): Descriptors.FileDescriptor = {
    val fileDescriptorProtoMap: Map[String, DescriptorProtos.FileDescriptorProto] = fileDescriptorProtoList.asScala
      .map(bs => DescriptorProtos.FileDescriptorProto.parseFrom(bs))
      .foldLeft(Map.empty[String, DescriptorProtos.FileDescriptorProto]) { case (acc, item) =>
        acc + (item.getName -> item)
      }

    if (fileDescriptorProtoMap.isEmpty) {
      throw new IllegalArgumentException("fileDescriptorProtoMap.isEmpty()")
    }

    val fileDescriptorProto: DescriptorProtos.FileDescriptorProto =
      findServiceFileDescriptorProto(packageName, serviceName, fileDescriptorProtoMap)

    // Descriptors.FileDescriptor.buildFrom(FileDescriptorProto.parseFrom(customProto.getBytes(StandardCharsets.UTF_8)), Array.empty[FileDescriptor])

    Descriptors.FileDescriptor.buildFrom(fileDescriptorProto, Array.empty[FileDescriptor])
  }

  def findServiceFileDescriptorProto(
      packageName: String,
      serviceName: String,
      fileDescriptorProtoMap: Map[String, DescriptorProtos.FileDescriptorProto]
  ): DescriptorProtos.FileDescriptorProto = {
    for (proto <- fileDescriptorProtoMap.values) {
      if (proto.getPackage.equals(packageName)) {
        val exist = proto.getServiceList.stream.anyMatch(s => serviceName == s.getName)
        if (exist) {
          return proto
        }
      }
    }

    null
  }

  private def asyncUnaryCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]]
  ): Unit = {
    generateMethodDescriptor(originMethodDescriptor) match {
      case Left(error) => promise.success(Left(error))
      case Right(methodDescriptor) =>
        val observer = streamingResponseObserver(promise)

        buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent) match {
          case Left(error) => promise.success(Left(error.getMessage))
          case Right(message) =>
            ClientCalls.asyncUnaryCall(
              channel.newCall(methodDescriptor, CallOptions.DEFAULT),
              message,
              observer
            )
        }
    }
  }

  private def streamingResponseObserver(promise: Promise[Either[String, Seq[DynamicMessage]]]): StreamObserver[DynamicMessage] =
    new StreamObserver[DynamicMessage]() {
      var responses = Seq.empty[DynamicMessage]

      override def onNext(response: DynamicMessage): Unit = responses = responses :+ response

      override def onError(t: Throwable): Unit = {
        if (!promise.isCompleted) {
          promise.success(Left(t.getMessage))
        }
        logger.error("Error while streaming response", t)
      }

      override def onCompleted(): Unit = {
        if (!promise.isCompleted) {
          promise.success(Right(responses))
        }
      }
    }

  private def asyncClientStreamingCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]]
  ): Unit = {
    generateMethodDescriptor(originMethodDescriptor) match {
      case Left(error) => promise.success(Left(error))
      case Right(methodDescriptor) =>
        val observer = streamingResponseObserver(promise)

        val clientObserver =
          ClientCalls.asyncClientStreamingCall(channel.newCall(methodDescriptor, CallOptions.DEFAULT), observer)

        buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent) match {
          case Left(error) => promise.success(Left(error.getMessage))
          case Right(message) =>
            clientObserver.onNext(message)
            clientObserver.onCompleted()
        }
    }
  }

  private def asyncBidiStreamingCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]],
      responseObserver: StreamObserver[DynamicMessage]
  ): Unit = {
    generateMethodDescriptor(originMethodDescriptor) match {
      case Left(error) => promise.success(Left(error))
      case Right(methodDescriptor) =>
        val observer = new StreamObserver[DynamicMessage]() {
          override def onNext(response: DynamicMessage): Unit = {
            responseObserver.onNext(response)
          }

          override def onError(t: Throwable): Unit = logger.error("Error while streaming bidi response", t)

          override def onCompleted(): Unit = responseObserver.onCompleted()
        }

        val clientObserver =
          ClientCalls.asyncBidiStreamingCall(channel.newCall(methodDescriptor, CallOptions.DEFAULT), observer)

        buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent) match {
          case Left(value) => responseObserver.onError(value)
          case Right(message) =>
            clientObserver.onNext(message)
            clientObserver.onCompleted()
        }
    }
  }

  private def blockingUnaryCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Either[String, Seq[DynamicMessage]]]
  ): Unit = {
    generateMethodDescriptor(originMethodDescriptor) match {
      case Left(error) => promise.success(Left(error))
      case Right(methodDescriptor) =>
        buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent) match {
          case Left(error) => promise.success(Left(error.getMessage))
          case Right(dynamicMessage) => promise.success(
            Right(Seq(
              ClientCalls.blockingUnaryCall(
                channel.newCall(methodDescriptor, CallOptions.DEFAULT),
                dynamicMessage
              )
            ))
          )
        }
    }
  }

  private def buildDynamicMessage(
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String]
  ): Either[Exception, DynamicMessage] = {
    val registry = TypeRegistry.newBuilder.add(fileDescriptor.getMessageTypes).build

    val messageBuilder = DynamicMessage.newBuilder(originMethodDescriptor.getInputType)

    requestContent.foreach(content => {
      val parser = com.google.protobuf.util.JsonFormat.parser.usingTypeRegistry(registry)

      Try {
        parser.merge(content, messageBuilder)
      } recover {
        case e: Exception =>
          logger.error("Error merging incoming body to message", e)
          return Left(e)
      }
    })

    Right(messageBuilder.build())
  }

  private def generateMethodDescriptor(
      originMethodDescriptor: Descriptors.MethodDescriptor
  ): Either[String, MethodDescriptor[DynamicMessage, DynamicMessage]] = {

    if (originMethodDescriptor == null || originMethodDescriptor.getService == null) {
        Left("GRPC service or method not found")
    } else {
      val fullMethodName = MethodDescriptor.generateFullMethodName(
        originMethodDescriptor.getService.getFullName,
        originMethodDescriptor.getName
      )

      val inputTypeMarshaller  =
        ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getInputType).buildPartial)
      val outputTypeMarshaller =
        ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getOutputType).buildPartial)

      Right(MethodDescriptor
        .newBuilder[DynamicMessage, DynamicMessage]
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(inputTypeMarshaller)
        .setResponseMarshaller(outputTypeMarshaller)
        .setType(MethodDescriptor.MethodType.UNKNOWN)
        .build)
    }
  }
}
