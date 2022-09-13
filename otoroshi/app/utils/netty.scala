package otoroshi.utils.netty

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.netty.handler.codec.http._
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.incubator.codec.quic.{InsecureQuicTokenHandler, QuicSslContextBuilder}
import org.reactivestreams.Publisher
import otoroshi.env.Env
import otoroshi.next.proxy.ProxyEngine
import otoroshi.script.RequestHandler
import otoroshi.utils.syntax.implicits._
import play.api.http.{HttpChunk, HttpEntity}
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import play.api.mvc.{Cookies, Headers, Request, Results}
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.incubator.quic.QuicServer

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ReactiveStreamUtils {
  object MonoUtils {
    import reactor.core.publisher.Mono
    def fromFuture[A](future: => Future[A])(implicit ec: ExecutionContext): Mono[A] = {
      Mono.create[A] { sink =>
        future.andThen {
          case Success(value) => sink.success(value)
          case Failure(exception) => sink.error(exception)
        }
      }
    }

  }
  object FluxUtils {
    import reactor.core.publisher._
    def fromFPublisher[A](future: => Future[Publisher[A]])(implicit ec: ExecutionContext): Flux[A] = {
      Mono.create[Publisher[A]] { sink =>
        future.andThen {
          case Success(value) => sink.success(value)
          case Failure(exception) => sink.error(exception)
        }
      }.flatMapMany(i => i)
    }
  }
}

class ReactorNettyRemoteConnection(req: HttpServerRequest, val secure: Boolean) extends RemoteConnection {
  lazy val remoteAddress: InetAddress = req.remoteAddress().getAddress
  lazy val clientCertificateChain: Option[Seq[X509Certificate]] = None // TODO: fixme
}

class ReactorNettyRequestTarget(req: HttpServerRequest) extends RequestTarget {
  lazy val kUri = akka.http.scaladsl.model.Uri(uriString)
  lazy val uri: URI = new URI(uriString)
  lazy val uriString: String = req.uri()
  lazy val path: String = req.path()
  lazy val queryMap: Map[String, Seq[String]] = kUri.query().toMultiMap.mapValues(_.toSeq)
}

object ReactorNettyRequest {
  val counter = new AtomicLong(0L)
}

class ReactorNettyRequest(req: HttpServerRequest, secure: Boolean) extends Request[Source[ByteString, _]] {

  import scala.collection.JavaConverters._

  val attrs = TypedMap.apply(
    RequestAttrKey.Id      -> ReactorNettyRequest.counter.incrementAndGet(),
    RequestAttrKey.Cookies -> Cell(Cookies.apply(Seq.empty)) // TODO: fixme
  )
  lazy val method: String = req.method().toString
  lazy val version: String = req.version().toString
  lazy val headers: Headers = Headers(
    req.requestHeaders().entries().asScala.map(e => (e.getKey, e.getValue)): _*
  )
  lazy val body: Source[ByteString, _] = Source.fromPublisher(req.receive()).map(bb => ByteString(bb.array()))
  lazy val connection: RemoteConnection = new ReactorNettyRemoteConnection(req, secure)
  lazy val target: RequestTarget = new ReactorNettyRequestTarget(req)
}

case class HttpServerBodyResponse(body: Publisher[Array[Byte]], contentType: Option[String], contentLength: Option[Long], chunked: Boolean)

class HttpServer(env: Env) {

  import reactor.core.publisher.{Flux, Mono}
  import reactor.netty.http.HttpProtocol
  import reactor.netty.http.server._

  implicit private val ec = env.otoroshiExecutionContext
  implicit private val mat = env.otoroshiMaterializer
  implicit private val ev = env

  private val engine: ProxyEngine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get.asInstanceOf[ProxyEngine]

