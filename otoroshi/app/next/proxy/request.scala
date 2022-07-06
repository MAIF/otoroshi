package otoroshi.next.proxy

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.typedmap.TypedMap
import play.api.mvc.{Cookies, Headers, Request}
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate

class RelayRoutingRequest(req: Request[Source[ByteString, _]], cookies: Cookies, certs: Option[Seq[X509Certificate]]) extends Request[Source[ByteString, _]] {

  lazy val version = req.version
  lazy val reqId = req.headers.get("Otoroshi-Regional-Routing-Id").get.toLong
  lazy val method = req.headers.get("Otoroshi-Regional-Routing-Method").get
  lazy val body = req.body
  lazy val _remoteAddr = req.headers.get("Otoroshi-Regional-Routing-Remote-Addr").get
  lazy val _remoteAddrInet = InetAddress.getByName(_remoteAddr)
  lazy val _remoteSecured = req.headers.get("Otoroshi-Regional-Routing-Secured").get.toBoolean
  lazy val _remoteHasBody = req.headers.get("Otoroshi-Regional-Routing-Has-Body").get.toBoolean
  lazy val _remoteUriStr = req.headers.get("Otoroshi-Regional-Routing-Uri").get
  lazy val attrs = TypedMap.apply(
    RequestAttrKey.Id -> reqId,
    RequestAttrKey.Cookies -> Cell(cookies)
  )

  lazy val headers: Headers = Headers(
    req.headers.toSimpleMap.toSeq
      .filterNot(_._1 == "Otoroshi-Regional-Routing-Cookies")
      .filter(_._1.startsWith("Otoroshi-Regional-Routing-Header-"))
      .map(v => (v._1.replace("Otoroshi-Regional-Routing-Header-", ""), v._2))
      : _*)
  lazy val connection: RemoteConnection = new RelayRoutingRemoteConnection(_remoteAddrInet, _remoteSecured, certs)
  lazy val target: RequestTarget = new RelayRoutingRequestTarget(_remoteUriStr)
}

class RelayRoutingRemoteConnection(_remoteAddrInet: InetAddress, _remoteSecured: Boolean, certs: Option[Seq[X509Certificate]]) extends RemoteConnection {
  override def remoteAddress: InetAddress = _remoteAddrInet
  override def secure: Boolean = _remoteSecured
  override def clientCertificateChain: Option[Seq[X509Certificate]] = certs
}

class RelayRoutingRequestTarget(_remoteUriStr: String) extends RequestTarget {

  private lazy val _remoteUri = Uri(_remoteUriStr)
  private lazy val _remoteURI = URI.create(_remoteUriStr)

  override def uri: URI = _remoteURI
  override def uriString: String = _remoteUriStr
  override def path: String = _remoteUri.path.toString()
  override def queryMap: Map[String, Seq[String]] = _remoteUri.query().toMultiMap
}
