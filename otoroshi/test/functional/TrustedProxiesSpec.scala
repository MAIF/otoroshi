package functional

import otoroshi.models.GlobalConfig
import otoroshi.utils.ForwardedFor

class TrustedProxiesSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers {

  private val remote = "198.51.100.10"
  private val proxy  = "198.51.100.10"
  private val client = "192.0.2.15"

  // what `GlobalConfig.resolvedTrustedProxies` hands over
  private def trusted(entries: String*): Seq[String] = ForwardedFor.trustRules(entries)

  "ForwardedFor.clientIp" should {

    "keep the previous leftmost behaviour when no proxy is trusted" in {
      ForwardedFor.clientIp(Seq(s"203.0.113.7, $client"), Seq.empty, remote) mustBe "203.0.113.7"
    }

    // these five returned exactly this before the change
    "return degenerate headers untouched when no proxy is trusted" in {
      val legacy = (raw: String) => ForwardedFor.clientIp(Seq(raw), Seq.empty, remote)
      legacy("") mustBe ""
      legacy("   ") mustBe "   "
      legacy(s" $client ") mustBe s" $client "
      legacy(s",$client") mustBe ""
      // `",,,".split(",")` yields an empty array, so the raw header comes back
      legacy(",,,") mustBe ",,,"
    }

    "read only the first occurrence when no proxy is trusted" in {
      ForwardedFor.clientIp(Seq("203.0.113.7", "203.0.113.8"), Seq.empty, remote) mustBe "203.0.113.7"
    }

    "read the address added by the trusted proxy, not the one the client claims" in {
      ForwardedFor.clientIp(Seq(s"203.0.113.7, $client, $proxy"), trusted(proxy), remote) mustBe client
    }

    "ignore a forged entry even when it impersonates the trusted proxy" in {
      ForwardedFor.clientIp(Seq(s"$proxy, $client, $proxy"), trusted(proxy), remote) mustBe client
    }

    "discard every trusted hop, not just the last one" in {
      ForwardedFor.clientIp(
        Seq(s"$client, $proxy, 198.51.100.11"),
        trusted("198.51.100.0/24"),
        remote
      ) mustBe client
    }

    "accept CIDR blocks and wildcards as trusted proxies" in {
      ForwardedFor.clientIp(Seq(s"$client, $proxy"), trusted("198.51.100.0/24"), remote) mustBe client
      ForwardedFor.clientIp(Seq(s"$client, $proxy"), trusted("198.51.100.*"), remote) mustBe client
    }

    "fall back to the remote address when every entry is a trusted proxy" in {
      ForwardedFor.clientIp(Seq(s"$proxy, 198.51.100.11"), trusted("198.51.100.0/24"), remote) mustBe remote
    }

    "ignore the header entirely when the peer is not a trusted proxy" in {
      ForwardedFor.clientIp(Seq(s"$client, $proxy"), trusted(proxy), "203.0.113.99") mustBe "203.0.113.99"
    }

    "read the header when the peer is a trusted proxy" in {
      ForwardedFor.clientIp(Seq(s"203.0.113.7, $client"), trusted(proxy), proxy) mustBe client
    }

    "join every occurrence of the header" in {
      ForwardedFor.clientIp(Seq("203.0.113.7", s"$client, $proxy"), trusted(proxy), proxy) mustBe client
    }

    // known limit: a front filing its entry before a caller controlled one leaves
    // the last field unauthenticated
    "trust the last entry, which assumes our proxies append after the caller" in {
      ForwardedFor.clientIp(Seq(s"$client, $proxy", "203.0.113.7"), trusted(proxy), proxy) mustBe "203.0.113.7"
    }

    "fall back to the remote address on an empty header" in {
      ForwardedFor.clientIp(Seq("   "), trusted(proxy), remote) mustBe remote
    }

    "handle a single entry" in {
      ForwardedFor.clientIp(Seq(client), trusted(proxy), remote) mustBe client
      ForwardedFor.clientIp(Seq(proxy), trusted(proxy), remote) mustBe remote
    }

    // otherwise the hop is not recognised and its own address is handed back
    "recognise a trusted hop written in another spelling of the same address" in {
      ForwardedFor.clientIp(
        Seq(s"$client, 2001:db8:0:0:0:0:0:1"),
        trusted("10.0.0.1", "2001:db8::1"),
        "10.0.0.1"
      ) mustBe client
    }

    "recognise a trusted peer written in another spelling of the same address" in {
      ForwardedFor.clientIp(Seq(client), trusted("2001:db8::1"), "2001:db8:0:0:0:0:0:1") mustBe client
    }

    "return the client address exactly as it was written" in {
      ForwardedFor.clientIp(
        Seq("2001:0DB8:0:0:0:0:0:99, 2001:db8:0:0:0:0:0:1"),
        trusted("2001:db8::1"),
        "2001:db8::1"
      ) mustBe "2001:0DB8:0:0:0:0:0:99"
    }
  }

  "GlobalConfig.resolvedTrustedProxies" should {

    // the shape a single environment variable takes
    "split a comma separated entry and turn its literals into blocks" in {
      GlobalConfig(trustedProxies = Seq(" 10.0.0.1, 2001:db8::1 ", "")).resolvedTrustedProxies mustBe
        Seq("10.0.0.1/32", "2001:db8::1/128")
    }
  }

  "ForwardedFor.trustRules" should {

    "turn a literal address into a single address block" in {
      ForwardedFor.trustRules(Seq("10.0.0.1")) mustBe Seq("10.0.0.1/32")
      ForwardedFor.trustRules(Seq("2001:db8::1")) mustBe Seq("2001:db8::1/128")
    }

    "leave blocks, wildcards and anything else alone" in {
      ForwardedFor.trustRules(Seq("10.0.0.0/8", "10.0.*.*", "not-an-address")) mustBe
        Seq("10.0.0.0/8", "10.0.*.*", "not-an-address")
    }

    // converted, the rule would cover every interface instead of the one it names
    "leave a zoned address alone" in {
      ForwardedFor.trustRules(Seq("fe80::1%eth0")) mustBe Seq("fe80::1%eth0")
      ForwardedFor.clientIp(
        Seq(s"$client, fe80::1%eth1"),
        ForwardedFor.trustRules(Seq("fe80::1%eth0")),
        "fe80::1%eth0"
      ) mustBe "fe80::1%eth1"
    }
  }
}
