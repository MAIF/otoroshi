package reactor.netty.http.server

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.cookie._
import io.netty.handler.codec.http._
import reactor.core.publisher.Mono
import reactor.netty.{Connection, ConnectionObserver}

import java.util.function.{BiFunction, BiPredicate}

object NettyHelper {
  def toHttpServerOperations(req: HttpServerRequest): reactor.netty.http.server.HttpServerOperations = {
    req.asInstanceOf[reactor.netty.http.server.HttpServerOperations]
  }
  def getContext(req: HttpServerRequest): reactor.util.context.Context = {
    toHttpServerOperations(req).currentContext()
  }
  def getChannel(req: HttpServerRequest): io.netty.channel.Channel = {
    toHttpServerOperations(req).channel()
  }

  def newServerOp(
      nettyRequest: HttpRequest,
      nettyResponse: HttpResponse,
      ctx: ChannelHandlerContext
  ): reactor.netty.http.server.HttpServerOperations = {
    val decoder                                                                  = io.netty.handler.codec.http.cookie.ServerCookieDecoder.LAX
    val encoder                                                                  = io.netty.handler.codec.http.cookie.ServerCookieEncoder.LAX
    val formDecoderProvider                                                      = new HttpServerFormDecoderProvider.Build().build()
    val connection: Connection                                                   = Connection.from(ctx.channel())
    val connectionInfo: ConnectionInfo                                           =
      ConnectionInfo.from(ctx.channel(), nettyRequest, true, ctx.channel().remoteAddress(), (info, req) => info)
    val listener: ConnectionObserver                                             = ???
    val compressionPredicate: BiPredicate[HttpServerRequest, HttpServerResponse] =
      (req: HttpServerRequest, res: HttpServerResponse) => false
    val mapHandle: BiFunction[_ >: Mono[Void], _ >: Connection, _ <: Mono[Void]] =
      (mono: Mono[Void], conn: Connection) => mono
    val resolvePath                                                              = true
    val secured                                                                  = true
    new reactor.netty.http.server.HttpServerOperations(
      connection,
      listener,
      nettyRequest,
      compressionPredicate,
      connectionInfo,
      decoder,
      encoder,
      formDecoderProvider,
      mapHandle,
      resolvePath,
      secured
    )
  }
}
