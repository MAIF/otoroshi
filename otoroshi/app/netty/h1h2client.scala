package otoroshi.netty

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.headers.{`Content-Length`, `Content-Type`, `User-Agent`, RawHeader}
import akka.http.scaladsl.model.{ContentType, HttpHeader, Uri}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.google.common.base.Charsets
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelOption
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.logging.LogLevel
import io.netty.handler.ssl.{ClientAuth, SslContextBuilder}
import org.apache.commons.codec.binary.Base64
import otoroshi.env.Env
import otoroshi.models.{ClientConfig, Target}
import otoroshi.ssl.{Cert, VeryNiceTrustManager}
import otoroshi.utils.http.{AkkaWsClientRequest, MtlsConfig}
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{
  BodyWritable,
  DefaultWSCookie,
  EmptyBody,
  InMemoryBody,
  SourceBody,
  WSAuthScheme,
  WSBody,
  WSCookie,
  WSProxyServer,
  WSRequest,
  WSRequestFilter,
  WSResponse,
  WSSignatureCalculator
}
import play.api.mvc.MultipartFormData
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.ByteBufFlux
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.{HttpClient, HttpClientResponse}
import reactor.netty.tcp.SslProvider
import reactor.netty.transport.ProxyProvider

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import scala.xml.{Elem, XML}

trait TrailerSupport {
  def trailingHeaders(): Future[Map[String, Seq[String]]]
  def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit
}

object NettyHttpClient {
  val logger = Logger("otoroshi-netty-client")
}

class NettyHttpClient(env: Env) {

  // TODO: custom connection provider for clientconfig ?
  // TODO: support websockets
  private val client = HttpClient.create()

  def url(rawUrl: String): NettyWsClientRequest = {
    NettyWsClientRequest(
      client = client,
      _url = rawUrl,
      protocol = None,
      method = "GET",
      body = EmptyBody,
      headers = Map.empty,
      followRedirects = None,
      requestTimeout = None,
      proxy = None,
      targetOpt = None,
      clientConfig = ClientConfig(),
      alreadyFailed = AkkaWsClientRequest.atomicFalse,
      env = env
    )
  }
}

