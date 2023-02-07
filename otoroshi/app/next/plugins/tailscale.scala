package otoroshi.next.plugins

import io.netty.channel.unix.DomainSocketAddress
import otoroshi.env.Env
import otoroshi.netty.EventLoopUtils
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.utils.reactive.ReactiveStreamUtils
import play.api.libs.json._
import reactor.netty.http.client.HttpClient
import otoroshi.utils.syntax.implicits._
import reactor.netty.resources.LoopResources

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Failure

case class TailscaleStatusPeer(raw: JsValue) {
  lazy val id: String = raw.select("ID").asString
  lazy val hostname: String = raw.select("HostName").asString
  lazy val dnsname: String = raw.select("DNSName").asString
  lazy val ipAddress: String = raw.select("TailscaleIPs").asOpt[Seq[String]].flatMap(_.headOption).get
  lazy val online: Boolean = raw.select("Online").asOpt[Boolean].getOrElse(false)
}
case class TailscaleStatus(raw: JsValue) {
  lazy val peers: Seq[TailscaleStatusPeer] = raw.select("Peer").asOpt[JsObject].getOrElse(Json.obj()).value.values.toSeq.map(v => TailscaleStatusPeer(v))
  lazy val onlinePeers: Seq[TailscaleStatusPeer] = peers.filter(_.online)
}
case class TailscaleCert(raw: JsValue)

class TailscaleLocalApiClientLinux(env: Env) {

  private implicit val ec = env.otoroshiExecutionContext

  private val client = HttpClient
    .create()
    .remoteAddress(() => new DomainSocketAddress("/run/tailscale/tailscaled.sock"))
    .runOn(LoopResources.create("epoll-group", 2, true))
    //.runOn(EventLoopUtils.createEpoll(2).group)

  def status(): Future[TailscaleStatus] = {
    val mono = client
      .responseTimeout(java.time.Duration.ofMillis(2000))
      .headers(h => h
        .add("Host", "local-tailscaled.sock")
        .add("Tailscale-Cap", "57")
        .add("Authentication", s"Basic ${":no token on linux".byteString.encodeBase64.utf8String}")
      )
      .get()
      .uri("/localapi/v0/status")
      .responseContent()
      .aggregate()
      .asString()
    ReactiveStreamUtils.MonoUtils.toFuture(mono).map(_.parseJson).map(TailscaleStatus.apply).andThen {
      case Failure(exception) => exception.printStackTrace()
    }
  }

  def fetchCert(domain: String): Future[TailscaleCert] = {
    val mono = client
      .headers(h => h
        .add("Host", "local-tailscaled.sock")
        .add("Tailscale-Cap", "57")
        .add("Authentication", s"Basic ${":no token on linux".byteString.encodeBase64.utf8String}")
      )
      .get()
      .uri(s"/localapi/v0/cert/${domain}?type=pair")
      .responseContent()
      .aggregate()
      .asString()
    ReactiveStreamUtils.MonoUtils.toFuture(mono).map(_.parseJson).map(TailscaleCert.apply)
  }
}

class TailscaleTargetsJob extends Job {


  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.TailscaleTargetsJob")

  override def name: String = "Tailscale targets job"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will aggregates Tailscale possible targets""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println("\n\nfetching tailscale peers")
    val client = new TailscaleLocalApiClientLinux(env)
    client.status().map { status =>
      status.onlinePeers.map { peer =>
        s"  - ${peer.id} - ${peer.dnsname} - ${peer.hostname}"
      }
      .mkString("\n")
      .debugPrintln
    }
  }
}
