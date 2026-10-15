package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class IpAddressPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgIpAddressesConfig" should {

    val format = NgIpAddressesConfig.format

    "write the addresses under addresses" in {
      format.writes(NgIpAddressesConfig(Seq("10.0.0.1", "192.168.0.0/16"))) mustBe Json.obj(
        "addresses" -> Json.arr("10.0.0.1", "192.168.0.0/16")
      )
    }

    "write the default config as an empty addresses array" in {
      format.writes(NgIpAddressesConfig()) mustBe Json.obj("addresses" -> Json.arr())
    }

    "round trip the default config" in {
      roundTrip(format, NgIpAddressesConfig())
    }

    "round trip a single address" in {
      roundTrip(format, NgIpAddressesConfig(Seq("127.0.0.1")))
    }

    "round trip plain addresses, cidrs and wildcards, ipv4 and ipv6, in their order" in {
      val addresses = Seq("10.0.0.1", "2001:db8::1", "192.168.0.0/24", "2001:db8::/32", "172.16.*", "*")
      roundTrip(format, NgIpAddressesConfig(addresses)).addresses mustBe addresses
    }

    "round trip addresses as written, without normalizing them" in {
      roundTrip(format, NgIpAddressesConfig(Seq("2001:DB8:0:0:0:0:0:1", " 10.0.0.1 "))).addresses mustBe Seq(
        "2001:DB8:0:0:0:0:0:1",
        " 10.0.0.1 "
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgIpAddressesConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgIpAddressesConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("10.0.0.1")) mustBe NgIpAddressesConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("10.0.0.1")) mustBe NgIpAddressesConfig()
    }

    "ignore addresses written as a string" in {
      readOk(format, Json.obj("addresses" -> "10.0.0.1")) mustBe NgIpAddressesConfig()
    }

    "ignore addresses holding something else than strings" in {
      readOk(format, Json.obj("addresses" -> Json.arr("10.0.0.1", 42))) mustBe NgIpAddressesConfig()
    }

    "ignore the match_forwarded_chain flag of a block list config" in {
      readOk(
        format,
        Json.obj("addresses" -> Json.arr("10.0.0.1"), "match_forwarded_chain" -> true)
      ) mustBe NgIpAddressesConfig(Seq("10.0.0.1"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgIpAddressesConfig(Seq("10.0.0.1")))
    }

    "be the default config of IpAddressAllowedList" in {
      defaultConfigReadsBack(new IpAddressAllowedList(), format) mustBe NgIpAddressesConfig()
    }

    "match nothing when no address is listed" in {
      NgIpAddressesConfig().matcher.matches("127.0.0.1") mustBe false
      NgIpAddressesConfig().matcher.isEmpty mustBe true
    }

    "match a listed address and nothing else" in {
      val matcher = NgIpAddressesConfig(Seq("10.0.0.1")).matcher
      matcher.matches("10.0.0.1") mustBe true
      matcher.matches("10.0.0.2") mustBe false
      matcher.matches("10.0.0.10") mustBe false
    }

    "match the addresses of a cidr" in {
      val matcher = NgIpAddressesConfig(Seq("192.168.0.0/24")).matcher
      matcher.matches("192.168.0.42") mustBe true
      matcher.matches("192.168.1.42") mustBe false
    }

    "match the addresses of an ipv6 cidr" in {
      val matcher = NgIpAddressesConfig(Seq("2001:db8::/32")).matcher
      matcher.matches("2001:db8:1234::1") mustBe true
      matcher.matches("2001:db9::1") mustBe false
    }

    "match the addresses of a wildcard" in {
      val matcher = NgIpAddressesConfig(Seq("172.16.*")).matcher
      matcher.matches("172.16.3.4") mustBe true
      matcher.matches("172.17.3.4") mustBe false
    }

    "match an ipv6 address whatever its spelling" in {
      val matcher = NgIpAddressesConfig(Seq("2001:db8::1")).matcher
      matcher.matches("2001:db8:0:0:0:0:0:1") mustBe true
      matcher.matches("2001:DB8::1") mustBe true
    }

    "match an address read with a port, brackets or as an ipv4 mapped address" in {
      NgIpAddressesConfig(Seq("10.0.0.1")).matcher.matches("10.0.0.1:8080") mustBe true
      NgIpAddressesConfig(Seq("10.0.0.1")).matcher.matches("::ffff:10.0.0.1") mustBe true
      NgIpAddressesConfig(Seq("2001:db8::1")).matcher.matches("[2001:db8::1]:443") mustBe true
    }

    "build its matcher from the addresses read from the json" in {
      val config = readOk(format, Json.obj("addresses" -> Json.arr("10.0.0.0/8")))
      config.matcher.matches("10.20.30.40") mustBe true
      config.matcher.matches("11.0.0.1") mustBe false
    }

    "not take the matcher into account in its equality" in {
      val config = NgIpAddressesConfig(Seq("10.0.0.1"))
      config.matcher.matches("10.0.0.1") mustBe true
      config mustBe NgIpAddressesConfig(Seq("10.0.0.1"))
    }
  }

  "NgIpAddressBlockListConfig" should {

    val format = NgIpAddressBlockListConfig.format

    "not match the forwarded chain by default" in {
      NgIpAddressBlockListConfig() mustBe NgIpAddressBlockListConfig(Seq.empty, matchForwardedChain = false)
    }

    "write the default config" in {
      format.writes(NgIpAddressBlockListConfig()) mustBe Json.obj(
        "addresses"             -> Json.arr(),
        "match_forwarded_chain" -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(NgIpAddressBlockListConfig(Seq("10.0.0.1"), matchForwardedChain = true)) mustBe Json.obj(
        "addresses"             -> Json.arr("10.0.0.1"),
        "match_forwarded_chain" -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgIpAddressBlockListConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgIpAddressBlockListConfig(Seq("10.0.0.1", "192.168.0.0/16", "2001:db8::*"), true))
    }

    "round trip the forwarded chain flag without address" in {
      roundTrip(format, NgIpAddressBlockListConfig(matchForwardedChain = true))
    }

    "round trip addresses without the forwarded chain flag" in {
      roundTrip(format, NgIpAddressBlockListConfig(Seq("10.0.0.1")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgIpAddressBlockListConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgIpAddressBlockListConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("10.0.0.1")) mustBe NgIpAddressBlockListConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("10.0.0.1")) mustBe NgIpAddressBlockListConfig()
    }

    "read an allowed list config as a block list that does not match the forwarded chain" in {
      val allowed = NgIpAddressesConfig.format.writes(NgIpAddressesConfig(Seq("10.0.0.1")))
      readOk(format, allowed) mustBe NgIpAddressBlockListConfig(Seq("10.0.0.1"))
    }

    "read the match_forwarded_chain flag alone" in {
      readOk(format, Json.obj("match_forwarded_chain" -> true)) mustBe NgIpAddressBlockListConfig(
        matchForwardedChain = true
      )
    }

    "ignore a match_forwarded_chain flag written as a string" in {
      readOk(format, Json.obj("match_forwarded_chain" -> "true")).matchForwardedChain mustBe false
    }

    "ignore addresses that are not an array and keep the flag" in {
      readOk(
        format,
        Json.obj("addresses" -> "10.0.0.1", "match_forwarded_chain" -> true)
      ) mustBe NgIpAddressBlockListConfig(matchForwardedChain = true)
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("matchForwardedChain" -> true)) mustBe NgIpAddressBlockListConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("addresses" -> Json.arr("10.0.0.1"), "foo" -> "bar")) mustBe NgIpAddressBlockListConfig(
        Seq("10.0.0.1")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgIpAddressBlockListConfig(Seq("10.0.0.1"), true))
    }

    "be the default config of IpAddressBlockList" in {
      defaultConfigReadsBack(new IpAddressBlockList(), format) mustBe NgIpAddressBlockListConfig()
    }

    "build its matcher from its addresses only" in {
      val matcher = NgIpAddressBlockListConfig(Seq("10.0.0.0/8", "192.168.1.*"), matchForwardedChain = true).matcher
      matcher.matches("10.1.2.3") mustBe true
      matcher.matches("192.168.1.200") mustBe true
      matcher.matches("192.168.2.1") mustBe false
    }

    "match nothing when no address is listed" in {
      NgIpAddressBlockListConfig(matchForwardedChain = true).matcher.matches("10.0.0.1") mustBe false
    }
  }

  "NgEndlessHttpResponseConfig" should {

    val format = NgEndlessHttpResponseConfig.format

    "send zeros to nobody, outside debug, by default" in {
      NgEndlessHttpResponseConfig() mustBe NgEndlessHttpResponseConfig(
        finger = false,
        addresses = Seq.empty,
        isDebug = false
      )
    }

    "write the default config" in {
      format.writes(NgEndlessHttpResponseConfig()) mustBe Json.obj(
        "finger"    -> false,
        "addresses" -> Json.arr(),
        "is_debug"  -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(NgEndlessHttpResponseConfig(true, Seq("10.0.0.1"), true)) mustBe Json.obj(
        "finger"    -> true,
        "addresses" -> Json.arr("10.0.0.1"),
        "is_debug"  -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgEndlessHttpResponseConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgEndlessHttpResponseConfig(true, Seq("10.0.0.1", "2001:db8::/32"), true))
    }

    "round trip the finger alone" in {
      roundTrip(format, NgEndlessHttpResponseConfig(finger = true))
    }

    "round trip the debug mode alone" in {
      roundTrip(format, NgEndlessHttpResponseConfig(isDebug = true))
    }

    "round trip addresses alone" in {
      roundTrip(format, NgEndlessHttpResponseConfig(addresses = Seq("10.0.0.1")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgEndlessHttpResponseConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgEndlessHttpResponseConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("10.0.0.1")) mustBe NgEndlessHttpResponseConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("10.0.0.1")) mustBe NgEndlessHttpResponseConfig()
    }

    "read the debug mode alone" in {
      readOk(format, Json.obj("is_debug" -> true)) mustBe NgEndlessHttpResponseConfig(isDebug = true)
    }

    "read the finger alone" in {
      readOk(format, Json.obj("finger" -> true)) mustBe NgEndlessHttpResponseConfig(finger = true)
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("finger" -> "true", "is_debug" -> "true")) mustBe NgEndlessHttpResponseConfig()
    }

    "ignore addresses holding something else than strings and keep the flags" in {
      readOk(
        format,
        Json.obj("addresses" -> Json.arr("10.0.0.1", JsNull), "finger" -> true)
      ) mustBe NgEndlessHttpResponseConfig(finger = true)
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("isDebug" -> true)) mustBe NgEndlessHttpResponseConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("is_debug" -> true, "foo" -> "bar")) mustBe NgEndlessHttpResponseConfig(isDebug = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgEndlessHttpResponseConfig(true, Seq("10.0.0.1"), false))
    }

    "be the default config of EndlessHttpResponse" in {
      defaultConfigReadsBack(new EndlessHttpResponse(), format) mustBe NgEndlessHttpResponseConfig()
    }

    "build its matcher from its addresses" in {
      val matcher = NgEndlessHttpResponseConfig(addresses = Seq("10.0.0.1", "192.168.0.0/24")).matcher
      matcher.matches("10.0.0.1") mustBe true
      matcher.matches("192.168.0.7") mustBe true
      matcher.matches("10.0.0.2") mustBe false
    }

    "have a matcher matching nothing in debug mode without address" in {
      NgEndlessHttpResponseConfig(isDebug = true).matcher.matches("127.0.0.1") mustBe false
    }
  }
}