case class NettyWsClientRequest(
    client: HttpClient,
    _url: String,
    protocol: Option[String],
    method: String,
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    followRedirects: Option[Boolean] = None,
    requestTimeout: Option[Duration] = None,
    proxy: Option[WSProxyServer] = None,
    //////
    tlsConfig: Option[MtlsConfig] = None,
    targetOpt: Option[Target] = None,
    clientConfig: ClientConfig = ClientConfig(),
    alreadyFailed: AtomicBoolean = AkkaWsClientRequest.atomicFalse,
    env: Env
) extends WSRequest {

  private val _uri = Uri(_url)

  def withProtocol(proto: String): NettyWsClientRequest                                                  = copy(protocol = proto.some)
  def withTlsConfig(tlsConfig: MtlsConfig): NettyWsClientRequest                                         = copy(tlsConfig = tlsConfig.some)
  def withTarget(target: Target): NettyWsClientRequest                                                   = copy(targetOpt = target.some)
  def withClientConfig(clientConfig: ClientConfig): NettyWsClientRequest                                 = copy(clientConfig = clientConfig)
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
  ///////
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
  ///////
  override def execute(): Future[WSResponse] = {
    val proto = protocol.orElse(targetOpt.map(_.protocol.value)).getOrElse("HTTP/1.1")
    if (proto.toLowerCase().startsWith("http/3")) {
      NettyHttp3ClientWsRequest(
        client = env.http3Client,
        _url = _url,
        protocol = protocol,
        method = method,
        body = body,
        headers = headers,
        followRedirects = followRedirects,
        requestTimeout = requestTimeout,
        proxy = proxy,
        tlsConfig = tlsConfig,
        targetOpt = targetOpt,
        clientConfig = clientConfig
      ).execute()
    } else {
      stream().map(_.asInstanceOf[NettyWsResponse].toStrict())(env.otoroshiExecutionContext)
    }
  }

  override def stream(): Future[WSResponse] = {
    val config = NettyClientConfig.parseFrom(env)
    val proto  = protocol.orElse(targetOpt.map(_.protocol.value)).getOrElse("HTTP/1.1")
    if (proto.toLowerCase().startsWith("http/3")) {
      NettyHttp3ClientWsRequest(
        client = env.http3Client,
        _url = _url,
        protocol = protocol,
        method = method,
        body = body,
        headers = headers,
        followRedirects = followRedirects,
        requestTimeout = requestTimeout,
        proxy = proxy,
        tlsConfig = tlsConfig,
        targetOpt = targetOpt,
        clientConfig = clientConfig
      ).stream()
    } else {
      client
        .disableRetry(false)
        .followRedirect(followRedirects.getOrElse(false))
        .keepAlive(true)
        .compress(false)
        .applyOnIf(config.wiretap)(_.wiretap("otoroshi-experimental-netty-client-wiretap", LogLevel.INFO))
        .applyOn { client =>
          // client config here
          client
            .responseTimeout(java.time.Duration.ofMillis(clientConfig.callAndStreamTimeout))
            .option(
              ChannelOption.CONNECT_TIMEOUT_MILLIS,
              java.lang.Integer.valueOf(clientConfig.connectionTimeout.toInt)
            )
        }
        .applyOn { client =>
          // target config and manual resolving
          val host = targetOpt.map(_.theHost).getOrElse(_uri.authority.host.toString())
          val port = targetOpt.map(_.thePort).getOrElse(_uri.authority.port)
          client
            .host(host)
            .port(port)
            .applyOnWithOpt(targetOpt.flatMap(_.ipAddress)) { case (cli, ip) =>
              cli.remoteAddress(() => InetSocketAddress.createUnresolved(ip, port))
            }
        }
        .applyOn { client =>
          // tls config
          val tls = targetOpt.map(_.scheme.startsWith("https")).getOrElse(_uri.scheme.startsWith("https"))
          if (tls) {
            val tlsConf   = tlsConfig.orElse(targetOpt.map(_.mtlsConfig))
            val customTls = tlsConf.map(c => c.mtls && c.legit).getOrElse(false)
            if (customTls) {
              val certs: Seq[Cert]        = tlsConf.toSeq
                .flatMap(_.actualCerts)
              val trustedCerts: Seq[Cert] = tlsConf.toSeq
                .flatMap(_.actualTrustedCerts)
              val trustAll: Boolean       = tlsConf
                .exists(_.trustAll)
              val ctx                     = SslContextBuilder
                .forClient()
                .applyOn { ctx =>
                  if (NettyHttpClient.logger.isDebugEnabled)
                    NettyHttpClient.logger.debug(
                      s"Calling ${_uri.toString()} with mTLS context of ${certs.size} client certificates and ${trustedCerts.size} trusted certificates ($trustAll)"
                    )
                  certs.map(c => ctx.keyManager(c.cryptoKeyPair.getPrivate, c.certificatesChain: _*))
                  ctx
                }
                .applyOn { ctx =>
                  if (trustAll) {
                    ctx.trustManager(new VeryNiceTrustManager(Seq.empty))
                  } else {
                    ctx.trustManager(trustedCerts.map(_.certificatesChain.head): _*)
                  }
                }
                .build()
              client
                .secure((spec: SslProvider.SslContextSpec) => spec.sslContext(ctx))
              // TODO: if targetOpt.ipAddress, spec.sslContext(ctx).serverNames(new SNIHostName(targetOpt.theHost)))
            } else {
              client.secure()
            }
          } else {
            client.noSSL()
          }
        }
        .applyOn { client =>
          // proxy config
          proxy match {
            case None            => client.noProxy()
            case Some(proxyconf) =>
              client.proxy(spec =>
                spec
                  .`type`(ProxyProvider.Proxy.HTTP)
                  .host(proxyconf.host)
                  .port(proxyconf.port)
                  .applyOnWithOpt(proxyconf.nonProxyHosts.filter(_.nonEmpty)) { case (pr, nph) =>
                    pr.nonProxyHosts(nph.head)
                  }
                  .applyOnWithOpt(proxyconf.principal) { case (pr, principal) =>
                    pr.username(principal)
                  }
                  .applyOnWithOpt(proxyconf.password) { case (pr, password) =>
                    pr.password(usr => password)
                  }
              )
          }
        }
        .applyOnIf(proto.toLowerCase().startsWith("http/2")) { client =>
          val tls = targetOpt.map(_.scheme.startsWith("https")).getOrElse(_uri.scheme.startsWith("https"))
          if (tls) {
            client.protocol(HttpProtocol.H2)
          } else {
            client.protocol(HttpProtocol.H2C)
          }
        }
        .applyOnIf(proto.toLowerCase() == "h2c")(_.protocol(HttpProtocol.H2C))
        .applyOnIf(proto.toLowerCase() == "h2")(_.protocol(HttpProtocol.H2C))
        .applyOnIf(proto.toLowerCase().startsWith("http/1"))(_.protocol(HttpProtocol.HTTP11))
        .applyOnIf(
          proto.toLowerCase().startsWith("http/2") || proto.toLowerCase() == "h2c" || proto.toLowerCase() == "h2"
        ) { client =>
          client.http2Settings(builder => builder.build())
        }
        //.httpResponseDecoder(spec => spec) // TODO: check if needed
        .headers { heads =>
          import collection.JavaConverters._
          headers.foreach { case (name, values) =>
            heads.add(name, values.asJava)
          }
        }
        .request(HttpMethod.valueOf(method.toUpperCase()))
        .uri(_uri.toString())
        .applyOn { client =>
          body match {
            case EmptyBody           =>
              client
                .responseConnection((resp, conn) =>
                  Mono.just(NettyWsResponse(resp, ByteBufFlux.fromInbound(conn.inbound().receive()), _uri, env))
                )
            case InMemoryBody(bytes) =>
              client
                .send(Mono.just(Unpooled.copiedBuffer(bytes.toArray)))
                .responseConnection((resp, conn) =>
                  Mono.just(NettyWsResponse(resp, ByteBufFlux.fromInbound(conn.inbound().receive()), _uri, env))
                )
            case SourceBody(source)  =>
              client
                .send(
                  Flux.from(
                    source
                      .map(chunk => Unpooled.copiedBuffer(chunk.toArray))
                      .runWith(Sink.asPublisher(true))(env.otoroshiMaterializer)
                  )
                )
                .responseConnection((resp, conn) =>
                  Mono.just(NettyWsResponse(resp, ByteBufFlux.fromInbound(conn.inbound().receive()), _uri, env))
                )
          }
        }
        .applyOn(flux => ReactiveStreamUtils.FluxUtils.toFuture(flux))
    }
  }
}

