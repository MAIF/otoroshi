package functional

import org.apache.pekko.util.ByteString
import otoroshi.gateway.GatewayRequestHandler
import play.api.mvc.Cookie
import play.api.test.FakeRequest

import scala.util.Random

// the checks the gateway handler runs on every request: the size limits of otoroshi.requests.*, in bytes of UTF-8,
// measured without encoding anything in the common case, and the protocol a request is counted under in the status a
// node reports to the leader
class GatewayRequestChecksSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private def utf8Size(value: String): Long = ByteString(value).size.toLong

  // one, two, three and four bytes chars, and lone surrogates, which UTF-8 encodes as a single '?'
  private val pieces = Seq("a", "Z", "/", "é", "€", "中", "😀", "\uD83D", "\uDE00")

  private def randomString(random: Random, length: Int): String =
    (0 until length).map(_ => pieces(random.nextInt(pieces.size))).mkString

  "GatewayRequestHandler.fitsInUtf8" should {

    "give the verdict of the UTF-8 size around it" in {
      val samples = Seq("", "abc", "héllo", "€", "中文", "a😀b", "\uD83D", "x\uDE00y", "\uDE00\uD83D")
      for (value <- samples; limit <- (utf8Size(value) - 2) to (utf8Size(value) + 2) if limit >= 0) {
        GatewayRequestHandler.fitsInUtf8(value, limit) mustBe (utf8Size(value) <= limit)
      }
    }

    "give the verdict of the UTF-8 size on random strings, up to three bytes per char" in {
      val random = new Random(42)
      for (_ <- 0 until 2000) {
        val value = randomString(random, random.nextInt(200))
        val size  = utf8Size(value)
        for (limit <- Seq(size - 1, size, size + 1, value.length * 3L - 1, value.length * 3L) if limit >= 0) {
          GatewayRequestHandler.fitsInUtf8(value, limit) mustBe (size <= limit)
        }
      }
    }
  }

  "GatewayRequestHandler.urlFits" should {

    "give the verdict of the UTF-8 size of the whole url" in {
      val random = new Random(7)
      for (_ <- 0 until 2000) {
        val protocol = if (random.nextBoolean()) "https" else "http"
        val host     = randomString(random, random.nextInt(30))
        val uri      = "/" + randomString(random, random.nextInt(200))
        val size     = utf8Size(s"$protocol://$host$uri")
        for (limit <- Seq(size - 1, size, size + 1)) {
          GatewayRequestHandler.urlFits(protocol, host, uri, limit) mustBe (size <= limit)
        }
      }
    }
  }

  "GatewayRequestHandler.cookiesFit" should {

    "check the value of every cookie" in {
      val request = FakeRequest("GET", "/").withCookies(Cookie("a", "short"), Cookie("b", "x" * 100))
      GatewayRequestHandler.cookiesFit(request, 100) mustBe true
      GatewayRequestHandler.cookiesFit(request, 99) mustBe false
      GatewayRequestHandler.cookiesFit(FakeRequest("GET", "/"), 0) mustBe true
    }
  }

  "GatewayRequestHandler.headersFit" should {

    "check the name and the value of every header" in {
      val request = FakeRequest("GET", "/").withHeaders("X-Value" -> ("v" * 20), ("X-" + "n" * 18) -> "v")
      GatewayRequestHandler.headersFit(request, 20, 20) mustBe true
      GatewayRequestHandler.headersFit(request, 19, 20) mustBe false
      GatewayRequestHandler.headersFit(request, 20, 19) mustBe false
    }

    "count the bytes of a value, not its chars" in {
      val request = FakeRequest("GET", "/").withHeaders("X-Value" -> ("€" * 10))
      GatewayRequestHandler.headersFit(request, 128, 30) mustBe true
      GatewayRequestHandler.headersFit(request, 128, 29) mustBe false
    }

    "check the first value of a repeated header, the one the proxy forwards" in {
      val request = FakeRequest("GET", "/").withHeaders("X-Dup" -> "short", "X-Dup" -> ("v" * 100))
      GatewayRequestHandler.headersFit(request, 128, 10) mustBe true
    }
  }

  "GatewayRequestHandler.isHttp2 and isHttp3" should {

    "recognize the versions the servers report" in {
      def request(version: String) = FakeRequest("GET", "/").withVersion(version)
      GatewayRequestHandler.isHttp2(request("HTTP/2.0")) mustBe true
      GatewayRequestHandler.isHttp2(request("HTTP/2")) mustBe true
      GatewayRequestHandler.isHttp2(request("HTTP/1.1")) mustBe false
      GatewayRequestHandler.isHttp2(request("HTTP/3.0")) mustBe false
      GatewayRequestHandler.isHttp2(FakeRequest("GET", "/").withHeaders("x-http2-stream-id" -> "3")) mustBe true
      GatewayRequestHandler.isHttp3(request("HTTP/3.0")) mustBe true
      GatewayRequestHandler.isHttp3(request("HTTP/3")) mustBe true
      GatewayRequestHandler.isHttp3(request("HTTP/2.0")) mustBe false
      GatewayRequestHandler.isHttp3(request("HTTP/1.1")) mustBe false
    }
  }
}
