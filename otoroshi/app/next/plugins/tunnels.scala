package otoroshi.next.plugins

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, Tcp}
import akka.util.ByteString
import otoroshi.el.TargetExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginConfig, NgPluginVisibility, NgStep, NgTunnelHandler, NgTunnelHandlerContext}
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.utils.udp.{Datagram, UdpClient}
import play.api.http.websocket.{BinaryMessage, Message}
import play.api.libs.json.Json

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}

class TcpTunnel extends NgTunnelHandler {

  override def steps: Seq[NgStep] = Seq(NgStep.HandlesTunnel)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Tunnel)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = false
  override def core: Boolean               = true
  override def name: String                = "TCP Tunnel"
  override def description: Option[String] = "This plugin creates TCP tunnels through otoroshi".some
  override def isAccessAsync: Boolean      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def handle(
      ctx: NgTunnelHandlerContext
  )(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _] = {
    val target                          = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).get
    val elCtx                           = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)
    val apikey                          = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val user                            = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
    val (theHost: String, thePort: Int) =
      (
        target.scheme,
        TargetExpressionLanguage(
          target.host,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          apikey,
          user,
          elCtx,
          ctx.attrs,
          env
        )
      ) match {
        case (_, host) if host.contains(":")            =>
          (host.split(":").apply(0), host.split(":").apply(1).toInt)
        case (scheme, host) if scheme.contains("https") => (host, 443)
        case (_, host)                                  => (host, 80)
      }
    val remoteAddress                   = target.ipAddress match {
      case Some(ip) =>
        new InetSocketAddress(
          InetAddress.getByAddress(theHost, InetAddress.getByName(ip).getAddress),
          thePort
        )
      case None     => new InetSocketAddress(theHost, thePort)
    }
    val flow: Flow[Message, Message, _] =
      Flow[Message]
        .collect {
          case BinaryMessage(data) =>
            data
          case _                   =>
            ByteString.empty
        }
        .via(
          Tcp()(env.otoroshiActorSystem)
            .outgoingConnection(
              remoteAddress = remoteAddress,
              connectTimeout = ctx.route.backend.client.connectionTimeout.millis,
              idleTimeout = ctx.route.backend.client.idleTimeout.millis
            )
            .map(bs => BinaryMessage(bs))
        )
    flow
  }
}

class UdpTunnel extends NgTunnelHandler {

  override def steps: Seq[NgStep] = Seq(NgStep.HandlesTunnel)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Tunnel)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = false
  override def core: Boolean               = true
  override def name: String                = "UDP Tunnel"
  override def description: Option[String] = "This plugin creates UDP tunnels through otoroshi".some
  override def isAccessAsync: Boolean      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def handle(
      ctx: NgTunnelHandlerContext
  )(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _] = {
    import akka.stream.scaladsl.{Flow, GraphDSL, UnzipWith, ZipWith}
    import GraphDSL.Implicits._
    val base64decoder                   = java.util.Base64.getDecoder
    val base64encoder                   = java.util.Base64.getEncoder
    val target                          = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).get
    val elCtx                           = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)
    val apikey                          = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val user                            = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
    val (theHost: String, thePort: Int) =
      (
        target.scheme,
        TargetExpressionLanguage(
          target.host,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          apikey,
          user,
          elCtx,
          ctx.attrs,
          env
        )
      ) match {
        case (_, host) if host.contains(":")            =>
          (host.split(":").apply(0), host.split(":").apply(1).toInt)
        case (scheme, host) if scheme.contains("https") => (host, 443)
        case (_, host)                                  => (host, 80)
      }
    val remoteAddress                   = target.ipAddress match {
      case Some(ip) =>
        new InetSocketAddress(
          InetAddress.getByAddress(theHost, InetAddress.getByName(ip).getAddress),
          thePort
        )
      case None     => new InetSocketAddress(theHost, thePort)
    }

    val fromJson: Flow[Message, (Int, String, Datagram), NotUsed] =
      Flow[Message].collect {
        case BinaryMessage(data) =>
          val json              = Json.parse(data.utf8String)
          val port: Int         = (json \ "port").as[Int]
          val address: String   = (json \ "address").as[String]
          val _data: ByteString = (json \ "data")
            .asOpt[String]
            .map(str => ByteString(base64decoder.decode(str)))
            .getOrElse(ByteString.empty)
          (port, address, Datagram(_data, remoteAddress))
        case _                   =>
          (0, "localhost", Datagram(ByteString.empty, remoteAddress))
      }

    val updFlow: Flow[Datagram, Datagram, Future[InetSocketAddress]] =
      UdpClient
        .flow(new InetSocketAddress("0.0.0.0", 0))(env.otoroshiActorSystem)

    def nothing[T]: Flow[T, T, NotUsed] = Flow[T].map(identity)

    val flow: Flow[Message, BinaryMessage, NotUsed] = fromJson via Flow
      .fromGraph(GraphDSL.create() { implicit builder =>
        val dispatch = builder.add(
          UnzipWith[(Int, String, Datagram), Int, String, Datagram](a => a)
        )
        val merge    = builder.add(
          ZipWith[Int, String, Datagram, (Int, String, Datagram)]((a, b, c) => (a, b, c))
        )
        dispatch.out2 ~> updFlow.async ~> merge.in2
        dispatch.out1 ~> nothing[String].async ~> merge.in1
        dispatch.out0 ~> nothing[Int].async ~> merge.in0
        FlowShape(dispatch.in, merge.out)
      })
      .map { case (port, address, dg) =>
        BinaryMessage(
          ByteString(
            Json.stringify(
              Json.obj(
                "port"    -> port,
                "address" -> address,
                "data"    -> base64encoder.encodeToString(dg.data.toArray)
              )
            )
          )
        )
      }
    flow
  }
}
