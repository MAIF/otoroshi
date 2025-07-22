package otoroshi.netty

import com.github.blemale.scaffeine.Scaffeine
import io.netty.bootstrap._
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio._
import io.netty.handler.codec.http._
import io.netty.incubator.codec.http3.{Http3, Http3ServerConnectionHandler}
import io.netty.incubator.codec.quic._
import io.netty.util.{CharsetUtil, ReferenceCountUtil}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.netty.ImplicitUtils._
import otoroshi.ssl.{DynamicKeyManager, DynamicSSLEngineProvider}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpEntity, HttpRequestHandler}
import play.api.libs.json.Json
import play.api.mvc.{EssentialAction, FlashCookieBaker, SessionCookieBaker}
import reactor.core.publisher.{Flux, Sinks}

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator, Security}
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Try}

class Http1RequestHandler(
    handler: HttpRequestHandler,
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker,
    env: Env,
    logger: Logger,
    addressGet: () => String,
    config: ReactorNettyServerConfig
) extends ChannelInboundHandlerAdapter {

  private implicit val ec: ExecutionContext = env.otoroshiExecutionContext
  private implicit val mat: Materializer = env.otoroshiMaterializer

  private val NOT_HANDLED          =
    Unpooled.wrappedBuffer(s"${Json.obj("error" -> "not handled")}\r\n".getBytes(CharsetUtil.US_ASCII))
  private val NOT_ESSENTIAL_ACTION =
    Unpooled.wrappedBuffer(s"${Json.obj("error" -> "not essential action")}\r\n".getBytes(CharsetUtil.US_ASCII))
  private val ERROR                = Unpooled.wrappedBuffer(s"${Json.obj("error" -> "error")}\r\n".getBytes(CharsetUtil.US_ASCII))

  private var keepAlive            = false
  private var request: HttpRequest = _
  private val hotSource            = Sinks.many().unicast().onBackpressureBuffer[ByteString]()
  private val hotFlux              = hotSource.asFlux()

  private var log_method: String      = "NONE"
  private var log_status: Int         = 0
  private var log_uri: String         = "NONE"
  private var log_start: Long         = 0L
  private var log_contentLength: Long = 0L
  private var log_protocol: String    = "-"

  private def send100Continue(ctx: ChannelHandlerContext): Unit = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER)
    ctx.write(response)
  }

  @SuppressWarnings(Array("deprecation")) // todo needs to be removed when the underlying method is also removed
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
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
    val session                  = ctx.channel() match {
      case c: io.netty.incubator.codec.quic.QuicChannel       =>
        Option(c)
          .flatMap(p => Option(p.sslEngine()))
          .flatMap(p => Option(p.getSession))
          .orElse(
            Option(c)
              .flatMap(p => Option(p.sslEngine()))
              .flatMap(p => Option(p.getHandshakeSession))
          )
      case c: io.netty.incubator.codec.quic.QuicStreamChannel =>
        Option(c.parent())
          .flatMap(p => Option(p.sslEngine()))
          .flatMap(p => Option(p.getSession))
          .orElse(
            Option(c.parent())
              .flatMap(p => Option(p.sslEngine()))
              .flatMap(p => Option(p.getHandshakeSession))
          )
      case _                                                  => None
    }
    log_protocol = session.map(_.getProtocol).flatMap(TlsVersion.parseSafe).map(_.name).getOrElse("-")
    val rawOtoReq                =
      new NettyRequest(
        config.id,
        req,
        ctx,
        Flux.empty(),
        true,
        session,
        sessionCookieBaker,
        flashCookieBaker,
        addressGet
      )
    val hasBody                  = otoroshi.utils.body.BodyUtils.hasBodyWithoutOrZeroLength(rawOtoReq)._1
    val bodyIn: Flux[ByteString] = if (hasBody) hotFlux else Flux.empty()
    val otoReq                   = rawOtoReq.withBody(bodyIn)
    val (nreq, reqHandler)       = handler.handlerForRequest(otoReq)
    reqHandler match {
      case a: EssentialAction =>
        a.apply(nreq)
          .run(otoReq.body)
          .flatMap { result =>
            log_status = result.header.status
            log_contentLength = result.header.headers.getIgnoreCase("Content-Length").map(_.toLong).getOrElse(-1L)
            val response            = result.body match {
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
            val trailerHeadersIdOpt = result.header.headers.get("otoroshi-netty-trailers")
            val hasTrailer          = trailerHeadersIdOpt.isDefined
            result.header.headers.foreach { case (key, value) =>
              if (key != "otoroshi-netty-trailers") response.headers().set(key, value)
            }
            if (result.body.contentType.contains("application/grpc")) {
              response.headers().add(HttpHeaderNames.TRAILER, "grpc-status, grpc-message")
            }
            var cookies             = Seq.empty[io.netty.handler.codec.http.cookie.Cookie]
            if (result.newSession.nonEmpty) {
              result.newSession.foreach { session =>
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
              case HttpEntity.NoEntity if hasTrailer             =>
                trailerHeadersIdOpt.foreach { trailersId =>
                  otoroshi.netty.NettyRequestAwaitingTrailers.get(trailersId).foreach {
                    case Left(future)    =>
                      // if (logger.isWarnEnabled) logger.warn(s"Unable to get trailer header for request '${trailersId}'")
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val start    = System.nanoTime()
                      val trailers = Await.result(future, 10.seconds)
                      if (logger.isWarnEnabled)
                        logger.warn(
                          s"Blocked thread for ${(System.nanoTime() - start).nanos.toHumanReadable} to get trailer header for request '$trailersId'"
                        )
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val frtr     = response.asInstanceOf[DefaultFullHttpResponse].trailingHeaders()
                      trailers.foreach { case (name, values) =>
                        try {
                          frtr.add(name, values.asJava)
                        } catch {
                          case e: Throwable =>
                            if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                        }
                      }
                      ctx
                        .writeAndFlush(response)
                        .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                      ReferenceCountUtil.release(msg)
                    case Right(trailers) =>
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val frtr = response.asInstanceOf[DefaultFullHttpResponse].trailingHeaders()
                      trailers.foreach { case (name, values) =>
                        try {
                          frtr.add(name, values.asJava)
                        } catch {
                          case e: Throwable =>
                            if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                        }
                      }
                      ctx
                        .writeAndFlush(response)
                        .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                      ReferenceCountUtil.release(msg)
                  }
                }
                ().vfuture
              case HttpEntity.NoEntity                           =>
                ctx.writeAndFlush(response).applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                ReferenceCountUtil.release(msg)
                ().vfuture
              case HttpEntity.Strict(_, _) if hasTrailer         =>
                trailerHeadersIdOpt.foreach { trailersId =>
                  otoroshi.netty.NettyRequestAwaitingTrailers.get(trailersId).foreach {
                    case Left(future)    =>
                      // if (logger.isWarnEnabled) logger.warn(s"Unable to get trailer header for request '${trailersId}'")
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val start    = System.nanoTime()
                      val trailers = Await.result(future, 10.seconds)
                      if (logger.isWarnEnabled)
                        logger.warn(
                          s"Blocked thread for ${(System.nanoTime() - start).nanos.toHumanReadable} to get trailer header for request '$trailersId'"
                        )
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val frtr     = response.asInstanceOf[DefaultFullHttpResponse].trailingHeaders()
                      trailers.foreach { case (name, values) =>
                        try {
                          frtr.add(name, values.asJava)
                        } catch {
                          case e: Throwable =>
                            if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                        }
                      }
                      ctx
                        .writeAndFlush(response)
                        .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                      ReferenceCountUtil.release(msg)
                    case Right(trailers) =>
                      otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                      val frtr = response.asInstanceOf[DefaultFullHttpResponse].trailingHeaders()
                      trailers.foreach { case (name, values) =>
                        try {
                          frtr.add(name, values.asJava)
                        } catch {
                          case e: Throwable =>
                            if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                        }
                      }
                      ctx
                        .writeAndFlush(response)
                        .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                      ReferenceCountUtil.release(msg)
                  }
                }
                ().vfuture
              case HttpEntity.Strict(_, _)                       =>
                ctx.writeAndFlush(response).applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                ReferenceCountUtil.release(msg)
                ().vfuture
              case e @ HttpEntity.Chunked(_, _) if hasTrailer    =>
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                ctx.write(response)
                e.dataStream
                  .runWith(Sink.foreach { chunk =>
                    // val payload = ByteString(s"${Integer.toHexString(chunk.size)}\r\n") ++ chunk ++ ByteString("\r\n")
                    ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(chunk.toArray)), ctx.newProgressivePromise())
                  })
                  .map { _ =>
                    trailerHeadersIdOpt.foreach { trailersId =>
                      otoroshi.netty.NettyRequestAwaitingTrailers.get(trailersId).foreach {
                        case Left(future)    =>
                          // if (logger.isWarnEnabled) logger.warn(s"Unable to get trailer header for request '${trailersId}'")
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val start       = System.nanoTime()
                          val trailers    = Await.result(future, 10.seconds)
                          if (logger.isWarnEnabled)
                            logger.warn(
                              s"Blocked thread for ${(System.nanoTime() - start).nanos.toHumanReadable} to get trailer header for request '$trailersId'"
                            )
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val lastHeaders = new DefaultHttpHeaders()
                          trailers.foreach { case (name, values) =>
                            try {
                              lastHeaders.add(name, values.asJava)
                            } catch {
                              case e: Throwable =>
                                if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                            }
                          }
                          val lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, lastHeaders)
                          ctx
                            .writeAndFlush(lastContent)
                            .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                          ReferenceCountUtil.release(msg)
                        case Right(trailers) =>
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val lastHeaders = new DefaultHttpHeaders()
                          trailers.foreach { case (name, values) =>
                            try {
                              lastHeaders.add(name, values.asJava)
                            } catch {
                              case e: Throwable =>
                                if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                            }
                          }
                          val lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, lastHeaders)
                          ctx
                            .writeAndFlush(lastContent)
                            .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                          ReferenceCountUtil.release(msg)
                      }
                    }
                  }
                  .map(_ => ())
              case e @ HttpEntity.Chunked(_, _)                  =>
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
              case HttpEntity.Streamed(data, _, _) if hasTrailer =>
                ctx.write(response)
                data
                  .runWith(Sink.foreach { chunk =>
                    ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(chunk.toArray)), ctx.newProgressivePromise())
                  })
                  .map { _ =>
                    trailerHeadersIdOpt.foreach { trailersId =>
                      otoroshi.netty.NettyRequestAwaitingTrailers.get(trailersId).foreach {
                        case Left(future)    =>
                          // if (logger.isWarnEnabled) logger.warn(s"Unable to get trailer header for request '${trailersId}'")
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val start       = System.nanoTime()
                          val trailers    = Await.result(future, 10.seconds)
                          if (logger.isWarnEnabled)
                            logger.warn(
                              s"Blocked thread for ${(System.nanoTime() - start).nanos.toHumanReadable} to get trailer header for request '$trailersId'"
                            )
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val lastHeaders = new DefaultHttpHeaders()
                          trailers.foreach { case (name, values) =>
                            try {
                              lastHeaders.add(name, values.asJava)
                            } catch {
                              case e: Throwable =>
                                if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                            }
                          }
                          val lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, lastHeaders)
                          ctx
                            .writeAndFlush(lastContent)
                            .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                          ReferenceCountUtil.release(msg)
                        case Right(trailers) =>
                          otoroshi.netty.NettyRequestAwaitingTrailers.remove(trailersId)
                          val lastHeaders = new DefaultHttpHeaders()
                          trailers.foreach { case (name, values) =>
                            try {
                              lastHeaders.add(name, values.asJava)
                            } catch {
                              case e: Throwable =>
                                if (logger.isErrorEnabled) logger.error("error while adding trailer header", e)
                            }
                          }
                          val lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, lastHeaders)
                          ctx
                            .writeAndFlush(lastContent)
                            .applyOnIf(keepAlive)(_.addListener(ChannelFutureListener.CLOSE))
                          ReferenceCountUtil.release(msg)
                      }
                    }
                  }
                  .map(_ => ())
              case HttpEntity.Streamed(data, _, _)               =>
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
          .andThen {
            case Failure(exception) =>
              logger.error("error while handling http3 request", exception)
              directResponse(ctx, msg, HttpResponseStatus.INTERNAL_SERVER_ERROR, ERROR.retainedDuplicate())
          }
          .andThen { case _ =>
            accessLog()
          }
      case _                  => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_ESSENTIAL_ACTION.retainedDuplicate())
    }
  }

  private def accessLog(): Unit = {
    if (config.accessLog) {
      val formattedDate = DateTime.now().toString("dd/MMM/yyyy:HH:mm:ss Z") //"yyyy-MM-dd HH:mm:ss.SSS Z"
      val duration      = System.currentTimeMillis() - log_start
      AccessLogHandler.logger.info(
        s"""${addressGet
          .apply()} - - [$formattedDate] "$log_method $log_uri HTTP/3.0" $log_status $log_contentLength $duration $log_protocol"""
      )
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    log_start = System.currentTimeMillis()
    msg match {
      case _req: FullHttpRequest       =>
        request = _req
        log_method = request.method().name()
        log_uri = request.uri()
        if (HttpUtil.is100ContinueExpected(request)) {
          send100Continue(ctx)
          hotSource.tryEmitComplete()
          ReferenceCountUtil.release(msg)
        } else {
          keepAlive = HttpUtil.isKeepAlive(request)
          msg match {
            case _: LastHttpContent =>
              hotSource.tryEmitComplete()
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            case contentMsg: HttpContent =>
              val content = contentMsg.content()
              if (content.isReadable()) {
                appendToBody(content.readContentAsByteString())
              }
              if (msg.isInstanceOf[LastHttpContent]) {
                hotSource.tryEmitComplete()
                runOtoroshiRequest(request, keepAlive, ctx, msg)
              }
            case _ =>
          }
        }
      case _req: HttpRequest           =>
        request = _req
        log_method = request.method().name()
        log_uri = request.uri()
        if (HttpUtil.is100ContinueExpected(request)) {
          send100Continue(ctx)
          ReferenceCountUtil.release(msg)
        } else {
          keepAlive = HttpUtil.isKeepAlive(request)
          msg match {
            case _: LastHttpContent =>
              hotSource.tryEmitComplete()
              runOtoroshiRequest(request, keepAlive, ctx, msg)
            case contentMsg: HttpContent =>
              val content = contentMsg.content()
              if (content.isReadable()) {
                appendToBody(content.readContentAsByteString())
              }
              if (msg.isInstanceOf[LastHttpContent]) {
                hotSource.tryEmitComplete()
                runOtoroshiRequest(request, keepAlive, ctx, msg)
              }
            case _ =>
          }
        }
      case contentMsg: LastHttpContent =>
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(content.readContentAsByteString())
        }
        hotSource.tryEmitComplete()
        runOtoroshiRequest(request, keepAlive, ctx, msg)
      case contentMsg: HttpContent     =>
        val content = contentMsg.content()
        if (content.isReadable()) {
          appendToBody(content.readContentAsByteString())
        }
      case _                           => directResponse(ctx, msg, HttpResponseStatus.NOT_IMPLEMENTED, NOT_HANDLED.retainedDuplicate())
    }
  }
}

