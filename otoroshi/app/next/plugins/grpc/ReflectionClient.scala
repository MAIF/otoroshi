package otoroshi.next.plugins.grpc;

import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver

import java.util.concurrent.TimeUnit
import scala.concurrent.Promise
import scala.util.Try;

class ReflectionClient(val address: String, val port: Int, val secured: Boolean) {
    private val channel = if(secured) {
        ManagedChannelBuilder.forAddress(address, port).build();
    } else {
        ManagedChannelBuilder.forAddress(address, port)
                .usePlaintext()
                .asInstanceOf[ManagedChannelBuilder[_]]
                .build();
    }

    private val reflectionStub = ServerReflectionGrpc.newStub(channel);


    def call(promise: Promise[String]) {
        val streamObserver = new StreamObserver[ServerReflectionResponse]() {
            override def onNext(response: ServerReflectionResponse) {
                val messageResponseCase = response.getMessageResponseCase();


                val res = String.format("Case: %s, Response: %s", messageResponseCase, response);
                promise.complete(Try { res });
            }

            override  def onError(t: Throwable) {
                System.out.println("on error");
                t.printStackTrace();
            }

            override def onCompleted() {
                System.out.println("Complete")
            }
        };

        val requestStreamObserver = reflectionStub.serverReflectionInfo(streamObserver)

        val listServiceRequest = listService()

        requestStreamObserver.onNext(listServiceRequest)
        requestStreamObserver.onCompleted()

        // channel.awaitTermination(500, TimeUnit.MILLISECONDS)
    }

    def listService(): ServerReflectionRequest = {
        System.out.println("listService");
        ServerReflectionRequest.newBuilder()
                .setListServices("")
                .build()
    }
}
