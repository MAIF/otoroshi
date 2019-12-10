package utils

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.model.Uri
import env.Env
import play.api.mvc.RequestHeader
import ssl.PemHeaders

import scala.util.Try

object RequestImplicits {

  private val uriCache = new ConcurrentHashMap[String, String]()

  implicit class EnhancedRequestHeader(val requestHeader: RequestHeader) extends AnyVal {
    def relativeUri: String = {
      val uri = requestHeader.uri
      uriCache.computeIfAbsent(uri, _ => {
        // println(s"computing uri for $uri")
        Try(Uri(uri).toRelative.toString()).getOrElse(uri)
      })
    }
    @inline
    def theDomain(implicit env: Env): String = theHost.split(':').head
    @inline
    def theSecured(implicit env: Env): Boolean = {
      if (env.trustXForwarded) {
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
    def theProtocol(implicit env: Env): String = {
      if (env.trustXForwarded) {
        requestHeader.headers
          .get("X-Forwarded-Proto")
          .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
          .map(_ == "https")
          .orElse(Some(requestHeader.secure))
          .map {
            case true => "https"
            case false => "http"
          }
          .getOrElse("http")
      } else {
        if (requestHeader.secure) "https" else "http"
      }
    }
    @inline
    def theWsProtocol(implicit env: Env): String = {
      if (env.trustXForwarded) {
        requestHeader.headers
          .get("X-Forwarded-Proto")
          .orElse(requestHeader.headers.get("X-Forwarded-Protocol"))
          .map(_ == "https")
          .orElse(Some(requestHeader.secure))
          .map {
            case true => "wss"
            case false => "ws"
          }
          .getOrElse("ws")
      } else {
        if (requestHeader.secure) "wss" else "ws"
      }
    }
    @inline
    def theHost(implicit env: Env): String = {
      if (env.trustXForwarded) {
        requestHeader.headers.get("X-Forwarded-Host").getOrElse(requestHeader.host)
      } else {
        requestHeader.host
      }
    }
    @inline
    def theIpAddress(implicit env: Env): String = {
      if (env.trustXForwarded) {
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
      requestHeader.clientCertificateChain
        .map(
          chain =>
            chain.map { cert =>
              s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
          }
        )
        .getOrElse(Seq.empty[String])
    }
    @inline
    def clientCertChainPemString: String = clientCertChainPem.mkString("\n")
  }
}
