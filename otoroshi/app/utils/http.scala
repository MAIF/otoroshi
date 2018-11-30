package utils.http

import java.io.File
import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import play.api.libs.json.JsValue
import play.api.libs.ws.{BodyReadable, BodyWritable, EmptyBody, InMemoryBody, SourceBody, WSAuthScheme, WSBody, WSClient, WSClientConfig, WSCookie, WSProxyServer, WSRequest, WSRequestFilter, WSResponse, WSSignatureCalculator}
import play.api.mvc.MultipartFormData

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.xml.Elem

class AkkWsClient(config: WSClientConfig)(implicit system: ActorSystem, materializer: Materializer) extends WSClient {

  val ec = system.dispatcher
  val mat = materializer
  val client = Http(system)

  override def underlying[T]: T = client.asInstanceOf[T]

  def forProtocol(protocol: String, url: String, standardClient: WSClient): WSRequest = {
    protocol.toLowerCase() match {
      case "http2" => new AkkWsClientRequest(this, url.replace("http2://", "http://"), HttpProtocols.`HTTP/2.0`)
      case "http2s" => new AkkWsClientRequest(this, url.replace("http2s://", "https://"), HttpProtocols.`HTTP/2.0`)
      case _ => standardClient.url(url)
    }
  }

  def url(url: String): WSRequest = throw new RuntimeException("Not supported bro !!!")

  override def close(): Unit = Await.ready(Http().shutdownAllConnectionPools(), 10.seconds)

  private[utils] val wsClientConfig: WSClientConfig = config
  private[utils] val akkaSSLConfig: AkkaSSLConfig = AkkaSSLConfig(system).withSettings(config.ssl)
  private[utils] val connectionContext: HttpsConnectionContext = client.createClientHttpsContext(akkaSSLConfig)
  client.validateAndWarnAboutLooseSettings()

  private[utils] val clientConnectionSettings: ClientConnectionSettings = ClientConnectionSettings(system)
    .withConnectingTimeout(FiniteDuration(config.connectionTimeout._1, config.connectionTimeout._2))
    .withIdleTimeout(config.idleTimeout)
    .withUserAgentHeader(config.userAgent.map(`User-Agent`(_)))

  private[utils] val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(clientConnectionSettings)
    .withMaxRetries(0)
    .withIdleTimeout(config.idleTimeout)

  private[utils] def executeRequest[T](request: HttpRequest): Future[HttpResponse] = {
    println(s"${request}")
    client.singleRequest(request, connectionContext, connectionPoolSettings)
  }
}

case class AkkWsClientStreamedResponse(httpResponse: HttpResponse, underlyingUrl: String) extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  def status: Int = httpResponse.status.intValue()
  def statusText: String = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]] = allHeaders
  def underlying[T]: T = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _] = httpResponse.entity.dataBytes
  override def header(name: String): Option[String] = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def contentType: String = header("Content-Type").getOrElse("application/octet-stream")

  override def body[T: BodyReadable]: T = throw new RuntimeException("Not supported bro !!!")
  def body: String = throw new RuntimeException("Not supported bro !!!")
  def bodyAsBytes: ByteString = throw new RuntimeException("Not supported bro !!!")
  def cookies: Seq[WSCookie] = throw new RuntimeException("Not supported bro !!!")
  def cookie(name: String): Option[WSCookie] = throw new RuntimeException("Not supported bro !!!")
  override def xml: Elem = throw new RuntimeException("Not supported bro !!!")
  override def json: JsValue = throw new RuntimeException("Not supported bro !!!")
}

object CaseInsensitiveOrdered extends Ordering[String] {
  def compare(x: String, y: String): Int = {
    val xl = x.length
    val yl = y.length
    if (xl < yl) -1 else if (xl > yl) 1 else x.compareToIgnoreCase(y)
  }
}

