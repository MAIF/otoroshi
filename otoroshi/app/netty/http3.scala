package otoroshi.netty

import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http._
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.incubator.codec.quic.{QuicSslContext, QuicSslContextBuilder}
import io.netty.util.{CharsetUtil, Mapping, ReferenceCountUtil}
import otoroshi.env.Env
import otoroshi.ssl.{DynamicKeyManager, DynamicSSLEngineProvider}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpEntity, HttpRequestHandler}
import play.api.libs.json.Json
import play.api.mvc.{EssentialAction, FlashCookieBaker, SessionCookieBaker}
import reactor.core.publisher.{Flux, FluxSink}

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import scala.concurrent.duration.DurationInt
import scala.util.Failure

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
    // TODO: handle trailer headers
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
    val otoReq             = new NettyRequest(req, ctx, bodyPublisher, true, session, sessionCookieBaker, flashCookieBaker)
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
                  .andThen { case _ =>
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
      case _                  => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_ESSENTIAL_ACTION.retainedDuplicate())
    }
  }

  // override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
  //   println("userEventTriggered1", evt)
  // }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    msg match {
      case _req: FullHttpRequest       => {
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
              appendToBody(ByteString(content.array()))
            }
            if (msg.isInstanceOf[LastHttpContent]) {
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
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
              appendToBody(ByteString(content.array()))
            }
            if (msg.isInstanceOf[LastHttpContent]) {
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
            runOtoroshiRequest(request, keepAlive, ctx, msg)
          }
        }
      }
      case contentMsg: LastHttpContent => {
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(ByteString(content.array()))
        }
        runOtoroshiRequest(request, keepAlive, ctx, msg)
      }
      case contentMsg: HttpContent     => {
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(ByteString(content.array()))
        }
      }
      case _                           => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_HANDLED.retainedDuplicate())
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
          cache.get(
            domain,
            _ => {
              val (validCerts, byDomain) =
                DynamicKeyManager.validCertificatesByDomains(env.proxyState.allCertificates())
              DynamicKeyManager.getServerCertificateForDomain(domain, validCerts, byDomain, env, logger) match {
                case None       => fakeCtx
                case Some(cert) => {
                  val keypair = cert.cryptoKeyPair
                  val chain   = cert.certificatesChain
                  logger.debug(s"for domain: ${domain}, found ${cert.name} / ${cert.id}")
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
      })

      val codec = Http3.newQuicServerCodecBuilder
        //.option(QuicChannelOption.UDP_SEGMENTS, 10)
        .sslContext(sslContext)
        .maxIdleTimeout(config.idleTimeout.toMillis, TimeUnit.MILLISECONDS)
        .maxSendUdpPayloadSize(config.http3.maxSendUdpPayloadSize)                   // TODO: from config
        .maxRecvUdpPayloadSize(config.http3.maxRecvUdpPayloadSize)                   // TODO: from config
        .initialMaxData(config.http3.initialMaxData)                                 // TODO: from config
        .initialMaxStreamDataBidirectionalLocal(
          config.http3.initialMaxStreamDataBidirectionalLocal
        )                                                                            // TODO: from config
        .initialMaxStreamDataBidirectionalRemote(
          config.http3.initialMaxStreamDataBidirectionalRemote
        )                                                                            // TODO: from config
        .initialMaxStreamsBidirectional(config.http3.initialMaxStreamsBidirectional) // TODO: from config
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
