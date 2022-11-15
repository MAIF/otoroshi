package otoroshi.netty

import akka.util.ByteString
import io.netty.buffer.{ByteBuf, ByteBufHolder}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise, EventLoopGroup}
import io.netty.handler.codec.http._
import io.netty.incubator.codec.quic.QuicConnectionEvent
import org.joda.time.DateTime

case class EventLoopGroupCreation(group: EventLoopGroup, native: Option[String])

object EventLoopUtils {
  def create(config: NativeSettings, nThread: Int): EventLoopGroupCreation = {
    if (config.isEpoll && io.netty.channel.epoll.Epoll.isAvailable) {
      val channelHttp  = new io.netty.channel.epoll.EpollServerSocketChannel()
      val evlGroupHttp = new io.netty.channel.epoll.EpollEventLoopGroup(nThread)
      evlGroupHttp.register(channelHttp)
      EventLoopGroupCreation(evlGroupHttp, Some("Epoll"))
    } else if (config.isIOUring && io.netty.incubator.channel.uring.IOUring.isAvailable) {
      val channelHttp  = new io.netty.incubator.channel.uring.IOUringServerSocketChannel()
      val evlGroupHttp = new io.netty.incubator.channel.uring.IOUringEventLoopGroup(nThread)
      evlGroupHttp.register(channelHttp)
      EventLoopGroupCreation(evlGroupHttp, Some("IO-Uring"))
    } else if (config.isKQueue && io.netty.channel.kqueue.KQueue.isAvailable) {
      val channelHttp  = new io.netty.channel.kqueue.KQueueServerSocketChannel()
      val evlGroupHttp = new io.netty.channel.kqueue.KQueueEventLoopGroup(nThread)
      evlGroupHttp.register(channelHttp)
      EventLoopGroupCreation(evlGroupHttp, Some("KQueue"))
    } else {
      val channelHttp  = new NioServerSocketChannel()
      val evlGroupHttp = new NioEventLoopGroup(nThread)
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
    val session                  = ctx.channel() match {
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
    AccessLogHandler.logger.info(s"""${addr} - - [${DateTime
      .now()
      .toString(
        "dd/MMM/yyyy:HH:mm:ss Z"
        //"yyyy-MM-dd HH:mm:ss.SSS Z"
      )}] "${method} ${uri} HTTP/3.0" ${status} ${contentLength} ${duration} ${session.map(_.getProtocol).getOrElse("")}""")
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
          case _ =>
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
    ctx.write(msg, promise)
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
