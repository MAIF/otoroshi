package functional

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import otoroshi.netty.NettyHttp3Client
import otoroshi.security.IdGenerator
import otoroshi.utils.http.MtlsConfig
import play.api.Configuration
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.WSBodyReadables.given

import scala.concurrent.duration.DurationInt

/**
 * End to end smoke test for the HTTP/3 (QUIC) listener of the experimental netty server.
 *
 * The quic/http3 codecs graduated from io.netty.incubator.codec.{quic,http3} to
 * io.netty.handler.codec.{quic,http3} in netty 4.2. That move is a pure package rename, so it
 * compiles without telling us anything about the native quic library actually loading and about
 * the handshake still working. This spec boots the real listener and drives it with otoroshi's own
 * NettyHttp3Client, so the whole path (native lib -> quic handshake -> http3 frames -> router) is
 * exercised.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.Http3Spec'
 */
class Http3Spec extends OtoroshiSpec with BeforeAndAfterAll {

  private val nettyHttpsPort: Int = TargetService.freePort
  private val http3Port: Int      = TargetService.freePort

  private lazy val h3Client = new NettyHttp3Client(otoroshiComponents.env)

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
           |otoroshi.next.state-sync-interval = 5
           |otoroshi.next.experimental.netty-server.enabled = true
           |otoroshi.next.experimental.netty-server.http-port = -1
           |otoroshi.next.experimental.netty-server.https-port = $nettyHttpsPort
           |otoroshi.next.experimental.netty-server.native.enabled = false
           |otoroshi.next.experimental.netty-server.http3.enabled = true
           |otoroshi.next.experimental.netty-server.http3.port = $http3Port
           |otoroshi.next.experimental.netty-server.http3.exposedPort = $http3Port
           |""".stripMargin)
        .resolve()
    ).withFallback(configuration)
  }

  override def beforeAll(): Unit = {
    startOtoroshi()
    getOtoroshiRoutes().futureValue // warm up
    await(2.seconds)                // wait for router sync
  }

  override def afterAll(): Unit = {
    stopAll()
  }

  "Otoroshi HTTP/3 listener" should {

    "serve a route over quic" in {
      val domain = s"h3-${IdGenerator.uuid}.oto.tools"
      val route  = createLocalRoute(
        rawDomain = Some(domain),
        result = _ => Json.obj("message" -> "hello http3")
      ).futureValue

      // the url host drives the SNI, the :authority pseudo header and the udp address at once:
      // *.oto.tools resolves to 127.0.0.1, and quic needs a resolved address (passing a Target
      // goes through InetSocketAddress.createUnresolved, which QuicChannel rejects)
      val resp = h3Client
        .url(s"https://$domain:$http3Port/api")
        // the h3 client only honours trustAll when mtls is on (see NettyHttp3Client.getSslContextFrom),
        // and otoroshi serves a self signed cert for *.oto.tools here
        .withTlsConfig(MtlsConfig(mtls = true, trustAll = true, loose = true))
        .withRequestTimeout(30.seconds)
        .get()
        .futureValue

      resp.status mustBe Status.OK
      (resp.json \ "message").as[String] mustBe "hello http3"

      deleteOtoroshiRoute(route).futureValue
    }
  }
}