case class NettyWsResponse(resp: HttpClientResponse, bodyflux: ByteBufFlux, _uri: Uri, env: Env)
    extends WSResponse
    with TrailerSupport {

  private lazy val _body: Source[ByteString, _] = {
    val flux: Flux[ByteString] = bodyflux.map { bb =>
      try {
        val builder = ByteString.newBuilder
        bb.readBytes(builder.asOutputStream, bb.readableBytes())
        builder.result()
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          ByteString.empty
      }
    }
    Source.fromPublisher(flux).filter(_.nonEmpty)
  }
  private lazy val _bodyAsBytes: ByteString = {
    Await.result(
      bodyAsSource.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer),
      FiniteDuration(10, TimeUnit.MINUTES)
    ) // AWAIT: valid
  }
  private lazy val _allHeaders: Map[String, Seq[String]] = {
    resp
      .responseHeaders()
      .names()
      .asScala
      .map { name =>
        (name, resp.responseHeaders().getAll(name).asScala.toSeq)
      }
      .toMap
  }
  private lazy val _bodyAsString: String   = _bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem        = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue    = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie] = resp.cookies().asScala.toSeq.flatMap { case (name, cookies) =>
    cookies.asScala.map { cookie =>
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

  override def bodyAsSource: Source[ByteString, _]    = _body
  override def headers: Map[String, Seq[String]]      = _allHeaders
  override def status: Int                            = resp.status().code()
  override def statusText: String                     = resp.status().codeAsText().toString
  override def allHeaders: Map[String, Seq[String]]   = headers
  override def underlying[T]: T                       = resp.asInstanceOf[T]
  override def cookies: Seq[WSCookie]                 = _cookies
  override def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
  override def body: String                           = _bodyAsString
  override def bodyAsBytes: ByteString                = _bodyAsBytes
  override def xml: Elem                              = _bodyAsXml
  override def json: JsValue                          = _bodyAsJson
  override def uri: URI                               = new URI(_uri.toRelative.toString())

  def toStrict(): NettyWsStrictResponse = NettyWsStrictResponse(this, _bodyAsBytes)

  def trailingHeaders(): Future[Map[String, Seq[String]]] = {
    ReactiveStreamUtils.MonoUtils
      .toFuture(resp.trailerHeaders())
      .map { headers =>
        headers
          .names()
          .asScala
          .map { name =>
            (name, headers.getAll(name).asScala.toSeq)
          }
          .toMap
      }(env.otoroshiExecutionContext)
  }

  def registerTrailingHeaders(promise: Promise[Map[String, Seq[String]]]): Unit = {
    ReactiveStreamUtils.MonoUtils
      .toFuture(resp.trailerHeaders())
      .map { headers =>
        headers
          .names()
          .asScala
          .map { name =>
            (name, headers.getAll(name).asScala.toSeq)
          }
          .toMap
      }(env.otoroshiExecutionContext)
      .andThen {
        case Failure(ex)      => promise.tryFailure(ex)
        case Success(headers) => promise.trySuccess(headers)
      }(env.otoroshiExecutionContext)
  }
}

case class NettyWsStrictResponse(resp: NettyWsResponse, bodyAsBytes: ByteString)
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
