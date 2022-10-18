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
import otoroshi.netty.ImplicitUtils._
import otoroshi.ssl.{DynamicKeyManager, DynamicSSLEngineProvider}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpEntity, HttpRequestHandler}
import play.api.libs.json.Json
import play.api.mvc.{EssentialAction, FlashCookieBaker, SessionCookieBaker}
import reactor.core.publisher.{Flux, Sinks}

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
  private val hotSource = Sinks.many().unicast().onBackpressureBuffer[ByteString]()
  private val hotFlux = hotSource.asFlux()

  private def send100Continue(ctx: ChannelHandlerContext): Unit = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER)
    ctx.write(response)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
    ctx.close()
    hotSource.tryEmitError(cause)
  }

  private def directResponse(ctx: ChannelHandlerContext, msg: Any, status: HttpResponseStatus, body: ByteBuf): Unit = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes())
    ctx.writeAndFlush(response)
    ReferenceCountUtil.release(msg)
  }

  private def appendToBody(chunk: ByteString): Unit = {
    hotSource.tryEmitNext(chunk)
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
    val bodyIn: Flux[ByteString] = if (hasBody) hotFlux else Flux.empty()
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
            response.headers().remove("status").remove("Status")
            // logger.debug("write http3 response")
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
          hotSource.tryEmitComplete()
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
              hotSource.tryEmitComplete()
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
            hotSource.tryEmitComplete()
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
              hotSource.tryEmitComplete()
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            }
          } else if (msg.isInstanceOf[LastHttpContent]) {
            hotSource.tryEmitComplete()
            runOtoroshiRequest(request, keepAlive, ctx, msg)
          }
        }
      }
      case contentMsg: LastHttpContent => {
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(content.readContentAsByteString())
        }
        hotSource.tryEmitComplete()
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
      import io.netty.incubator.codec.http3.{Http3, Http3ServerConnectionHandler}
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
                    // logger.debug(s"found cert for domain: ${domain}: ${cert.name}")
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
                override def channelInactive(ctx: ChannelHandlerContext): Unit = ctx.fireChannelInactive()
              })
            ch.pipeline()
              .addLast(new Http3ServerConnectionHandler(new ChannelInitializer[QuicStreamChannel]() {
                override def initChannel(ch: QuicStreamChannel): Unit = {
                  ch.pipeline().addLast(new io.netty.incubator.codec.http3.Http3FrameToHttpObjectCodec(true))
                  if (config.accessLog) ch.pipeline().addLast(new AccessLogHandler())
                  ch.pipeline()
                    .addLast(new Http1RequestHandler(handler, sessionCookieBaker, flashCookieBaker, env, logger))
                }
              }, null, null, null, config.http3.disableQpackDynamicTable))
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
