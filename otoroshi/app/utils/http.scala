package utils.http

import java.io.File
import java.net.{InetAddress, InetSocketAddress, URI}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.Materializer
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{Flow, Source, Tcp}
import akka.util.ByteString
import com.google.common.base.Charsets
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{DefaultHostnameVerifier, SSLConfigSettings}
import com.typesafe.sslconfig.util.LoggerFactory
import env.Env
import javax.net.ssl.{HostnameVerifier, SSLContext, SSLSession}
import models.{ClientConfig, Target}
import org.apache.commons.codec.binary.Base64
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws._
import play.api.mvc.MultipartFormData
import play.api.{Logger, libs}
import play.shaded.ahc.org.asynchttpclient.util.Assertions
import ssl.DynamicSSLEngineProvider

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Try}
import scala.xml.{Elem, XML}

object WsClientChooser {
  def apply(standardClient: WSClient,
            akkaClient: AkkWsClient,
            ahcCreator: SSLConfigSettings => WSClient,
            fullAkka: Boolean,
            env: Env): WsClientChooser = new WsClientChooser(standardClient, akkaClient, ahcCreator, fullAkka, env)
}

class WsClientChooser(standardClient: WSClient,
                      akkaClient: AkkWsClient,
                      ahcCreator: SSLConfigSettings => WSClient,
                      fullAkka: Boolean,
                      env: Env)
    extends WSClient {

  private[utils] val logger                  = Logger("otoroshi-WsClientChooser")
  private[utils] val lastSslConfig           = new AtomicReference[SSLConfigSettings](null)
  private[utils] val connectionContextHolder = new AtomicReference[WSClient](null)

  // private def getAhcInstance(): WSClient = {
  //   val currentSslContext = DynamicSSLEngineProvider.sslConfigSettings
  //   if (currentSslContext != null && !currentSslContext.equals(lastSslConfig.get())) {
  //     lastSslConfig.set(currentSslContext)
  //     logger.debug("Building new client instance")
  //     val client = ahcCreator(currentSslContext)
  //     connectionContextHolder.set(client)
  //   }
  //   connectionContextHolder.get()
  // }

  def ws[T](request: WebSocketRequest,
            loose: Boolean,
            clientFlow: Flow[Message, Message, T],
            customizer: ClientConnectionSettings => ClientConnectionSettings): (Future[WebSocketUpgradeResponse], T) = {
    akkaClient.executeWsRequest(request, loose, clientFlow, customizer)
  }

  def url(url: String): WSRequest = {
    val protocol = scala.util.Try(url.split(":\\/\\/").apply(0)).getOrElse("http")
    urlWithProtocol(protocol, url)
  }

  def urlMulti(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val protocol = scala.util.Try(url.split(":\\/\\/").apply(0)).getOrElse("http")
    urlWithProtocol(protocol, url, clientConfig)
  }

  def akkaUrl(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
      akkaClient.mat
    )
  }
  def akkaUrlWithTarget(url: String, target: Target, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    new AkkaWsClientRequest(akkaClient, url, Some(target), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
      akkaClient.mat
    )
  }
  def akkaHttp2Url(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/2.0`, clientConfig = clientConfig, env = env)(
      akkaClient.mat
    )
  }

  // def ahcUrl(url: String): WSRequest = getAhcInstance().url(url)

  def classicUrl(url: String): WSRequest = standardClient.url(url)

  def urlWithTarget(url: String, target: Target, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val useAkkaHttpClient = env.datastores.globalConfigDataStore.latestSafe.map(_.useAkkaHttpClient).getOrElse(false)
    if (useAkkaHttpClient || fullAkka) {
      new AkkaWsClientRequest(akkaClient, url, Some(target), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
        akkaClient.mat
      )
    } else {
      urlWithProtocol(target.scheme, url, clientConfig)
    }
  }

  def urlWithProtocol(protocol: String, url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val useAkkaHttpClient = env.datastores.globalConfigDataStore.latestSafe.map(_.useAkkaHttpClient).getOrElse(false)
    protocol.toLowerCase() match {

      case "http" if useAkkaHttpClient || fullAkka =>
        new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
          akkaClient.mat
        )
      case "https" if useAkkaHttpClient || fullAkka =>
        new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
          akkaClient.mat
        )

      case "http" if !useAkkaHttpClient  => standardClient.url(url)
      case "https" if !useAkkaHttpClient => standardClient.url(url)

      case "standard:http"  => standardClient.url(url.replace("standard:http://", "http://"))
      case "standard:https" => standardClient.url(url.replace("standard:https://", "https://"))

      // case "ahc:http"  => getAhcInstance().url(url.replace("ahc:http://", "http://"))
      // case "ahc:https" => getAhcInstance().url(url.replace("ahc:https://", "https://"))

      case "ahc:http" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("ahc:http://", "http://"),
                                None,
                                HttpProtocols.`HTTP/1.1`,
                                clientConfig = clientConfig,
                                env = env)(
          akkaClient.mat
        )
      case "ahc:https" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("ahc:https://", "http://"),
                                None,
                                HttpProtocols.`HTTP/1.1`,
                                clientConfig = clientConfig,
                                env = env)(
          akkaClient.mat
        )

      case "ahttp" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("ahttp://", "http://"),
                                None,
                                HttpProtocols.`HTTP/1.1`,
                                clientConfig = clientConfig,
          env = env)(
          akkaClient.mat
        )
      case "ahttps" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("ahttps://", "https://"),
                                None,
                                HttpProtocols.`HTTP/1.1`,
                                clientConfig = clientConfig,
          env = env)(
          akkaClient.mat
        )
      case "http2" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("http2://", "http://"),
                                None,
                                HttpProtocols.`HTTP/2.0`,
                                clientConfig = clientConfig,
          env = env)(
          akkaClient.mat
        )
      case "http2s" =>
        new AkkaWsClientRequest(akkaClient,
                                url.replace("http2s://", "https://"),
                                None,
                                HttpProtocols.`HTTP/2.0`,
                                clientConfig = clientConfig,
          env = env)(
          akkaClient.mat
        )

      case _ if useAkkaHttpClient || fullAkka =>
        new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
          akkaClient.mat
        )
      case _ if !(useAkkaHttpClient || fullAkka) => standardClient.url(url)
    }
  }

  override def underlying[T]: T = standardClient.underlying[T]

  override def close(): Unit = ()
}

object AkkWsClient {
  def cookies(httpResponse: HttpResponse): Seq[WSCookie] = {
    httpResponse.headers
      .collect {
        case c: `Set-Cookie` => c.cookie
      }
      .map { c =>
        libs.ws.DefaultWSCookie(
          name = c.name,
          value = c.value,
          domain = c.domain,
          path = c.path,
          maxAge = c.maxAge,
          secure = c.secure,
          httpOnly = c.httpOnly
        )
      }
  }
}

// huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
class CustomLooseHostnameVerifier(mkLogger: LoggerFactory) extends HostnameVerifier {

  private val logger = mkLogger(getClass)

  private val defaultHostnameVerifier = new DefaultHostnameVerifier(mkLogger)

  override def verify(hostname: String, sslSession: SSLSession): Boolean = {
    if (hostname.contains("&")) {
      val parts = hostname.split("&")
      val actualHost = parts(1)
      val hostNameMatches = defaultHostnameVerifier.verify(actualHost, sslSession)
      if (!hostNameMatches) {
        logger.warn(s"Hostname verification failed on hostname $actualHost, but the connection was accepted because 'loose' is enabled on service target.")
      }
      true
    } else {
      val hostNameMatches = defaultHostnameVerifier.verify(hostname, sslSession)
      if (!hostNameMatches) {
        logger.warn(s"Hostname verification failed on hostname $hostname, but the connection was accepted because 'loose' is enabled on service target.")
      }
      true
    }
  }
}

// huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
class CustomHostnameVerifier(mkLogger: LoggerFactory) extends HostnameVerifier {

  private val logger = mkLogger(getClass)

  private val defaultHostnameVerifier = new DefaultHostnameVerifier(mkLogger)

  override def verify(hostname: String, sslSession: SSLSession): Boolean = {
    if (hostname.contains("&")) {
      val parts = hostname.split("&")
      val actualHost = parts(1)
      // println(s"verifying ${actualHost} over ${sslSession.getPeerCertificateChain.head.getSubjectDN.getName}")
      defaultHostnameVerifier.verify(actualHost, sslSession)
    } else {
      // println(s"verifying ${hostname} over ${sslSession.getPeerCertificateChain.head.getSubjectDN.getName}")
      defaultHostnameVerifier.verify(hostname, sslSession)
    }
  }
}

object SSLConfigSettingsCustomizer {
  implicit class BetterSSLConfigSettings(val sslc: SSLConfigSettings) extends AnyVal {
    def callIf(pred: => Boolean, f: SSLConfigSettings => SSLConfigSettings): SSLConfigSettings = {
      if (pred) {
        f(sslc)
      } else {
        sslc
      }
    }
  }
}

class AkkWsClient(config: WSClientConfig, env: Env)(implicit system: ActorSystem, materializer: Materializer) extends WSClient {

  import SSLConfigSettingsCustomizer._

  val ec     = system.dispatcher
  val mat    = materializer
  val client = Http(system)

  override def underlying[T]: T = client.asInstanceOf[T]

  def url(url: String): WSRequest = new AkkaWsClientRequest(this, url, clientConfig = ClientConfig(), targetOpt = None, env = env)

  override def close(): Unit = Await.ready(client.shutdownAllConnectionPools(), 10.seconds)

  private[utils] val wsClientConfig: WSClientConfig = config
  private[utils] val akkaSSLConfig: AkkaSSLConfig = AkkaSSLConfig(system).withSettings(
    config.ssl
      // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
      .callIf(env.manualDnsResolve, _.withHostnameVerifierClass(classOf[CustomHostnameVerifier]))
      .withSslParametersConfig(
        config.ssl.sslParametersConfig.withClientAuth(com.typesafe.sslconfig.ssl.ClientAuth.need) // TODO: do we really need that ?
      )
      .withDefault(false)
  )
  private[utils] val akkaSSLLooseConfig: AkkaSSLConfig = AkkaSSLConfig(system).withSettings(
    config.ssl
      // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
      .callIf(env.manualDnsResolve, _.withHostnameVerifierClass(classOf[CustomLooseHostnameVerifier]))
      .withLoose(config.ssl.loose.withAcceptAnyCertificate(true)) // .withDisableHostnameVerification(true))
      .withSslParametersConfig(
        config.ssl.sslParametersConfig.withClientAuth(com.typesafe.sslconfig.ssl.ClientAuth.need) // TODO: do we really need that ?
      )
      .withDefault(false)
  )

  private[utils] val lastSslContext = new AtomicReference[SSLContext](null)
  private[utils] val connectionContextHolder =
    new AtomicReference[HttpsConnectionContext](client.createClientHttpsContext(akkaSSLConfig))
  private[utils] val connectionContextLooseHolder =
    new AtomicReference[HttpsConnectionContext](connectionContextHolder.get())

  // client.validateAndWarnAboutLooseSettings()

  private[utils] val clientConnectionSettings: ClientConnectionSettings = ClientConnectionSettings(system)
    .withConnectingTimeout(FiniteDuration(config.connectionTimeout._1, config.connectionTimeout._2))
    .withIdleTimeout(config.idleTimeout)
    //.withUserAgentHeader(Some(`User-Agent`("Otoroshi-akka"))) // config.userAgent.map(_ => `User-Agent`(_)))

  private[utils] val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(clientConnectionSettings)
    .withMaxRetries(0)
    .withIdleTimeout(config.idleTimeout)

  private[utils] def executeRequest[T](
      request: HttpRequest,
      loose: Boolean,
      customizer: ConnectionPoolSettings => ConnectionPoolSettings
  ): Future[HttpResponse] = {
    val currentSslContext = DynamicSSLEngineProvider.current
    if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
      lastSslContext.set(currentSslContext)
      val connectionContext: HttpsConnectionContext = ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLConfig))
      val connectionContextLoose: HttpsConnectionContext = ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLLooseConfig))
      connectionContextHolder.set(connectionContext)
      connectionContextLooseHolder.set(connectionContextLoose)
    }
    val pool = customizer(connectionPoolSettings).withMaxConnections(512)
    client.singleRequest(request, if (loose) connectionContextLooseHolder.get() else connectionContextHolder.get(), pool)
  }

  private[utils] def executeWsRequest[T](
      request: WebSocketRequest,
      loose: Boolean,
      clientFlow: Flow[Message, Message, T],
      customizer: ClientConnectionSettings => ClientConnectionSettings
  ): (Future[WebSocketUpgradeResponse], T) = {
    val currentSslContext = DynamicSSLEngineProvider.current
    if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
      lastSslContext.set(currentSslContext)
      val connectionContext: HttpsConnectionContext = ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLConfig))
      val connectionContextLoose: HttpsConnectionContext = ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLLooseConfig))
      connectionContextHolder.set(connectionContext)
      connectionContextLooseHolder.set(connectionContextLoose)
    }
    client.singleWebSocketRequest(
      request = request,
      clientFlow = clientFlow,
      connectionContext = if (loose) connectionContextLooseHolder.get() else connectionContextHolder.get(),
      settings = customizer(ClientConnectionSettings(system))
    )(mat)
  }
}

case class AkkWsClientStreamedResponse(httpResponse: HttpResponse,
                                       underlyingUrl: String,
                                       mat: Materializer,
                                       requestTimeout: FiniteDuration)
    extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(
      ("Content-Type" -> Seq(contentType))
    ) /* ++ (if (httpResponse.entity.isChunked()) {
      Seq(("Transfer-Encoding" -> Seq("chunked")))
    } else {
      Seq.empty
    })*/
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  private lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  private lazy val _contentType: String = httpResponse.entity.contentType.mediaType
    .toString() + _charset.map(v => ";charset=" + v.value).getOrElse("")
  private lazy val _bodyAsBytes: ByteString =
    Await.result(bodyAsSource.runFold(ByteString.empty)(_ ++ _)(mat), FiniteDuration(10, TimeUnit.MINUTES))
  private lazy val _bodyAsString: String   = _bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem        = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue    = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie] = AkkWsClient.cookies(httpResponse)

  def status: Int                                      = httpResponse.status.intValue()
  def statusText: String                               = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]]                = allHeaders
  def underlying[T]: T                                 = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _]              = httpResponse.entity.dataBytes.takeWithin(requestTimeout)
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def contentType: String                     = _contentType

  override def body[T: BodyReadable]: T =
    throw new RuntimeException("Not supported on this WSClient !!! (StreamedResponse.body)")
  def body: String                           = _bodyAsString
  def bodyAsBytes: ByteString                = _bodyAsBytes
  def cookies: Seq[WSCookie]                 = _cookies
  def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
  override def xml: Elem                     = _bodyAsXml
  override def json: JsValue                 = _bodyAsJson
}

case class AkkWsClientRawResponse(httpResponse: HttpResponse, underlyingUrl: String, rawbody: ByteString)
    extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(
      ("Content-Type" -> Seq(contentType))
    ) /*++ (if (httpResponse.entity.isChunked()) {
      Seq(("Transfer-Encoding" -> Seq("chunked")))
    } else {
      Seq.empty
    })*/
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  private lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  private lazy val _contentType: String = httpResponse.entity.contentType.mediaType
    .toString() + _charset.map(v => ";charset=" + v.value).getOrElse("")
  private lazy val _bodyAsBytes: ByteString = rawbody
  private lazy val _bodyAsString: String    = rawbody.utf8String
  private lazy val _bodyAsXml: Elem         = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue     = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie]  = AkkWsClient.cookies(httpResponse)

  def status: Int                                      = httpResponse.status.intValue()
  def statusText: String                               = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]]                = allHeaders
  def underlying[T]: T                                 = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _]              = Source.single(rawbody)
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  def body: String                                     = _bodyAsString
  def bodyAsBytes: ByteString                          = _bodyAsBytes
  override def xml: Elem                               = _bodyAsXml
  override def json: JsValue                           = _bodyAsJson
  override def contentType: String                     = _contentType
  def cookies: Seq[WSCookie]                           = _cookies
  override def body[T: BodyReadable]: T =
    throw new RuntimeException("Not supported on this WSClient !!! (RawResponse.body)")
  def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
}

object CaseInsensitiveOrdered extends Ordering[String] {
  def compare(x: String, y: String): Int = {
    val xl = x.length
    val yl = y.length
    if (xl < yl) -1 else if (xl > yl) 1 else x.compareToIgnoreCase(y)
  }
}

object WSProxyServerUtils {

  def isIgnoredForHost(hostname: String, nonProxyHosts: Seq[String]): Boolean = {
    Assertions.assertNotNull(hostname, "hostname")
    if (nonProxyHosts.nonEmpty) {
      val var2: Iterator[_] = nonProxyHosts.iterator
      while ({
        var2.hasNext
      }) {
        val nonProxyHost: String = var2.next.asInstanceOf[String]
        if (this.matchNonProxyHost(hostname, nonProxyHost)) return true
      }
    }
    false
  }

  private def matchNonProxyHost(targetHost: String, nonProxyHost: String): Boolean = {
    if (nonProxyHost.length > 1) {
      if (nonProxyHost.charAt(0) == '*')
        return targetHost.regionMatches(true,
                                        targetHost.length - nonProxyHost.length + 1,
                                        nonProxyHost,
                                        1,
                                        nonProxyHost.length - 1)
      if (nonProxyHost.charAt(nonProxyHost.length - 1) == '*')
        return targetHost.regionMatches(true, 0, nonProxyHost, 0, nonProxyHost.length - 1)
    }
    nonProxyHost.equalsIgnoreCase(targetHost)
  }
}

case class AkkaWsClientRequest(
    client: AkkWsClient,
    rawUrl: String,
    targetOpt: Option[Target],
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
    _method: HttpMethod = HttpMethods.GET,
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    requestTimeout: Option[Int] = None,
    proxy: Option[WSProxyServer] = None,
    clientConfig: ClientConfig = ClientConfig(),
    env: Env
)(implicit materializer: Materializer)
    extends WSRequest {

  implicit val ec = client.ec

  override type Self = WSRequest

  private val _uri = {
    val u = Uri(rawUrl)
    targetOpt match {
      case None => u
      case Some(target) => {
        target.ipAddress match {
          case None => u // TODO: fix it
          // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
          case Some(ipAddress) if env.manualDnsResolve && u.authority.host.isNamedHost() => {
            u.copy(
              authority = u.authority.copy(
                port = target.thePort,
                host = akka.http.scaladsl.model.Uri.Host(s"${ipAddress}&${u.authority.host.address()}")
              )
            )
          }
          case Some(ipAddress) if !env.manualDnsResolve && u.authority.host.isNamedHost() => {
            val addr = InetAddress.getByAddress(u.authority.host.address(), InetAddress.getByName(ipAddress).getAddress)
            u.copy(
              authority = u.authority.copy(
                port = target.thePort,
                host = akka.http.scaladsl.model.Uri.Host(addr)
              )
            )
          }
          case Some(ipAddress) => {
            u.copy(
              authority = u.authority.copy(
                port = target.thePort,
                host = akka.http.scaladsl.model.Uri.Host(InetAddress.getByName(ipAddress))
              )
            )
          }
        }
      }
    }
  }

  private def customizer: ConnectionPoolSettings => ConnectionPoolSettings = {
    val relUri            = _uri.toRelative.toString()
    val idleTimeout       = clientConfig.extractTimeout(relUri, _.idleTimeout, _.idleTimeout)
    val connectionTimeout = clientConfig.extractTimeout(relUri, _.connectionTimeout, _.connectionTimeout)
    proxy
      .filter(
        p =>
          WSProxyServerUtils.isIgnoredForHost(Uri(rawUrl).authority.host.toString(),
                                              p.nonProxyHosts.getOrElse(Seq.empty))
      )
      .map { proxySettings =>
        val proxyAddress = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port)
        val httpsProxyTransport = (proxySettings.principal, proxySettings.password) match {
          case (Some(principal), Some(password)) => {
            val auth = akka.http.scaladsl.model.headers.BasicHttpCredentials(principal, password)
            //val realmBuilder = new Realm.Builder(proxySettings.principal.orNull, proxySettings.password.orNull)
            //val scheme: Realm.AuthScheme = proxySettings.protocol.getOrElse("http").toLowerCase(java.util.Locale.ENGLISH) match {
            //  case "http" | "https" => Realm.AuthScheme.BASIC
            //  case "kerberos" => Realm.AuthScheme.KERBEROS
            //  case "ntlm" => Realm.AuthScheme.NTLM
            //  case "spnego" => Realm.AuthScheme.SPNEGO
            //  case _ => scala.sys.error("Unrecognized protocol!")
            //}
            //realmBuilder.setScheme(scheme)
            ClientTransport.httpsProxy(proxyAddress, auth)
          }
          case _ => ClientTransport.httpsProxy(proxyAddress)
        }
        a: ConnectionPoolSettings =>
          {
            a.withTransport(httpsProxyTransport)
              .withIdleTimeout(idleTimeout)
              .withConnectionSettings(
                a.connectionSettings
                  .withConnectingTimeout(connectionTimeout)
                  .withIdleTimeout(idleTimeout)
              )
          }
      } getOrElse { a: ConnectionPoolSettings =>
      if (env.manualDnsResolve) {
        // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
        a.withTransport(ManualResolveTransport.http)
          .withIdleTimeout(idleTimeout)
          .withConnectionSettings(
            a.connectionSettings
              .withConnectingTimeout(connectionTimeout)
              .withIdleTimeout(idleTimeout)
          )
      } else {
        a.withIdleTimeout(idleTimeout)
          .withConnectionSettings(
            a.connectionSettings
              .withConnectingTimeout(connectionTimeout)
              .withIdleTimeout(idleTimeout)
          )
      }
    }
  }

  def withMethod(method: String): AkkaWsClientRequest = {
    copy(_method = HttpMethods.getForKeyCaseInsensitive(method).getOrElse(HttpMethod.custom(method)))
  }

  def withHttpHeaders(headers: (String, String)*): WSRequest = {
    copy(
      headers = headers.foldLeft(this.headers)(
        (m, hdr) =>
          if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
          else m + (hdr._1 -> Seq(hdr._2))
      )
    )
  }

  def withRequestTimeout(timeout: Duration): Self = copy(requestTimeout = Some(timeout.toMillis.toInt))

  override def withBody[T](body: T)(implicit evidence$1: BodyWritable[T]): WSRequest =
    copy(body = evidence$1.transform(body))

  override def withHeaders(headers: (String, String)*): WSRequest = withHttpHeaders(headers: _*)

  def stream(): Future[WSResponse] = {
    val req = buildRequest()
    client
      .executeRequest(req, targetOpt.exists(_.loose), customizer)
      .map { resp =>
        AkkWsClientStreamedResponse(resp,
                                    rawUrl,
                                    client.mat,
                                    requestTimeout
                                      .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
                                      .getOrElse(FiniteDuration(365, TimeUnit.DAYS))) // yeah that's infinity ...
      }(client.ec)
  }

  override def execute(method: String): Future[WSResponse] = {
    withMethod(method).execute()
  }

  override def execute(): Future[WSResponse] = {
    client
      .executeRequest(buildRequest(), targetOpt.exists(_.loose), customizer)
      .flatMap { response: HttpResponse =>
        response.entity
          .toStrict(FiniteDuration(client.wsClientConfig.requestTimeout._1, client.wsClientConfig.requestTimeout._2))
          .map(a => (response, a))
      }
      .map {
        case (response: HttpResponse, body: HttpEntity.Strict) => AkkWsClientRawResponse(response, rawUrl, body.data)
      }
  }

  private def realContentType: Option[ContentType] = {
    headers
      .get(`Content-Type`.name)
      .map(_.head)
      .map { value =>
        HttpHeader.parse("Content-Type", value)
      }
      .flatMap {
        case ParsingResult.Ok(header, _) => Option(header.asInstanceOf[`Content-Type`].contentType)
        case _                           => None
      }
  }

  private def realContentLength: Option[Long] = {
    headers
      .get(`Content-Length`.name)
      .map(_.head.toLong)
  }

  private def realUserAgent: Option[String] = {
    headers
      .get(`User-Agent`.name)
      .orElse(headers.get(`User-Agent`.name.toLowerCase))
      .map(_.head)
  }

  def buildRequest(): HttpRequest = {
    // val internalUri = Uri(rawUrl)
    val ct = realContentType.getOrElse(ContentTypes.`application/octet-stream`)
    val cl = realContentLength
    // val ua = realUserAgent.flatMap(s => Try(`User-Agent`(s)).toOption)
    val (akkaHttpEntity, updatedHeaders) = body match {
      case EmptyBody                         => (HttpEntity.Empty, headers)
      case InMemoryBody(bytes)               => (HttpEntity.apply(ct, bytes), headers)
      case SourceBody(bytes) if cl.isDefined => (HttpEntity(ct, cl.get, bytes), headers)
      case SourceBody(bytes)                 => (HttpEntity(ct, bytes), headers)
    }
    val akkaHeaders: List[HttpHeader] = updatedHeaders
      .flatMap {
        case (key, values) =>
          values.distinct.map(value => HttpHeader.parse(key, value))
      }
      .flatMap {
        case ParsingResult.Ok(header, _) => Option(header)
        case _                           => None
      }
      .filter { h =>
        h.isNot(`Content-Type`.lowercaseName) &&
        h.isNot(`Content-Length`.lowercaseName) &&
        //h.isNot(`User-Agent`.lowercaseName) &&
        !(h.is(Cookie.lowercaseName) && h.value().trim.isEmpty)
      }
      .toList// ++ ua

    HttpRequest(
      method = _method,
      uri = _uri,
      headers = akkaHeaders,
      entity = akkaHttpEntity,
      protocol = targetOpt.map(_.protocol).getOrElse(protocol)
    )
  }

  ///////////

  override def withCookies(cookies: WSCookie*): WSRequest = {
    val oldCookies = headers.get("Cookie").getOrElse(Seq.empty[String])
    val newCookies = oldCookies :+ cookies.toList
      .map { c =>
        s"${c.name}=${c.value}"
      }
      .mkString(";")
    copy(
      headers = headers + ("Cookie" -> newCookies)
    )
  }

  override lazy val followRedirects: Option[Boolean]      = Some(false)
  override def withFollowRedirects(follow: Boolean): Self = this
  override def method: String                             = _method.value
  override def queryString: Map[String, Seq[String]]      = _uri.query().toMultiMap
  override def get(): Future[WSResponse]                  = withMethod("GET").execute()
  override def post[T](body: T)(implicit evidence$2: BodyWritable[T]): Future[WSResponse] =
    withMethod("POST").withBody(evidence$2.transform(body)).execute()
  override def post(body: File): Future[WSResponse] =
    withMethod("POST").withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString))).execute()
  override def patch[T](body: T)(implicit evidence$3: BodyWritable[T]): Future[WSResponse] =
    withMethod("PATCH").withBody(evidence$3.transform(body)).execute()
  override def patch(body: File): Future[WSResponse] =
    withMethod("PATCH").withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString))).execute()
  override def put[T](body: T)(implicit evidence$4: BodyWritable[T]): Future[WSResponse] =
    withMethod("PUT").withBody(evidence$4.transform(body)).execute()
  override def put(body: File): Future[WSResponse] =
    withMethod("PUT").withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString))).execute()
  override def delete(): Future[WSResponse]     = withMethod("DELETE").execute()
  override def head(): Future[WSResponse]       = withMethod("HEAD").execute()
  override def options(): Future[WSResponse]    = withMethod("OPTIONS").execute()
  override lazy val url: String                 = _uri.toString()
  override lazy val uri: URI                    = new URI(_uri.toRelative.toString())
  override lazy val contentType: Option[String] = realContentType.map(_.value)
  override lazy val cookies: Seq[WSCookie] = {
    headers.get("Cookie").map { headers =>
      headers.flatMap { header =>
        header.split(";").map { value =>
          val parts = value.split("=")
          libs.ws.DefaultWSCookie(
            name = parts(0),
            value = parts(1)
          )
        }
      }
    } getOrElse Seq.empty
  }
  override def withQueryString(parameters: (String, String)*): WSRequest = addQueryStringParameters(parameters: _*)
  override def withQueryStringParameters(parameters: (String, String)*): WSRequest =
    copy(rawUrl = _uri.withQuery(Uri.Query.apply(parameters: _*)).toString())
  override def addQueryStringParameters(parameters: (String, String)*): WSRequest = {
    val params
      : Seq[(String, String)] = _uri.query().toMultiMap.toSeq.flatMap(t => t._2.map(t2 => (t._1, t2))) ++ parameters
    copy(rawUrl = _uri.withQuery(Uri.Query.apply(params: _*)).toString())
  }
  override def withProxyServer(proxyServer: WSProxyServer): WSRequest = copy(proxy = Option(proxyServer))
  override def proxyServer: Option[WSProxyServer]                     = proxy
  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    post[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    patch[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    put[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def auth: Option[(String, String, WSAuthScheme)] =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.auth)")
  override def calc: Option[WSSignatureCalculator] =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.calc)")
  override def virtualHost: Option[String] =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.virtualHost)")
  override def sign(calc: WSSignatureCalculator): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.sign)")
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequest = {
    scheme match {
      case WSAuthScheme.BASIC =>
        addHttpHeaders(
          "Authorization" -> s"Basic ${Base64.encodeBase64String(s"${username}:${password}".getBytes(Charsets.UTF_8))}"
        )
      case _ => throw new RuntimeException("Not supported on this WSClient !!! (Request.withAuth)")
    }
  }
  override def withRequestFilter(filter: WSRequestFilter): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withRequestFilter)")
  override def withVirtualHost(vh: String): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withVirtualHost)")
}

object Implicits {

  private val logger = Logger("otoroshi-http-implicits")

  implicit class BetterStandaloneWSRequest[T <: StandaloneWSRequest](val req: T) extends AnyVal {
    def withMaybeProxyServer(opt: Option[WSProxyServer]): req.Self = {
      opt match {
        case Some(proxy) => req.withProxyServer(proxy)
        case None        => req.asInstanceOf[req.Self]
      }
    }
  }
  implicit class BetterStandaloneWSResponse[T <: StandaloneWSResponse](val resp: T) extends AnyVal {
    def contentLength: Option[Long] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => httpResponse.entity.contentLengthOption
        case _                          => resp.header("Content-Length").map(_.toLong)
      }
    }
    def contentLengthStr: Option[String] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => httpResponse.entity.contentLengthOption.map(_.toString)
        case _                          => resp.header("Content-Length")
      }
    }
    def isChunked(): Option[Boolean] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => Some(httpResponse.entity.isChunked())
        //case responsePublisher: play.shaded.ahc.org.asynchttpclient.netty.handler.StreamedResponsePublisher => {
        //  val ahc = req.asInstanceOf[play.api.libs.ws.ahc.AhcWSResponse]
        //  val field = ahc.getClass.getDeclaredField("underlying")
        //  field.setAccessible(true)
        //  val sawsr = field.get(ahc).asInstanceOf[play.api.libs.ws.ahc.StreamedResponse]//.asInstanceOf[play.api.libs.ws.ahc.StandaloneAhcWSResponse]
        //  println(sawsr.headers)
        //  // val field2 = sawsr.getClass.getField("ahcResponse")
        //  // field2.setAccessible(true)
        //  // val response = field2.get(sawsr).asInstanceOf[play.shaded.ahc.org.asynchttpclient.Response]
        //  // println(response.getHeaders.names())
        //  //play.shaded.ahc.org.asynchttpclient.netty.handler.
        //  //HttpHeaders.isTransferEncodingChunked(responsePublisher)
        //  None
        //}
        case _ => None
      }
    }
    def ignore()(implicit mat: Materializer): StandaloneWSResponse = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse =>
          Try(httpResponse.discardEntityBytes()) match {
            case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
            case _          => ()
          }
          resp
        case _ => resp
      }
    }
    def ignoreIf(predicate: => Boolean)(implicit mat: Materializer): StandaloneWSResponse = {
      if (predicate) {
        resp.underlying[Any] match {
          case httpResponse: HttpResponse =>
            Try(httpResponse.discardEntityBytes()) match {
              case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
              case _          => ()
            }
            resp
          case _ => resp
        }
      } else {
        resp
      }
    }
  }
}

object ManualResolveTransport {

  // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false

  lazy val http: ClientTransport = ManualResolveTransport()

  private case class ManualResolveTransport() extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      val inetSocketAddress = if (host.contains("&")) {
        val parts = host.split("&")
        val ipAddress = parts(0)
        val actualHost = parts(1)
        new InetSocketAddress(InetAddress.getByAddress(actualHost, InetAddress.getByName(ipAddress).getAddress), port)
      } else {
        InetSocketAddress.createUnresolved(host, port)
      }
      Tcp().outgoingConnection(
        inetSocketAddress,
        settings.localAddress,
        settings.socketOptions,
        halfClose = true,
        settings.connectingTimeout,
        settings.idleTimeout
      ).mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
    }
  }

  //private case class ManualResolveTransport(ipAddress: String) extends ClientTransport {
  //  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
  //    Tcp().outgoingConnection(
  //      new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(ipAddress).getAddress), port),
  //      settings.localAddress,
  //      settings.socketOptions,
  //      halfClose = true,
  //      settings.connectingTimeout,
  //      settings.idleTimeout
  //    ).mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  //}
  //
  //private case class ManualResolveTransportTLS(ipAddress: String, sslContext: SSLContext, neg: NegotiateNewSession) extends ClientTransport {
  //  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
  //    Tcp().outgoingTlsConnection(
  //      new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(ipAddress).getAddress), port),
  //      sslContext = sslContext,
  //      negotiateNewSession = neg,
  //      localAddress = settings.localAddress,
  //      options = settings.socketOptions,
  //      connectTimeout =  settings.connectingTimeout,
  //      idleTimeout = settings.idleTimeout
  //    ).mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  //}
}