  private def handle(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean): Publisher[Void] = {
    ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
      engine.handle(new ReactorNettyRequest(req, secure), _ => Results.InternalServerError("bad default routing").vfuture).map { result =>
        val bresponse: HttpServerBodyResponse = result.body match {
          case HttpEntity.NoEntity => HttpServerBodyResponse(Flux.empty[Array[Byte]](), None, None, false)
          case HttpEntity.Strict(data, contentType) => HttpServerBodyResponse(Flux.just(Seq(data.toArray[Byte]): _*), contentType, Some(data.size.toLong), false)
          case HttpEntity.Chunked(chunks, contentType) => {
            val publisher = chunks.map {
              case HttpChunk.Chunk(data) => Some(data.toArray[Byte])
              case HttpChunk.LastChunk(_) => None
            }.collect{
              case Some(data) => data
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
          case (key, value) => headers.add(key, value)
        }
        bresponse.contentType.foreach(ct => headers.add("Content-Type", ct))
        bresponse.contentLength.foreach(cl => headers.addInt("Content-Length", cl.toInt))
        res
          .status(result.header.status)
          .headers(headers)
          // .addCookie() // TODO: fixme
          .chunkedTransfer(bresponse.chunked)
          .keepAlive(true)
          .sendByteArray(bresponse.body)
      }
    }
  }

  def start(): Unit = {
    println(s"Starting the Reactor Netty Server !!! https://127.0.0.1:${env.httpsPort + 50} / https://127.0.0.1:${env.httpsPort + 51} / http://127.0.0.1:${env.httpPort + 50}")
    // TODO: fixme - use otoroshi one
    val cert = new SelfSignedCertificate()
    val serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).trustManager()

    val serverHttps = HttpServer
      .create()
      .host("0.0.0.0")
      .accessLog(true)
      .wiretap(false)
      .port(env.httpsPort + 50)
      .protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
      .secure { ssl: reactor.netty.tcp.SslProvider.SslContextSpec =>
        // TODO: use dyn keymanagerfactory
        ssl.sslContext(serverOptions)
      }
      .handle((req, res) => handle(req, res, true))
      .bindNow()
    val serverHttp = HttpServer
      .create()
      .host("0.0.0.0")
      .noSSL()
      .accessLog(true)
      .wiretap(false)
      .port(env.httpPort + 50)
      .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
      .handle((req, res) => handle(req, res, false))
      .bindNow()
    // val serverCtx = QuicSslContextBuilder.forServer(cert.privateKey(), null, cert.certificate())
    //     .applicationProtocols("http/1.1")
    //     .build()
    // val serverHttp3 =
    //   QuicServer.create()
    //     .host("0.0.0.0")
    //     .port(env.httpsPort + 51)
    //     .secure(serverCtx)
    //     .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
    //     // .wiretap(true)
    //     // .accessLog(true)
    //     // .idleTimeout(Duration.ofSeconds(5))
    //     .initialSettings(spec =>
    //       spec.maxData(10000000)
    //         .maxStreamDataBidirectionalRemote(1000000)
    //         .maxStreamsBidirectional(100))
    //     .handleStream { (in, out) =>
    //       // TODO: plug handle here
    //       out.send(in.receive().retain())
    //     }
    //     .bindNow()
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      serverHttp.disposeNow()
      serverHttps.disposeNow()
      // serverHttp3.disposeNow()
    }))
  }
}

