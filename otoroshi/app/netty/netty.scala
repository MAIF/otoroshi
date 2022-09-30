package otoroshi.netty

import akka.stream.scaladsl.Sink
import akka.util.ByteString
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{Channel, EventLoopGroup}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.logging.LogLevel
import io.netty.handler.ssl._
import org.reactivestreams.{Processor, Publisher}
import otoroshi.env.Env
import otoroshi.next.proxy.ProxyEngine
import otoroshi.script.RequestHandler
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.websocket.Message
import play.api.http.{HttpChunk, HttpEntity, HttpRequestHandler}
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.Json
import play.api.mvc._
import play.core.server.common.WebSocketFlowHandler
import play.core.server.common.WebSocketFlowHandler.{MessageType, RawMessage}
import reactor.netty.NettyOutbound

import java.security.{Provider, SecureRandom}
import java.util.function.BiFunction
import javax.net.ssl._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

case class HttpServerBodyResponse(body: Publisher[Array[Byte]], contentType: Option[String], contentLength: Option[Long], chunked: Boolean)

class ReactorNettyServer(env: Env) {

  import reactor.core.publisher.Flux
  import reactor.netty.http.HttpProtocol
  import reactor.netty.http.server._

  implicit private val ec = env.otoroshiExecutionContext
  implicit private val mat = env.otoroshiMaterializer
  implicit private val ev = env

  private val logger = Logger("otoroshi-experimental-netty-server")

  private val engine: ProxyEngine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get.asInstanceOf[ProxyEngine]

  private val config = ReactorNettyServerConfig.parseFrom(env)

  private val cookieSignerProvider = new CookieSignerProvider(env.httpConfiguration.secret)
  private val sessionCookieBaker = new DefaultSessionCookieBaker(env.httpConfiguration.session, env.httpConfiguration.secret, cookieSignerProvider.get)
  private val flashCookieBaker = new DefaultFlashCookieBaker(env.httpConfiguration.flash, env.httpConfiguration.secret, cookieSignerProvider.get)
  private val cookieEncoder = new DefaultCookieHeaderEncoding(env.httpConfiguration.cookies)

