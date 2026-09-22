package otoroshi.utils

import com.comcast.ip4s.IpAddress
import otoroshi.models.{CidrOfString, IpFiltering}
import otoroshi.utils.cache.Caches
import otoroshi.utils.json.Jsonable
import play.api.libs.json.{JsValue, Json}

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

  def isAddress(value: String): Boolean = IpAddress.fromString(value).isDefined

  private def unquote(value: String): String = {
    if (value.length > 1 && value.startsWith("\"") && value.endsWith("\"")) value.substring(1, value.length - 1)
    else value
  }

  // the elements of the rfc 7239 Forwarded header, leftmost (closest to the client) first. the host
  // and the protocol of an element are the ones the proxy that wrote it received. an element without
  // a node is not a hop and is left out. `unknown` and `_obfuscated` nodes are kept, they are hops
  // otoroshi cannot tell anything about
  def parseForwardedElements(values: Seq[String]): Seq[ForwardedElement] = {
    values.flatMap(_.split(',')).flatMap { element =>
      val pairs = element.split(';').toSeq.flatMap { pair =>
        val idx = pair.indexOf('=')
        if (idx > 0) Some(pair.substring(0, idx).trim.toLowerCase -> unquote(pair.substring(idx + 1).trim)) else None
      }
      pairs.collectFirst { case ("for", node) => normalize(node) }.filter(_.nonEmpty).map { address =>
        ForwardedElement(
          address = address,
          host = pairs.collectFirst { case ("host", host) if host.nonEmpty => host },
          proto = pairs.collectFirst { case ("proto", proto) if proto.nonEmpty => proto.toLowerCase }
        )
      }
    }
  }

  // the proxy chain read from the header the client address comes from, leftmost (closest to the
  // client) first. only that header is read: a proxy that does not build or sanitize the others lets
  // the client write them. every occurrence of the header is taken into account, as a client can
  // send its own before a proxy appends to it. any header but Forwarded is a comma separated list of
  // addresses, which X-Real-IP or CF-Connecting-IP holding a single one are too
  def parseChain(header: ClientAddressHeader, values: Seq[String]): Seq[String] = {
    if (header.isForwarded) {
      parseForwardedElements(values).map(_.address)
    } else {
      values.flatMap(_.split(',')).map(normalize).filter(_.nonEmpty)
    }
  }

  // walks the proxy chain from the closest hop to the farthest and stops at the first hop that is
  // not a trusted proxy. returns None when no trusted proxy is configured, or when the connection
  // itself does not come from one, as the chain proves nothing in that case, and when that hop is
  // not an address, like an `unknown` one: going on would reach what the client wrote
  def resolveFromChain(socketAddress: String, chain: Seq[String], trustedProxies: IpAddressMatcher): Option[String] = {
    resolveIndex(socketAddress, chain, trustedProxies) match {
      case None                              => None
      // with an empty chain, the trusted proxy is the peer and there is nothing else to read
      case Some(_) if chain.isEmpty          => Some(normalize(socketAddress))
      case Some(idx) if isAddress(chain(idx)) => Some(chain(idx))
      case Some(_)                           => None
    }
  }

  // the Forwarded element written by the first trusted proxy the client went through, the one
  // carrying the host and the protocol the client asked for
  def resolveForwardedElement(
      socketAddress: String,
      elements: Seq[ForwardedElement],
      trustedProxies: IpAddressMatcher
  ): Option[ForwardedElement] = {
    resolveIndex(socketAddress, elements.map(_.address), trustedProxies).filter(_ < elements.size).map(elements.apply)
  }

  // the index of the hop resolved in the chain, every hop being a trusted proxy making the leftmost
  // one the client itself
  private def resolveIndex(socketAddress: String, chain: Seq[String], trustedProxies: IpAddressMatcher): Option[Int] = {
    if (trustedProxies.isEmpty || !trustedProxies.matches(socketAddress)) {
      None
    } else {
      val idx = chain.lastIndexWhere(address => !trustedProxies.matches(address))
      Some(if (idx < 0) 0 else idx)
    }
  }
}

case class ForwardedElement(address: String, host: Option[String], proto: Option[String])

// the header the client address is read from when the connection comes from a reverse proxy. only
// that one is read, and the protocol and the host of the original request come from the same
// family: Forwarded, or X-Forwarded-Proto and X-Forwarded-Host for any other header
case class ClientAddressHeader(name: String) {
  val isForwarded: Boolean = name.equalsIgnoreCase("Forwarded")
}

object ClientAddressHeader {
  val default: ClientAddressHeader = ClientAddressHeader("X-Forwarded-For")
}

// the client address of a request, resolved once against a single version of the global config.
// `address` is the one the request is handled with, `safe` the one resolved through the trusted
// proxies, which differ only when useLegacyClientIpAddress is enabled. `forwardedChain` is the proxy
// chain read from the client address header, empty when the forwarded headers are not trusted
case class ClientIpAddress(address: String, safe: String, forwardedChain: Seq[String]) extends Jsonable {
  override def json: JsValue = Json.obj(
    "address"         -> address,
    "safe"            -> safe,
    "forwarded_chain" -> forwardedChain
  )
}
