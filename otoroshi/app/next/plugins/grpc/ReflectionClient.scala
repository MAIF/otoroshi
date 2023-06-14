package otoroshi.next.plugins.grpc

import com.github.benmanes.caffeine
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf._
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc.{CallOptions, ManagedChannel, ManagedChannelBuilder, MethodDescriptor}
import otoroshi.env.Env
import play.api.Logger

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

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
  case object asyncClientStreaming extends GRPCClientKind {
    def value: Int = 4
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

  def convertToManagedChannel(channelRef: GRPCChannelRef): ManagedChannel = {
    if (channelRef.secured) {
      ManagedChannelBuilder.forAddress(channelRef.address, channelRef.port).build()
    } else {
      ManagedChannelBuilder
        .forAddress(channelRef.address, channelRef.port)
        .usePlaintext()
        .asInstanceOf[ManagedChannelBuilder[_]]
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
      promise: Promise[Seq[DynamicMessage]]
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
      promise: Promise[Seq[DynamicMessage]]
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
          streamingResponseObserver(promise)
        )
      case GRPCClientKind.asyncClientStreaming =>
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
      promise: Promise[Seq[DynamicMessage]]
  ): Unit = {
    val methodDescriptor = generateMethodDescriptor(originMethodDescriptor)

    val observer = streamingResponseObserver(promise)

    ClientCalls.asyncUnaryCall(
      channel.newCall(methodDescriptor, CallOptions.DEFAULT),
      buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent),
      observer
    )
  }

  private def streamingResponseObserver(promise: Promise[Seq[DynamicMessage]]): StreamObserver[DynamicMessage] =
    new StreamObserver[DynamicMessage]() {
      var responses = Seq.empty[DynamicMessage]

      override def onNext(response: DynamicMessage): Unit = responses = responses :+ response

      override def onError(t: Throwable): Unit = logger.error("Error while streaming response", t)

      override def onCompleted(): Unit = promise.success(responses)
    }

  private def asyncClientStreamingCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Seq[DynamicMessage]]
  ): Unit = {
    val methodDescriptor = generateMethodDescriptor(originMethodDescriptor)

    val observer = streamingResponseObserver(promise)

    val clientObserver =
      ClientCalls.asyncClientStreamingCall(channel.newCall(methodDescriptor, CallOptions.DEFAULT), observer)

    clientObserver.onNext(buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent))
    clientObserver.onCompleted()
  }

  private def asyncBidiStreamingCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      responseObserver: StreamObserver[DynamicMessage]
  ): Unit = {
    val methodDescriptor = generateMethodDescriptor(originMethodDescriptor)

    val observer = new StreamObserver[DynamicMessage]() {
      override def onNext(response: DynamicMessage): Unit = {
        responseObserver.onNext(response)
      }

      override def onError(t: Throwable): Unit = logger.error("Error while streaming bidi response", t)

      override def onCompleted(): Unit = responseObserver.onCompleted()
    }

    val clientObserver =
      ClientCalls.asyncBidiStreamingCall(channel.newCall(methodDescriptor, CallOptions.DEFAULT), observer)

    clientObserver.onNext(buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent))
  }

  private def blockingUnaryCall(
      channel: ManagedChannel,
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String],
      promise: Promise[Seq[DynamicMessage]]
  ): Unit = {
    val methodDescriptor = generateMethodDescriptor(originMethodDescriptor)

    promise.success(
      Seq(
        ClientCalls.blockingUnaryCall(
          channel.newCall(methodDescriptor, CallOptions.DEFAULT),
          buildDynamicMessage(fileDescriptor, originMethodDescriptor, requestContent)
        )
      )
    )
  }

  private def buildDynamicMessage(
      fileDescriptor: Descriptors.FileDescriptor,
      originMethodDescriptor: Descriptors.MethodDescriptor,
      requestContent: scala.Option[String]
  ): DynamicMessage = {
    val registry = TypeRegistry.newBuilder.add(fileDescriptor.getMessageTypes).build

    val messageBuilder = DynamicMessage.newBuilder(originMethodDescriptor.getInputType)

    requestContent.foreach(content => {
      val parser = com.google.protobuf.util.JsonFormat.parser.usingTypeRegistry(registry)
      parser.merge(content, messageBuilder)
    })

    messageBuilder.build()
  }

  private def generateMethodDescriptor(
      originMethodDescriptor: Descriptors.MethodDescriptor
  ): MethodDescriptor[DynamicMessage, DynamicMessage] = {

    val fullMethodName = MethodDescriptor.generateFullMethodName(
      originMethodDescriptor.getService.getFullName,
      originMethodDescriptor.getName
    )

    val inputTypeMarshaller  =
      ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getInputType).buildPartial)
    val outputTypeMarshaller =
      ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getOutputType).buildPartial)

    MethodDescriptor
      .newBuilder[DynamicMessage, DynamicMessage]
      .setFullMethodName(fullMethodName)
      .setRequestMarshaller(inputTypeMarshaller)
      .setResponseMarshaller(outputTypeMarshaller)
      .setType(MethodDescriptor.MethodType.UNKNOWN)
      .build
  }
}
