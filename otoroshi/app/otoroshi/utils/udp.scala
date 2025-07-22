package otoroshi.utils.udp

import java.net.InetSocketAddress

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import scala.concurrent.{Future, Promise}

final class Datagram(val data: ByteString, val remote: InetSocketAddress) {

  def withData(data: ByteString): Datagram                                        = copy(data = data)
  def withRemote(remote: InetSocketAddress): Datagram                             = copy(remote = remote)
  def copy(data: ByteString = data, remote: InetSocketAddress = remote) = new Datagram(data, remote)

  override def toString: String =
    s"""Datagram(
       |  data   = $data
       |  remote = $remote
       |)""".stripMargin
}

object Datagram {
  def apply(data: ByteString, remote: InetSocketAddress) = new Datagram(data, remote)
}

object UdpClient {
  def flow(
      localAddress: InetSocketAddress
  )(implicit system: ActorSystem): Flow[Datagram, Datagram, Future[InetSocketAddress]] = {
    Flow.fromGraph(new UdpBindFlow(localAddress))
  }
}

private[utils] final class UdpBindLogic(localAddress: InetSocketAddress, boundPromise: Promise[InetSocketAddress])(
    val shape: FlowShape[Datagram, Datagram]
)(implicit val system: ActorSystem)
    extends GraphStageLogic(shape) {

  private def in  = shape.in
  private def out = shape.out

  private var listener: ActorRef = _

  override def preStart(): Unit = {
    implicit val sender: ActorRef = getStageActor(processIncoming).ref
    IO(Udp) ! Udp.Bind(sender, localAddress)
  }

  override def postStop(): Unit = {
    unbindListener()
  }

  private def processIncoming(event: (ActorRef, Any)): Unit =
    event match {
      case (sender, Udp.Bound(boundAddress))     =>
        boundPromise.success(boundAddress)
        listener = sender
        pull(in)
      case (_, Udp.CommandFailed(cmd: Udp.Bind)) =>
        val ex = new IllegalArgumentException(s"Unable to bind to [${cmd.localAddress}]")
        boundPromise.failure(ex)
        failStage(ex)
      case (s, Udp.Received(data, sender))       =>
        if (isAvailable(out)) {
          // println("received: " + data.utf8String)
          push(out, Datagram(data, sender))
        }
      case _                                     =>
    }

  private def unbindListener(): Unit =
    if (listener != null) {
      listener ! Udp.Unbind
    }

  setHandler(
    in,
    new InHandler {
      override def onPush(): Unit = {
        val msg: Datagram = grab(in)
        // println("sent: " + msg.data.utf8String)
        listener ! Udp.Send(msg.data, msg.remote)
        pull(in)
      }
    }
  )

  setHandler(
    out,
    new OutHandler {
      override def onPull(): Unit = ()
    }
  )
}

private[utils] final class UdpBindFlow(localAddress: InetSocketAddress)(implicit val system: ActorSystem)
    extends GraphStageWithMaterializedValue[FlowShape[Datagram, Datagram], Future[InetSocketAddress]] {
  val in: Inlet[Datagram]                  = Inlet("UdpBindFlow.in")
  val out: Outlet[Datagram]                = Outlet("UdpBindFlow.in")
  val shape: FlowShape[Datagram, Datagram] = FlowShape.of(in, out)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[InetSocketAddress]) = {
    val boundPromise = Promise[InetSocketAddress]()
    (new UdpBindLogic(localAddress, boundPromise)(shape), boundPromise.future)
  }
}
