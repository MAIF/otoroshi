package plugins

import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.algorithms.Algorithm
import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.{Bind, Volume}
import functional.PluginsTestSpec
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.testcontainers.utility.MountableFile
import otoroshi.models.{HSAlgoSettings, SecComVersionV2}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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

  buildAndSaveDockerImage("grpc-server")

  val grpcServer = GenericContainer("grpc-server", Seq(8082))

  grpcServer.start()

  Thread.sleep(500000)

//    val route = createLocalRoute(
//      Seq(
//        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
//        NgPluginInstance(
//          plugin = NgPluginHelper.pluginId[GrpcWebProxyPlugin],
//          config = NgPluginInstanceConfig(
//            GrpcWebConfig(
//            ).json.as[JsObject]
//          )
//        )
//      ),
//      rawResult = Some(req => {
//        val tokenBody           = req.headers.find(_.name() == "foo").get.value().split("\\.")(1)
//        val requestTokenPayload = Json
//          .parse(ApacheBase64.decodeBase64(tokenBody))
//          .as[JsObject]
//        val rawPayload          = requestTokenPayload
//          .deepMerge(Json.obj("aud" -> "Otoroshi", "state-resp" -> requestTokenPayload.selectAsString("state")))
//        val headerJson          = Json.obj("alg" -> "HS256", "typ" -> "JWT")
//        val header              =
//          ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
//        val payload             =
//          ApacheBase64.encodeBase64URLSafeString(Json.stringify(rawPayload).getBytes(StandardCharsets.UTF_8))
//        val content             = String.format("%s.%s", header, payload)
//        val signatureBytes      =
//          Algorithm
//            .HMAC256("verysecret")
//            .sign(
//              header.getBytes(StandardCharsets.UTF_8),
//              payload.getBytes(StandardCharsets.UTF_8)
//            )
//        val signature           = ApacheBase64.encodeBase64URLSafeString(signatureBytes)
//
//        val signedToken = s"$content.$signature"
//        (200, "", List(RawHeader("bar", signedToken)))
//      })
//    ).futureValue
//
//    val resp = ws
//      .url(s"http://127.0.0.1:$port/api/users")
//      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
//      .get()
//      .futureValue
//
//    resp.status mustBe Status.OK
//
//    deleteOtoroshiRoute(route).futureValue

}
