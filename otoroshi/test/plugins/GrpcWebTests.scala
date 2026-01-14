package plugins

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.{Bind, Volume}
import functional.PluginsTestSpec
import io.netty.buffer.Unpooled
import org.testcontainers.utility.MountableFile
import otoroshi.models.HttpProtocols
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.JsObject
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import scala.concurrent.{Future, Promise}

class GrpcWebTests(parent: PluginsTestSpec) {

  import parent._

  def buildAndSaveDockerImage(imageName: String) = {
    val dockerClient = GenericContainer("docker:27-cli")
      .configure { c =>
        c.withCreateContainerCmdModifier { cmd =>
          cmd.getHostConfig.withBinds(
            new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock"))
          )
        }
        c.withCommand("sh", "-c", "sleep infinity")
      }

    dockerClient.start()

    val resourcePath = Paths.get(getClass.getResource("/grpc-server").toURI).toString

    // Copy build artifacts
    dockerClient.copyFileToContainer(
      MountableFile.forHostPath(s"$resourcePath/helloworld.proto"),
      "/build/helloworld.proto"
    )
    dockerClient.copyFileToContainer(
      MountableFile.forHostPath(s"$resourcePath/server.js"),
      "/build/server.js"
    )
    dockerClient.copyFileToContainer(
      MountableFile.forHostPath(s"$resourcePath/package.json"),
      "/build/package.json"
    )
    dockerClient.copyFileToContainer(MountableFile.forHostPath(s"$resourcePath/Dockerfile"), "/build/Dockerfile")

    // Build
    println(s"Building Docker image: $imageName:latest")
    val buildResult = dockerClient.execInContainer("docker", "build", "-t", s"$imageName:latest", "/build")
    if (buildResult.getExitCode != 0) {
      throw new RuntimeException(s"Docker build failed: ${buildResult.getStderr}")
    }

    println(s"Image saved to /tmp/$imageName.tar")

    dockerClient.stop()
  }

  def run() = {
    buildAndSaveDockerImage("grpc-server")

    val grpcServer = GenericContainer("grpc-server", exposedPorts = Seq(8082))

    grpcServer.start()

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[GrpcWebProxyPlugin],
          config = NgPluginInstanceConfig(
            GrpcWebConfig().json.as[JsObject]
          )
        )
      ),
      target = NgTarget(
        hostname = grpcServer.host,
        port = grpcServer.mappedPort(8082),
        id = "grpc-target",
        tls = false,
        protocol = HttpProtocols.HTTP_2_0
      ).some
    ).futureValue

    def encodeHelloRequest(name: String): Array[Byte] = {
      val nameBytes = name.getBytes("UTF-8")
      val out       = new Array[Byte](2 + nameBytes.length)
      out(0) = ((1 << 3) | 2).toByte // field 1, wire type 2 (string)
      out(1) = nameBytes.length.toByte
      System.arraycopy(nameBytes, 0, out, 2, nameBytes.length)
      out
    }

    val name    = "World"
    val payload = encodeHelloRequest(name)

    val grpcFrame = {
      val buf = Unpooled.buffer(5 + payload.length)
      buf.writeByte(0)
      buf.writeInt(payload.length)
      buf.writeBytes(payload)
      buf
    }

    val grpcWebPayload = {
      val bytes = new Array[Byte](grpcFrame.readableBytes())
      grpcFrame.readBytes(bytes)
      Base64.getEncoder.encodeToString(bytes)
    }

    val pureNettyClient = HttpClient
      .create()
      .host(route.frontend.domains.head.domain)
      .port(port)
      .protocol(reactor.netty.http.HttpProtocol.HTTP11)

    val responseFuture: Future[String] = {
      val promise = Promise[String]()
      pureNettyClient
        .headers { h =>
          h.set("content-type", "application/grpc-web-text")
          h.set("accept", "application/grpc-web-text")
          h.set("x-grpc-web", "1")
        }
        .post()
        .uri("/helloworld.Greeter/SayHello")
        .send(ByteBufFlux.fromString(Mono.just(grpcWebPayload)))
        .responseSingle((res, content) => content.asString())
        .doOnNext(result => promise.success(result))
        .doOnError(error => promise.failure(error))
        .subscribe()
      promise.future
    }

    def cleanup(): Future[Unit] = Future {
      deleteOtoroshiRoute(route).futureValue
      grpcServer.stop()
    }

    responseFuture
      .map { case body =>
        println(s"Body: $body")
        new String(Base64.getUrlDecoder.decode(body), StandardCharsets.UTF_8).contains("Hello! World") mustBe true
        cleanup()
      }
  }
}
