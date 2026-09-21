package otoroshi.utils

import com.comcast.ip4s.IpAddress
import otoroshi.models.{CidrOfString, IpFiltering}
import otoroshi.utils.cache.Caches

// a list of ip patterns compiled once, so that matching an address against it does not cost a
// regex evaluation per entry. platforms publishing their reverse proxies through an env var can
// hand over several hundred plain addresses, which the exact set answers in constant time
case class IpAddressMatcher(patterns: Seq[String]) {

  private val exact: Set[String]       =
    patterns.filter(IpAddressMatcher.isPlainAddress).map(p => IpAddressMatcher.canonical(IpAddresses.normalize(p))).toSet
  private val cidrs: Seq[CidrOfString] = patterns.filter(_.contains("/")).map(IpFiltering.cidr)
  private val wildcards: Seq[Regex]    =
    patterns.filterNot(_.contains("/")).filterNot(IpAddressMatcher.isPlainAddress).map(RegexPool.apply)

  def isEmpty: Boolean  = patterns.isEmpty
  def nonEmpty: Boolean = patterns.nonEmpty

  def matches(address: String): Boolean = {
    if (patterns.isEmpty) {
      false
    } else {
      val normalized = IpAddresses.normalize(address)
      val canonical  = IpAddressMatcher.canonical(normalized)
      exact.contains(canonical) ||
      cidrs.exists(_.contains(normalized)) ||
      // a wildcard is written against one spelling of an ipv6 address, usually the compressed one,
      // while the socket address java hands over is not. both are tried, so that no pattern
      // written against the form otoroshi used to compare stops matching
      wildcards.exists(wildcard =>
        wildcard.matches(normalized) || (canonical != normalized && wildcard.matches(canonical))
      )
    }
  }
}

object IpAddressMatcher {

  private val cache = Caches.bounded[Seq[String], IpAddressMatcher](128)

  // what play trusted by default before otoroshi took over the forwarded headers
  val loopback: Seq[String] = Seq("127.0.0.1", "::1")

  // an ipv6 address has many spellings: java hands over `2001:db8:0:0:0:0:0:1` where a configuration
  // says `2001:db8::1`, so both sides of the exact path are compared in their canonical form. an
  // ipv4 literal only has one and stays a plain string comparison
  def canonical(address: String): String = {
    if (address.indexOf(':') >= 0) IpAddress.fromString(address).map(_.toString).getOrElse(address) else address
  }

  def apply(patterns: Seq[String], cached: Boolean): IpAddressMatcher = {
    if (cached) cache.get(patterns, ps => IpAddressMatcher(ps)) else IpAddressMatcher(patterns)
  }

  // only an address literal can take the exact path. anything else keeps going through RegexPool,
  // which escapes the dots and turns `*` into `.*` but leaves the other regex characters alone
  def isPlainAddress(pattern: String): Boolean = {
    pattern.nonEmpty && pattern.forall { c =>
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':'
    }
  }
}

object IpAddresses {

  private val ipv4MappedPrefix = "::ffff:"

  // normalizes an address as read from a socket or from a forwarded header, so that it can be
  // compared with the cidr and wildcard patterns used everywhere else in otoroshi. it strips the
  // brackets and the port of `[2001:db8::1]:8080`, the port of `192.168.0.1:8080`, the zone of
  // `fe80::1%eth0` and unwraps ipv4-mapped addresses like `::ffff:192.168.0.1` that would never
  // match an ipv4 cidr otherwise
  def normalize(address: String): String = {
    val trimmed         = address.trim
    val withoutBrackets = if (trimmed.startsWith("[")) {
      val idx = trimmed.indexOf(']')
      if (idx > 0) trimmed.substring(1, idx) else trimmed.substring(1)
    } else {
      // a single colon can only be a port on an ipv4 address, several colons mean a bare ipv6 address
      val idx = trimmed.indexOf(':')
      if (idx > 0 && trimmed.indexOf(':', idx + 1) < 0) trimmed.substring(0, idx) else trimmed
    }
    val withoutZone     = {
      val idx = withoutBrackets.indexOf('%')
      if (idx > 0) withoutBrackets.substring(0, idx) else withoutBrackets
    }
    if (withoutZone.toLowerCase.startsWith(ipv4MappedPrefix) && withoutZone.count(_ == '.') == 3) {
      withoutZone.substring(ipv4MappedPrefix.length)
    } else {
      withoutZone
    }
  }

  // matches an address against a list of cidr ranges and wildcard patterns, the same way ip
  // filtering, endless responses and the ip allowed/block lists do. callers holding a stable list
  // should keep an IpAddressMatcher instead of going through this one
  def matches(address: String, patterns: Seq[String]): Boolean = {
    IpAddressMatcher(patterns, cached = true).matches(address)
  }

  // builds the proxy chain, leftmost (closest to the client) first, from the rfc 7239 Forwarded
  // header when it is present, from X-Forwarded-For otherwise. every occurrence of the header is
  // taken into account, as a client can send its own before a proxy appends to it
  def parseForwardedChain(forwardedValues: Seq[String], xForwardedForValues: Seq[String]): Seq[String] = {
    if (forwardedValues.nonEmpty) {
      forwardedValues
        .flatMap(_.split(','))
        .flatMap { element =>
          element
            .split(';')
            .map(_.trim)
            .find(_.toLowerCase.startsWith("for="))
            .map(_.substring(4).trim)
        }
        .map(value =>
          if (value.length > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
          } else {
            value
          }
        )
        .map(normalize)
        // `unknown` and `_obfuscated` identifiers are valid rfc 7239 values but are not addresses
        .filterNot(value => value.isEmpty || value == "unknown" || value.startsWith("_"))
    } else {
      xForwardedForValues
        .flatMap(_.split(','))
        .map(normalize)
        .filterNot(_.isEmpty)
    }
  }

  // walks the proxy chain from the closest hop to the farthest and stops at the first address that
  // is not a trusted proxy. returns None when no trusted proxy is configured, or when the
  // connection itself does not come from one, as the chain proves nothing in that case
  def resolveFromChain(socketAddress: String, chain: Seq[String], trustedProxies: IpAddressMatcher): Option[String] = {
    if (trustedProxies.isEmpty) {
      None
    } else if (!trustedProxies.matches(socketAddress)) {
      None
    } else {
      chain.reverseIterator.find(address => !trustedProxies.matches(address)) match {
        // every hop is a trusted proxy, so the leftmost entry is the client itself. with an empty
        // chain, the trusted proxy is the peer and there is nothing else to read
        case None          => chain.headOption.orElse(Some(normalize(socketAddress)))
        case Some(address) => Some(address)
      }
    }
  }
}
