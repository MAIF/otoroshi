package otoroshi.utils.http

import org.apache.pekko.http.scaladsl.model.Uri
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.ssl.PemHeaders
import otoroshi.utils.{IpAddresses, TypedMap}
import play.api.mvc.RequestHeader

import java.util.Base64
import scala.util.Try

object RequestImplicits {

  private val uriCache = Scaffeine().maximumSize(9999).build[String, String]()

  implicit class EnhancedRequestHeader(val requestHeader: RequestHeader) extends AnyVal {
    def contentLengthStr: Option[String]     = requestHeader.headers.get("Content-Length")
    def theUri: Uri                          = Uri(requestHeader.uri)
    def thePath: String                      = theUri.path.toString()
    def relativeUri: String = {
      val uri = requestHeader.uri
      uriCache.get(
        uri,
        _ => {
          // println(s"computing uri for $uri")
          Try(Uri(uri).toRelative.toString()).getOrElse(uri)
        }
      )
    }
    @inline
    def theDomain(using env: Env): String = theHost.split(':').head
    @inline
    def theSecured(using env: Env): Boolean = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers
          .get("X-Forwarded-Proto")
          .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
          .map(_ == "https")
          .getOrElse(requestHeader.secure)
      } else {
        requestHeader.secure
      }
    }
    @inline
    def theSecuredTrusted: Boolean = {
      requestHeader.headers
        .get("X-Forwarded-Proto")
        .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
        .map(_ == "https")
        .getOrElse(requestHeader.secure)
    }
    @inline
    def theUrl(using env: Env): String = {
      s"${theProtocol}://${theHost}${relativeUri}"
    }
    @inline
    def theProtocol(using env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers
          .get("X-Forwarded-Proto")
          .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
          .map(_ == "https")
          .orElse(Some(requestHeader.secure))
          .map {
            case true  => "https"
            case false => "http"
          }
          .getOrElse("http")
      } else {
        if (requestHeader.secure) "https" else "http"
      }
    }
    @inline
    def theWsProtocol(using env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers
          .get("X-Forwarded-Proto")
          .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
          .map(_ == "https")
          .orElse(Some(requestHeader.secure))
          .map {
            case true  => "wss"
            case false => "ws"
          }
          .getOrElse("ws")
      } else {
        if (requestHeader.secure) "wss" else "ws"
      }
    }
    @inline
    def theHost(using env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers.get("X-Forwarded-Host").getOrElse(requestHeader.host)
      } else {
        requestHeader.host
      }
    }
    // the client address used everywhere otoroshi does not ask for a specific source. it is the safe
    // resolution unless useLegacyClientIpAddress is enabled
    @inline
    def theIpAddress(using env: Env): String = {
      if (env.useLegacyClientIpAddress) legacyIpAddress else ipSafe
    }

    @inline
    def theIpAddress(attrs: TypedMap)(using env: Env): String = {
      if (env.useLegacyClientIpAddress) legacyIpAddress else ipSafe(attrs)
    }

    // the resolution otoroshi used before trusted proxies existed: the raw leftmost X-Forwarded-For
    // entry when trustXForwarded is enabled, the socket address otherwise. it is only reachable
    // through useLegacyClientIpAddress, as a way back during the migration, and will be removed
    def legacyIpAddress(using env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers
          .get("X-Forwarded-For")
          .map { rawHeader =>
            if (rawHeader.nonEmpty && rawHeader.contains(",")) {
              rawHeader.split(",").map(_.trim).headOption.getOrElse(rawHeader)
            } else {
              rawHeader
            }
          }
          .getOrElse(requestHeader.remoteAddress)
      } else {
        requestHeader.remoteAddress
      }
    }

    // the address of the peer that actually opened the connection, without any header involved.
    // play is explicitly configured not to resolve forwarded headers itself (see
    // play.http.forwarded.trustedProxies in base.conf) so that this stays true on every server
    @inline
    def ipFromSocket: String = IpAddresses.normalize(requestHeader.remoteAddress)

    // the addresses of the proxy chain, leftmost (closest to the client) first
    def forwardedChain: Seq[String] = {
      IpAddresses.parseForwardedChain(
        requestHeader.headers.getAll("Forwarded").toSeq,
        requestHeader.headers.getAll("X-Forwarded-For").toSeq
      )
    }

    // the address claimed by the leftmost entry of X-Forwarded-For. it is chosen by whoever sent
    // the header, including the client itself, so it must not be used for security decisions
    // unless the topology guarantees the header is rewritten by a proxy
    @inline
    def ipFromXForwardedHeaderOpt: Option[String] = {
      requestHeader.headers
        .getAll("X-Forwarded-For")
        .toSeq
        .flatMap(_.split(','))
        .map(IpAddresses.normalize)
        .find(_.nonEmpty)
    }

    @inline
    def ipFromXForwardedHeader: String = ipFromXForwardedHeaderOpt.getOrElse(ipFromSocket)

    // the client address resolved through the trusted proxies, None when it cannot be trusted. no
    // forwarded header is read at all when trustXForwarded is disabled
    @inline
    def ipFromTrustedProxyOpt(using env: Env): Option[String] = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        IpAddresses.resolveFromChain(ipFromSocket, forwardedChain, env.trustedProxiesMatcher)
      } else {
        None
      }
    }

    @inline
    def ipFromTrustedProxy(using env: Env): String = ipFromTrustedProxyOpt.getOrElse(ipFromSocket)

    // the address that can be trusted for security decisions. trustXForwarded is the master switch:
    // when it is disabled neither Forwarded nor X-Forwarded-For is read, like for the protocol and
    // the host. when it is enabled and trusted proxies are configured, they are the only accepted
    // way to rewrite the client address: falling back to a blind X-Forwarded-For there would give
    // back to the client the ability to choose its own
    def ipSafe(using env: Env): String = {
      if (!env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        ipFromSocket
      } else {
        val trustedProxies = env.trustedProxiesMatcher
        if (trustedProxies.nonEmpty) {
          IpAddresses.resolveFromChain(ipFromSocket, forwardedChain, trustedProxies).getOrElse(ipFromSocket)
        } else {
          ipFromXForwardedHeaderOpt.getOrElse(ipFromSocket)
        }
      }
    }

    // resolves the address once for the whole request. the same value is read by the legacy
    // checks, by every plugin taking a decision on the client address and by the event emitted at
    // the end, and parsing the forwarded chain again for each of them is not free
    def ipSafe(attrs: TypedMap)(using env: Env): String = {
      attrs.get(otoroshi.plugins.Keys.ClientIpAddressKey) match {
        case Some(address) => address
        case None          =>
          val address = ipSafe(using env)
          attrs.put(otoroshi.plugins.Keys.ClientIpAddressKey -> address)
          address
      }
    }

    @inline
    def theUserAgent: String = {
      requestHeader.headers.get("User-Agent").getOrElse("none")
    }
    @inline
    def clientCertChainPem: Seq[String] = {
      import otoroshi.ssl.SSLImplicits.*
      requestHeader.clientCertificateChain
        .map(chain =>
          chain.map { cert =>
            cert.asPem
          // s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
          }
        )
        .getOrElse(Seq.empty[String]).toSeq
    }
    @inline
    def clientCertChainPemString: String     = clientCertChainPem.mkString("\n")

    @inline
    def inlinePem: String = {
      requestHeader.clientCertificateChain
        .map(chain =>
          chain
            .map { cert =>
              val value = Base64.getEncoder.encodeToString(cert.getEncoded)
              val begin = PemHeaders.BeginCertificate
              val end   = PemHeaders.EndCertificate

              val lines = value.replace("\r", "").linesIterator.toList

              val payload = lines
                .filterNot(_.startsWith("-----")) // keep only the base64 lines
                .mkString(" ")                    // flatten
                .split(" +")                      // collapse multiple spaces
                .mkString(" ")

              s"$begin $payload $end"
            }
            .mkString(",")
        )
        .getOrElse("")
    }

    @inline
    def theHasBody: Boolean = {
      otoroshi.utils.body.BodyUtils.hasBody(requestHeader)
      // (requestHeader.method, requestHeader.headers.get("Content-Length")) match {
      //   case ("GET", Some(_))    => true
      //   case ("GET", None) if ctype.isDefined => true
      //   case ("GET", None)       => false
      //   case ("HEAD", Some(_))   => true
      //   case ("HEAD", None) if ctype.isDefined => true
      //   case ("HEAD", None)      => false
      //   case ("PATCH", _)        => true
      //   case ("POST", _)         => true
      //   case ("PUT", _)          => true
      //   case ("QUERY", _)        => true
      //   case ("DELETE", Some(_)) => true
      //   case ("DELETE", None) if ctype.isDefined => true
      //   case ("DELETE", None)    => false
      //   case _                   => true
      // }
    }

    @inline
    def theHasBodyWithoutLength: (Boolean, Boolean) = otoroshi.utils.body.BodyUtils.hasBodyWithoutLength(requestHeader)
  }
}
