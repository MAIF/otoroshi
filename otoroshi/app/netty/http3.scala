package otoroshi.netty

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.{ContentType, HttpHeader, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Length`, `Content-Type`, `User-Agent`}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import com.google.common.base.Charsets
import io.netty
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, ByteBufAllocator, Unpooled}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.{Channel, ChannelFutureListener, ChannelHandler, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.cookie.ClientCookieDecoder
import io.netty.handler.ssl.ApplicationProtocolNegotiator
import io.netty.handler.ssl.util.{InsecureTrustManagerFactory, SelfSignedCertificate}
import io.netty.incubator.codec.http3.{DefaultHttp3DataFrame, DefaultHttp3HeadersFrame, Http3, Http3ClientConnectionHandler, Http3DataFrame, Http3Exception, Http3HeadersFrame, Http3RequestStreamFrame, Http3RequestStreamInboundHandler}
import io.netty.incubator.codec.quic.{QuicChannel, QuicException, QuicSslContext, QuicSslContextBuilder, QuicSslEngine, QuicStreamChannel}
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.{CharsetUtil, Mapping, ReferenceCountUtil}
import org.apache.commons.codec.binary.Base64
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.models.{ClientConfig, Target}
import otoroshi.netty.ImplicitUtils._
import otoroshi.netty.NettyHttp3Client.logger
import otoroshi.ssl.{DynamicKeyManager, DynamicSSLEngineProvider}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpEntity, HttpRequestHandler}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{BodyWritable, DefaultWSCookie, EmptyBody, InMemoryBody, SourceBody, WSAuthScheme, WSBody, WSCookie, WSProxyServer, WSRequest, WSRequestFilter, WSResponse, WSSignatureCalculator}
import play.api.mvc.{EssentialAction, FlashCookieBaker, MultipartFormData, SessionCookieBaker}
import reactor.core.publisher.{Flux, FluxSink}
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClientResponse

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.net.ssl.SSLSessionContext
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import scala.xml.{Elem, XML}

class Http1RequestHandler(
    handler: HttpRequestHandler,
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker,
    env: Env,
    logger: Logger
) extends ChannelInboundHandlerAdapter {

  private implicit val ec  = env.otoroshiExecutionContext
  private implicit val mat = env.otoroshiMaterializer

  private val NOT_HANDLED          =
    Unpooled.wrappedBuffer(s"${Json.obj("error" -> "not handled")}\r\n".getBytes(CharsetUtil.US_ASCII))
  private val NOT_ESSENTIAL_ACTION =
    Unpooled.wrappedBuffer(s"${Json.obj("error" -> "not essential action")}\r\n".getBytes(CharsetUtil.US_ASCII))
  private val ERROR                = Unpooled.wrappedBuffer(s"${Json.obj("error" -> "error")}\r\n".getBytes(CharsetUtil.US_ASCII))

  private var keepAlive            = false
  private var request: HttpRequest = _
  private val subscriptionRef      = new AtomicReference[FluxSink[ByteString]]()
  private val bodyBuffer           = new AtomicReference[Seq[ByteString]](Seq.empty)
  private val bodyPublisher        = Flux.push[ByteString](
    new Consumer[FluxSink[ByteString]] {
      override def accept(sink: FluxSink[ByteString]): Unit = {
        subscriptionRef.set(sink)
        drainBuffer()
      }
    },
    FluxSink.OverflowStrategy.BUFFER
  )

  private def drainBuffer(): Unit = {
    if (subscriptionRef.get() != null) {
      val sink   = subscriptionRef.get()
      val buffer = bodyBuffer.get()
      if (buffer.nonEmpty) {
        def next(zeBuffer: Seq[ByteString]): Unit = {
          bodyBuffer.set(zeBuffer)
          if (zeBuffer.nonEmpty) {
            val head = buffer.headOption
            val tail = buffer.tail
            if (head.isDefined) {
              sink.next(head.get)
              next(tail)
            }
          }
        }
        next(buffer)
      }
    }
  }

  private def send100Continue(ctx: ChannelHandlerContext): Unit = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER)
    ctx.write(response)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
    ctx.close()
  }

  private def directResponse(ctx: ChannelHandlerContext, msg: Any, status: HttpResponseStatus, body: ByteBuf): Unit = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes())
    ctx.writeAndFlush(response)
    ReferenceCountUtil.release(msg)
  }

  private def appendToBody(chunk: ByteString): Unit = {
    bodyBuffer.getAndUpdate(b => b :+ chunk)
    drainBuffer()
  }

  private def runOtoroshiRequest(req: HttpRequest, keepAlive: Boolean, ctx: ChannelHandlerContext, msg: Any): Unit = {
    if (request == null) throw new RuntimeException("no request found !!!")
    val session            = ctx.channel() match {
      case c: io.netty.incubator.codec.quic.QuicChannel       =>
        Option(c)
          .flatMap(p => Option(p.sslEngine()))
          .flatMap(p => Option(p.getSession()))
          .orElse(
            Option(c)
              .flatMap(p => Option(p.sslEngine()))
              .flatMap(p => Option(p.getHandshakeSession()))
          )
      case c: io.netty.incubator.codec.quic.QuicStreamChannel =>
        Option(c.parent())
          .flatMap(p => Option(p.sslEngine()))
          .flatMap(p => Option(p.getSession()))
          .orElse(
            Option(c.parent())
              .flatMap(p => Option(p.sslEngine()))
              .flatMap(p => Option(p.getHandshakeSession()))
          )
      case _                                                  => None
    }
    val rawOtoReq = new NettyRequest(req, ctx, Flux.empty(), true, session, sessionCookieBaker, flashCookieBaker)
    val hasBody = otoroshi.utils.body.BodyUtils.hasBodyWithoutOrZeroLength(rawOtoReq)._1
    val bodyIn: Flux[ByteString] = if (hasBody) bodyPublisher else Flux.empty()
    val otoReq = rawOtoReq.withBody(bodyIn)
    val (nreq, reqHandler) = handler.handlerForRequest(otoReq)
    reqHandler match {
      case a: EssentialAction => {
        a.apply(nreq)
          .run(otoReq.body)
          .flatMap { result =>
            val response = result.body match {
              case HttpEntity.NoEntity          =>
                new DefaultFullHttpResponse(
                  HttpVersion.HTTP_1_1,
                  HttpResponseStatus.valueOf(result.header.status),
                  Unpooled.EMPTY_BUFFER
                )
              case HttpEntity.Strict(data, _)   =>
                new DefaultFullHttpResponse(
                  HttpVersion.HTTP_1_1,
                  HttpResponseStatus.valueOf(result.header.status),
                  Unpooled.copiedBuffer(data.toArray)
                )
              case HttpEntity.Chunked(_, _)     =>
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.header.status))
              case HttpEntity.Streamed(_, _, _) =>
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.header.status))
            }
            result.header.headers.foreach { case (key, value) =>
              response.headers().set(key, value)
            }
            var cookies  = Seq.empty[io.netty.handler.codec.http.cookie.Cookie]
            if (result.newSession.nonEmpty) {
              result.newSession.map { session =>
                val cookie        = sessionCookieBaker.encodeAsCookie(session)
                val sessionCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
                sessionCookie.setPath(cookie.path)
                sessionCookie.setHttpOnly(cookie.httpOnly)
                sessionCookie.setSecure(cookie.secure)
                cookie.domain.foreach(d => sessionCookie.setDomain(d))
                cookie.maxAge.foreach(d => sessionCookie.setMaxAge(d.toLong))
                cookie.sameSite.foreach {
                  case play.api.mvc.Cookie.SameSite.None   =>
                    sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
                  case play.api.mvc.Cookie.SameSite.Strict =>
                    sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
                  case play.api.mvc.Cookie.SameSite.Lax    =>
                    sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
                }
                cookies = cookies :+ sessionCookie
              }
            }
            if (result.newFlash.nonEmpty) {
              result.newFlash.foreach { flash =>
                val cookie      = flashCookieBaker.encodeAsCookie(flash)
                val flashCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
                flashCookie.setPath(cookie.path)
                flashCookie.setHttpOnly(cookie.httpOnly)
                flashCookie.setSecure(cookie.secure)
                cookie.domain.foreach(d => flashCookie.setDomain(d))
                cookie.maxAge.foreach(d => flashCookie.setMaxAge(d.toLong))
                cookie.sameSite.foreach {
                  case play.api.mvc.Cookie.SameSite.None   =>
                    flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
                  case play.api.mvc.Cookie.SameSite.Strict =>
                    flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
                  case play.api.mvc.Cookie.SameSite.Lax    =>
                    flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
                }
                cookies = cookies :+ flashCookie
              }
            }
            if (result.newCookies.nonEmpty) {
              result.newCookies.foreach { cookie =>
                val nettyCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
                nettyCookie.setPath(cookie.path)
                nettyCookie.setHttpOnly(cookie.httpOnly)
                nettyCookie.setSecure(cookie.secure)
                cookie.domain.foreach(d => nettyCookie.setDomain(d))
                cookie.maxAge.foreach(d => nettyCookie.setMaxAge(d.toLong))
                cookie.sameSite.foreach {
                  case play.api.mvc.Cookie.SameSite.None   =>
                    nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
                  case play.api.mvc.Cookie.SameSite.Strict =>
                    nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
                  case play.api.mvc.Cookie.SameSite.Lax    =>
                    nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
                }
                cookies = cookies :+ nettyCookie
              }
            }
            cookies.map(cookie =>
              response
                .headers()
                .set(
                  HttpHeaderNames.SET_COOKIE,
                  io.netty.handler.codec.http.cookie.ServerCookieEncoder.LAX.encode(cookie)
                )
            )
            result.body.contentLength.foreach(l => response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, l.toInt))
            result.body.contentType.foreach(l => response.headers().set(HttpHeaderNames.CONTENT_TYPE, l))
            if (keepAlive) {
              if (!req.protocolVersion().isKeepAliveDefault) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
              }
            } else {
              response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            }
            result.body match {
              case HttpEntity.NoEntity             =>
                ctx.writeAndFlush(response).applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                ReferenceCountUtil.release(msg)
                ().vfuture
              case HttpEntity.Strict(_, _)         =>
                ctx.writeAndFlush(response).applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                ReferenceCountUtil.release(msg)
                ().vfuture
              case e @ HttpEntity.Chunked(_, _)    => {
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                ctx.write(response)
                e.dataStream
                  .runWith(Sink.foreach { chunk =>
                    // val payload = ByteString(s"${Integer.toHexString(chunk.size)}\r\n") ++ chunk ++ ByteString("\r\n")
                    ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(chunk.toArray)), ctx.newProgressivePromise())
                  })
                  .andThen { case _ =>
                    ctx
                      .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                      .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                    ReferenceCountUtil.release(msg)
                    ().vfuture
                  }
                  .map(_ => ())
              }
              case HttpEntity.Streamed(data, _, _) => {
                ctx.write(response)
                data
                  .runWith(Sink.foreach { chunk =>
                    ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(chunk.toArray)), ctx.newProgressivePromise())
                  })
                  .andThen { case e =>
                    ctx
                      .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                      .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                    ReferenceCountUtil.release(msg)
                    ().vfuture
                  }
                  .map(_ => ())
              }
            }
          }
          .andThen {
            case Failure(exception) => {
              logger.error("error while handling http3 request", exception)
              directResponse(ctx, msg, HttpResponseStatus.INTERNAL_SERVER_ERROR, ERROR.retainedDuplicate())
            }
          }
      }
      case _ => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_ESSENTIAL_ACTION.retainedDuplicate())
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    msg match {
      case _req: FullHttpRequest       => {
        request = _req
        if (HttpUtil.is100ContinueExpected(request)) {
          send100Continue(ctx)
          Option(subscriptionRef.get()).foreach(_.complete())
          ReferenceCountUtil.release(msg)
        } else {
          keepAlive = HttpUtil.isKeepAlive(request)
          if (msg.isInstanceOf[HttpContent]) {
            val contentMsg = msg.asInstanceOf[HttpContent]
            val content    = contentMsg.content()
            if (content.isReadable()) {
              appendToBody(content.readContentAsByteString())
            }
            if (msg.isInstanceOf[LastHttpContent]) {
              Option(subscriptionRef.get()).foreach(_.complete())
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
            Option(subscriptionRef.get()).foreach(_.complete())
            runOtoroshiRequest(request, keepAlive, ctx, msg)
          }
        }
      }
      case _req: HttpRequest           => {
        request = _req
        if (HttpUtil.is100ContinueExpected(request)) {
          send100Continue(ctx)
          ReferenceCountUtil.release(msg)
        } else {
          keepAlive = HttpUtil.isKeepAlive(request)
          if (msg.isInstanceOf[HttpContent]) {
            val contentMsg = msg.asInstanceOf[HttpContent]
            val content    = contentMsg.content()
            if (content.isReadable()) {
              appendToBody(content.readContentAsByteString())
            }
            if (msg.isInstanceOf[LastHttpContent]) {
              Option(subscriptionRef.get()).foreach(_.complete())
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
            Option(subscriptionRef.get()).foreach(_.complete())
            runOtoroshiRequest(request, keepAlive, ctx, msg)
          }
        }
      }
      case contentMsg: LastHttpContent => {
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(content.readContentAsByteString())
        }
        Option(subscriptionRef.get()).foreach(_.complete())
        runOtoroshiRequest(request, keepAlive, ctx, msg)
      }
      case contentMsg: HttpContent     => {
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(content.readContentAsByteString())
        }
      }
      case _ => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_HANDLED.retainedDuplicate())
    }
  }
}

class NettyHttp3Server(config: ReactorNettyServerConfig, env: Env) {

  private val logger = Logger("otoroshi-experimental-netty-http3-server")
  private val cache  = Scaffeine().maximumSize(1000).expireAfterWrite(5.seconds).build[String, QuicSslContext]

  def start(
      handler: HttpRequestHandler,
      sessionCookieBaker: SessionCookieBaker,
      flashCookieBaker: FlashCookieBaker
  ): Unit = {

    if (config.http3.enabled) {

      import io.netty.bootstrap._
      import io.netty.channel._
      import io.netty.channel.socket.nio._
      import io.netty.incubator.codec.http3.{Http3, Http3FrameToHttpObjectCodec, Http3ServerConnectionHandler}
      import io.netty.incubator.codec.quic.{InsecureQuicTokenHandler, QuicChannel, QuicStreamChannel}

      import java.util.concurrent.TimeUnit

      val cert       = new SelfSignedCertificate()
      val fakeCtx    = QuicSslContextBuilder
        .forServer(cert.key(), null, cert.cert())
        .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
        .earlyData(true)
        .build()
      val sslContext = QuicSslContextBuilder.buildForServerWithSni(new Mapping[String, QuicSslContext] {
        override def map(domain: String): QuicSslContext = {
          if (logger.isDebugEnabled) logger.debug(s"sni domain: ${domain}")
          if (domain == null) {
            env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env).tlsSettings.defaultDomain match {
              case None => fakeCtx
              case Some(dom) => this.map(dom)
            }
          } else {
            cache.get(
              domain,
              _ => {
                val (validCerts, byDomain) =
                  DynamicKeyManager.validCertificatesByDomains(env.proxyState.allCertificates())
                DynamicKeyManager.getServerCertificateForDomain(domain, validCerts, byDomain, env, logger) match {
                  case None => fakeCtx
                  case Some(cert) => {
                    val keypair = cert.cryptoKeyPair
                    val chain = cert.certificatesChain
                    if (logger.isDebugEnabled) logger.debug(s"for domain: ${domain}, found ${cert.name} / ${cert.id}")
                    QuicSslContextBuilder
                      .forServer(keypair.getPrivate, cert.password.orNull, chain: _*)
                      .clientAuth(config.clientAuth match {
                        case otoroshi.ssl.ClientAuth.None => io.netty.handler.ssl.ClientAuth.NONE
                        case otoroshi.ssl.ClientAuth.Want => io.netty.handler.ssl.ClientAuth.OPTIONAL
                        case otoroshi.ssl.ClientAuth.Need => io.netty.handler.ssl.ClientAuth.REQUIRE
                      })
                      .trustManager(DynamicSSLEngineProvider.currentServerTrustManager)
                      .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
                      .earlyData(true)
                      .build()
                  }
                }
              }
            )
          }
        }
      })

      val codec = Http3.newQuicServerCodecBuilder
        //.option(QuicChannelOption.UDP_SEGMENTS, 10)
        .sslContext(sslContext)
        .maxIdleTimeout(config.idleTimeout.toMillis, TimeUnit.MILLISECONDS)
        .maxSendUdpPayloadSize(config.http3.maxSendUdpPayloadSize)
        .maxRecvUdpPayloadSize(config.http3.maxRecvUdpPayloadSize)
        .initialMaxData(config.http3.initialMaxData)
        .initialMaxStreamDataBidirectionalLocal(
          config.http3.initialMaxStreamDataBidirectionalLocal
        )
        .initialMaxStreamDataBidirectionalRemote(
          config.http3.initialMaxStreamDataBidirectionalRemote
        )
        .initialMaxStreamsBidirectional(config.http3.initialMaxStreamsBidirectional)
        .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
        .handler(new ChannelInitializer[QuicChannel]() {
          override def initChannel(ch: QuicChannel): Unit = {
            ch.pipeline()
              .addLast(new ChannelInboundHandlerAdapter() {
                @Override
                override def channelInactive(ctx: ChannelHandlerContext): Unit = {}
              })
            ch.pipeline()
              .addLast(new Http3ServerConnectionHandler(new ChannelInitializer[QuicStreamChannel]() {
                // Called for each request-stream,
                override def initChannel(ch: QuicStreamChannel): Unit = {
                  ch.pipeline().addLast(new Http3FrameToHttpObjectCodec(true))
                  if (config.accessLog) ch.pipeline().addLast(new AccessLogHandler())
                  ch.pipeline()
                    .addLast(new Http1RequestHandler(handler, sessionCookieBaker, flashCookieBaker, env, logger))
                }
              }))
          }
        })
        .build()
      val group = new NioEventLoopGroup(config.nThread)
      val bs      = new Bootstrap()
      val channel = bs
        .group(group)
        .channel(classOf[NioDatagramChannel])
        .handler(codec)
        .bind(config.host, config.http3.port)
        .sync()
        .channel()
      channel.closeFuture()
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        group.shutdownGracefully();
      }))
    }
  }
}

case class Http3Response(status: Int, headers: Map[String, Seq[String]], body: Option[ByteString])

class NettySniSslContext(sslContext: QuicSslContext, host: String, port: Int) extends QuicSslContext {
  override def newEngine(alloc: ByteBufAllocator): QuicSslEngine = sslContext.newEngine(alloc, host, port)
  override def newEngine(alloc: ByteBufAllocator, peerHost: String, peerPort: Int): QuicSslEngine = sslContext.newEngine(alloc, peerHost, peerPort)
  override def isClient: Boolean = sslContext.isClient
  override def cipherSuites(): util.List[String] = sslContext.cipherSuites()
  override def applicationProtocolNegotiator(): ApplicationProtocolNegotiator = sslContext.applicationProtocolNegotiator()
  override def sessionContext(): SSLSessionContext = sslContext.sessionContext()
}

case class NettyHttp3ClientBody(source: Flux[ByteString], contentType: Option[String], contentLength: Option[Long])


class NettyHttp3Client(val env: Env) {

  private val group = new NioEventLoopGroup(Runtime.getRuntime.availableProcessors() + 1)
  private val bs = new Bootstrap()

  private val codecs = new TrieMap[String, ChannelHandler]()
  private val channels = new TrieMap[String, Future[Channel]]()
  private val quicChannels = new TrieMap[String, Future[QuicChannel]]()

  private[netty] def codecFor(context: QuicSslContext, host: String, port: Int): ChannelHandler = {
    val key = s"${host}:${port}" // TODO: check if ok
    codecs.getOrElseUpdate(key, {
      val codec = Http3.newQuicClientCodecBuilder()
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
    })
  }

  private[netty] def getChannel(codec: ChannelHandler, host: String, port: Int): Future[Channel] = {
    val key = s"${host}:${port}" // TODO: check if ok
    channels.getOrElseUpdate(key, {
      val promise = Promise.apply[Channel]()
      val future = bs
        .group(group)
        .channel(classOf[NioDatagramChannel])
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
    })
  }

  private[netty] def getQuicChannel(channel: Channel, host: String, port: Int): Future[QuicChannel] = {
    // TODO: cache
    val address: InetSocketAddress = new InetSocketAddress(host, port)
    val promise = Promise.apply[QuicChannel]()
    val future = QuicChannel
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

  private[netty] def getSslContext(): QuicSslContext = {
    val context = QuicSslContextBuilder
      .forClient()
      .trustManager(InsecureTrustManagerFactory.INSTANCE)
      .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
      .earlyData(true)
      .build()
    context
  }

  // TODO: fix and use
  private[netty] def getSslContextFrom(tlsSettings: MtlsConfig): QuicSslContext = {
    val context = QuicSslContextBuilder
      .forClient()
      .trustManager(InsecureTrustManagerFactory.INSTANCE)
      .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
      .earlyData(true)
      .build()
    context
  }

  private[netty] def http3Channel(quicChannel: QuicChannel, promise: Promise[Http3Response]): Future[QuicStreamChannel] = {
    val finalPromise = Promise.apply[QuicStreamChannel]()
    val future = Http3.newRequestStream(quicChannel,
      new Http3RequestStreamInboundHandler() {

        var headers: Map[String, Seq[String]] = Map.empty
        var body: ByteString = ByteString.empty
        var status: Int = 0

        override def channelActive(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channel active")
        }

        override def channelInactive(ctx: ChannelHandlerContext): Unit = {
          if (logger.isDebugEnabled) logger.debug("channel inactive")
        }

        override def handleHttp3Exception(ctx: ChannelHandlerContext, exception: Http3Exception): Unit = {
          if (logger.isDebugEnabled) logger.debug("handleHttp3Exception")
          exception.printStackTrace()
          promise.tryFailure(exception)
        }

        override def handleQuicException(ctx: ChannelHandlerContext, exception: QuicException): Unit = {
          if (logger.isDebugEnabled) logger.debug("handleQuicException")
          exception.printStackTrace()
          promise.tryFailure(exception)
        }

        override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame, isLast: Boolean): Unit = {
          if (logger.isDebugEnabled) logger.debug(s"got header frame !!!! ${isLast}")
          status = frame.headers().status().toString.toInt
          headers = frame.headers().names().asScala.map(name => (name.toString, frame.headers().getAll(name).asScala.map(_.toString))).toMap
          releaseFrameAndCloseIfLast(ctx, frame, isLast)
        }

        override def channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame, isLast: Boolean): Unit = {
          val content = frame.content().toString(CharsetUtil.US_ASCII)
          if (logger.isDebugEnabled) logger.debug(s"got data frame !!! - ${isLast}")
          body = body ++ ByteString(content)
          releaseFrameAndCloseIfLast(ctx, frame, isLast)
        }

        private def releaseFrameAndCloseIfLast(ctx: ChannelHandlerContext, frame: Http3RequestStreamFrame, isLast: Boolean) {
          ReferenceCountUtil.release(frame)
          if (isLast) {
            promise.trySuccess(Http3Response(status, headers, if (body.isEmpty) None else body.some))
            ctx.close()
          }
        }
      })
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
    channels.toSeq.map(_._2).map(_.map(c => c.close().sync())(env.otoroshiExecutionContext))
    group.shutdownGracefully()
  }

  def url(rawUrl: String): NettyHttp3ClientWsRequest = NettyHttp3ClientWsRequest(this, rawUrl)
}

case class NettyHttp3ClientStrictWsResponse(resp: NettyHttp3ClientWsResponse, bodyAsBytes: ByteString) extends WSResponse {

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

  //def trailingHeaders(): Future[Map[String, Seq[String]]]                       = resp.trailingHeaders()
  //def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit = resp.registerTrailingHeaders(promise)
}

case class NettyHttp3ClientWsResponse(resp: Http3Response, _uri: Uri, env: Env) extends WSResponse {

  private lazy val _body: Source[ByteString, _] = resp.body match {
    case None => Source.empty
    case Some(b) => Source.single(b) // Source.fromPublisher(b).filter(_.nonEmpty)
  }
  private lazy val _bodyAsBytes: ByteString = {
    Await.result(
      bodyAsSource.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer),
      FiniteDuration(10, TimeUnit.MINUTES)
    ) // AWAIT: valid
  }
  private lazy val _allHeaders: Map[String, Seq[String]] = resp.headers.map {
    case (name, values) => (name.replace(":", ""), values)
  }
  private lazy val _bodyAsString: String   = _bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem        = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue    = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie] = {
    resp.headers.get("set-cookie").map { values =>
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
    }.getOrElse(Seq.empty)
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

  // def trailingHeaders(): Future[Map[String, Seq[String]]] = {
  //   ReactiveStreamUtils.MonoUtils
  //     .toFuture(resp.trailerHeaders())
  //     .map { headers =>
  //       headers
  //         .names()
  //         .asScala
  //         .map { name =>
  //           (name, headers.getAll(name).asScala.toSeq)
  //         }
  //         .toMap
  //     }(env.otoroshiExecutionContext)
  // }

  // def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit = {
  //   ReactiveStreamUtils.MonoUtils
  //     .toFuture(resp.trailerHeaders())
  //     .map { headers =>
  //       headers
  //         .names()
  //         .asScala
  //         .map { name =>
  //           (name, headers.getAll(name).asScala.toSeq)
  //         }
  //         .toMap
  //     }(env.otoroshiExecutionContext)
  //     .andThen {
  //       case Failure(ex)      => promise.tryFailure(ex)
  //       case Success(headers) => promise.trySuccess(headers)
  //     }(env.otoroshiExecutionContext)
  // }
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
  clientConfig: ClientConfig = ClientConfig(),
) extends WSRequest {

  private val _uri = Uri(_url)

  def withProtocol(proto: String): NettyHttp3ClientWsRequest                                                  = copy(protocol = proto.some)
  def withTlsConfig(tlsConfig: MtlsConfig): NettyHttp3ClientWsRequest                                         = copy(tlsConfig = tlsConfig.some)
  def withTarget(target: Target): NettyHttp3ClientWsRequest                                                   = copy(targetOpt = target.some)
  def withClientConfig(clientConfig: ClientConfig): NettyHttp3ClientWsRequest                                 = copy(clientConfig = clientConfig)
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
    val uri = new URI(url)
    val thePort = uri.getPort match {
      case -1 => uri.getScheme match {
        case "https" => 443
        case "http" => 80
        case _ => 80
      }
      case v => v
    }
    val promise = Promise.apply[Http3Response]()
    val context = client.getSslContext()
    val codec = client.codecFor(context, uri.getHost, thePort)
    (for {
      channel <- client.getChannel(codec, uri.getHost, thePort)
      quicChannel <- client.getQuicChannel(channel, uri.getHost, thePort)
      streamChannel <- client.http3Channel(quicChannel, promise)
    } yield {
      if (logger.isDebugEnabled) logger.debug("building header frame !")
      val hasBody = body match {
        case EmptyBody => false
        case _ => true
      }
      val frame = new DefaultHttp3HeadersFrame()
      frame.headers()
        .method(method)
        .path(uri.getRawPath)
        .authority(uri.getHost)
        .scheme(uri.getScheme)
        .applyOn { heads =>
          headers.map {
            case (name, values) => heads.add(name.toLowerCase(), values.asJava)
          }
          heads
        }
        .add("content-length", "0")
      if (hasBody) {
        frame.headers().method(method)
        headers.map {
          case (name, values) => frame.headers().add(name.toLowerCase(), values.asJava)
        }
        frame.headers().remove("content-length")
        realContentType.foreach(ct => frame.headers().add("content-type", ct.value))
        realContentLength.foreach(cl => frame.headers().add("content-length", cl.toString))
        if (logger.isDebugEnabled) logger.debug(s"sending header frame: ${frame}")
        streamChannel.write(frame)
        if (logger.isDebugEnabled) logger.debug("sending body chunks")
        val bodySource: Flux[ByteString] = body match {
          case EmptyBody => Flux.empty[ByteString]
          case InMemoryBody(bs) => Flux.just(Seq(bs): _*)
          case SourceBody(source) => Flux.from(source.runWith(Sink.asPublisher(false))(client.env.otoroshiMaterializer))
        }
        bodySource
          .doOnNext(chunk => {
            streamChannel.write(new DefaultHttp3DataFrame(Unpooled.copiedBuffer(chunk.toArray)))
          })
          .doOnComplete(() => {
            if (logger.isDebugEnabled) logger.debug("body send complete !")
            streamChannel.shutdownOutput()
          })
          .subscribe()
      } else {
        if (logger.isDebugEnabled) logger.debug(s"sending header frame: ${frame}")
        streamChannel
          .writeAndFlush(frame)
          .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
      }
      promise.future.andThen {
        case _ => {
          streamChannel.closeFuture().sync()
          quicChannel.close().sync()
          // TODO: ?????
          // channel.close().sync()
          // group.shutdownGracefully()
        }
      }
    }).flatMap(a => a.map(f => NettyHttp3ClientWsResponse(f, _uri, client.env)))
  }
}

object NettyHttp3Client {

  import io.netty.bootstrap._
  import io.netty.channel._
  import io.netty.incubator.codec.http3._
  import io.netty.incubator.codec.quic._

  val logger = Logger("otoroshi-experimental-netty-http3-client")

  def getUrl(method: String, url: String, headers: Map[String, Seq[String]], body: Option[NettyHttp3ClientBody])(implicit ec: ExecutionContext): Future[Http3Response] = {
    try {
      val uri = new URI(url)
      val thePort = uri.getPort match {
        case -1 => uri.getScheme match {
          case "https" => 443
          case "http" => 80
          case _ => 80
        }
        case v => v
      }
      if (logger.isDebugEnabled) logger.debug(Json.obj(
        "port" -> uri.getPort,
        "the_port" -> thePort,
        "host" -> uri.getHost,
        "scheme" -> uri.getScheme,
        "raw_scheme_specific_part" -> uri.getRawSchemeSpecificPart,
        "path" -> uri.getPath,
        "raw_path" -> uri.getRawPath,
        "authority" -> uri.getAuthority,
        "raw_authority" -> uri.getRawAuthority,
        "fragment" -> uri.getFragment,
        "raw_fragment" -> uri.getRawFragment,
        "query" -> uri.getQuery,
        "raw_query" -> uri.getRawQuery,
        "user_info" -> uri.getUserInfo,
        "raw_user_info" -> uri.getRawUserInfo,
        "is_absolute" -> uri.isAbsolute,
        "is_opaque" -> uri.isOpaque,
      ).prettify)

      val promise = Promise.apply[Http3Response]()
      val group = new NioEventLoopGroup(1)
      val context = QuicSslContextBuilder
        .forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
        .earlyData(true)
        .build()
      val codec = Http3.newQuicClientCodecBuilder()
        .sslContext(new NettySniSslContext(context, uri.getHost, thePort))
        .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
        .initialMaxData(10000000)
        .initialMaxStreamDataBidirectionalLocal(1000000)
        .initialMaxStreamDataBidirectionalRemote(1000000)
        .initialMaxStreamsBidirectional(100000)
        .maxSendUdpPayloadSize(1500)
        .maxRecvUdpPayloadSize(1500)
        .build()
      val bs = new Bootstrap()
      val channel = bs
        .group(group)
        .channel(classOf[NioDatagramChannel])
        .handler(codec)
        .bind(0)
        .sync()
        .channel() // TODO: async !!!
      if (logger.isDebugEnabled) logger.debug("opening quic channel !")
      val address = new InetSocketAddress(uri.getHost, thePort)
      val quicChannel = QuicChannel
        .newBootstrap(channel)
        .handler(new Http3ClientConnectionHandler())
        .remoteAddress(address)
        .connect()
        .get()
      if (logger.isDebugEnabled) logger.debug("setup request stream !")
      val streamChannel = Http3.newRequestStream(quicChannel,
        new Http3RequestStreamInboundHandler() {

          var headers: Map[String, Seq[String]] = Map.empty
          var body: ByteString = ByteString.empty
          var status: Int = 0

          override def channelActive(ctx: ChannelHandlerContext): Unit = {
            if (logger.isDebugEnabled) logger.debug("channel active")
          }

          override def channelInactive(ctx: ChannelHandlerContext): Unit = {
            if (logger.isDebugEnabled) logger.debug("channel inactive")
          }

          override def handleHttp3Exception(ctx: ChannelHandlerContext, exception: Http3Exception): Unit = {
            if (logger.isDebugEnabled) logger.debug("handleHttp3Exception")
            exception.printStackTrace()
            promise.tryFailure(exception)
          }

          override def handleQuicException(ctx: ChannelHandlerContext, exception: QuicException): Unit = {
            if (logger.isDebugEnabled) logger.debug("handleQuicException")
            exception.printStackTrace()
            promise.tryFailure(exception)
          }

          override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame, isLast: Boolean): Unit = {
            if (logger.isDebugEnabled) logger.debug(s"got header frame !!!! ${isLast}")
            status = frame.headers().status().toString.toInt
            headers = frame.headers().names().asScala.map(name => (name.toString, frame.headers().getAll(name).asScala.map(_.toString))).toMap
            releaseFrameAndCloseIfLast(ctx, frame, isLast)
          }

          override def channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame, isLast: Boolean): Unit = {
            val content = frame.content().toString(CharsetUtil.US_ASCII)
            if (logger.isDebugEnabled) logger.debug(s"got data frame !!! - ${isLast}")
            body = body ++ ByteString(content)
            releaseFrameAndCloseIfLast(ctx, frame, isLast)
          }

          private def releaseFrameAndCloseIfLast(ctx: ChannelHandlerContext, frame: Http3RequestStreamFrame, isLast: Boolean) {
            ReferenceCountUtil.release(frame)
            if (isLast) {
              promise.trySuccess(Http3Response(status, headers, if (body.isEmpty) None else body.some))
              ctx.close()
            }
          }
        }
      ).sync().getNow()

      if (logger.isDebugEnabled) logger.debug("building header frame !")
      val hasBody = body.isDefined
      val frame = new DefaultHttp3HeadersFrame()
      frame.headers()
        .method(method)
        .path(uri.getRawPath)
        .authority(uri.getHost)
        .scheme(uri.getScheme)
        .applyOn { heads =>
          headers.map {
            case (name, values) => heads.add(name, values.asJava)
          }
          heads
        }
        .add("content-length", "0")
      if (hasBody) {
        val theBody = body.get
        frame.headers().method(method) // "POST")
        headers.map {
          case (name, values) => frame.headers().add(name, values.asJava)
        }
        frame.headers().remove("content-length")
        theBody.contentType.foreach(ct => frame.headers().add("content-type", ct))
        theBody.contentLength.foreach(cl => frame.headers().add("content-length", cl.toString))
        if (logger.isDebugEnabled) logger.debug(s"sending header frame: ${frame}")
        streamChannel.write(frame)
        if (logger.isDebugEnabled) logger.debug("sending body chunks")
        theBody.source
          .doOnNext(chunk => {
            streamChannel.write(new DefaultHttp3DataFrame(Unpooled.copiedBuffer(chunk.toArray)))
          })
          .doOnComplete(() => {
            if (logger.isDebugEnabled) logger.debug("body send complete !")
            streamChannel.shutdownOutput()
          })
          .subscribe()
      } else {
        if (logger.isDebugEnabled) logger.debug(s"sending header frame: ${frame}")
        streamChannel
          .writeAndFlush(frame)
          .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
      }

      promise.future.andThen {
        case _ => {
          streamChannel.closeFuture().sync()
          quicChannel.close().sync()
          channel.close().sync()
          group.shutdownGracefully()
        }
      }
    } catch {
      case e: Throwable =>
        e.printStackTrace()
        Http3Response(500, Map.empty, e.getMessage.byteString.some).vfuture
    }
  }
}
