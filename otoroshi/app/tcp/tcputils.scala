package akka

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.{MatchResult, Pattern}

import akka.actor.ActorSystem
import akka.io.Inet.SocketOption
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source, TLS, Tcp}
import akka.stream.{IgnoreComplete, TLSClosing, TLSProtocol}
import akka.util.ByteString
import javax.net.ssl.{SSLEngine, SSLSession}

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.Try

case class AwesomeIncomingConnection(underlying: IncomingConnection, domain: Future[String]) {
  def localAddress: InetSocketAddress             = underlying.localAddress
  def remoteAddress: InetSocketAddress            = underlying.remoteAddress
  def flow: Flow[ByteString, ByteString, NotUsed] = underlying.flow
}

object TcpUtils {

  val domainNamePattern                                                                                                = Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]")
  private val tlsWrapping: BidiFlow[ByteString, TLSProtocol.SendBytes, TLSProtocol.SslTlsInbound, ByteString, NotUsed] =
    BidiFlow.fromFlows(
      Flow[ByteString].map(TLSProtocol.SendBytes),
      Flow[TLSProtocol.SslTlsInbound].collect { case sb: TLSProtocol.SessionBytes =>
        sb.bytes
      // ignore other kinds of inbounds (currently only Truncated)
      }
    )
  def bindTlsWithSSLEngine(
      interface: String,
      port: Int,
      createSSLEngine: () => SSLEngine,
      backlog: Int = 100,
      options: immutable.Seq[SocketOption] = Nil,
      idleTimeout: Duration = Duration.Inf,
      verifySession: SSLSession => Try[Unit],
      closing: TLSClosing = IgnoreComplete
  )(implicit system: ActorSystem): Source[IncomingConnection, Future[ServerBinding]] = {
    Tcp().bindWithTls(
      interface = interface,
      port = port,
      createSSLEngine = createSSLEngine,
      backlog = backlog,
      options = options,
      idleTimeout = idleTimeout,
      verifySession = verifySession,
      closing = closing
    )
  }

  def bindTlsWithSSLEngineAndSNI(
      interface: String,
      port: Int,
      createSSLEngine: () => SSLEngine,
      backlog: Int = 100,
      options: immutable.Traversable[SocketOption] = Nil,
      idleTimeout: Duration = Duration.Inf,
      verifySession: SSLSession => Try[Unit],
      closing: TLSClosing = IgnoreComplete
  )(implicit system: ActorSystem): Source[AwesomeIncomingConnection, Future[ServerBinding]] = {
    val tls = tlsWrapping.atop(TLS(createSSLEngine, verifySession, closing)).reversed
    val tcp = Tcp()
    tcp.bind(interface, port, backlog, options, true, idleTimeout).map { incomingConnection =>
      val promise    = Promise[String]
      val firstChunk = new AtomicBoolean(false)
      AwesomeIncomingConnection(
        incomingConnection.copy(
          flow = incomingConnection.flow
            .alsoTo(Sink.foreach { bs =>
              if (firstChunk.compareAndSet(false, true)) {
                val packetString = bs.utf8String
                val matcher      = domainNamePattern.matcher(packetString)
                while (matcher.find()) {
                  val matchResult: MatchResult = matcher.toMatchResult
                  val expression: String       = matchResult.group()
                  promise.trySuccess(expression)
                }
                if (!promise.isCompleted) {
                  promise.tryFailure(new RuntimeException("SNI not found !"))
                }
              }
            })
            .join(tls)
        ),
        promise.future
      )
    }
  }
}
