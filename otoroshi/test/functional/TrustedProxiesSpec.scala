package functional

import org.scalatest.OptionValues
import otoroshi.utils.{IpAddressMatcher, IpAddresses}

class TrustedProxiesSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with OptionValues {

  "IpAddresses.normalize" should {

    "keep a plain address untouched" in {
      IpAddresses.normalize("192.168.0.1") mustBe "192.168.0.1"
      IpAddresses.normalize("2001:db8::1") mustBe "2001:db8::1"
    }

    "strip the port of an ipv4 address" in {
      IpAddresses.normalize("192.168.0.1:8080") mustBe "192.168.0.1"
    }

    "strip the brackets and the port of an ipv6 address" in {
      IpAddresses.normalize("[2001:db8::1]:8080") mustBe "2001:db8::1"
      IpAddresses.normalize("[2001:db8::1]") mustBe "2001:db8::1"
    }

    "strip the zone of an ipv6 address" in {
      IpAddresses.normalize("fe80::1%eth0") mustBe "fe80::1"
    }

    "unwrap an ipv4-mapped ipv6 address" in {
      IpAddresses.normalize("::ffff:192.168.0.1") mustBe "192.168.0.1"
      IpAddresses.normalize("::FFFF:192.168.0.1") mustBe "192.168.0.1"
    }

    "trim the surrounding spaces" in {
      IpAddresses.normalize("  192.168.0.1  ") mustBe "192.168.0.1"
    }
  }

  "IpAddresses.matches" should {

    "never match an empty pattern list" in {
      IpAddresses.matches("192.168.0.1", Seq.empty) mustBe false
    }

    "match a cidr range" in {
      IpAddresses.matches("192.168.0.42", Seq("192.168.0.0/24")) mustBe true
      IpAddresses.matches("192.168.1.42", Seq("192.168.0.0/24")) mustBe false
    }

    "match a wildcard pattern" in {
      IpAddresses.matches("192.168.0.42", Seq("192.168.0.*")) mustBe true
      IpAddresses.matches("192.168.1.42", Seq("192.168.0.*")) mustBe false
    }

    "match an ipv4-mapped address against an ipv4 cidr" in {
      IpAddresses.matches("::ffff:192.168.0.42", Seq("192.168.0.0/24")) mustBe true
    }

    "match an address carrying a port" in {
      IpAddresses.matches("192.168.0.42:51234", Seq("192.168.0.0/24")) mustBe true
    }
  }

  "IpAddresses.parseForwardedChain" should {

    "read X-Forwarded-For when there is no Forwarded header" in {
      IpAddresses.parseForwardedChain(Seq.empty, Seq("1.1.1.1, 10.0.0.1")) mustBe Seq("1.1.1.1", "10.0.0.1")
    }

    "concatenate every occurrence of X-Forwarded-For" in {
      IpAddresses.parseForwardedChain(Seq.empty, Seq("1.1.1.1", "10.0.0.1, 10.0.0.2")) mustBe Seq(
        "1.1.1.1",
        "10.0.0.1",
        "10.0.0.2"
      )
    }

    "prefer the Forwarded header when both are present" in {
      IpAddresses.parseForwardedChain(Seq("for=1.1.1.1"), Seq("9.9.9.9")) mustBe Seq("1.1.1.1")
    }

    "read every parameter layout of the Forwarded header" in {
      val header = """for=1.1.1.1;proto=https, by=10.0.0.9;for="[2001:db8::1]:4711";proto=http, For=10.0.0.1:8080"""
      IpAddresses.parseForwardedChain(Seq(header), Seq.empty) mustBe Seq("1.1.1.1", "2001:db8::1", "10.0.0.1")
    }

    "drop the obfuscated and unknown identifiers of the Forwarded header" in {
      IpAddresses.parseForwardedChain(Seq("for=unknown, for=_hidden, for=1.1.1.1"), Seq.empty) mustBe Seq("1.1.1.1")
    }
  }

