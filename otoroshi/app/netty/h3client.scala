package otoroshi.netty

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Length`, `Content-Type`, `User-Agent`}
import akka.http.scaladsl.model.{ContentType, HttpHeader, StatusCode, Uri}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.google.common.base.Charsets
import io.netty
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBufAllocator, Unpooled}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.{Channel, ChannelHandler, ChannelHandlerContext}
import io.netty.handler.codec.http.cookie.ClientCookieDecoder
import io.netty.handler.ssl.ApplicationProtocolNegotiator
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.incubator.codec.http3._
import io.netty.incubator.codec.quic._
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.{CharsetUtil, ReferenceCountUtil}
import org.apache.commons.codec.binary.Base64
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.models.{ClientConfig, Target}
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.utils.cache.Caches
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws._
import play.api.mvc.MultipartFormData
import reactor.core.publisher.{Flux, Sinks}

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.util
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSessionContext
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, XML}

case class Http3Response(
    status: Int,
    headers: Map[String, Seq[String]],
    bodyFlux: Flux[ByteString],
    trailer: Future[Map[String, Seq[String]]]
)

class NettySniSslContext(sslContext: QuicSslContext, host: String, port: Int) extends QuicSslContext {
  override def newEngine(alloc: ByteBufAllocator): QuicSslEngine                                  = sslContext.newEngine(alloc, host, port)
  override def newEngine(alloc: ByteBufAllocator, peerHost: String, peerPort: Int): QuicSslEngine =
    sslContext.newEngine(alloc, peerHost, peerPort)
  override def isClient: Boolean                                                                  = sslContext.isClient
  override def cipherSuites(): util.List[String]                                                  = sslContext.cipherSuites()
  override def applicationProtocolNegotiator(): ApplicationProtocolNegotiator                     =
    sslContext.applicationProtocolNegotiator()
  override def sessionContext(): SSLSessionContext                                                = sslContext.sessionContext()
}

case class NettyHttp3ClientBody(source: Flux[ByteString], contentType: Option[String], contentLength: Option[Long])

class NettyHttp3Client(val env: Env) {

  // TODO: support proxy ????

  private[netty] val logger = NettyHttp3Client.logger
  private val group         = new NioEventLoopGroup(Runtime.getRuntime.availableProcessors() + 1)
  private val bs            = new Bootstrap().group(group).channel(classOf[NioDatagramChannel])

  private val codecs   = Caches.bounded[String, ChannelHandler](999)
  private val channels = Caches.bounded[String, Future[Channel]](999)

  private[netty] def codecFor(context: QuicSslContext, host: String, port: Int): ChannelHandler = {
    val key = s"${host}:${port}"
    codecs.get(
      key,
      _ => {
        val codec = Http3
          .newQuicClientCodecBuilder()
          .sslContext(new NettySniSslContext(context, host, port))
          .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
          .initialMaxData(10000000)
          .initialMaxStreamDataBidirectionalLocal(1000000)
          .initialMaxStreamDataBidirectionalRemote(1000000)
          .initialMaxStreamsBidirectional(100000)
          .maxSendUdpPayloadSize(1500)
          .maxRecvUdpPayloadSize(1500)
          .build()
        codec
      }
    )
  }

  private[netty] def getChannel(codec: ChannelHandler, host: String, port: Int): Future[Channel] = {
    val key = s"${host}:${port}"
    channels.get(
      key,
      _ => {
        val promise = Promise.apply[Channel]()
        val future  = bs
          //.group(group)
          //.channel(classOf[NioDatagramChannel])
          .handler(codec)
          .bind(0)
        future.addListener(new GenericFutureListener[io.netty.util.concurrent.Future[Void]] {
          override def operationComplete(f: netty.util.concurrent.Future[Void]): Unit = {
            if (f.isSuccess) {
              promise.trySuccess(future.sync().channel())
            } else {
              promise.tryFailure(future.cause())
            }
          }
        })
        promise.future
      }
    )
  }

  private[netty] def getQuicChannel(
      channel: Channel,
      host: String,
      port: Int,
      target: Option[Target]
  ): Future[QuicChannel] = {
    val address: InetSocketAddress = target.flatMap(_.ipAddress) match {
      case None     => new InetSocketAddress(host, port)
      case Some(ip) => InetSocketAddress.createUnresolved(ip, port)
    }
    val promise                    = Promise.apply[QuicChannel]()
    val future                     = QuicChannel
      .newBootstrap(channel)
      .handler(new Http3ClientConnectionHandler())
      .remoteAddress(address)
      .connect()
    future.addListener(new GenericFutureListener[io.netty.util.concurrent.Future[QuicChannel]] {
      override def operationComplete(f: netty.util.concurrent.Future[QuicChannel]): Unit = {
        if (f.isSuccess) {
          promise.trySuccess(future.get())
        } else {
          promise.tryFailure(future.cause())
        }
      }
    })
    promise.future
  }

  private[netty] def getStandardSslContext(): QuicSslContext = {
    val context = QuicSslContextBuilder
      .forClient()
      .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
      .earlyData(true)
      .build()
    context
  }

  private[netty] def getSslContextFrom(tlsSettings: MtlsConfig): QuicSslContext = {
    if (tlsSettings.mtls) {
      val context = QuicSslContextBuilder
        .forClient()
        .applyOnIf(tlsSettings.trustAll)(_.trustManager(InsecureTrustManagerFactory.INSTANCE))
        .applyOnIf(tlsSettings.certs.nonEmpty || tlsSettings.trustedCerts.nonEmpty) { ctx =>
          val (_, keyManager, trustManager) = DynamicSSLEngineProvider.setupSslContextForWithManagers(
            tlsSettings.actualCerts,
            tlsSettings.actualTrustedCerts,
            tlsSettings.trustAll,
            true,
            env
          )
          ctx.keyManager(keyManager, null).trustManager(trustManager)
        }
        .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
        .earlyData(true)
        .build()
      context
    } else {
      getStandardSslContext()
    }
  }

  private[netty] def http3Channel(
      quicChannel: QuicChannel,
      promise: Promise[Http3Response]
  ): Future[QuicStreamChannel] = {
    val finalPromise = Promise.apply[QuicStreamChannel]()
    val future       = Http3.newRequestStream(
      quicChannel,
      new Http3RequestStreamInboundHandler() {

        var headers: Map[String, Seq[String]] = Map.empty
        var status: Int                       = 0
        var headersReceived: Boolean          = false
        val trailerPromise                    = Promise.apply[Map[String, Seq[String]]]()

        val hotSource = Sinks.many().unicast().onBackpressureBuffer[ByteString]()
        val hotFlux   = hotSource.asFlux()

        override def channelRead(ctx: ChannelHandlerContext, frame: Http3UnknownFrame): Unit = {
          if (logger.isDebugEnabled) logger.debug("unknown frame")
        }

        override def channelActive(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channel active")
        }

        override def channelInactive(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channel inactive")
        }

        override def handleHttp3Exception(ctx: ChannelHandlerContext, exception: Http3Exception): Unit = {
          if (logger.isDebugEnabled) logger.debug("handleHttp3Exception", exception)
          hotSource.tryEmitError(exception)
          promise.tryFailure(exception)
        }

        override def handleQuicException(ctx: ChannelHandlerContext, exception: QuicException): Unit = {
          if (logger.isDebugEnabled) logger.debug("handleQuicException", exception)
          hotSource.tryEmitError(exception)
          promise.tryFailure(exception)
        }

        override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channelReadComplete")
          ctx.close()
          hotSource.tryEmitComplete()
        }

        override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame): Unit = {
          val isLast = headersReceived
          if (logger.isDebugEnabled) logger.debug(s"got header frame !!!! ${isLast}")
          if (headersReceived) {
            val trailerHeaders = frame
              .headers()
              .names()
              .asScala
              .map(name => (name.toString, frame.headers().getAll(name).asScala.map(_.toString)))
              .toMap
            trailerPromise.trySuccess(trailerHeaders)
            ReferenceCountUtil.release(frame)
          } else {
            headersReceived = true
            status = frame.headers().status().toString.toInt
            headers = frame
              .headers()
              .names()
              .asScala
              .map(name => (name.toString, frame.headers().getAll(name).asScala.map(_.toString)))
              .toMap
            promise.trySuccess(Http3Response(status, headers, hotFlux, trailerPromise.future))
            ReferenceCountUtil.release(frame)
          }
        }

        override def channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame): Unit = {
          val content = frame.content().toString(CharsetUtil.US_ASCII)
          val chunk   = ByteString(content)
          if (logger.isDebugEnabled) logger.debug(s"got data frame in !!!")
          hotSource.tryEmitNext(chunk)
          ReferenceCountUtil.release(frame)
        }

        override def channelInputClosed(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channelInputClosed")
          // TODO: check if right
          ctx.close()
          hotSource.tryEmitComplete()
        }
      }
    )
    future.addListener(new GenericFutureListener[io.netty.util.concurrent.Future[QuicStreamChannel]] {
      override def operationComplete(f: netty.util.concurrent.Future[QuicStreamChannel]): Unit = {
        if (f.isSuccess) {
          finalPromise.trySuccess(future.get())
        } else {
          finalPromise.tryFailure(future.cause())
        }
      }
    })
    finalPromise.future
  }

  def close(): Unit = {
    channels.asMap().values.map(_.map(c => c.close().sync())(env.otoroshiExecutionContext))
    group.shutdownGracefully()
  }

  def url(rawUrl: String): NettyHttp3ClientWsRequest = NettyHttp3ClientWsRequest(this, rawUrl)
}

case class NettyHttp3ClientStrictWsResponse(resp: NettyHttp3ClientWsResponse, bodyAsBytes: ByteString)
    extends WSResponse
    with TrailerSupport {

  private lazy val _bodyAsString: String = bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem      = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue  = Json.parse(_bodyAsString)

  override def status: Int                            = resp.status
  override def statusText: String                     = resp.statusText
  override def headers: Map[String, Seq[String]]      = resp.headers
  override def underlying[T]: T                       = resp.underlying
  override def cookies: Seq[WSCookie]                 = resp.cookies
  override def cookie(name: String): Option[WSCookie] = resp.cookie(name)
  override def allHeaders: Map[String, Seq[String]]   = resp.allHeaders
  override def uri: URI                               = resp.uri

  override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)
  override def body: String                        = _bodyAsString
  override def xml: Elem                           = _bodyAsXml
  override def json: JsValue                       = _bodyAsJson

  def trailingHeaders(): Future[Map[String, Seq[String]]]                       = resp.trailingHeaders()
  def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit = resp.registerTrailingHeaders(promise)
}

case class NettyHttp3ClientWsResponse(resp: Http3Response, _uri: Uri, env: Env) extends WSResponse with TrailerSupport {

  private lazy val _body: Source[ByteString, _] = Try {
    Source.fromPublisher(resp.bodyFlux).filter(_.nonEmpty).alsoTo(Sink.onComplete {
      case Failure(e) => e.printStackTrace()
      case Success(_) => ()
    })
  } match {
    case Failure(e) =>
      e.printStackTrace()
      Source.empty
    case Success(source) => source
  }

  private lazy val _bodyAsBytes: ByteString = {
    Await.result(
      bodyAsSource.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer),
      FiniteDuration(10, TimeUnit.MINUTES)
    ) // AWAIT: valid
  }
  private lazy val _allHeaders: Map[String, Seq[String]] = resp.headers.map { case (name, values) =>
    (name.replace(":", ""), values)
  }
  private lazy val _bodyAsString: String                 = _bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem                      = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue                  = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie] = {
    resp.headers
      .get("set-cookie")
      .map { values =>
        values.map(ClientCookieDecoder.LAX.decode).map { cookie =>
          DefaultWSCookie(
            name = cookie.name(),
            value = cookie.value(),
            domain = Option(cookie.domain()),
            path = Option(cookie.path()),
            maxAge = Option(cookie.maxAge()),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly
          )
        }
      }
      .getOrElse(Seq.empty)
  }

  override def bodyAsSource: Source[ByteString, _]    = _body
  override def headers: Map[String, Seq[String]]      = _allHeaders
  override def status: Int                            = resp.status
  override def statusText: String                     = StatusCode.int2StatusCode(resp.status).reason()
  override def allHeaders: Map[String, Seq[String]]   = headers
  override def underlying[T]: T                       = resp.asInstanceOf[T]
  override def cookies: Seq[WSCookie]                 = _cookies
  override def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
  override def body: String                           = _bodyAsString
  override def bodyAsBytes: ByteString                = _bodyAsBytes
  override def xml: Elem                              = _bodyAsXml
  override def json: JsValue                          = _bodyAsJson
  override def uri: URI                               = new URI(_uri.toRelative.toString())

  def toStrict(): NettyHttp3ClientStrictWsResponse = NettyHttp3ClientStrictWsResponse(this, _bodyAsBytes)

  def trailingHeaders(): Future[Map[String, Seq[String]]] = {
    resp.trailer
  }

  def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit = {
    resp.trailer.andThen {
      case Failure(ex)      => promise.tryFailure(ex)
      case Success(headers) => promise.trySuccess(headers)
    }(env.otoroshiExecutionContext)
  }
}

case class NettyHttp3ClientWsRequest(
    client: NettyHttp3Client,
    _url: String,
    method: String = "GET",
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    protocol: Option[String] = None,
    followRedirects: Option[Boolean] = None,
    requestTimeout: Option[Duration] = None,
    proxy: Option[WSProxyServer] = None,
    //////
    tlsConfig: Option[MtlsConfig] = None,
    targetOpt: Option[Target] = None,
    clientConfig: ClientConfig = ClientConfig()
) extends WSRequest {

  private val _uri = Uri(_url)

  def withProtocol(proto: String): NettyHttp3ClientWsRequest                                             = copy(protocol = proto.some)
  def withTlsConfig(tlsConfig: MtlsConfig): NettyHttp3ClientWsRequest                                    = copy(tlsConfig = tlsConfig.some)
  def withTarget(target: Target): NettyHttp3ClientWsRequest                                              = copy(targetOpt = target.some)
  def withClientConfig(clientConfig: ClientConfig): NettyHttp3ClientWsRequest                            = copy(clientConfig = clientConfig)
  //////
  override def auth: Option[(String, String, WSAuthScheme)]                                              =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.auth)")
  override def calc: Option[WSSignatureCalculator]                                                       =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.calc)")
  override def virtualHost: Option[String]                                                               =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.virtualHost)")
  override def sign(calc: WSSignatureCalculator): WSRequest                                              =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.sign)")
  override def withRequestFilter(filter: WSRequestFilter): WSRequest                                     =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withRequestFilter)")
  override def withVirtualHost(vh: String): WSRequest                                                    =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withVirtualHost)")
  //////
  override def proxyServer: Option[WSProxyServer]                                                        = proxy
  override def withFollowRedirects(follow: Boolean): WSRequest                                           = copy(followRedirects = Some(follow))
  override def withRequestTimeout(timeout: Duration): WSRequest                                          = copy(requestTimeout = Some(timeout))
  override def withProxyServer(proxyServer: WSProxyServer): WSRequest                                    = copy(proxy = Some(proxyServer))
  override def withBody[T](body: T)(implicit evidence$1: BodyWritable[T]): WSRequest                     =
    copy(body = evidence$1.transform(body))
  override def withMethod(method: String): WSRequest                                                     = copy(method = method)
  override def get(): Future[WSResponse]                                                                 = copy(method = "GET").execute()
  override def delete(): Future[WSResponse]                                                              = copy(method = "DELETE").execute()
  override def head(): Future[WSResponse]                                                                = copy(method = "HEAD").execute()
  override def options(): Future[WSResponse]                                                             = copy(method = "OPTIONS").execute()
  override def execute(method: String): Future[WSResponse]                                               = copy(method = method).execute()
  override def withUrl(url: String): WSRequest                                                           = copy(_url = url)
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequest = {
    scheme match {
      case WSAuthScheme.BASIC =>
        addHttpHeaders(
          "Authorization" -> s"Basic ${Base64.encodeBase64String(s"${username}:${password}".getBytes(Charsets.UTF_8))}"
        )
      case _                  => throw new RuntimeException("Not supported on this WSClient !!! (Request.withAuth)")
    }
  }
  override lazy val url: String                                                                          = _uri.toString()
  override lazy val uri: URI                                                                             = new URI(_uri.toRelative.toString())
  override lazy val contentType: Option[String]                                                          = realContentType.map(_.value)
  override lazy val cookies: Seq[WSCookie] = {
    headers.get("Cookie").map { headers =>
      headers.flatMap { header =>
        header.split(";").map { value =>
          val parts = value.split("=")
          DefaultWSCookie(
            name = parts(0),
            value = parts(1)
          )
        }
      }
    } getOrElse Seq.empty
  }
  override def withQueryString(parameters: (String, String)*): WSRequest                                 = addQueryStringParameters(parameters: _*)
  override def withQueryStringParameters(parameters: (String, String)*): WSRequest                       =
    copy(_url = _uri.withQuery(Uri.Query.apply(parameters: _*)).toString())
  override def addQueryStringParameters(parameters: (String, String)*): WSRequest = {
    val params: Seq[(String, String)] =
      _uri.query().toMultiMap.toSeq.flatMap(t => t._2.map(t2 => (t._1, t2))) ++ parameters
    copy(_url = _uri.withQuery(Uri.Query.apply(params: _*)).toString())
  }
  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse]  =
    post[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    patch[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse]   =
    put[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def post[T](body: T)(implicit evidence$2: BodyWritable[T]): Future[WSResponse]                =
    withMethod("POST")
      .withBody(evidence$2.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$2.contentType)
      .execute()
  override def post(body: File): Future[WSResponse]                                                      =
    withMethod("POST")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def patch[T](body: T)(implicit evidence$3: BodyWritable[T]): Future[WSResponse]               =
    withMethod("PATCH")
      .withBody(evidence$3.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$3.contentType)
      .execute()
  override def patch(body: File): Future[WSResponse]                                                     =
    withMethod("PATCH")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def put[T](body: T)(implicit evidence$4: BodyWritable[T]): Future[WSResponse]                 =
    withMethod("PUT")
      .withBody(evidence$4.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$4.contentType)
      .execute()
  override def put(body: File): Future[WSResponse]                                                       =
    withMethod("PUT")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def withCookies(cookies: WSCookie*): WSRequest = {
    if (cookies.nonEmpty) {
      val oldCookies = headers.get("Cookie").getOrElse(Seq.empty[String])
      val newCookies = oldCookies :+ cookies.toList
        .map { c =>
          s"${c.name}=${c.value}"
        }
        .mkString(";")
      copy(
        headers = headers + ("Cookie" -> newCookies)
      )
    } else this
  }
  override def withHeaders(headers: (String, String)*): WSRequest                                        = withHttpHeaders(headers: _*)
  override def withHttpHeaders(headers: (String, String)*): WSRequest = {
    copy(
      headers = headers.foldLeft(this.headers)((m, hdr) =>
        if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
        else m + (hdr._1 -> Seq(hdr._2))
      )
    )
  }
  override def queryString: Map[String, Seq[String]]                                                     = _uri.query().toMultiMap
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private def realContentType: Option[ContentType] = {
    headers
      .get(`Content-Type`.name)
      .orElse(
        headers
          .get(`Content-Type`.lowercaseName)
      )
      .flatMap(_.headOption)
      .map { value =>
        HttpHeader.parse("Content-Type", value)
      }
      .flatMap {
        // case ParsingResult.Ok(header, _) => Option(header.asInstanceOf[`Content-Type`].contentType)
        case ParsingResult.Ok(header, _) =>
          header match {
            case `Content-Type`(contentType)                => contentType.some
            case RawHeader(_, value) if value.contains(",") =>
              value.split(",").headOption.map(_.trim).map(v => `Content-Type`.parseFromValueString(v)) match {
                case Some(Left(errs))                         => {
                  ClientConfig.logger.error(s"Error while parsing request content-type: ${errs}")
                  None
                }
                case Some(Right(`Content-Type`(contentType))) => contentType.some
                case None                                     => None
              }
            case RawHeader(_, value)                        =>
              `Content-Type`.parseFromValueString(value) match {
                case Left(errs)                         => {
                  ClientConfig.logger.error(s"Error while parsing request content-type: ${errs}")
                  None
                }
                case Right(`Content-Type`(contentType)) => contentType.some
              }
            case _                                          => None
          }
        case _                           => None
      }
  }
  private def realContentLength: Option[Long] = {
    headers
      .get(`Content-Length`.name)
      .orElse(headers.get(`Content-Length`.lowercaseName))
      .flatMap(_.headOption)
      .map(_.toLong)
  }
  private def realUserAgent: Option[String] = {
    headers
      .get(`User-Agent`.name)
      .orElse(headers.get(`User-Agent`.lowercaseName))
      .flatMap(_.headOption)
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def execute(): Future[WSResponse] = {
    val env = OtoroshiEnvHolder.get()
    stream().map(_.asInstanceOf[NettyHttp3ClientWsResponse].toStrict())(env.otoroshiExecutionContext)
  }
  override def stream(): Future[WSResponse] = {
    implicit val ec = client.env.otoroshiExecutionContext
    val uri         = new URI(url)
    val thePort     = uri.getPort match {
      case -1 =>
        uri.getScheme match {
          case "https" => 443
          case "http"  => 80
          case _       => 80
        }
      case v  => v
    }
    val promise     = Promise.apply[Http3Response]()
    val context     = tlsConfig match {
      case None      => client.getStandardSslContext()
      case Some(tls) => client.getSslContextFrom(tls)
    }
    val codec       = client.codecFor(context, uri.getHost, thePort)
    (for {
      channel       <- client.getChannel(codec, uri.getHost, thePort)
      quicChannel   <- client.getQuicChannel(channel, uri.getHost, thePort, targetOpt)
      streamChannel <- client.http3Channel(quicChannel, promise)
    } yield {
      if (client.logger.isDebugEnabled) client.logger.debug("building header frame !")
      val hasBody = body match {
        case EmptyBody => false
        case _         => true
      }
      val frame   = new DefaultHttp3HeadersFrame()
      frame
        .headers()
        .method(method)
        .path(uri.getRawPath)
        .authority(uri.getHost)
        .scheme(uri.getScheme)
        .applyOn { heads =>
          headers.map { case (name, values) =>
            heads.add(name.toLowerCase(), values.asJava)
          }
          heads
        }
        .add("content-length", "0")
      if (hasBody) {
        frame.headers().method(method)
        headers.map { case (name, values) =>
          frame.headers().add(name.toLowerCase(), values.asJava)
        }
        frame.headers().remove("content-length")
        realContentType.foreach(ct => frame.headers().add("content-type", ct.value))
        realContentLength.foreach(cl => frame.headers().add("content-length", cl.toString))
        if (client.logger.isDebugEnabled) client.logger.debug(s"sending header frame: ${frame}")
        streamChannel.write(frame)
        if (client.logger.isDebugEnabled) client.logger.debug("sending body chunks")
        val bodySource: Flux[ByteString] = body match {
          case EmptyBody          => Flux.empty[ByteString]
          case InMemoryBody(bs)   => Flux.just(Seq(bs): _*)
          case SourceBody(source) => Flux.from(source.runWith(Sink.asPublisher(false))(client.env.otoroshiMaterializer))
        }
        bodySource
          .doOnNext(chunk => {
            streamChannel.write(new DefaultHttp3DataFrame(Unpooled.copiedBuffer(chunk.toArray)))
          })
          .doOnComplete(() => {
            if (client.logger.isDebugEnabled) client.logger.debug("body send complete !")
            streamChannel.shutdownOutput()
          })
          .subscribe()
      } else {
        if (client.logger.isDebugEnabled) client.logger.debug(s"sending header frame: ${frame}")
        streamChannel
          .writeAndFlush(frame)
          .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
      }
      promise.future.andThen {
        case e => {
          streamChannel.closeFuture().sync()
          quicChannel.close().sync()
        }
      }
    }).flatMap(a => a.map(f => NettyHttp3ClientWsResponse(f, _uri, client.env)))
  }
}

object NettyHttp3Client {
  val logger = Logger("otoroshi-experimental-netty-http3-client")
}
