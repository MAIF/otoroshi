package plugins

import otoroshi.next.plugins.ForwardedHeader
import otoroshi.utils.IpAddresses
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.FakeRequest

// the rfc 7239 value built by the Forwarded header plugin, without any otoroshi instance involved
class ForwardedHeaderSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private def request(remoteAddress: String, headers: (String, String)*) = {
    FakeRequest("GET", "/api", Headers(headers*), AnyContentAsEmpty, remoteAddress = remoteAddress)
  }

  "ForwardedHeader.node" should {

    "keep an ipv4 address as a token" in {
      ForwardedHeader.node("192.0.2.43") mustBe "192.0.2.43"
    }

    "enclose an ipv6 address in brackets and quotes" in {
      ForwardedHeader.node("2001:db8:cafe::17") mustBe "\"[2001:db8:cafe::17]\""
      ForwardedHeader.node("0:0:0:0:0:0:0:1") mustBe "\"[0:0:0:0:0:0:0:1]\""
    }

    "quote a node carrying a port" in {
      ForwardedHeader.node("192.0.2.43:47011") mustBe "\"192.0.2.43:47011\""
      ForwardedHeader.node("[2001:db8:cafe::17]:4711") mustBe "\"[2001:db8:cafe::17]:4711\""
    }

    "keep the unknown and obfuscated identifiers as tokens" in {
      ForwardedHeader.node("unknown") mustBe "unknown"
      ForwardedHeader.node("_hidden") mustBe "_hidden"
    }
  }

  "ForwardedHeader.quoted" should {

    "escape the quotes and backslashes of a quoted-string" in {
      ForwardedHeader.quoted("a\"b\\c d") mustBe "\"a\\\"b\\\\c d\""
    }

    "quote a host carrying a port" in {
      ForwardedHeader.quoted("api.example.com") mustBe "api.example.com"
      ForwardedHeader.quoted("api.example.com:8443") mustBe "\"api.example.com:8443\""
    }
  }

  "ForwardedHeader.value" should {

    "describe the peer alone when the forwarded headers are not trusted" in {
      val req = request(
        "198.51.100.2",
        "X-Forwarded-For" -> "192.0.2.15",
        "Forwarded"       -> "for=192.0.2.15"
      )
      ForwardedHeader.value(req, "api.example.com", "http", trustXForwarded = false) mustBe
      "for=198.51.100.2;host=api.example.com;proto=http"
    }

    "enclose an ipv6 peer in brackets and quotes" in {
      ForwardedHeader.value(request("2001:db8::1"), "api.example.com", "https", trustXForwarded = false) mustBe
      "for=\"[2001:db8::1]\";host=api.example.com;proto=https"
    }

    "leave out the parameters that are not known" in {
      ForwardedHeader.value(request("198.51.100.2"), "", "http", trustXForwarded = false) mustBe
      "for=198.51.100.2;proto=http"
    }

    "describe the peer alone when there is no chain in front of otoroshi" in {
      ForwardedHeader.value(request("198.51.100.2"), "api.example.com", "https", trustXForwarded = true) mustBe
      "for=198.51.100.2;host=api.example.com;proto=https"
    }

    "rewrite a trusted X-Forwarded-For chain element by element" in {
      val req = request("198.51.100.2", "X-Forwarded-For" -> "192.0.2.15, 2001:db8::7", "X-Forwarded-For" -> "198.51.100.1")
      ForwardedHeader.value(req, "api.example.com", "https", trustXForwarded = true) mustBe
      "for=192.0.2.15;host=api.example.com;proto=https, for=\"[2001:db8::7]\", for=198.51.100.1, for=198.51.100.2"
    }

    "append the peer to a trusted Forwarded chain" in {
      val req = request(
        "198.51.100.2",
        "Forwarded"       -> "for=192.0.2.15;proto=https;host=api.example.com",
        "Forwarded"       -> "for=198.51.100.1",
        "X-Forwarded-For" -> "203.0.113.9"
      )
      ForwardedHeader.value(req, "api.example.com", "https", trustXForwarded = true) mustBe
      "for=192.0.2.15;proto=https;host=api.example.com, for=198.51.100.1, for=198.51.100.2"
    }

    "be read back by otoroshi as the same proxy chain" in {
      val req   = request("2001:db8::2", "X-Forwarded-For" -> "192.0.2.15, 198.51.100.1")
      val value = ForwardedHeader.value(req, "api.example.com", "https", trustXForwarded = true)
      IpAddresses.parseForwardedChain(Seq(value), Seq.empty) mustBe Seq("192.0.2.15", "198.51.100.1", "2001:db8::2")
    }
  }
}