  private def sendResultAsHttpResponse(result: Result, res: HttpServerResponse): NettyOutbound = {
    val bresponse: HttpServerBodyResponse = result.body match {
      case HttpEntity.NoEntity => HttpServerBodyResponse(Flux.empty[Array[Byte]](), None, None, false)
      case HttpEntity.Strict(data, contentType) => HttpServerBodyResponse(Flux.just(Seq(data.toArray[Byte]): _*), contentType, Some(data.size.toLong), false)
      case HttpEntity.Chunked(chunks, contentType) => {
        val publisher = chunks.collect {
          case HttpChunk.Chunk(data) => data.toArray[Byte]
        }.runWith(Sink.asPublisher(false))
        HttpServerBodyResponse(publisher, contentType, None, true)
      }
      case HttpEntity.Streamed(data, contentLength, contentType) => {
        val publisher = data.map(_.toArray[Byte]).runWith(Sink.asPublisher(false))
        HttpServerBodyResponse(publisher, contentType, contentLength, false)
      }
    }
    val headers = new DefaultHttpHeaders()
    result.header.headers.map {
      case (key, value) => if (key != "otoroshi-netty-trailers") headers.add(key, value)
    }
    if (bresponse.contentType.contains("application/grpc")) {
      headers.add(HttpHeaderNames.TRAILER, "grpc-status, grpc-message")
    }
    bresponse.contentType.foreach(ct => headers.add("Content-Type", ct))
    bresponse.contentLength.foreach(cl => headers.addInt("Content-Length", cl.toInt))
    res
      .status(result.header.status)
      .headers(headers)
      .applyOnIf(result.newCookies.nonEmpty) { r =>
        result.newCookies.map { cookie =>
          val nettyCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
          nettyCookie.setPath(cookie.path)
          nettyCookie.setHttpOnly(cookie.httpOnly)
          nettyCookie.setSecure(cookie.secure)
          cookie.domain.foreach(d => nettyCookie.setDomain(d))
          cookie.maxAge.foreach(d => nettyCookie.setMaxAge(d.toLong))
          cookie.sameSite.foreach {
            case play.api.mvc.Cookie.SameSite.None => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
            case play.api.mvc.Cookie.SameSite.Strict => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
            case play.api.mvc.Cookie.SameSite.Lax => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
          }
          r.addCookie(nettyCookie)
        }
        r
      }
      .applyOnIf(result.newSession.isDefined) { r =>
        result.newSession.map { session =>
          val cookie = sessionCookieBaker.encodeAsCookie(session)
          val sessionCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
          sessionCookie.setPath(cookie.path)
          sessionCookie.setHttpOnly(cookie.httpOnly)
          sessionCookie.setSecure(cookie.secure)
          cookie.domain.foreach(d => sessionCookie.setDomain(d))
          cookie.maxAge.foreach(d => sessionCookie.setMaxAge(d.toLong))
          cookie.sameSite.foreach {
            case play.api.mvc.Cookie.SameSite.None => sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
            case play.api.mvc.Cookie.SameSite.Strict => sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
            case play.api.mvc.Cookie.SameSite.Lax => sessionCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
          }
          r.addCookie(sessionCookie)
        }
        r
      }
      .applyOnIf(result.newFlash.isDefined) { r =>
        result.newFlash.map { flash =>
          val cookie = flashCookieBaker.encodeAsCookie(flash)
          val flashCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
          flashCookie.setPath(cookie.path)
          flashCookie.setHttpOnly(cookie.httpOnly)
          flashCookie.setSecure(cookie.secure)
          cookie.domain.foreach(d => flashCookie.setDomain(d))
          cookie.maxAge.foreach(d => flashCookie.setMaxAge(d.toLong))
          cookie.sameSite.foreach {
            case play.api.mvc.Cookie.SameSite.None => flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
            case play.api.mvc.Cookie.SameSite.Strict => flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
            case play.api.mvc.Cookie.SameSite.Lax => flashCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
          }
          r.addCookie(flashCookie)
        }
        r
      }
      .chunkedTransfer(bresponse.chunked)
      .trailerHeaders(theaders => {
        import collection.JavaConverters._
        result.header.headers.get("otoroshi-netty-trailers").foreach { trailersId =>
          otoroshi.netty.NettyRequestAwaitingTrailers.awaiting.get(trailersId).foreach {
            case Left(future) =>
              logger.warn(s"Unable to get trailer header for request '${trailersId}'")
              otoroshi.netty.NettyRequestAwaitingTrailers.awaiting.remove(trailersId)
              val trailers = Await.result(future, 10.seconds)
              otoroshi.netty.NettyRequestAwaitingTrailers.awaiting.remove(trailersId)
              trailers.foreach {
                case (name, values) =>
                  try {
                    theaders.add(name, values.asJava)
                  } catch {
                    case e: Throwable => logger.error("error while adding trailer header", e)
                  }
              }
            case Right(trailers) =>
              otoroshi.netty.NettyRequestAwaitingTrailers.awaiting.remove(trailersId)
              trailers.foreach {
                case (name, values) =>
                  try {
                    theaders.add(name, values.asJava)
                  } catch {
                    case e: Throwable => logger.error("error while adding trailer header", e)
                  }
              }
          }
        }
      })
      .keepAlive(true)
      .sendByteArray(bresponse.body)
  }

  private def frameToRawMessage(frame: WebSocketFrame): RawMessage = {
    Try {
      val builder = ByteString.newBuilder
      frame.content().readBytes(builder.asOutputStream, frame.content().readableBytes())
      val bytes = builder.result()
      val messageType = frame match {
        case _: TextWebSocketFrame         => MessageType.Text
        case _: BinaryWebSocketFrame       => MessageType.Binary
        case close: CloseWebSocketFrame    => MessageType.Close
        case _: PingWebSocketFrame         => MessageType.Ping
        case _: PongWebSocketFrame         => MessageType.Pong
        case _: ContinuationWebSocketFrame => MessageType.Continuation
      }
      RawMessage(messageType, bytes, frame.isFinalFragment)
    } match {
      case Failure(ex) =>
        ex.printStackTrace()
        RawMessage(MessageType.Text, ByteString("frameToRawMessage error: " + ex.getMessage), frame.isFinalFragment)
      case Success(s) => s
    }
  }

  private def messageToFrame(message: Message): WebSocketFrame = {
    import io.netty.handler.codec.http.websocketx._
    def byteStringToByteBuf(bytes: ByteString): ByteBuf = {
      if (bytes.isEmpty) {
        Unpooled.EMPTY_BUFFER
      } else {
        Unpooled.wrappedBuffer(bytes.asByteBuffer)
      }
    }
    message match {
      case play.api.http.websocket.TextMessage(data)                      => new TextWebSocketFrame(data)
      case play.api.http.websocket.BinaryMessage(data)                    => new BinaryWebSocketFrame(byteStringToByteBuf(data))
      case play.api.http.websocket.PingMessage(data)                      => new PingWebSocketFrame(byteStringToByteBuf(data))
      case play.api.http.websocket.PongMessage(data)                      => new PongWebSocketFrame(byteStringToByteBuf(data))
      case play.api.http.websocket.CloseMessage(Some(statusCode), reason) => new CloseWebSocketFrame(statusCode, reason)
      case play.api.http.websocket.CloseMessage(None, _)                  => new CloseWebSocketFrame()
    }
  }

