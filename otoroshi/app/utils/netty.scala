package otoroshi.utils.netty

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.reactivestreams.Publisher
import otoroshi.env.Env
import otoroshi.next.proxy.ProxyEngine
import otoroshi.script.RequestHandler
import otoroshi.ssl.{ClientAuth, DynamicSSLEngineProvider}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpChunk, HttpEntity}
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import play.api.mvc.{Cookie, Cookies, Headers, Request, Results}
import reactor.netty.http.server.HttpServerRequest

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.security.{Provider, SecureRandom}
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl._
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
  lazy val clientCertificateChain: Option[Seq[X509Certificate]] = {
    if (secure) {
      None // TODO: fixme
    } else {
      None
    }
  }
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
    RequestAttrKey.Cookies -> Cell(Cookies(req.cookies().asScala.toSeq.flatMap {
      case (_, cookies) => cookies.asScala.map {
        case cookie: io.netty.handler.codec.http.cookie.DefaultCookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = Option(cookie.sameSite()).map {
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None =>  play.api.mvc.Cookie.SameSite.None
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict => play.api.mvc.Cookie.SameSite.Strict
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax => play.api.mvc.Cookie.SameSite.Lax
              case _ => play.api.mvc.Cookie.SameSite.None
            }
          )
        }
        case cookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = None
          )
        }
      }
    }))
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

  import reactor.core.publisher.Flux
  import reactor.netty.http.HttpProtocol
  import reactor.netty.http.server._

  implicit private val ec = env.otoroshiExecutionContext
  implicit private val mat = env.otoroshiMaterializer
  implicit private val ev = env

  private val logger = Logger("otoroshi-experiments-reactor-netty-server")

  private val engine: ProxyEngine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get.asInstanceOf[ProxyEngine]

  private lazy val cipherSuites =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
      .filterNot(_.isEmpty)
  private lazy val protocols    =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
      .filterNot(_.isEmpty)
  private lazy val clientAuth = {
    val auth = env.configuration
      .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
      .flatMap(ClientAuth.apply)
      .getOrElse(ClientAuth.None)
    if (DynamicSSLEngineProvider.logger.isDebugEnabled)
      DynamicSSLEngineProvider.logger.debug(s"Otoroshi client auth: ${auth}")
    auth
  }

  private def handle(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean): Publisher[Void] = {
    ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
      engine.handle(new ReactorNettyRequest(req, secure), _ => Results.InternalServerError("bad default routing").vfuture).map { result =>
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
          case (key, value) => headers.add(key, value)
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
          .chunkedTransfer(bresponse.chunked)
          .keepAlive(true)
          .sendByteArray(bresponse.body)
      }
    }
  }

  private def setupSslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     = DynamicSSLEngineProvider.createSSLEngine(clientAuth, cipherSuites, protocols, None)
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

  def start(): Unit = {

    logger.info("")
    logger.info(s"Starting the Reactor Netty Server !!!")
    logger.info(s" - https://127.0.0.1:${env.httpsPort + 50}")
    logger.info(s" - https://127.0.0.1:${env.httpsPort + 51}")
    logger.info(s" - http://127.0.0.1:${env.httpPort + 50}")
    logger.info("")

    val serverHttps = HttpServer
      .create()
      .host("0.0.0.0")
      .accessLog(true) // TODO: from config
      .wiretap(false) // TODO: from config
      .port(env.httpsPort + 50) // TODO: from config
      .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
      .doOnChannelInit { (observer, channel, _) =>
        val engine = setupSslContext().createSSLEngine()
        engine.setHandshakeApplicationProtocolSelector((e, protocols) => {
          protocols match {
            case ps if ps.contains("h2") => "h2"
            case ps if ps.contains("spdy/3") => "spdy/3"
            case _ => "http/1.1"
          }
        })
        // we do not use .secure() because of no dynamic sni support and use SslHandler instead !
        channel.pipeline().addFirst(new SslHandler(engine))
      }
      .handle((req, res) => handle(req, res, true))
      .bindNow()
    val serverHttp = HttpServer
      .create()
      .host("0.0.0.0")
      .noSSL()
      .accessLog(true) // TODO: from config
      .wiretap(false) // TODO: from config
      .port(env.httpPort + 50) // TODO: from config
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