package plugins

import akka.http.scaladsl.model.ContentTypes
import akka.stream.Attributes
import akka.stream.alpakka.s3.AccessStyle.PathAccessStyle
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.headers.CannedAcl
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.dimafeng.testcontainers.GenericContainer
import functional.PluginsTestSpec
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, S3Backend}
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.inmemory.S3Configuration
import play.api.libs.json.JsObject
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.duration.DurationInt

class S3BackendTests(parent: PluginsTestSpec) {
  import parent._


  val s3Container = GenericContainer(
    dockerImage = "quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z",
    exposedPorts = Seq(9000, 9001),
    env = Map(
      "MINIO_ROOT_USER"     -> "admin",
      "MINIO_ROOT_PASSWORD" -> "secret123"
    ),
    command = Seq("server", "/data", "--console-address", ":9001"),
    waitStrategy = Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200)
  )
  s3Container.start()

  val s3Host = s3Container.host
  val s3Port = s3Container.mappedPort(9000)

  println(s"S3 endpoint: http://$s3Host:$s3Port")

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[S3Backend],
        config = NgPluginInstanceConfig(
          S3Configuration(
            bucket = "foobar2",
            endpoint = s"http://$s3Host:$s3Port",
            access = "admin",
            secret = "secret123",
            key = "",
            region = "eu-west-1",
            writeEvery = 60000.seconds,
            acl = CannedAcl.Private,
            pathStyleAccess = true
          ).json
            .as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid,
    domain = "s3backend.oto.tools"
  )

  def s3Client = {
    S3Attributes.settings(
      S3Settings(
        bufferType = MemoryBufferType,
        credentialsProvider = StaticCredentialsProvider.create(
          AwsBasicCredentials.create("admin", "secret123")
        ),
        s3RegionProvider = new AwsRegionProvider {
          override def getRegion: Region = Region.US_EAST_1
        },
        listBucketApiVersion = ApiVersion.ListBucketVersion2
      )
        .withEndpointUrl(s"http://$s3Host:$s3Port")
        .withAccessStyle(PathAccessStyle)
    )
  }

  implicit val attrs: Attributes = s3Client

  val htmlContent =
    """<!DOCTYPE html>
      |<html>
      |  <head><title>My MinIO Page</title></head>
      |  <body><h1>Hello from MinIO!</h1></body>
      |</html>""".stripMargin

  S3
    .makeBucket("foobar2", S3Headers.empty)
    .futureValue
  S3.putObject(
    "foobar2",
    "index.html",
    Source.single(ByteString(htmlContent)),
    htmlContent.length,
    contentType = ContentTypes.`text/html(UTF-8)`,
    S3Headers.empty
  ).withAttributes(s3Client)
    .runWith(Sink.headOption)
    .futureValue

  val resp2 = ws
    .url(s"http://127.0.0.1:$port/index.html")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp2.status mustBe 200
  resp2.body contains "Hello from MinIO" mustBe true

  deleteOtoroshiRoute(route).futureValue
  s3Container.stop()
}
