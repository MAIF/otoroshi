package otoroshi.utils

import com.comcast.ip4s.IpAddress
import otoroshi.models.IpFiltering

object ForwardedFor {

  // a literal becomes a block so that matching compares parsed addresses, not
  // text. not a zoned one: the cidr matcher ignores the zone
  def trustRules(entries: Seq[String]): Seq[String] = entries.map { entry =>
    if (entry.contains("/") || entry.contains("%")) entry
    else IpAddress.fromString(entry).map(_.fold(_ => s"$entry/32", _ => s"$entry/128")).getOrElse(entry)
  }

  // `X-Forwarded-For` grows left to right, so ours are the rightmost entries and
  // every occurrence belongs to the same list. an empty list keeps the old path
  def clientIp(
      headerValues: Seq[String],
      trustedProxies: Seq[String],
      remoteAddress: String
  ): String = {
    if (trustedProxies.isEmpty) {
      val rawHeader = headerValues.headOption.getOrElse("")
      if (rawHeader.nonEmpty && rawHeader.contains(",")) {
        rawHeader.split(",").map(_.trim).headOption.getOrElse(rawHeader)
      } else {
        rawHeader
      }
    } else {
      val entries = headerValues.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)
      val trusted = IpFiltering(whitelist = trustedProxies)
      if (entries.isEmpty || !trusted.matchesWhitelist(remoteAddress)) remoteAddress
      else entries.reverse.dropWhile(trusted.matchesWhitelist).headOption.getOrElse(remoteAddress)
    }
  }
}