  private def handleWebsocket(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean, session: Option[SSLSession]): Publisher[Void] = {
    ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
      val otoReq = new ReactorNettyRequestHeader(req, secure, session, sessionCookieBaker, flashCookieBaker)
      engine.handleWs(otoReq, engine.badDefaultRoutingWs).map {
        case Left(result) => sendResultAsHttpResponse(result, res)
        case Right(flow) => {
          res.sendWebsocket { (wsInbound, wsOutbound) =>
            val processor: Processor[RawMessage, Message] = WebSocketFlowHandler.webSocketProtocol(65536).join(flow).toProcessor.run()
            wsInbound
              .receiveFrames()
              .map[RawMessage](frameToRawMessage)
              .subscribe(processor)
            val fluxOut: Flux[WebSocketFrame] = Flux.from(processor).map(messageToFrame)
            wsOutbound.sendObject(fluxOut)
          }
        }
      }
    }
  }

  private def handleHttp(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean, channel: Channel): Publisher[Void] = {
    val parent = channel.parent()
    val sslHandler = Option(parent.pipeline().get(classOf[SslHandler]))
    val sessionOpt = sslHandler.map(_.engine.getSession)
    val isWebSocket = (req.requestHeaders().contains("Upgrade") || req.requestHeaders().contains("upgrade")) &&
      (req.requestHeaders().contains("Sec-WebSocket-Version") || req.requestHeaders().contains("Sec-WebSocket-Version".toLowerCase)) &&
      Option(req.requestHeaders().get("Upgrade")).contains("websocket")
    if (isWebSocket) {
      handleWebsocket(req, res, secure, sessionOpt)
    } else {
      ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
        val otoReq = new ReactorNettyRequest(req, secure, sessionOpt, sessionCookieBaker, flashCookieBaker)
        engine.handle(otoReq, engine.badDefaultRoutingHttp).map { result =>
          sendResultAsHttpResponse(result, res)
        }
      }
    }
  }

  private def handle(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean, channel: Channel, handler: HttpRequestHandler): Publisher[Void] = {
    val parent = channel.parent()
    val sslHandler = Option(parent.pipeline().get(classOf[SslHandler]))
    val sessionOpt = sslHandler.map(_.engine.getSession)
    val isWebSocket = (req.requestHeaders().contains("Upgrade") || req.requestHeaders().contains("upgrade")) &&
      (req.requestHeaders().contains("Sec-WebSocket-Version") || req.requestHeaders().contains("Sec-WebSocket-Version".toLowerCase)) &&
      Option(req.requestHeaders().get("Upgrade")).contains("websocket")
    ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
      val otoReq = new ReactorNettyRequest(req, secure, sessionOpt, sessionCookieBaker, flashCookieBaker)
      val (nreq, reqHandler) = handler.handlerForRequest(otoReq)
      reqHandler match {
        case a: EssentialAction => {
          a.apply(nreq).run(otoReq.body).map { result =>
            sendResultAsHttpResponse(result, res)
          }
        }
        case a: WebSocket if isWebSocket => {
          a.apply(nreq).map {
            case Left(result) => sendResultAsHttpResponse(result, res)
            case Right(flow) => {
              res.sendWebsocket { (wsInbound, wsOutbound) =>
                val processor: Processor[RawMessage, Message] = WebSocketFlowHandler.webSocketProtocol(65536).join(flow).toProcessor.run()
                wsInbound
                  .receiveFrames()
                  .map[RawMessage](frameToRawMessage)
                  .subscribe(processor)
                val fluxOut: Flux[WebSocketFrame] = Flux.from(processor).map(messageToFrame)
                wsOutbound.sendObject(fluxOut)
              }
            }
          }
        }
        case a => {
          sendResultAsHttpResponse(Results.InternalServerError(Json.obj("err" -> s"unknown handler: ${a.getClass.getName} - ${a}")), res).vfuture
        }
      }
    }
  }

  private def setupSslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     = DynamicSSLEngineProvider.createSSLEngine(config.clientAuth, config.cipherSuites, config.protocols, None)
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(
                                 keyManagers: Array[KeyManager],
                                 trustManagers: Array[TrustManager],
                                 secureRandom: SecureRandom
                               ): Unit                                                             = ()
        override def engineGetClientSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getClientSessionContext
        override def engineGetServerSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getServerSessionContext
        override def engineGetSocketFactory(): SSLSocketFactory             =
          DynamicSSLEngineProvider.currentServer.getSocketFactory
        override def engineGetServerSocketFactory(): SSLServerSocketFactory =
          DynamicSSLEngineProvider.currentServer.getServerSocketFactory
      },
      new Provider(
        "[NETTY] Otoroshi SSlEngineProvider delegate",
        "1.0",
        "[NETTY] A provider that delegates calls to otoroshi dynamic one"
      )                   {},
      "[NETTY] Otoroshi SSLEngineProvider delegate"
    ) {}
  }

  def createEventLoops(): (EventLoopGroup, EventLoopGroup) = {
    val groupHttp = EventLoopUtils.create(config.native, config.nThread)
    val groupHttps = EventLoopUtils.create(config.native, config.nThread)
    groupHttp.native.foreach { name =>
      logger.info(s"  using ${name} native transport")
      logger.info("")
    }
    (groupHttp.group, groupHttps.group)
  }

  def start(handler: HttpRequestHandler): Unit = {
    if (config.enabled) {

      logger.info("")
      logger.info(s"Starting the experimental Netty Server !!!")
      logger.info("")

      val (groupHttp: EventLoopGroup, groupHttps: EventLoopGroup) = createEventLoops()

      if (config.http3.enabled) logger.info(s"  https://${config.host}:${config.http3.port} (HTTP/3)")
      logger.info(s"  https://${config.host}:${config.httpsPort} (HTTP/1.1, HTTP/2)")
      logger.info(s"  http://${config.host}:${config.httpPort}  (HTTP/1.1, HTTP/2 H2C)")
      logger.info("")

      def handleFunction(secure: Boolean): BiFunction[_ >: HttpServerRequest, _ >: HttpServerResponse, _ <: Publisher[Void]] = {
        if (config.newEngineOnly) {
          (req, res) => {
            val channel = NettyHelper.getChannel(req)
            handleHttp(req, res, secure, channel)
          }
        } else {
          (req, res) => {
            val channel = NettyHelper.getChannel(req)
            handle(req, res, secure, channel, handler)
          }
        }
      }

      val serverHttps = HttpServer
        .create()
        .host(config.host)
        .accessLog(config.accessLog)
        .applyOnIf(config.wiretap)(_.wiretap(logger.logger.getName + "-wiretap-https", LogLevel.INFO))
        .port(config.httpsPort)
        .applyOnIf(config.http2.enabled)(_.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C))
        .applyOnIf(!config.http2.enabled)(_.protocol(HttpProtocol.HTTP11))
        .runOn(groupHttps)
        .httpRequestDecoder(spec => spec
          .allowDuplicateContentLengths(config.parser.allowDuplicateContentLengths)
          .h2cMaxContentLength(config.parser.h2cMaxContentLength)
          .initialBufferSize(config.parser.initialBufferSize)
          .maxHeaderSize(config.parser.maxHeaderSize)
          .maxInitialLineLength(config.parser.maxInitialLineLength)
          .maxChunkSize(config.parser.maxChunkSize)
          .validateHeaders(config.parser.validateHeaders)
        )
        .idleTimeout(config.idleTimeout)
        .doOnChannelInit { (observer, channel, socket) =>
          val engine = setupSslContext().createSSLEngine()
          engine.setHandshakeApplicationProtocolSelector((e, protocols) => {
            protocols match {
              case ps if ps.contains("h2") && config.http2.enabled => "h2"
              case ps if ps.contains("spdy/3") => "spdy/3"
              case _ => "http/1.1"
            }
          })
          // we do not use .secure() because of no dynamic sni support and use SslHandler instead !
          channel.pipeline().addFirst(new SslHandler(engine))
        }
        .handle(handleFunction(true))
        .bindNow()
      val serverHttp = HttpServer
        .create()
        .host(config.host)
        .noSSL()
        .accessLog(config.accessLog)
        .applyOnIf(config.wiretap)(_.wiretap(logger.logger.getName + "-wiretap-http", LogLevel.INFO))
        .port(config.httpPort)
        .applyOnIf(config.http2.h2cEnabled)(_.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C))
        .applyOnIf(!config.http2.h2cEnabled)(_.protocol(HttpProtocol.HTTP11))
        .handle(handleFunction(false))
        .runOn(groupHttp)
        .httpRequestDecoder(spec => spec
          .allowDuplicateContentLengths(config.parser.allowDuplicateContentLengths)
          .h2cMaxContentLength(config.parser.h2cMaxContentLength)
          .initialBufferSize(config.parser.initialBufferSize)
          .maxHeaderSize(config.parser.maxHeaderSize)
          .maxInitialLineLength(config.parser.maxInitialLineLength)
          .maxChunkSize(config.parser.maxChunkSize)
          .validateHeaders(config.parser.validateHeaders)
        )
        .idleTimeout(config.idleTimeout)
        .bindNow()
      new NettyHttp3Server(config, env).start(handler, sessionCookieBaker, flashCookieBaker)
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        serverHttp.disposeNow()
        serverHttps.disposeNow()
      }))
    } else {
      ()
    }
  }
}