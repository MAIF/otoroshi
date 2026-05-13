package otoroshi.netty

import io.netty.buffer.{ByteBufAllocator, Unpooled}
import io.netty.channel.{ChannelHandlerContext, ChannelOutboundHandler, ChannelPromise}
import io.netty.handler.codec.{EncoderException, UnsupportedMessageTypeException}
import io.netty.handler.codec.http.*
import io.netty.incubator.codec.http3.*
import io.netty.incubator.codec.quic.QuicStreamChannel
import otoroshi.utils.syntax.implicits.given

import java.net.SocketAddress

class CustomHttp3FrameToHttpObjectCodec extends Http3RequestStreamInboundHandler with ChannelOutboundHandler {

  val validateHeaders = true

  override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame): Unit = {
    val isLast  = false
    val headers = frame.headers()
    val status  = headers.status()
    val id      = ctx.channel().asInstanceOf[QuicStreamChannel].streamId()
    if (null != status && HttpResponseStatus.CONTINUE.codeAsText().contentEquals(status)) {
      val fullMsg = newFullMessage(id, headers, ctx.alloc())
      ctx.fireChannelRead(fullMsg)
      return
    }
    if (isLast) {
      if (headers.method() == null && status == null) {
        val last = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER)
        Http3ConversionUtil.addHttp3ToHttpHeaders(
          id,
          headers,
          last.trailingHeaders(),
          HttpVersion.HTTP_1_1,
          isTrailer = true,
          isRequest = true
        )
        ctx.fireChannelRead(last)
      } else {
        val full = newFullMessage(id, headers, ctx.alloc())
        ctx.fireChannelRead(full)
      }
    } else {
      val req = newMessage(id, headers)
      if (!HttpUtil.isContentLengthSet(req)) {
        req.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
      }
      ctx.fireChannelRead(req)
    }
  }

  override def channelInputClosed(ctx: ChannelHandlerContext): Unit = {
    ctx.close()
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.fireChannelReadComplete()
  }

  override def channelRead(
      ctx: ChannelHandlerContext,
      frame: io.netty.incubator.codec.http3.Http3DataFrame
  ): Unit = {
    val isLast = false
    if (isLast) {
      ctx.fireChannelRead(new DefaultLastHttpContent(frame.content()))
    } else {
      ctx.fireChannelRead(new DefaultHttpContent(frame.content()))
    }
  }

  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit = {
    if (!msg.isInstanceOf[HttpObject]) {
      throw new UnsupportedMessageTypeException()
    }
    msg match {
      case res: HttpResponse     =>
        if (res.status().equals(HttpResponseStatus.CONTINUE)) {
          res match {
            case fres: FullHttpResponse =>
              val headers = toHttp3Headers(res)
              ctx.write(new DefaultHttp3HeadersFrame(headers))
              fres.release()
              return
            case _                      => throw new EncoderException(HttpResponseStatus.CONTINUE.toString() + " must be a FullHttpResponse")
          }
        }
      case msg: HttpMessage      =>
        val headers = toHttp3Headers(msg)
        ctx.write(new DefaultHttp3HeadersFrame(headers))
      case last: LastHttpContent =>
        val readable    = last.content().isReadable()
        val hasTrailers = !last.trailingHeaders().isEmpty()

        if (readable) {
          ctx.write(new DefaultHttp3DataFrame(last.content()))
        }
        if (hasTrailers) {
          val headers = Http3ConversionUtil.toHttp3Headers(last.trailingHeaders(), validateHeaders)
          ctx.write(new DefaultHttp3HeadersFrame(headers))
        }
        if (!readable) {
          last.release();
        }
        ctx.channel().asInstanceOf[QuicStreamChannel].shutdownOutput()
      case content: HttpContent  => ctx.write(new DefaultHttp3DataFrame(content.content()))
      case _                     => throw new RuntimeException("error")
    }
  }

  def toHttp3Headers(msg: HttpMessage): io.netty.incubator.codec.http3.Http3Headers = {
    msg match {
      case r: HttpRequest => msg.headers().set(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), HttpScheme.HTTPS)
      case _              =>
    }
    Http3ConversionUtil.toHttp3Headers(msg, validateHeaders)
  }

  def newMessage(id: Long, headers: io.netty.incubator.codec.http3.Http3Headers): HttpMessage = {
    Http3ConversionUtil.toHttpRequest(id, headers, validateHeaders)
  }

  def newFullMessage(
      id: Long,
      headers: io.netty.incubator.codec.http3.Http3Headers,
      alloc: ByteBufAllocator
  ): FullHttpMessage = {
    Http3ConversionUtil.toFullHttpRequest(id, headers, alloc, validateHeaders)
  }

  override def flush(ctx: ChannelHandlerContext): Unit = {
    ctx.flush()
  }

  override def bind(ctx: ChannelHandlerContext, localAddress: SocketAddress, promise: ChannelPromise): Unit = {
    ctx.bind(localAddress, promise)
  }

  override def connect(
      ctx: ChannelHandlerContext,
      remoteAddress: SocketAddress,
      localAddress: SocketAddress,
      promise: ChannelPromise
  ): Unit = {
    ctx.connect(remoteAddress, localAddress, promise)
  }

  override def disconnect(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
    ctx.disconnect(promise)
  }

  override def close(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
    ctx.close(promise)
  }

  override def deregister(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
    ctx.deregister(promise)
  }

  override def read(ctx: ChannelHandlerContext): Unit = {
    ctx.read()
  }
}
