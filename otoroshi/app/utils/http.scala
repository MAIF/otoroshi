package utils.http

import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import javax.net.ssl.SSLContext
import play.api.{Logger, libs}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{BodyReadable, BodyWritable, DefaultWSCookie, EmptyBody, InMemoryBody, SourceBody, WSAuthScheme, WSBody, WSClient, WSClientConfig, WSCookie, WSProxyServer, WSRequest, WSRequestFilter, WSResponse, WSSignatureCalculator}
import play.api.mvc.MultipartFormData
import play.libs.ws.DefaultWSCookie
import ssl.DynamicSSLEngineProvider

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.Try
import scala.xml.{Elem, XML}

object WsClientChooser {
  def apply(standardClient: WSClient,
            akkaClient: AkkWsClient,
            ahcCreator: SSLConfigSettings => WSClient,
            fullAkka: Boolean): WsClientChooser = new WsClientChooser(standardClient, akkaClient, ahcCreator, fullAkka)
}

class WsClientChooser(standardClient: WSClient,
                      akkaClient: AkkWsClient,
                      ahcCreator: SSLConfigSettings => WSClient,
                      fullAkka: Boolean)
    extends WSClient {

  private[utils] val logger                  = Logger("otoroshi-WsClientChooser")
  private[utils] val lastSslConfig           = new AtomicReference[SSLConfigSettings](null)
  private[utils] val connectionContextHolder = new AtomicReference[WSClient](null)

  private def getAhcInstance(): WSClient = {
    val currentSslContext = DynamicSSLEngineProvider.sslConfigSettings
    if (currentSslContext != null && !currentSslContext.equals(lastSslConfig.get())) {
      lastSslConfig.set(currentSslContext)
      logger.debug("Building new client instance")
      val client = ahcCreator(currentSslContext)
      connectionContextHolder.set(client)
    }
    connectionContextHolder.get()
  }

  def url(url: String): WSRequest = {
    val protocol = scala.util.Try(url.split(":\\/\\/").apply(0)).getOrElse("http")
    urlWithProtocol(protocol, url)
  }

  def urlWithProtocol(protocol: String, url: String): WSRequest = { // TODO: handle idle timeout and other timeout per request here
    protocol.toLowerCase() match {

      case "http"  => standardClient.url(url)
      case "https" => standardClient.url(url)

      case "ahc:http"  => getAhcInstance().url(url.replace("ahc:http://", "http://"))
      case "ahc:https" => getAhcInstance().url(url.replace("ahc:https://", "https://"))

      case "ahttp" =>
        new AkkaWsClientRequest(akkaClient, url.replace("ahttp://", "http://"), HttpProtocols.`HTTP/1.1`)(
          akkaClient.mat
        )
      case "ahttps" =>
        new AkkaWsClientRequest(akkaClient, url.replace("ahttps://", "https://"), HttpProtocols.`HTTP/1.1`)(
          akkaClient.mat
        )
      case "http2" =>
        new AkkaWsClientRequest(akkaClient, url.replace("http2://", "http://"), HttpProtocols.`HTTP/2.0`)(
          akkaClient.mat
        )
      case "http2s" =>
        new AkkaWsClientRequest(akkaClient, url.replace("http2s://", "https://"), HttpProtocols.`HTTP/2.0`)(
          akkaClient.mat
        )

      case _ if fullAkka => new AkkaWsClientRequest(akkaClient, url, HttpProtocols.`HTTP/1.1`)(akkaClient.mat)

      case _ if !fullAkka => standardClient.url(url)
    }
  }

  override def underlying[T]: T = standardClient.underlying[T]

  override def close(): Unit = ()
}

object AkkWsClient {
  def cookies(httpResponse: HttpResponse): Seq[WSCookie] = {
    httpResponse.headers.collect {
      case c: `Set-Cookie` => c.cookie
    }.map { c =>
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

  def url(url: String): WSRequest = new AkkaWsClientRequest(this, url)

  override def close(): Unit = Await.ready(Http().shutdownAllConnectionPools(), 10.seconds)

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

  private[utils] def executeRequest[T](request: HttpRequest): Future[HttpResponse] = {
    val currentSslContext = DynamicSSLEngineProvider.current
    if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
      lastSslContext.set(currentSslContext)
      val connectionContext: HttpsConnectionContext = ConnectionContext.https(currentSslContext)
      connectionContextHolder.set(connectionContext)
    }
    client.singleRequest(request, connectionContextHolder.get(), connectionPoolSettings)
  }
}

case class AkkWsClientStreamedResponse(httpResponse: HttpResponse, underlyingUrl: String, mat: Materializer)
    extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(("Content-Type" -> Seq(contentType)))
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  lazy val _contentType: String          = httpResponse.entity.contentType.mediaType.toString() + _charset.map(v => ";charset=" + v.value).getOrElse("")
  lazy val _bodyAsBytes: ByteString      = Await.result(bodyAsSource.runFold(ByteString.empty)(_ ++ _)(mat), FiniteDuration(10, TimeUnit.MINUTES))
  lazy val _bodyAsString: String         = _bodyAsBytes.utf8String
  lazy val _bodyAsXml: Elem              = XML.loadString(_bodyAsString)
  lazy val _bodyAsJson: JsValue          = Json.parse(_bodyAsString)
  lazy val _cookies: Seq[WSCookie]       = AkkWsClient.cookies(httpResponse)