class NettyHttp3Server(config: ReactorNettyServerConfig, env: Env) {

  private val logger = Logger("otoroshi-experimental-netty-http3-server")
  private val cache  = Scaffeine().maximumSize(1000).expireAfterWrite(5.seconds).build[String, QuicSslContext]()

  private lazy val fallbackCert: (KeyPair, X509Certificate) = {
    // Add BouncyCastle provider if not already added
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider())
    }

    // Generate key pair
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair()

    // Certificate details
    val issuer = new X500Name("CN=localhost, O=Otoroshi Fallback, L=Unknown, ST=Unknown, C=XX")
    val subject = issuer // self-signed, so issuer = subject
    val serial = new BigInteger(64, new java.security.SecureRandom())
    val notBefore = new Date()
    val notAfter = new Date(notBefore.getTime + 365L * 24 * 60 * 60 * 1000) // 1 year

    // Build certificate
    val certBuilder = new JcaX509v3CertificateBuilder(
      issuer,
      serial,
      notBefore,
      notAfter,
      subject,
      keyPair.getPublic
    )

    // Sign certificate
    val signer = new JcaContentSignerBuilder("SHA256WithRSA")
        .setProvider("BC")
        .build(keyPair.getPrivate)

    val certificateHolder = certBuilder.build(signer)
    val certificate = new JcaX509CertificateConverter()
        .setProvider("BC")
        .getCertificate(certificateHolder)

    (keyPair, certificate)
  }

  private lazy val fallbackContext: QuicSslContext = {
    val (keyPair, certificate) = fallbackCert
    QuicSslContextBuilder
        .forServer(keyPair.getPrivate, null, certificate)
        .applicationProtocols(Http3.supportedApplicationProtocols(): _*)
        .earlyData(true)
        .build()
  }


  def start(
      handler: HttpRequestHandler,
      sessionCookieBaker: SessionCookieBaker,
      flashCookieBaker: FlashCookieBaker
  ): DisposableNettyHttp3Server = {

    if (config.http3.enabled && config.http3.port != -1) {


      @tailrec
      def mapDomain(domain: String): QuicSslContext = {
        if (logger.isDebugEnabled) logger.debug(s"sni domain: $domain")
        if (domain == null) {
          env.datastores.globalConfigDataStore
              .latest()(env.otoroshiExecutionContext, env)
              .tlsSettings
              .defaultDomain match {
            case None => fallbackContext  // Use the BouncyCastle-generated context
            case Some(dom) => mapDomain(dom)  // Recursive call with the default domain
          }
        } else {
          cache.get(
            domain,
            _ => {
              val (validCerts, byDomain) =
                DynamicKeyManager.validCertificatesByDomains(env.proxyState.allCertificates())
              DynamicKeyManager.getServerCertificateForDomain(domain, validCerts, byDomain, env, logger) match {
                case None => fallbackContext  // Use the BouncyCastle-generated context
                case Some(cert) =>
                  // logger.debug(s"found cert for domain: ${domain}: ${cert.name}")
                  val keypair = cert.cryptoKeyPair
                  val chain = cert.certificatesChain
                  if (logger.isDebugEnabled) logger.debug(s"for domain: $domain, found ${cert.name} / ${cert.id}")
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
          )
        }
      }

      val sslContext       = QuicSslContextBuilder.buildForServerWithSni(mapDomain(_))
      val codec            = Http3.newQuicServerCodecBuilder
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
          val address                 = new AtomicReference[String]("0.0.0.0")
          def addressAccess(): String = address.get()
          override def initChannel(ch: QuicChannel): Unit = {
            ch.collectPathStats(0).addListener { fu: io.netty.util.concurrent.Future[QuicConnectionPathStats] =>
              Option(fu.get())
                .flatMap(v => Try(v.toString).toOption)
                .flatMap(v => Try(v.split("/").last).toOption)
                .flatMap(v => Try(v.split(":").head).toOption)
                .foreach(add => address.set(add))
            }
            ch.pipeline()
              .addLast(
                new Http3ServerConnectionHandler(
                  new ChannelInitializer[QuicStreamChannel]() {
                    override def initChannel(ch: QuicStreamChannel): Unit = {
                      ch.pipeline().addLast(new io.netty.incubator.codec.http3.Http3FrameToHttpObjectCodec(true, false))
                      ch.pipeline()
                        .addLast(
                          new Http1RequestHandler(
                            handler,
                            sessionCookieBaker,
                            flashCookieBaker,
                            env,
                            logger,
                            addressAccess,
                            config
                          )
                        )
                    }
                  },
                  null,
                  null,
                  null,
                  config.http3.disableQpackDynamicTable
                )
              )
          }
        })
        .build()
      val group = new MultiThreadIoEventLoopGroup(config.nThread, NioIoHandler.newFactory())
      val bs               = new Bootstrap()
      val channel          = bs
        .group(group)
        .channel(classOf[NioDatagramChannel])
        .handler(codec)
        .bind(config.host, config.http3.port)
        .sync()
        .channel()
      channel.closeFuture()
      val disposableServer = DisposableNettyHttp3Server(group.some)
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        disposableServer.stop()
      }))
      disposableServer
    } else {
      DisposableNettyHttp3Server(None)
    }
  }
}

case class DisposableNettyHttp3Server(group: Option[MultiThreadIoEventLoopGroup]) {
  def stop(): Unit = {
    group.foreach(_.shutdownGracefully())
  }
}