  "IpAddressMatcher" should {

    "answer exactly like the regex scan it replaces on canonical addresses" in {
      val patterns = Seq("10.0.0.0/8", "192.168.0.*", "172.16.0.1", "2001:db8::1")
      val matcher  = IpAddressMatcher(patterns)
      val probes   = Seq(
        "10.1.2.3",
        "192.168.0.7",
        "192.168.1.7",
        "172.16.0.1",
        "172.16.0.2",
        "2001:db8::1",
        "2001:db8::2",
        "8.8.8.8"
      )
      probes.foreach { probe =>
        val normalized = IpAddresses.normalize(probe)
        val bySlowPath = patterns.exists { pattern =>
          if (pattern.contains("/")) {
            otoroshi.models.IpFiltering.cidr(pattern).contains(normalized)
          } else {
            otoroshi.utils.RegexPool(pattern).matches(normalized)
          }
        }
        withClue(s"probe $probe: ") {
          matcher.matches(probe) mustBe bySlowPath
        }
      }
    }

    "send each pattern to a single bucket" in {
      val matcher = IpAddressMatcher(Seq("10.0.0.0/8", "192.168.0.*", "172.16.0.1"))
      matcher.matches("10.1.2.3") mustBe true
      matcher.matches("192.168.0.7") mustBe true
      matcher.matches("172.16.0.1") mustBe true
      matcher.matches("172.16.0.2") mustBe false
    }

    "match an ipv6 address whatever its spelling" in {
      // the socket address java hands over is never compressed
      IpAddressMatcher(Seq("2001:db8::1")).matches("2001:db8:0:0:0:0:0:1") mustBe true
      IpAddressMatcher(Seq("2001:db8:0:0:0:0:0:1")).matches("2001:db8::1") mustBe true
      IpAddressMatcher(Seq("::1")).matches("0:0:0:0:0:0:0:1") mustBe true
      IpAddressMatcher(Seq("FE80::1")).matches("fe80::1") mustBe true
      IpAddressMatcher(Seq("2001:db8::1")).matches("[2001:db8:0:0:0:0:0:1]:443") mustBe true
      IpAddressMatcher(Seq("2001:db8::1")).matches("2001:db8::2") mustBe false
    }

    "keep ipv4 literals on the plain string path" in {
      IpAddressMatcher.canonical("10.0.0.1") mustBe "10.0.0.1"
      IpAddressMatcher.canonical("2001:db8:0:0:0:0:0:1") mustBe "2001:db8::1"
      // anything ip4s cannot parse is left untouched
      IpAddressMatcher.canonical("not:an:address") mustBe "not:an:address"
    }

    "treat a pattern carrying regex characters as a pattern" in {
      IpAddressMatcher.isPlainAddress("10.0.0.1") mustBe true
      IpAddressMatcher.isPlainAddress("2001:db8::1") mustBe true
      IpAddressMatcher.isPlainAddress("10.0.0.*") mustBe false
      IpAddressMatcher.isPlainAddress("10.0.0.0/8") mustBe false
      IpAddressMatcher.isPlainAddress("10.0.0.1?") mustBe false
    }

    "be empty when it holds no pattern" in {
      IpAddressMatcher(Seq.empty).isEmpty mustBe true
      IpAddressMatcher(Seq.empty).matches("10.0.0.1") mustBe false
    }

    "match an ipv6 wildcard whatever the spelling it was written against" in {
      // written against the compressed form, while java hands over the full one
      IpAddressMatcher(Seq("2001:db8::*")).matches("2001:db8:0:0:0:0:0:1") mustBe true
      // written against the full form, which is what otoroshi used to compare with
      IpAddressMatcher(Seq("2001:db8:0:0:*")).matches("2001:db8:0:0:0:0:0:1") mustBe true
      IpAddressMatcher(Seq("2001:db8::*")).matches("2001:db8::1") mustBe true
      IpAddressMatcher(Seq("2001:db9::*")).matches("2001:db8:0:0:0:0:0:1") mustBe false
    }
  }

