package reactor.netty.http.server

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
}
