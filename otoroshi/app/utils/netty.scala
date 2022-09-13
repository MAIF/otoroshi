package otoroshi.utils.netty

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.netty.handler.codec.http._
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.SelfSignedCertificate
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

  import reactor.core.publisher.Flux
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