  def status: Int                                      = httpResponse.status.intValue()
  def statusText: String                               = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]]                = allHeaders
  def underlying[T]: T                                 = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _]              = httpResponse.entity.dataBytes
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def contentType: String                     = _contentType

  override def body[T: BodyReadable]: T      = throw new RuntimeException("Not supported on this WSClient !!!")
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
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(("Content-Type" -> Seq(contentType)))
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  lazy val _contentType: String          = httpResponse.entity.contentType.mediaType.toString() + _charset.map(v => ";charset=" + v.value).getOrElse("")
  lazy val _bodyAsBytes: ByteString      = rawbody
  lazy val _bodyAsString: String         = rawbody.utf8String
  lazy val _bodyAsXml: Elem              = XML.loadString(_bodyAsString)
  lazy val _bodyAsJson: JsValue          = Json.parse(_bodyAsString)
  lazy val _cookies: Seq[WSCookie]       = AkkWsClient.cookies(httpResponse)

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
  override def body[T: BodyReadable]: T      = throw new RuntimeException("Not supported on this WSClient !!!")
  def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
}

object CaseInsensitiveOrdered extends Ordering[String] {
  def compare(x: String, y: String): Int = {
    val xl = x.length
    val yl = y.length
    if (xl < yl) -1 else if (xl > yl) 1 else x.compareToIgnoreCase(y)
  }
}

case class AkkaWsClientRequest(
    client: AkkWsClient,
    rawUrl: String,
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
    _method: HttpMethod = HttpMethods.GET,
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    requestTimeout: Option[Int] = None
)(implicit materializer: Materializer)
    extends WSRequest {

  implicit val ec = client.ec

  override type Self = WSRequest

  def withFollowRedirects(follow: Boolean): Self = this

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
    client.executeRequest(buildRequest()).map(resp => AkkWsClientStreamedResponse(resp, rawUrl, client.mat))(client.ec)
    // execute()
  }

  override def execute(method: String): Future[WSResponse] = {
    withMethod(method).execute()
  }

  override def execute(): Future[WSResponse] = {
    client
      .executeRequest(buildRequest())
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
          values.map(value => HttpHeader.parse(key, value))
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

  override def withQueryString(parameters: (String, String)*): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def withQueryStringParameters(parameters: (String, String)*): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def withCookies(cookies: WSCookie*): WSRequest = {
    val oldCookies = headers.get("Cookie").getOrElse(Seq.empty[String])
    val newCookies = oldCookies :+ cookies.toList.map { c =>
      s"${c.name}=${c.value}"
    }.mkString(";")
    copy(
      headers = headers + ("Cookie" -> newCookies)
    )
  }
  override def method: String                        = throw new RuntimeException("Not supported on this WSClient !!!")
  override def queryString: Map[String, Seq[String]] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def calc: Option[WSSignatureCalculator]   = throw new RuntimeException("Not supported on this WSClient !!!")
  override def auth: Option[(String, String, WSAuthScheme)] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def followRedirects: Option[Boolean]   = throw new RuntimeException("Not supported on this WSClient !!!")
  override def virtualHost: Option[String]        = throw new RuntimeException("Not supported on this WSClient !!!")
  override def proxyServer: Option[WSProxyServer] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def sign(calc: WSSignatureCalculator): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def withRequestFilter(filter: WSRequestFilter): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def withVirtualHost(vh: String): WSRequest = throw new RuntimeException("Not supported on this WSClient !!!")
  override def withProxyServer(proxyServer: WSProxyServer): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def get(): Future[WSResponse] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def post[T](body: T)(implicit evidence$2: BodyWritable[T]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def post(body: File): Future[WSResponse] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def patch[T](body: T)(implicit evidence$3: BodyWritable[T]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def patch(body: File): Future[WSResponse] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def put[T](body: T)(implicit evidence$4: BodyWritable[T]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def put(body: File): Future[WSResponse] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    throw new RuntimeException("Not supported on this WSClient !!!")
  override def delete(): Future[WSResponse]  = throw new RuntimeException("Not supported on this WSClient !!!")
  override def head(): Future[WSResponse]    = throw new RuntimeException("Not supported on this WSClient !!!")
  override def options(): Future[WSResponse] = throw new RuntimeException("Not supported on this WSClient !!!")
  override def url: String                   = throw new RuntimeException("Not supported on this WSClient !!!")
  override def uri: URI                      = throw new RuntimeException("Not supported on this WSClient !!!")
  override def contentType: Option[String]   = throw new RuntimeException("Not supported on this WSClient !!!")
  override def cookies: Seq[WSCookie]        = throw new RuntimeException("Not supported on this WSClient req.cookies !!!")
}
