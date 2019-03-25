package akka

import akka.actor.ActorSystem
import akka.io.Inet.SocketOption
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.{IgnoreComplete, TLSClosing}
import akka.stream.scaladsl.{Source, Tcp}
import javax.net.ssl.{SSLEngine, SSLSession}

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

object TcpUtils {
  def bindTlsWithSSLEngine(
    interface:       String,
    port:            Int,
    createSSLEngine: () ⇒ SSLEngine,
    backlog:         Int                                 = 100,
    options:         immutable.Traversable[SocketOption] = Nil,
    idleTimeout:     Duration                            = Duration.Inf,
    verifySession:   SSLSession ⇒ Try[Unit],
    closing:         TLSClosing                          = IgnoreComplete)(implicit system: ActorSystem): Source[IncomingConnection, Future[ServerBinding]] = {
    Tcp().bindTlsWithSSLEngine(
      interface = interface,
      port = port,
      createSSLEngine = createSSLEngine,
      backlog = backlog,
      options = options,
      idleTimeout = idleTimeout,
      verifySession = verifySession,
      closing = closing,
    )
  }
}