/*
class NettyHttpServer(env: Env) {

  lazy val cipherSuites =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
      .filterNot(_.isEmpty)
  lazy val protocols    =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
      .filterNot(_.isEmpty)
  lazy val clientAuth = {
    val auth = env.configuration
      .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
      .flatMap(ClientAuth.apply)
      .getOrElse(ClientAuth.None)
    if (DynamicSSLEngineProvider.logger.isDebugEnabled)
      DynamicSSLEngineProvider.logger.debug(s"Otoroshi client auth: ${auth}")
    auth
  }

  implicit val ec = env.otoroshiExecutionContext

  val engine: ProxyEngine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get.asInstanceOf[ProxyEngine]

  private def setupSslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     = {
          val engine = DynamicSSLEngineProvider.createSSLEngine(clientAuth, cipherSuites, protocols, Some("alpn"))
          engine.setHandshakeApplicationProtocolSelector(new BiFunction[SSLEngine, java.util.List[String], String] {
            override def apply(t: SSLEngine, u: util.List[String]): String = {
              println("protocols", u, t.getApplicationProtocol)
              t.set
              if (u.contains("h2")) {
                "h2"
              } else if (u.contains("h2c")) {
                "h2c"
              } else {
                "http/1.1"
              }
            }
          })
          engine
        }
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(kms: Array[KeyManager], tms: Array[TrustManager], sr: SecureRandom): Unit = ()
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
      ) {},
      "[NETTY] Otoroshi SSLEngineProvider delegate"
    ) {}
  }

  def startHttp(): (Channel, EventLoopGroup) = {
    // Configure the server.
    val group: EventLoopGroup = new NioEventLoopGroup()
    val b = new ServerBootstrap()
    b.option(ChannelOption.SO_BACKLOG, java.lang.Integer.getInteger("1024"))
    b.group(group)
      .channel(classOf[NioServerSocketChannel])
      .handler(new LoggingHandler(LogLevel.INFO))
      .childHandler(new Http2ServerInitializer(None, env, engine))
    val ch: Channel = b.bind(env.httpPort + 1).sync().channel()
    System.err.println(s"Open your HTTP/2-enabled web browser and navigate to http://127.0.0.1:${env.httpPort + 1}");
    (ch, group)
  }

  def startHttps(): (Channel, EventLoopGroup) = {
    // Configure SSL.
    val ctx = setupSslContext()
    val sslCtx: SslContext = {
      val provider: SslProvider = if (SslProvider.isAlpnSupported(SslProvider.OPENSSL)) SslProvider.OPENSSL else SslProvider.JDK
      val ssc: SelfSignedCertificate = new SelfSignedCertificate()
      SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
        .sslProvider(provider)
        /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
         * Please refer to the HTTP/2 specification for cipher requirements. */
        .clientAuth(io.netty.handler.ssl.ClientAuth.OPTIONAL)
        .protocols("TLSv1.3", "TLSv1.2")
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(new ApplicationProtocolConfig(
          Protocol.ALPN,
          // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
          SelectorFailureBehavior.NO_ADVERTISE,
          // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
          SelectedListenerFailureBehavior.ACCEPT,
          ApplicationProtocolNames.HTTP_2,
          ApplicationProtocolNames.HTTP_1_1
        ))
        .build()
    }
    // Configure the server.
    val group: EventLoopGroup = new NioEventLoopGroup()
    val b = new ServerBootstrap()
    b.option(ChannelOption.SO_BACKLOG, java.lang.Integer.getInteger("1024"))
    b.group(group)
      .channel(classOf[NioServerSocketChannel])
      .handler(new LoggingHandler(LogLevel.INFO))
      .childHandler(new Http2ServerInitializer(ctx.some, env, engine))
    val ch: Channel = b.bind(env.httpsPort + 50).sync().channel();
    System.err.println(s"Open your HTTP/2-enabled web browser and navigate to https://127.0.0.1:${env.httpsPort + 50}");
    (ch, group)
  }

  def start(): Unit = {
    println("Starting the Netty Server !!!")
    val (channelHttp, groupHttp) = startHttp()
    val (channelHttps, groupHttps) = startHttps()
    channelHttp.closeFuture().addListener((_: Future[Void]) => groupHttp.shutdownGracefully())
    channelHttps.closeFuture().addListener((_: Future[Void]) => groupHttps.shutdownGracefully())
  }
}

class Http2ServerInitializer(sslCtxOpt: Option[SSLContext], env: Env, engine: ProxyEngine, maxHttpContentLength: Int = 16 * 1024) extends ChannelInitializer[SocketChannel] {

