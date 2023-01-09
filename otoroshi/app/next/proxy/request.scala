package otoroshi.next.proxy

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedMap
import play.api.mvc.{Cookies, Headers, Request, RequestHeader}
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.util.Base64

class RelayRoutingRequest(req: Request[Source[ByteString, _]], cookies: Cookies, certs: Option[Seq[X509Certificate]])
    extends Request[Source[ByteString, _]] {

  lazy val version         = req.version
  lazy val reqId           = req.headers.get("Otoroshi-Relay-Routing-Id").get.toLong
  lazy val method          = req.headers.get("Otoroshi-Relay-Routing-Method").get
  lazy val body            = req.body
  lazy val _remoteAddr     = req.headers.get("Otoroshi-Relay-Routing-Remote-Addr").get
  lazy val _remoteAddrInet = InetAddress.getByName(_remoteAddr)
  lazy val _remoteSecured  = req.headers.get("Otoroshi-Relay-Routing-Secured").get.toBoolean
  lazy val _remoteHasBody  = req.headers.get("Otoroshi-Relay-Routing-Has-Body").get.toBoolean
  lazy val _remoteUriStr   = req.headers.get("Otoroshi-Relay-Routing-Uri").get
  lazy val attrs           = TypedMap.apply(
    RequestAttrKey.Id      -> reqId,
    RequestAttrKey.Cookies -> Cell(cookies)
  )

  lazy val headers: Headers             = Headers(
    req.headers.toSimpleMap.toSeq
      .filterNot(_._1 == "Otoroshi-Relay-Routing-Cookies")
      .filter(_._1.startsWith("Otoroshi-Relay-Routing-Header-"))
      .map(v => (v._1.replace("Otoroshi-Relay-Routing-Header-", ""), v._2)): _*
  )
  lazy val connection: RemoteConnection = new RelayRoutingRemoteConnection(_remoteAddrInet, _remoteSecured, certs)
  lazy val target: RequestTarget        = new RelayRoutingRequestTarget(_remoteUriStr)
}

class TunnelRequest(
    requestId: Long,
    val version: String,
    val method: String,
    val body: Source[ByteString, _],
    _remoteUriStr: String,
    _remoteAddr: String,
    _remoteSecured: Boolean,
    _remoteHasBody: Boolean,
    _headers: Map[String, String],
    cookies: Cookies,
    certs: Option[Seq[X509Certificate]]
) extends Request[Source[ByteString, _]] {

  lazy val _remoteUri      = Uri(_remoteUriStr)
  lazy val _remoteAddrInet = InetAddress.getByName(_remoteAddr)
  lazy val attrs           = TypedMap.apply(
    RequestAttrKey.Id      -> requestId,
    RequestAttrKey.Cookies -> Cell(cookies)
  )

  lazy val headers: Headers             = Headers(_headers.toSeq: _*)
  lazy val connection: RemoteConnection = new RelayRoutingRemoteConnection(_remoteAddrInet, _remoteSecured, certs)
  lazy val target: RequestTarget        = new RelayRoutingRequestTarget(_remoteUriStr)
}

class RelayRoutingRemoteConnection(
    _remoteAddrInet: InetAddress,
    _remoteSecured: Boolean,
    certs: Option[Seq[X509Certificate]]
) extends RemoteConnection {
  override def remoteAddress: InetAddress                           = _remoteAddrInet
  override def secure: Boolean                                      = _remoteSecured
  override def clientCertificateChain: Option[Seq[X509Certificate]] = certs
}

class RelayRoutingRequestTarget(_remoteUriStr: String) extends RequestTarget {

  private lazy val _remoteUri = Uri(_remoteUriStr)
  private lazy val _remoteURI = URI.create(_remoteUriStr)

  override def uri: URI                           = _remoteURI
  override def uriString: String                  = _remoteUriStr
  override def path: String                       = _remoteUri.path.toString()
  override def queryMap: Map[String, Seq[String]] = _remoteUri.query().toMultiMap
}

class BackOfficeRequest(request: Request[Source[ByteString, _]], host: String, apikey: ApiKey, user: BackOfficeUser, env: Env) extends Request[Source[ByteString, _]] {

  private val newUri = request.uri.replaceFirst("/bo/api/proxy/", "/").replace("//", "/")
  private val addHeaders = Seq(
    "Host"                           -> host,
    "X-Forwarded-For"                -> request.theIpAddress(env),
    env.Headers.OtoroshiVizFromLabel -> "Otoroshi Admin UI",
    env.Headers.OtoroshiVizFrom      -> "otoroshi-admin-ui",
    env.Headers.OtoroshiClientId     -> apikey.clientId,
    env.Headers.OtoroshiClientSecret -> apikey.clientSecret,
    env.Headers.OtoroshiAdminProfile -> Base64.getUrlEncoder.encodeToString(
      Json.stringify(user.profile).getBytes(Charsets.UTF_8)
    ),
    "Otoroshi-Tenant"                -> request.headers.get("Otoroshi-Tenant").getOrElse("default"),
    "Otoroshi-BackOffice-User"       -> JWT
      .create()
      .withClaim("user", Json.stringify(user.toJson))
      .sign(Algorithm.HMAC512(apikey.clientSecret))
  )

  override def connection: RemoteConnection = new BackOfficeRemoteConnection(request)
  override def target: RequestTarget = new BackOfficeRequestTarget(newUri)
  override def headers: Headers = Headers.apply(((request.headers.headers.toMap ++ addHeaders.toMap).toSeq): _*)

  override def version: String = request.version
  override def attrs: TypedMap = request.attrs
  override def method: String = request.method
  override def body: Source[ByteString, _] = request.body
}

class BackOfficeRequestTarget(newUri: String) extends RequestTarget {
  private val _uri = Uri(newUri)
  override def uri: URI = URI.create(newUri)
  override def uriString: String = _uri.toString()
  override def path: String = _uri.path.toString()
  override def queryMap: Map[String, Seq[String]] = _uri.query().toMultiMap
}

class BackOfficeRemoteConnection(request: Request[Source[ByteString, _]]) extends RemoteConnection {
  override def remoteAddress: InetAddress = InetAddress.getLocalHost
  override def clientCertificateChain: Option[Seq[X509Certificate]] = request.clientCertificateChain
  override def secure: Boolean = request.secure
}