package utils

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.model.Uri
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
    def theProtocol: String = {
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
    }
    def theIpAddress: String = {
      requestHeader.headers.get("X-Forwarded-For").getOrElse(requestHeader.remoteAddress)
    }
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

    def clientCertChainPemString: String = clientCertChainPem.mkString("\n")
  }
}