  private val upgradeCodecFactory = new UpgradeCodecFactory() {
    override def newUpgradeCodec(protocol: CharSequence): HttpServerUpgradeHandler.UpgradeCodec = {
      if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
        new Http2ServerUpgradeCodec(new HelloWorldHttp2HandlerBuilder(env, engine).build())
      } else {
        null
      }
    }
  }

  override def initChannel(ch: SocketChannel): Unit = sslCtxOpt match {
    case Some(sslCtx) => configureSsl(ch, sslCtx)
    case None => configureClearText(ch)
  }

  private def configureSsl(ch: SocketChannel, ctx: SSLContext): Unit = {
    val sshHandler = new SslHandler(ctx.createSSLEngine())
    ch.pipeline().addLast(sshHandler, new Http2OrHttpHandler(env, engine))
  }

  private def configureClearText(ch: SocketChannel): Unit = {
    val p = ch.pipeline()
    val sourceCodec = new HttpServerCodec()
    val upgradeHandler = new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory)
    val cleartextHttp2ServerUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
      sourceCodec, upgradeHandler, new HelloWorldHttp2HandlerBuilder(env, engine).build()
    )
    p.addLast(cleartextHttp2ServerUpgradeHandler)
    p.addLast(new SimpleChannelInboundHandler[HttpMessage]() {
      override def channelRead0(ctx: ChannelHandlerContext, msg: HttpMessage): Unit = {
        // If this handler is hit then no upgrade has been attempted and the client is just talking HTTP.
        System.err.println("Directly talking: " + msg.protocolVersion() + " (no upgrade was attempted)");
        val pipeline = ctx.pipeline()
        pipeline.addAfter(ctx.name(), null, new HelloWorldHttp1Handler("Direct. No Upgrade Attempted.", env, engine))
        pipeline.replace(this, null, new HttpObjectAggregator(maxHttpContentLength))
        ctx.fireChannelRead(ReferenceCountUtil.retain(msg))
      }
    });
    //p.addLast(new UserEventLogger());
  }
}

class UserEventLogger extends ChannelInboundHandlerAdapter {
  override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    System.out.println("User Event Triggered: " + evt);
    ctx.fireUserEventTriggered(evt);
  }
}

class Http2OrHttpHandler(env: Env, engine: ProxyEngine) extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
  override def configurePipeline(ctx: ChannelHandlerContext, protocol: String): Unit = {
    protocol match {
      case "h2" => ctx.pipeline().addLast(new HelloWorldHttp2HandlerBuilder(env, engine).build())
      case "http/1.1" => {
        ctx.pipeline().addLast(new HttpServerCodec(),
          new HttpObjectAggregator(1024 * 100),
          new HelloWorldHttp1Handler("ALPN Negotiation", env, engine))
      }
      case _ => throw new IllegalStateException("unknown protocol: " + protocol)
    }
  }
}

object HelloWorldHttp2HandlerBuilder {
  private val logger = new Http2FrameLogger(io.netty.handler.logging.LogLevel.INFO, classOf[HelloWorldHttp2Handler])
}

class HelloWorldHttp2HandlerBuilder(env: Env, engine: ProxyEngine) extends AbstractHttp2ConnectionHandlerBuilder[HelloWorldHttp2Handler, HelloWorldHttp2HandlerBuilder] {

  frameLogger(HelloWorldHttp2HandlerBuilder.logger)

  override def build: HelloWorldHttp2Handler = super.build

  override def build(decoder: Http2ConnectionDecoder, encoder: Http2ConnectionEncoder, initialSettings: Http2Settings): HelloWorldHttp2Handler = {
    val handler = new HelloWorldHttp2Handler(decoder, encoder, initialSettings, env, engine)
    frameListener(handler)
    handler
  }
}

object HelloWorldHttp2Handler {
  val RESPONSE_BYTES: ByteBuf = io.netty.buffer.Unpooled.unreleasableBuffer(
    io.netty.buffer.Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8)
  ).asReadOnly()

  def http1HeadersToHttp2Headers(request: FullHttpRequest): Http2Headers = {
    val host = request.headers().get(HttpHeaderNames.HOST)
    val http2Headers = new DefaultHttp2Headers()
      .method(HttpMethod.GET.asciiName())
      .path(request.uri())
      .scheme(HttpScheme.HTTP.name())
    if (host != null) {
      http2Headers.authority(host)
    }
    http2Headers
  }
}

class HelloWorldHttp2Handler(decoder: Http2ConnectionDecoder, encoder: Http2ConnectionEncoder, initialSettings: Http2Settings, env: Env, engine: ProxyEngine) extends Http2ConnectionHandler(decoder, encoder, initialSettings) with Http2FrameListener {

