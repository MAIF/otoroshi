package otoroshi.netty

import akka.util.ByteString
import io.netty.buffer.{ByteBuf, ByteBufHolder}
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise, EventLoopGroup}
import io.netty.handler.codec.http._
import io.netty.incubator.codec.quic.QuicConnectionEvent
import org.joda.time.DateTime
import otoroshi.utils.syntax.implicits._
import reactor.netty.resources.LoopResources
import play.core.NamedThreadFactory
import sangria.schema.InstanceCheck.field

import java.lang.reflect.{Constructor, Field, Modifier}
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

sealed trait TlsVersion {
  def name: String
}
object TlsVersion       {
  case class Unknown(raw: String) extends TlsVersion { def name: String = s"Unknown($raw)" }
  case class SSL(raw: String)     extends TlsVersion { def name: String = s"SSL($raw)"     }
  case object SSLv1               extends TlsVersion { def name: String = s"SSLv1"         }
  case object SSLv2               extends TlsVersion { def name: String = s"SSLv2"         }
  case object TLS_1_0             extends TlsVersion { def name: String = s"TLSv1"         }
  case object TLS_1_1             extends TlsVersion { def name: String = s"TLSv1.1"       }
  case object TLS_1_2             extends TlsVersion { def name: String = s"TLSv1.2"       }
  case object TLS_1_3             extends TlsVersion { def name: String = s"TLSv1.3"       }
  def parse(version: String): TlsVersion             = parseSafe(version).getOrElse(Unknown(version))
  def parseSafe(version: String): Option[TlsVersion] = version match {
    case "TLSv1.3"                                => TLS_1_3.some
    case "TLSv1.1"                                => TLS_1_1.some
    case "TLSv1"                                  => TLS_1_0.some
    case v if v.toLowerCase().startsWith("sslv1") => SSLv1.some
    case v if v.toLowerCase().startsWith("sslv2") => SSLv2.some
    case v if v.toLowerCase().startsWith("ssl")   => SSL(v).some
    case _                                        => None
  }
}

case class EventLoopGroupCreation(group: EventLoopGroup, native: Option[String])

object EventLoopUtils {

  private val threadFactory = NamedThreadFactory("otoroshi-netty-event-loop")

  def createWithoutNative(nThread: Int): EventLoopGroupCreation = {
    val channelHttp  = new NioServerSocketChannel()
    val evlGroupHttp = new NioEventLoopGroup(nThread, threadFactory)
    evlGroupHttp.register(channelHttp)
    EventLoopGroupCreation(evlGroupHttp, None)
  }

  def createEpoll(nThread: Int): EventLoopGroupCreation = {
    val channelHttp  = new io.netty.channel.epoll.EpollServerSocketChannel()
    val evlGroupHttp = new io.netty.channel.epoll.EpollEventLoopGroup(nThread, threadFactory)
    evlGroupHttp.register(channelHttp).sync().await()
    EventLoopGroupCreation(evlGroupHttp, Some("Epoll"))
  }

  def createEpollDomainSocket(nThread: Int): EventLoopGroupCreation = {
    println(s"available: ${io.netty.channel.epoll.Epoll.isAvailable}")
    val channelHttp  = new EpollDomainSocketChannel()
    val evlGroupHttp = new io.netty.channel.epoll.EpollEventLoopGroup(nThread, threadFactory)
    evlGroupHttp.register(channelHttp).sync().await()
    EventLoopGroupCreation(evlGroupHttp, Some("Epoll"))
  }

