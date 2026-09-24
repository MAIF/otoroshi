package otoroshi.utils

import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.apache.pekko.io.Inet
import play.api.Logger
import play.core.server.{PekkoHttpServer, ServerProvider}

import java.net.{DatagramSocket, ServerSocket, Socket, SocketException}

/**
 * Applies a socket option to an accepted connection without letting a dead peer take the listener down.
 *
 * pekko applies the configured socket options to every accepted socket before announcing the connection, and
 * does not guard that call. On macOS, setting an option on a socket the peer has already reset fails with
 * `SocketException: Invalid argument`: the connection actor dies before sending `Connected`, and the listener,
 * which only resumes accepting once a connection has been announced, never accepts again. A single client that
 * resets its connection right after the handshake is enough to freeze the port for good, with no log and no
 * busy thread.
 *
 * Swallowing the failure is safe: the socket is already dead, and the connection fails on its first read like
 * any other reset connection, which releases it normally.
 */
final case class ResetTolerantSocketOption(underlying: Inet.SocketOption) extends Inet.SocketOption {
  override def beforeDatagramBind(ds: DatagramSocket): Unit   = underlying.beforeDatagramBind(ds)
  override def beforeServerSocketBind(ss: ServerSocket): Unit = underlying.beforeServerSocketBind(ss)
  override def beforeConnect(s: Socket): Unit                 = underlying.beforeConnect(s)
  override def afterConnect(s: Socket): Unit                  =
    try underlying.afterConnect(s)
    catch {
      case e: SocketException =>
        ResetTolerantSocketOption.logger.debug(s"could not apply $underlying on an incoming connection: ${e.getMessage}")
    }
}

object ResetTolerantSocketOption {

  private val logger = Logger("otoroshi-server-socket-options")

  // a SocketOptionV2 also has afterBind hooks this wrapper does not forward, so it is kept as is. None of the
  // options pekko-http reads from its configuration is one
  def wrap(option: Inet.SocketOption): Inet.SocketOption = option match {
    case v2: Inet.SocketOptionV2 => v2
    case other                   => ResetTolerantSocketOption(other)
  }
}

/** The Play server, with the configured socket options made safe to apply on a connection that is already gone. */
class OtoroshiPekkoHttpServer(context: PekkoHttpServer.Context) extends PekkoHttpServer(context) {

  // called from the parent constructor, while the ports are being bound: nothing here may read a field of this class
  override protected def createServerSettings(
      port: Int,
      connectionContext: ConnectionContext,
      secure: Boolean
  ): ServerSettings = {
    val settings = super.createServerSettings(port, connectionContext, secure)
    settings.withSocketOptions(settings.socketOptions.map(ResetTolerantSocketOption.wrap))
  }
}

/** Selected by `play.server.provider`, so that `ProdServerStart` builds an [[OtoroshiPekkoHttpServer]]. */
class OtoroshiPekkoHttpServerProvider extends ServerProvider {
  override def createServer(context: ServerProvider.Context): PekkoHttpServer =
    new OtoroshiPekkoHttpServer(PekkoHttpServer.Context.fromServerProviderContext(context))
}
