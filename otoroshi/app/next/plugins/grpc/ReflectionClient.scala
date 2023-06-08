package otoroshi.next.plugins.grpc

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf._
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc.{CallOptions, ManagedChannel, ManagedChannelBuilder, MethodDescriptor}

import scala.concurrent.Promise
import scala.jdk.CollectionConverters._

class ReflectionClient(val address: String, val port: Int, val secured: Boolean) {
    private val channel = if(secured) {
        ManagedChannelBuilder.forAddress(address, port).build()
    } else {
        ManagedChannelBuilder.forAddress(address, port)
                .usePlaintext()
                .asInstanceOf[ManagedChannelBuilder[_]]
                .build()
    }

    private val reflectionStub = ServerReflectionGrpc.newStub(channel)

    def call(fullServiceName: String,
                methodName: String,
                packageName: String,
                serviceName: String,
                requestContent: String,
                promise: Promise[DynamicMessage]) = {

        val observer = new StreamObserver[ServerReflectionResponse]() {
          override def onNext(response: ServerReflectionResponse) {
            if (response.getMessageResponseCase == ServerReflectionResponse.MessageResponseCase.FILE_DESCRIPTOR_RESPONSE) {
              handleResponse(response.getFileDescriptorResponse.getFileDescriptorProtoList,
                methodName,
                packageName,
                serviceName,
                requestContent,
                promise)
            } else {
              println(response.getMessageResponseCase)
            }
          }

        override def onError(t: Throwable): Unit = {
          System.out.println("on error")
          t.printStackTrace()
        }

        override def onCompleted(): Unit = {
          System.out.println("Complete call")
        }
      }

      val requestStreamObserver = reflectionStub.serverReflectionInfo(observer)

      val getFileContainingSymbolRequest = ServerReflectionRequest.newBuilder()
        .setFileContainingSymbol(fullServiceName)
        .build()
      requestStreamObserver.onNext(getFileContainingSymbolRequest)
      requestStreamObserver.onCompleted()
    }

  def handleResponse(fileDescriptorProtoList: java.util.List[ByteString],
                     methodName: String,
                     packageName: String,
                     serviceName: String,
                     requestContent: String,
                     promise: Promise[DynamicMessage]): Unit = {
      val fileDescriptor: Descriptors.FileDescriptor = getFileDescriptor(fileDescriptorProtoList, packageName, serviceName)

      val serviceDescriptor = fileDescriptor.getFile.findServiceByName(serviceName)
      val methodDescriptor = serviceDescriptor.findMethodByName(methodName)

      executeCall(channel, fileDescriptor, methodDescriptor, requestContent, promise)
  }

  def getFileDescriptor(fileDescriptorProtoList: java.util.List[ByteString],  packageName: String, serviceName: String): Descriptors.FileDescriptor = {
    val fileDescriptorProtoMap: Map[String, DescriptorProtos.FileDescriptorProto] = fileDescriptorProtoList
        .asScala
        .map(bs => DescriptorProtos.FileDescriptorProto.parseFrom(bs))
        .foldLeft(Map.empty[String, DescriptorProtos.FileDescriptorProto]) {
          case (acc, item) => acc + (item.getName -> item)
        }

    if (fileDescriptorProtoMap.isEmpty) {
      throw new IllegalArgumentException("fileDescriptorProtoMap.isEmpty()")
    }

    val fileDescriptorProto: DescriptorProtos.FileDescriptorProto = findServiceFileDescriptorProto(packageName, serviceName, fileDescriptorProtoMap)

    Descriptors.FileDescriptor.buildFrom(fileDescriptorProto, Array.empty[FileDescriptor])
  }

  def findServiceFileDescriptorProto(packageName: String,
                                     serviceName: String,
                                     fileDescriptorProtoMap: Map[String, DescriptorProtos.FileDescriptorProto]): DescriptorProtos.FileDescriptorProto = {
    for (proto <- fileDescriptorProtoMap.values) {
      if (proto.getPackage.equals(packageName)) {
        val exist = proto.getServiceList.stream.anyMatch((s) => serviceName == s.getName)
        if (exist) {
          return proto
        }
      }
    }

    null
  }

  private def executeCall(channel: ManagedChannel,
                          fileDescriptor: Descriptors.FileDescriptor,
                          originMethodDescriptor: Descriptors.MethodDescriptor,
                          requestContent: String,
                          promise: Promise[DynamicMessage]
                         ): Unit = {
    val methodDescriptor = generateMethodDescriptor(originMethodDescriptor)

    val registry = TypeRegistry.newBuilder.add(fileDescriptor.getMessageTypes).build

    val messageBuilder = DynamicMessage.newBuilder(originMethodDescriptor.getInputType)
    val parser = com.google.protobuf.util.JsonFormat.parser.usingTypeRegistry(registry)

    parser.merge(requestContent, messageBuilder)

    val observer = new StreamObserver[DynamicMessage]() {
      override def onNext(response: DynamicMessage): Unit = {
        println(response)
        promise.success(response)
      }

      override def onError(t: Throwable): Unit = {
        System.out.println("on error")
        t.printStackTrace()
      }

      override def onCompleted() {
        System.out.println("Complete async unary call")
      }
    }

    ClientCalls.asyncUnaryCall(
      channel.newCall(methodDescriptor, CallOptions.DEFAULT),
      messageBuilder.build,
      observer)
  }

  private def generateMethodDescriptor(originMethodDescriptor: Descriptors.MethodDescriptor): MethodDescriptor[DynamicMessage, DynamicMessage] = {

    val fullMethodName = MethodDescriptor.generateFullMethodName(originMethodDescriptor.getService.getFullName, originMethodDescriptor.getName)

    val inputTypeMarshaller = ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getInputType).buildPartial)
    val outputTypeMarshaller = ProtoUtils.marshaller(DynamicMessage.newBuilder(originMethodDescriptor.getOutputType).buildPartial)

    MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]
      .setFullMethodName(fullMethodName)
      .setRequestMarshaller(inputTypeMarshaller)
      .setResponseMarshaller(outputTypeMarshaller)
      .setType(MethodDescriptor.MethodType.UNKNOWN)
      .build
  }
}