  def create(config: NativeSettings, nThread: Int): EventLoopGroupCreation = {
    // LoopResources.create("epoll-loop", 1, nThread, true)
    if (config.isEpoll && io.netty.channel.epoll.Epoll.isAvailable) {
      // java.lang.IllegalStateException: channel not registered to an event loop on linux !!!
      val channelHttp  = new io.netty.channel.epoll.EpollServerSocketChannel()
      val evlGroupHttp = new io.netty.channel.epoll.EpollEventLoopGroup(nThread, threadFactory)
      evlGroupHttp.register(channelHttp).sync().await()
      EventLoopGroupCreation(evlGroupHttp, Some("Epoll"))
    } else if (config.isIOUring && io.netty.incubator.channel.uring.IOUring.isAvailable) {
      val channelHttp  = new io.netty.incubator.channel.uring.IOUringServerSocketChannel()
      val evlGroupHttp = new io.netty.incubator.channel.uring.IOUringEventLoopGroup(nThread, threadFactory)
      evlGroupHttp.register(channelHttp).sync().await()
      EventLoopGroupCreation(evlGroupHttp, Some("IO-Uring"))
    } else if (config.isKQueue && io.netty.channel.kqueue.KQueue.isAvailable) {
      val channelHttp  = new io.netty.channel.kqueue.KQueueServerSocketChannel()
      val evlGroupHttp = new io.netty.channel.kqueue.KQueueEventLoopGroup(nThread, threadFactory)
      evlGroupHttp.register(channelHttp).sync().await()
      EventLoopGroupCreation(evlGroupHttp, Some("KQueue"))
    } else {
      val channelHttp  = new NioServerSocketChannel()
      val evlGroupHttp = new NioEventLoopGroup(nThread, threadFactory)
      evlGroupHttp.register(channelHttp)
      EventLoopGroupCreation(evlGroupHttp, None)
    }
  }
}

object AccessLogHandler {
  val logger = reactor.util.Loggers.getLogger("reactor.netty.http.server.AccessLog")
}

class AccessLogHandler(addressGet: () => String) extends ChannelDuplexHandler {

  var method: String      = "NONE"
  var status: Int         = 0
  var uri: String         = "NONE"
  var start: Long         = 0L
  var contentLength: Long = 0L

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    msg match {
      case request: HttpRequest => {
        start = System.currentTimeMillis()
        method = request.method().name()
        uri = request.uri()
      }
      case _                    =>
    }
    ctx.fireChannelRead(msg)
  }

  def lastContent(ctx: ChannelHandlerContext, response: LastHttpContent, promise: ChannelPromise): Unit = {
    contentLength = contentLength + response.content().readableBytes()
    val duration = System.currentTimeMillis() - start
    val addr     = addressGet()
    val session  = ctx.channel() match {
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
    val protocol = session.map(_.getProtocol).flatMap(TlsVersion.parseSafe).map(_.name).getOrElse("-")
    AccessLogHandler.logger.info(s"""${addr} - - [${DateTime
      .now()
      .toString(
        "dd/MMM/yyyy:HH:mm:ss Z"
        //"yyyy-MM-dd HH:mm:ss.SSS Z"
      )}] "${method} ${uri} HTTP/3.0" ${status} ${contentLength} ${duration} ${protocol}""")
    ctx.write(response, promise.unvoid())
  }

  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise) {
    msg match {
      case response: HttpResponse    => {
        val s       = response.status()
        status = s.code()
        if (s.equals(HttpResponseStatus.CONTINUE)) {
          ctx.write(msg, promise)
          return
        }
        val chunked = HttpUtil.isTransferEncodingChunked(response)
        if (!chunked) {
          contentLength = contentLength + HttpUtil.getContentLength(response, 0)
        }
        msg match {
          case response: LastHttpContent => lastContent(ctx, response, promise)
          case _                         =>
        }
      }
      case response: LastHttpContent => {
        lastContent(ctx, response, promise)
      }
      case msg: ByteBuf              => {
        contentLength = contentLength + msg.readableBytes()
      }
      case msg: ByteBufHolder        => {
        contentLength = contentLength + msg.content().readableBytes()
      }
      case _                         =>
    }
    // ctx.write(msg, promise)
  }
}

object ImplicitUtils {
  implicit class BetterByteBuf(val buf: ByteBuf) extends AnyVal {
    def readContentAsByteString(): ByteString = {
      val builder = ByteString.newBuilder
      buf.readBytes(builder.asOutputStream, buf.readableBytes())
      val bytes   = builder.result()
      bytes
    }
  }
}