  /**
   * Handles the cleartext HTTP upgrade event. If an upgrade occurred, sends a simple response via HTTP/2
   * on stream 1 (the stream specifically reserved for cleartext HTTP upgrade).
   */
  override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
    evt match {
      case upgradeEvent: HttpServerUpgradeHandler.UpgradeEvent => onHeadersRead(ctx, 1, HelloWorldHttp2Handler.http1HeadersToHttp2Headers(upgradeEvent.upgradeRequest()), 0 , true)
      case _ =>
    }
    super.userEventTriggered(ctx, evt)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    super.exceptionCaught(ctx, cause)
    cause.printStackTrace()
    ctx.close()
  }

  /**
   * Sends a "Hello World" DATA frame to the client.
   */
  def sendResponse(ctx: ChannelHandlerContext, streamId: Int, payload: ByteBuf): Unit = {
    // Send a frame for the response status
    val headers = new DefaultHttp2Headers().status("200")
    encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise())
    encoder().writeData(ctx, streamId, payload, 0, true, ctx.newPromise())
    // no need to call flush as channelReadComplete(...) will take care of it.
  }

  override def onDataRead(ctx: ChannelHandlerContext, streamId: Int, data: ByteBuf, padding: Int, endOfStream: Boolean): Int = {
    val processed: Int = data.readableBytes() + padding
    if (endOfStream) {
      sendResponse(ctx, streamId, data.retain())
    }
    processed
  }

  override def onHeadersRead(ctx: ChannelHandlerContext, streamId: Int, headers: Http2Headers, padding: Int, endOfStream: Boolean): Unit = {
    if (endOfStream) {
      val content = ctx.alloc().buffer()
      content.writeBytes(HelloWorldHttp2Handler.RESPONSE_BYTES.duplicate())
      ByteBufUtil.writeAscii(content, " - via HTTP/2")
      sendResponse(ctx, streamId, content)
    }
  }

  override def onHeadersRead(ctx: ChannelHandlerContext, streamId: Int, headers: Http2Headers, streamDependency: Int, weight: Short, exclusive: Boolean, padding: Int, endOfStream: Boolean): Unit = {
    onHeadersRead(ctx, streamId, headers, padding, endOfStream);
  }

  override def onPriorityRead(ctx: ChannelHandlerContext, streamId: Int, streamDependency: Int, weight: Short, exclusive: Boolean): Unit = ()

  override def onRstStreamRead(ctx: ChannelHandlerContext, streamId: Int, errorCode: Long): Unit = ()

  override def onSettingsAckRead(ctx: ChannelHandlerContext): Unit = ()

  override def onSettingsRead(ctx: ChannelHandlerContext, settings: Http2Settings): Unit = ()

  override def onPingRead(ctx: ChannelHandlerContext, data: Long): Unit = ()

  override def onPingAckRead(ctx: ChannelHandlerContext, data: Long): Unit = ()

  override def onPushPromiseRead(ctx: ChannelHandlerContext, streamId: Int, promisedStreamId: Int, headers: Http2Headers, padding: Int): Unit = ()

  override def onGoAwayRead(ctx: ChannelHandlerContext, lastStreamId: Int, errorCode: Long, debugData: ByteBuf): Unit = ()

  override def onWindowUpdateRead(ctx: ChannelHandlerContext, streamId: Int, windowSizeIncrement: Int): Unit = ()

  override def onUnknownFrame(ctx: ChannelHandlerContext, frameType: Byte, streamId: Int, flags: Http2Flags, payload: ByteBuf): Unit = ()
}

class HelloWorldHttp1Handler(establishApproach: String, env: Env, engine: ProxyEngine) extends SimpleChannelInboundHandler[FullHttpRequest] {

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit = {
    if (HttpUtil.is100ContinueExpected(req)) {
      ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER))
    }

    val content = ctx.alloc().buffer()
    content.writeBytes(HelloWorldHttp2Handler.RESPONSE_BYTES.duplicate())
    ByteBufUtil.writeAscii(content, " - via " + req.protocolVersion() + " (" + establishApproach + ")")

    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())

    val keepAlive = HttpUtil.isKeepAlive(req)
    if (keepAlive) {
      if (req.protocolVersion().equals(HttpVersion.HTTP_1_0)) {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      }
      ctx.write(response)
    } else {
      // Tell the client we're going to close the connection.
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
      ctx.write(response).addListener(ChannelFutureListener.CLOSE)
    }
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.flush()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause.printStackTrace()
    ctx.close()
  }
}
 */