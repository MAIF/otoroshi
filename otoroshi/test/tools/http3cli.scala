package tools

import akka.util.ByteString
import cats.implicits.catsSyntaxOptionId
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{OptionValues, Suites}
import otoroshi.netty.{NettyHttp3Client, NettyHttp3ClientBody}
import reactor.core.publisher.Flux

import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/*
class Http3ClientSpec extends AnyWordSpec with Matchers with OptionValues {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  val fu = for {
    resp0 <- NettyHttp3Client.getUrl(
               "GET",
               "https://test-basic-apikey-next-gen.oto.tools:10048/api?foo=bar#a",
               Map.empty,
               None
             )
    resp1 <- NettyHttp3Client.getUrl("GET", "https://test-basic-apikey-next-gen.oto.tools:10048/", Map.empty, None)
    resp2 <- NettyHttp3Client.getUrl(
               "POST",
               "https://test-basic-apikey-next-gen.oto.tools:10048/",
               Map.empty,
               Some(NettyHttp3ClientBody(Flux.just(Seq(ByteString("coucou")): _*), "text/plain".some, 6L.some))
             )
    resp4 <- NettyHttp3Client.getUrl("GET", "https://www.google.fr", Map.empty, None)
  } yield {

    println(s"resp4 status:  ${resp4.status}")
    println(s"resp4 headers: ${resp4.headers}")
    println(s"resp4 body:    ${resp4.body}")

    println(s"resp0 status:  ${resp0.status}")
    println(s"resp0 headers: ${resp0.headers}")
    println(s"resp0 body:    ${resp0.body}")

    println(s"resp1 status:  ${resp1.status}")
    println(s"resp1 headers: ${resp1.headers}")
    println(s"resp1 body:    ${resp1.body}")

    println(s"resp2 status:  ${resp2.status}")
    println(s"resp2 headers: ${resp2.headers}")
    println(s"resp2 body:    ${resp2.body}")
  }

  Await.result(fu, 10.seconds)
}

class Http3ClientTests
    extends Suites(
      new Http3ClientSpec()
    )
 */
