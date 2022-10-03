package otoroshi.netty

import io.netty.buffer.{ByteBuf, ByteBufHolder}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise, EventLoopGroup}
import io.netty.handler.codec.http.{HttpRequest, HttpResponse, HttpResponseStatus, HttpUtil, LastHttpContent}
import org.joda.time.DateTime
import reactor.netty.http.server.logging.AccessLogArgProvider

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
      EventLoopGroupCreation(evlGroupHttp, Some("KQeue"))
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

class AccessLogHandler extends ChannelDuplexHandler {

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
    ctx.fireChannelRead(msg);
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
      }
      case response: LastHttpContent => {
        contentLength = contentLength + response.content().readableBytes()
        val duration = System.currentTimeMillis() - start
        val addr     = ctx.channel().remoteAddress().toString // not the right value :(
        AccessLogHandler.logger.info(s"""0.0.0.0 - - [${DateTime
          .now()
          .toString(
            "yyyy-MM-dd HH:mm:ss.SSS Z"
          )}] "${method} ${uri} HTTP/3.0" ${status} ${contentLength} ${duration}""")
        ctx.write(msg, promise.unvoid())
        return
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
