package otoroshi.utils.http

import akka.http.scaladsl.model.Uri
import otoroshi.env.Env
import play.api.mvc.RequestHeader

import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

object RequestImplicits {

  private val uriCache = new ConcurrentHashMap[String, String]()

  implicit class EnhancedRequestHeader(val requestHeader: RequestHeader) extends AnyVal {
    def theUri: Uri                          = Uri(requestHeader.uri)
    def thePath: String                      = theUri.path.toString()
    def relativeUri: String = {
      val uri = requestHeader.uri
      uriCache.computeIfAbsent(
        uri,
        _ => {
          // println(s"computing uri for $uri")
          Try(Uri(uri).toRelative.toString()).getOrElse(uri)
        }
      )
    }
    @inline
    def theDomain(implicit env: Env): String = theHost.split(':').head
    @inline
    def theSecured(implicit env: Env): Boolean = {
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
    def theUrl(implicit env: Env): String = {
      s"${theProtocol}://${theHost}${relativeUri}"
    }
    @inline
    def theProtocol(implicit env: Env): String = {
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
    def theWsProtocol(implicit env: Env): String = {
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
    def theHost(implicit env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers.get("X-Forwarded-Host").getOrElse(requestHeader.host)
      } else {
        requestHeader.host
      }
    }
    @inline
    def theIpAddress(implicit env: Env): String = {
      if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
        requestHeader.headers.get("X-Forwarded-For").getOrElse(requestHeader.remoteAddress)
      } else {
        requestHeader.remoteAddress
      }
    }
    @inline
    def theUserAgent: String = {
      requestHeader.headers.get("User-Agent").getOrElse("none")
    }
    @inline
    def clientCertChainPem: Seq[String] = {
      import otoroshi.ssl.SSLImplicits._
      requestHeader.clientCertificateChain
        .map(chain =>
          chain.map { cert =>
            cert.asPem
          // s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
          }
        )
        .getOrElse(Seq.empty[String])
    }
    @inline
    def clientCertChainPemString: String     = clientCertChainPem.mkString("\n")

    @inline
    def theHasBody: Boolean =
      (requestHeader.method, requestHeader.headers.get("Content-Length")) match {
        case ("GET", Some(_))    => true
        case ("GET", None)       => false
        case ("HEAD", Some(_))   => true
        case ("HEAD", None)      => false
        case ("PATCH", _)        => true
        case ("POST", _)         => true
        case ("PUT", _)          => true
        case ("DELETE", Some(_)) => true
        case ("DELETE", None)    => false
        case _                   => true
      }
  }
}