case class AkkWsClientRequest(
  client: AkkWsClient,
  rawUrl: String,
  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  _method: HttpMethod = HttpMethods.GET,
  body: WSBody = EmptyBody,
  headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
  requestTimeout: Option[Int] = None
)(implicit materializer: Materializer) extends WSRequest {

  override type Self = WSRequest

  def withFollowRedirects(follow: Boolean): Self = this

  def withMethod(method: String): AkkWsClientRequest = {
    copy(_method = HttpMethods.getForKeyCaseInsensitive(method).getOrElse(HttpMethod.custom(method)))
  }

  def withHttpHeaders(headers: (String, String)*): WSRequest = {
    copy(headers = headers.foldLeft(this.headers)((m, hdr) =>
      if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
      else m + (hdr._1 -> Seq(hdr._2))
    ))
  }

  def withRequestTimeout(timeout: Duration): Self = copy(requestTimeout = Some(timeout.toMillis.toInt))
  def stream(): Future[WSResponse] = {
    client.executeRequest(buildRequest()).map(resp => AkkWsClientStreamedResponse(resp, rawUrl))(client.ec)
  }

  private def realContentType: Option[ContentType] = {
    headers.get(`Content-Type`.name)
      .map(_.head)
      .map { value => HttpHeader.parse("Content-Type", value) }
      .flatMap {
        case ParsingResult.Ok(header, _) => Option(header.asInstanceOf[`Content-Type`].contentType)
        case _ => None
      }
  }

  def buildRequest(): HttpRequest = {
    val internalUri = Uri(rawUrl)
    val ct = realContentType.getOrElse(ContentTypes.`application/octet-stream`)
    val (akkaHttpEntity, updatedHeaders) = body match {
      case EmptyBody => (HttpEntity.Empty, headers)
      case InMemoryBody(bytes) => (HttpEntity(ct, bytes), headers)
      case SourceBody(bytes) => (HttpEntity(ct, bytes), headers)
    }
    val akkaHeaders = updatedHeaders.flatMap { case (key, values) =>
      values.map(value => HttpHeader.parse(key, value))
    }.flatMap {
      case ParsingResult.Ok(header, _) => Option(header)
      case _ => None
    }.filter {
      _.isNot(`Content-Type`.lowercaseName)
    }.toList

    HttpRequest(
      method = _method,
      uri = internalUri,
      headers = akkaHeaders,
      entity = akkaHttpEntity,
      protocol = protocol
    )
  }

  override def withBody[T](body: T)(implicit evidence$1: BodyWritable[T]): WSRequest = copy(body = evidence$1.transform(body))
  override def withHeaders(headers: (String, String)*): WSRequest = withHttpHeaders(headers:_*)

  ///////////

  override def withQueryString(parameters: (String, String)*): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withQueryStringParameters(parameters: (String, String)*): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withCookies(cookie: WSCookie*): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def method: String = throw new RuntimeException("Not supported bro !!!")
  override def queryString: Map[String, Seq[String]] = throw new RuntimeException("Not supported bro !!!")
  override def calc: Option[WSSignatureCalculator] = throw new RuntimeException("Not supported bro !!!")
  override def auth: Option[(String, String, WSAuthScheme)] = throw new RuntimeException("Not supported bro !!!")
  override def followRedirects: Option[Boolean] = throw new RuntimeException("Not supported bro !!!")
  override def virtualHost: Option[String] = throw new RuntimeException("Not supported bro !!!")
  override def proxyServer: Option[WSProxyServer] = throw new RuntimeException("Not supported bro !!!")
  override def sign(calc: WSSignatureCalculator): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withRequestFilter(filter: WSRequestFilter): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withVirtualHost(vh: String): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def withProxyServer(proxyServer: WSProxyServer): WSRequest = throw new RuntimeException("Not supported bro !!!")
  override def get(): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def post[T](body: T)(implicit evidence$2: BodyWritable[T]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def post(body: File): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def patch[T](body: T)(implicit evidence$3: BodyWritable[T]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def patch(body: File): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def put[T](body: T)(implicit evidence$4: BodyWritable[T]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def put(body: File): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def delete(): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def head(): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def options(): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def execute(method: String): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def execute(): Future[WSResponse] = throw new RuntimeException("Not supported bro !!!")
  override def url: String = throw new RuntimeException("Not supported bro !!!")
  override def uri: URI = throw new RuntimeException("Not supported bro !!!")
  override def contentType: Option[String] = throw new RuntimeException("Not supported bro !!!")
  override def cookies: Seq[WSCookie] = throw new RuntimeException("Not supported bro !!!")
}