  // the ip filters of the global config, the endless responses and the ip allowed/block list plugins
  // match addresses the same way as the trusted proxies
  "the ip address filters" should {

    val cases = Seq(
      ("2001:db8::1", "2001:db8:0:0:0:0:0:1"),
      ("10.0.0.1", "::ffff:10.0.0.1"),
      ("10.0.0.0/8", "::ffff:10.0.0.1"),
      ("2001:db8::*", "2001:db8:0:0:0:0:0:1")
    )

    "match an address whatever its spelling" in {
      cases.foreach { case (rule, address) =>
        withClue(s"rule $rule, address $address: ") {
          otoroshi.models.IpFiltering(blacklist = Seq(rule)).matchesBlacklist(address) mustBe true
          otoroshi.models.IpFiltering(whitelist = Seq(rule)).matchesWhitelist(address) mustBe true
          otoroshi.models.IpFiltering(whitelist = Seq(rule)).notMatchesWhitelist(address) mustBe false
          otoroshi.models.GlobalConfig(endlessIpAddresses = Seq(rule)).matchesEndlessIpAddresses(address) mustBe true
          otoroshi.next.plugins.NgIpAddressesConfig(Seq(rule)).matcher.matches(address) mustBe true
          otoroshi.next.plugins.NgEndlessHttpResponseConfig(addresses = Seq(rule)).matcher.matches(address) mustBe true
          // fail2ban with an identifier that is the client address, the rule being wrapped or not
          val wrapped = if (rule.contains("/")) s"Cidr($rule)" else s"Ip($rule)"
          otoroshi.next.plugins.Fail2BanConfig(blocked = Seq(wrapped)).isBlocked(address) mustBe true
          otoroshi.next.plugins.Fail2BanConfig(ignored = Seq(wrapped)).isIgnored(address) mustBe true
          otoroshi.next.plugins.Fail2BanConfig(blocked = Seq(rule)).isBlocked(address) mustBe true
        }
      }
    }

    "keep matching the fail2ban identifiers that are not addresses as regexes" in {
      val config = otoroshi.next.plugins.Fail2BanConfig(
        blocked = Seq("route_1-*", "Ip(route_2-*)", "Cidr(10.0.0.0/8)"),
        ignored = Seq("apikey:*")
      )
      config.isBlocked("route_1-10.0.0.1") mustBe true
      config.isBlocked("route_2-10.0.0.1") mustBe true
      // a range never contains something that is not an address
      config.isBlocked("route_3-10.0.0.1") mustBe false
      // the colon of the identifier is not taken for the port of an address
      config.isIgnored("apikey:client-id") mustBe true
      config.isBlocked("apikey:client-id") mustBe false
      otoroshi.next.plugins.Fail2BanConfig(blocked = Seq("Cidr(10.0.0.0/8)")).isBlocked("10.1.2.3") mustBe true
      otoroshi.next.plugins.Fail2BanConfig(blocked = Seq("Cidr(10.0.0.0/8)")).isBlocked("192.168.0.1") mustBe false
    }

    "keep the meaning of empty lists" in {
      val filtering = otoroshi.models.IpFiltering()
      filtering.matchesWhitelist("10.0.0.1") mustBe false
      // an empty whitelist lets everything through
      filtering.notMatchesWhitelist("10.0.0.1") mustBe false
      filtering.matchesBlacklist("10.0.0.1") mustBe false
      otoroshi.models.GlobalConfig().matchesEndlessIpAddresses("10.0.0.1") mustBe false
      otoroshi.next.plugins.NgIpAddressesConfig().matcher.matches("10.0.0.1") mustBe false
    }

    "turn away an address missing from a non empty whitelist" in {
      otoroshi.models.IpFiltering(whitelist = Seq("10.0.0.0/8")).notMatchesWhitelist("192.168.0.1") mustBe true
      otoroshi.models.IpFiltering(blacklist = Seq("10.0.0.0/8")).matchesBlacklist("192.168.0.1") mustBe false
    }
  }

  "IpAddresses.resolveFromChain" should {

    val trusted = IpAddressMatcher(Seq("10.0.0.0/8"))

    "resolve nothing when no trusted proxy is configured" in {
      IpAddresses.resolveFromChain("10.0.0.1", Seq("1.1.1.1"), IpAddressMatcher(Seq.empty)) mustBe None
    }

    "resolve nothing when the connection does not come from a trusted proxy" in {
      IpAddresses.resolveFromChain("192.168.0.1", Seq("1.1.1.1"), trusted) mustBe None
    }

    "return the last untrusted address of the chain" in {
      IpAddresses.resolveFromChain("10.0.0.1", Seq("1.1.1.1", "10.0.0.2"), trusted).value mustBe "1.1.1.1"
    }

    "ignore the addresses a client prepended to the chain" in {
      // the client claims 9.9.9.9 came before it, the real client address is still the one the
      // first trusted proxy saw
      IpAddresses.resolveFromChain("10.0.0.1", Seq("9.9.9.9", "1.1.1.1", "10.0.0.2"), trusted).value mustBe "1.1.1.1"
    }

    "fall back to the socket address when the chain is empty" in {
      IpAddresses.resolveFromChain("10.0.0.1", Seq.empty, trusted).value mustBe "10.0.0.1"
    }

    "return the leftmost entry when every hop is a trusted proxy" in {
      IpAddresses.resolveFromChain("10.0.0.1", Seq("10.0.0.3", "10.0.0.2"), trusted).value mustBe "10.0.0.3"
    }
  }
}
