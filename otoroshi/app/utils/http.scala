package utils.http

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.util.ByteString
import com.google.common.base.Charsets
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import env.Env
import javax.net.ssl.SSLContext
import models.ClientConfig
import org.apache.commons.codec.binary.Base64
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws._
import play.api.mvc.MultipartFormData
import play.api.{Logger, libs}
import play.shaded.ahc.org.asynchttpclient.util.{Assertions, MiscUtils}
import ssl.DynamicSSLEngineProvider

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, XML}

object WsClientChooser {
  def apply(standardClient: WSClient,
            akkaClient: AkkWsClient,
            ahcCreator: SSLConfigSettings => WSClient,
            fullAkka: Boolean, env: Env): WsClientChooser = new WsClientChooser(standardClient, akkaClient, ahcCreator, fullAkka, env)
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
            clientFlow: Flow[Message, Message, T],
            customizer: ClientConnectionSettings => ClientConnectionSettings): (Future[WebSocketUpgradeResponse], T) = {
    akkaClient.executeWsRequest(request, clientFlow, customizer)
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
    new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(
      akkaClient.mat
    )
  }
  def akkaHttp2Url(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/2.0`, clientConfig = clientConfig)(
      akkaClient.mat
    )
  }

  // def ahcUrl(url: String): WSRequest = getAhcInstance().url(url)

  def classicUrl(url: String): WSRequest = standardClient.url(url)

  def urlWithProtocol(protocol: String, url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = { // TODO: handle idle timeout and other timeout per request here
    val useAkkaHttpClient = env.datastores.globalConfigDataStore.latestSafe.map(_.useAkkaHttpClient).getOrElse(false)
    protocol.toLowerCase() match {

      case "http"  if useAkkaHttpClient || fullAkka => new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(akkaClient.mat)
      case "https" if useAkkaHttpClient || fullAkka => new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(akkaClient.mat)

      case "http"  if !useAkkaHttpClient => standardClient.url(url)
      case "https" if !useAkkaHttpClient => standardClient.url(url)

      case "standard:http"  => standardClient.url(url.replace("standard:http://", "http://"))
      case "standard:https" => standardClient.url(url.replace("standard:https://", "https://"))

      // case "ahc:http"  => getAhcInstance().url(url.replace("ahc:http://", "http://"))
      // case "ahc:https" => getAhcInstance().url(url.replace("ahc:https://", "https://"))

      case "ahc:http" => new AkkaWsClientRequest(akkaClient, url.replace("ahc:http://", "http://"), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(
        akkaClient.mat
      )
      case "ahc:https" => new AkkaWsClientRequest(akkaClient, url.replace("ahc:https://", "http://"), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(
        akkaClient.mat
      )

      case "ahttp" =>
        new AkkaWsClientRequest(akkaClient, url.replace("ahttp://", "http://"), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(
          akkaClient.mat
        )
      case "ahttps" =>
        new AkkaWsClientRequest(akkaClient, url.replace("ahttps://", "https://"), HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(
          akkaClient.mat
        )
      case "http2" =>
        new AkkaWsClientRequest(akkaClient, url.replace("http2://", "http://"), HttpProtocols.`HTTP/2.0`, clientConfig = clientConfig)(
          akkaClient.mat
        )
      case "http2s" =>
        new AkkaWsClientRequest(akkaClient, url.replace("http2s://", "https://"), HttpProtocols.`HTTP/2.0`, clientConfig = clientConfig)(
          akkaClient.mat
        )

      case _ if useAkkaHttpClient || fullAkka => new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig)(akkaClient.mat)
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

class AkkWsClient(config: WSClientConfig)(implicit system: ActorSystem, materializer: Materializer) extends WSClient {

  val ec     = system.dispatcher
  val mat    = materializer
  val client = Http(system)

  override def underlying[T]: T = client.asInstanceOf[T]

  def url(url: String): WSRequest = new AkkaWsClientRequest(this, url, clientConfig = ClientConfig())

  override def close(): Unit = Await.ready(client.shutdownAllConnectionPools(), 10.seconds)

  private[utils] val wsClientConfig: WSClientConfig = config
  private[utils] val akkaSSLConfig: AkkaSSLConfig = AkkaSSLConfig(system).withSettings(
    config.ssl
      .withSslParametersConfig(
        config.ssl.sslParametersConfig.withClientAuth(com.typesafe.sslconfig.ssl.ClientAuth.need)
      )
      .withDefault(false)
  )

  private[utils] val lastSslContext = new AtomicReference[SSLContext](null)
  private[utils] val connectionContextHolder =
    new AtomicReference[HttpsConnectionContext](client.createClientHttpsContext(akkaSSLConfig))

  client.validateAndWarnAboutLooseSettings()

  private[utils] val clientConnectionSettings: ClientConnectionSettings = ClientConnectionSettings(system)
    .withConnectingTimeout(FiniteDuration(config.connectionTimeout._1, config.connectionTimeout._2))
    .withIdleTimeout(config.idleTimeout) // TODO: fix that per request
    .withUserAgentHeader(config.userAgent.map(`User-Agent`(_)))

  private[utils] val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(clientConnectionSettings)
    .withMaxRetries(0)
    .withIdleTimeout(config.idleTimeout) // TODO: fix that per request

  private[utils] def executeRequest[T](
      request: HttpRequest,
      customizer: ConnectionPoolSettings => ConnectionPoolSettings
  ): Future[HttpResponse] = {
    val currentSslContext = DynamicSSLEngineProvider.current
    if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
      lastSslContext.set(currentSslContext)
      val connectionContext: HttpsConnectionContext = ConnectionContext.https(currentSslContext)
      connectionContextHolder.set(connectionContext)
    }
    val pool = customizer(connectionPoolSettings).withMaxConnections(512)
    client.singleRequest(request, connectionContextHolder.get(), pool)
  }

  private[utils] def executeWsRequest[T](
      request: WebSocketRequest,
      clientFlow: Flow[Message, Message, T],
      customizer: ClientConnectionSettings => ClientConnectionSettings
  ): (Future[WebSocketUpgradeResponse], T) = {
    val currentSslContext = DynamicSSLEngineProvider.current
    if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
      lastSslContext.set(currentSslContext)
      val connectionContext: HttpsConnectionContext = ConnectionContext.https(currentSslContext)
      connectionContextHolder.set(connectionContext)
    }
    client.singleWebSocketRequest(
      request = request,
      clientFlow = clientFlow,
      connectionContext = connectionContextHolder.get(),
      settings = customizer(ClientConnectionSettings(system))
    )(mat)
  }
}

case class AkkWsClientStreamedResponse(httpResponse: HttpResponse, underlyingUrl: String, mat: Materializer)
    extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(
      ("Content-Type" -> Seq(contentType))
    )
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
  def bodyAsSource: Source[ByteString, _]              = httpResponse.entity.dataBytes
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def contentType: String                     = _contentType

  override def body[T: BodyReadable]: T =
    throw new RuntimeException("Not supported on this WSClient !!! (StreameResponse.body)")
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
    )
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
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
    _method: HttpMethod = HttpMethods.GET,
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    requestTimeout: Option[Int] = None,
    proxy: Option[WSProxyServer] = None,
    clientConfig: ClientConfig = ClientConfig()
)(implicit materializer: Materializer)
    extends WSRequest {

  implicit val ec = client.ec

  override type Self = WSRequest

  private val _uri = Uri(rawUrl)

  private def customizer: ConnectionPoolSettings => ConnectionPoolSettings = {
    val relUri = _uri.toRelative.toString()
    val idleTimeout = clientConfig.extractTimeout(relUri, _.idleTimeout,_.idleTimeout)
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
        a: ConnectionPoolSettings => {
          a.withTransport(httpsProxyTransport)
            .withIdleTimeout(idleTimeout)
            .withConnectionSettings(a.connectionSettings
              .withConnectingTimeout(connectionTimeout)
              .withIdleTimeout(idleTimeout))
        }
      } getOrElse { a: ConnectionPoolSettings =>
        a.withIdleTimeout(idleTimeout)
          .withConnectionSettings(a.connectionSettings
            .withConnectingTimeout(connectionTimeout)
            .withIdleTimeout(idleTimeout))
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
      .executeRequest(req, customizer)
      .map { resp =>
        AkkWsClientStreamedResponse(resp, rawUrl, client.mat)
      }(client.ec)
  }

  override def execute(method: String): Future[WSResponse] = {
    withMethod(method).execute()
  }

  override def execute(): Future[WSResponse] = {
    client
      .executeRequest(buildRequest(), customizer)
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
      .map(_.head)
  }

  def buildRequest(): HttpRequest = {
    val internalUri = Uri(rawUrl)
    val ct          = realContentType.getOrElse(ContentTypes.`application/octet-stream`)
    val cl          = realContentLength
    val ua          = realUserAgent.flatMap(s => Try(`User-Agent`(s)).toOption)
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
        h.isNot(`Content-Type`.lowercaseName) && h.isNot(`Content-Length`.lowercaseName) && h.isNot(
          `User-Agent`.lowercaseName
        )
      }
      .toList ++ ua

    HttpRequest(
      method = _method,
      uri = internalUri,
      headers = akkaHeaders,
      entity = akkaHttpEntity,
      protocol = protocol
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
    headers.get("Cookies").map { headers =>
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
  implicit class BetterStandaloneWSResponse[T <: StandaloneWSResponse](val req: T) extends AnyVal {
    def ignore()(implicit mat: Materializer): StandaloneWSResponse = {
      req.underlying[Any] match {
        case httpResponse: HttpResponse =>
          Try(httpResponse.discardEntityBytes()) match {
            case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
            case _          => ()
          }
          req
        case _ => req
      }
    }
    def ignoreIf(predicate: => Boolean)(implicit mat: Materializer): StandaloneWSResponse = {
      if (predicate) {
        req.underlying[Any] match {
          case httpResponse: HttpResponse =>
            Try(httpResponse.discardEntityBytes()) match {
              case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
              case _          => ()
            }
            req
          case _ => req
        }
      } else {
        req
      }
    }
  }
}
