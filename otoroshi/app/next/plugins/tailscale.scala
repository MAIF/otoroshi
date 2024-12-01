package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import io.netty.channel.unix.DomainSocketAddress
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.script._
import otoroshi.ssl.{Cert, PemHeaders}
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{OS, RegexPool}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import reactor.netty.http.client.{HttpClient, HttpClientResponse}
import reactor.netty.resources.DefaultLoopResourcesHelper

import java.io.{BufferedReader, File, FileNotFoundException, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.stream.Collectors
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class TailscaleStatusPeer(raw: JsValue) {
  lazy val id: String        = raw.select("ID").asString
  lazy val hostname: String  = raw.select("HostName").asString
  lazy val dnsname: String   = raw.select("DNSName").asString.applyOnWithPredicate(_.endsWith("."))(_.init)
  lazy val ipAddress: String = raw.select("TailscaleIPs").asOpt[Seq[String]].flatMap(_.headOption).get
  lazy val online: Boolean   = raw.select("Online").asOpt[Boolean].getOrElse(false)
}

case class TailscaleStatus(raw: JsValue) {
  lazy val self: Option[TailscaleStatusPeer]     = raw.select("Self").asOpt[JsObject].map(v => TailscaleStatusPeer(v))
  lazy val peers: Seq[TailscaleStatusPeer]       =
    raw.select("Peer").asOpt[JsObject].getOrElse(Json.obj()).value.values.toSeq.map(v => TailscaleStatusPeer(v))
  lazy val onlinePeers: Seq[TailscaleStatusPeer] = peers.filter(_.online)
  lazy val magicDNSSuffix: Option[String]        = raw.select("MagicDNSSuffix").asOpt[String]
}

case class TailscaleCert(raw: String) {
  lazy val key: String   = if (raw.contains(PemHeaders.BeginPrivateKey)) {
    PemHeaders.BeginPrivateKey + raw
      .split(PemHeaders.BeginPrivateKey)
      .apply(1)
      .split(PemHeaders.EndPrivateKey)
      .apply(0) + PemHeaders.EndPrivateKey
  } else if (raw.contains(PemHeaders.BeginPrivateECKey)) {
    PemHeaders.BeginPrivateECKey + raw
      .split(PemHeaders.BeginPrivateECKey)
      .apply(1)
      .split(PemHeaders.EndPrivateECKey)
      .apply(0) + PemHeaders.EndPrivateECKey
  } else if (raw.contains(PemHeaders.BeginPrivateRSAKey)) {
    PemHeaders.BeginPrivateRSAKey + raw
      .split(PemHeaders.BeginPrivateRSAKey)
      .apply(1)
      .split(PemHeaders.EndPrivateRSAKey)
      .apply(0) + PemHeaders.EndPrivateRSAKey
  } else {
    ""
  }
  lazy val chain: String =
    PemHeaders.BeginCertificate + raw.split(PemHeaders.BeginCertificate).tail.mkString(PemHeaders.BeginCertificate)
}

case class TailscaleCertError(status: Int, body: String)

case class ReactorResponse(response: HttpClientResponse, body: String) {
  def json: JsValue = Json.parse(body)
}

object TailscaleLocalApiClient {
  val logger = Logger("otoroshi-tailscale-local-api-client")
}

class TailscaleLocalApiClient(env: Env) {

  private implicit val ec = env.otoroshiExecutionContext

  private lazy val doesMacOSDomainSocketExists = new File(socketAddress()).exists()

  private lazy val (macOSPort, macOSToken) = {
    // see https://github.com/tailscale/tscert/blob/main/internal/safesocket/safesocket_darwin.go
    val process = sys.runtime.exec(Array("/usr/sbin/lsof", "-n", "-a", "-c", "IPNExtension"))
    val out     = process.getInputStream
    val res     = process.waitFor()
    val outStr  =
      new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"))
    outStr
      .split("\n")
      .find(_.contains("sameuserproof-"))
      .map { line =>
        val parts = line.split("sameuserproof-").apply(1).split("-")
        (parts.head, parts.last)
      }
      .getOrElse {
        ("60000", "token-not-found")
      }
  }

  private val client: HttpClient = if (!doesMacOSDomainSocketExists) {
    HttpClient
      .create()
      .baseUrl(s"http://localhost:${macOSPort}")
  } else {
    HttpClient
      .create()
      .runOn {
        if (OS.isMac) {
          DefaultLoopResourcesHelper.getKQueueLoop("tailscale-group", 2, true)
        } else if (OS.isLinux) {
          DefaultLoopResourcesHelper.getEpollLoop("tailscale-group", 2, true)
        } else {
          DefaultLoopResourcesHelper.getDefaultLoop("tailscale-group", 2, true)
        }
      }
      .remoteAddress(() => new DomainSocketAddress(socketAddress()))
  }

  private def socketAddress(): String = {
    if (OS.isMac) {
      "/var/run/tailscaled.socket"
    } else if (OS.isLinux) {
      val run = new File("/var/run")
      if (run.exists() && run.isDirectory) {
        "/var/run/tailscale/tailscaled.sock"
      } else {
        "/run/tailscale/tailscaled.sock"
      }
    } else if (OS.isWindows) {
      "\\\\.\\pipe\\ProtectedPrefix\\Administrators\\Tailscale\\tailscaled"
    } else {
      "tailscaled.sock"
    }
  }

  private def token(): String = {
    if (OS.isLinux) {
      ":no token on linux".byteString.encodeBase64.utf8String
    } else if (OS.isMac) {
      s":${macOSToken}".byteString.encodeBase64.utf8String
    } else {
      ":no token on windows".byteString.encodeBase64.utf8String // ???
    }
  }

  private def callGet(uri: String): Future[ReactorResponse] = {
    val rec = client
      .responseTimeout(java.time.Duration.ofMillis(2000))
      .headers(h =>
        h
          .add("Host", "local-tailscaled.sock")
          .add("Tailscale-Cap", "57")
          .add("Authorization", s"Basic ${token()}")
      )
      .get()
      .uri(uri)
    (for {
      resp    <- ReactiveStreamUtils.MonoUtils.toFuture(rec.response())
      content <- ReactiveStreamUtils.MonoUtils.toFuture(rec.responseContent().aggregate().asString())
    } yield {
      ReactorResponse(resp, content)
    }).andThen {
      case Failure(_: FileNotFoundException) =>
        TailscaleLocalApiClient.logger.error(
          s"Tailscale socket does not exist at '${socketAddress}'. Maybe tailscaled does not run on your machine ..."
        )
      case Failure(exception)                =>
        TailscaleLocalApiClient.logger.error("Tailscale call failed", exception)
    }
  }

  def status(): Future[TailscaleStatus] = {
    callGet("/localapi/v0/status").map(_.json).map(TailscaleStatus.apply)
  }

  def fetchCert(domain: String): Future[Either[TailscaleCertError, TailscaleCert]] = {
    callGet(s"/localapi/v0/cert/${domain}?type=pair").map { resp =>
      if (resp.response.status().code() == 200 || resp.response.status().code() == 201) {
        Right(TailscaleCert(resp.body))
      } else {
        Left(TailscaleCertError(resp.response.status().code(), resp.body))
      }
    }
  }
}

class TailscaleTargetsJob extends Job {

  private val logger = Logger("otoroshi-job-tailscale-targets")

  private val clientRef = new AtomicReference[TailscaleLocalApiClient]()

  private def client(env: Env): TailscaleLocalApiClient = {
    Option(clientRef.get()).getOrElse {
      clientRef.compareAndSet(null, new TailscaleLocalApiClient(env))
      clientRef.get()
    }
  }

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.TailscaleTargetsJob")

  override def name: String = "Tailscale targets job"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will aggregates Tailscale possible online targets""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 30.seconds.some

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = {
    val config = currentConfig("TailscaleTargetsJob", ctx, env)
    config.map(_.select("predicates").isDefined) match {
      case None        => None
      case Some(false) => None
      case Some(true)  => Some(true)
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val cli = client(env)
    cli.status().map { status =>
      Future.sequence(status.onlinePeers.map { peer =>
        logger.debug(s"found peer: ${peer.id} - ${peer.dnsname} - ${peer.hostname}")
        env.datastores.rawDataStore.set(
          key = s"${env.storageRoot}:plugins:tailscale:targets:${peer.id}",
          value = peer.raw.stringify.byteString,
          ttl = 60.seconds.toMillis.some
        )
      })
    } andThen { case Failure(e) =>
      logger.error("error while fetching tailscale status", e)
    } map (_ => ())
  }
}

case class TailscaleSelectTargetByNameConfig(machineName: String, useIpAddress: Boolean) extends NgPluginConfig {
  def json: JsValue = TailscaleSelectTargetByNameConfig.format.writes(this)
}

object TailscaleSelectTargetByNameConfig {

  val format = new Format[TailscaleSelectTargetByNameConfig] {

    override def writes(o: TailscaleSelectTargetByNameConfig): JsValue = Json.obj(
      "machine_name"   -> o.machineName,
      "use_ip_address" -> o.useIpAddress
    )

    override def reads(json: JsValue): JsResult[TailscaleSelectTargetByNameConfig] = Try {
      TailscaleSelectTargetByNameConfig(
        machineName = json.select("machine_name").asString,
        useIpAddress = json.select("use_ip_address").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

class TailscaleSelectTargetByName extends NgRequestTransformer {

  private val counter = new AtomicLong(0L)
  private val logger  = Logger("otoroshi-plugin-tailscale-select-target-by-name")

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.ServiceDiscovery)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false

  override def name: String                                = "Tailscale select target by name"
  override def description: Option[String]                 =
    "This plugin selects a machine instance on Tailscale network based on its name".some
  override def defaultConfigObject: Option[NgPluginConfig] = TailscaleSelectTargetByNameConfig("my-machine", false).some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val useIpAddress = ctx.config.select("use_ip_address").asOpt[Boolean].getOrElse(false)
    ctx.config.select("machine_name").asOpt[String] match {
      case None           =>
        Left(
          Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no machine name found !"))
        ).vfuture
      case Some(hostname) => {
        val targetTemplate = ctx.route.backend.allTargets.head
        env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:plugins:tailscale:targets:*").map { items =>
          val allPeers      = items.map(_.utf8String.parseJson).map(TailscaleStatusPeer.apply)
          val possiblePeers = if (hostname.contains("*")) {
            allPeers.filter(p => RegexPool.apply(hostname).matches(p.hostname))
          } else if (hostname.startsWith("Regex(")) {
            val regex = hostname.substring(6).init
            allPeers.filter(p => RegexPool.regex(regex).matches(p.hostname))
          } else {
            allPeers.filter(p => hostname == p.hostname)
          }
          logger.debug(s"possible peers for '${hostname}': ${possiblePeers.size}")
          if (possiblePeers.isEmpty) {
            Left(
              Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no matching resource found !"))
            )
          } else {
            val index  = counter.incrementAndGet() % (if (possiblePeers.nonEmpty) possiblePeers.size else 1)
            val peer   = possiblePeers.apply(index.toInt)
            logger.debug(s"selected peer for '$hostname': ${peer.id} - ${peer.hostname} - ${peer.dnsname}")
            val target = targetTemplate
              .copy(
                id = peer.id,
                hostname = peer.dnsname
              )
              .applyOnIf(useIpAddress)(_.copy(ipAddress = peer.ipAddress.some))
            ctx.otoroshiRequest
              .copy(
                backend = target.some,
                url = ctx.otoroshiRequest.uri
                  .copy(
                    authority = ctx.otoroshiRequest.authority.copy(
                      host = akka.http.scaladsl.model.Uri.Host.apply(peer.dnsname)
                    )
                  )
                  .toString
              )
              .right
          }
        }
      }
    }
  }
}

class TailscaleCertificatesFetcherJob extends Job {

  private val logger = Logger("otoroshi-job-tailscale-certificates-fetcher")

  private val clientRef = new AtomicReference[TailscaleLocalApiClient]()

  private def client(env: Env): TailscaleLocalApiClient = {
    Option(clientRef.get()).getOrElse {
      clientRef.compareAndSet(null, new TailscaleLocalApiClient(env))
      clientRef.get()
    }
  }

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.TailscaleCertificatesFetcherJob")

  override def name: String = "Tailscale certificate fetcher job"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will fetch certificates from Tailscale ACME provider""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.minute.some

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = {
    val config = currentConfig("TailscaleCertificatesFetcherJob", ctx, env)
    config.map(_.select("predicates").isDefined) match {
      case None        => None
      case Some(false) => None
      case Some(true)  => Some(true)
    }
  }

  def certAlreadyExistsFor(domain: String)(implicit env: Env, ec: ExecutionContext): Boolean = {
    env.proxyState
      .allCertificates()
      .filter(_.notExpired)
      .filter(_.notExpiredSoon)
      .exists(_.allDomains.contains(domain))
  }

  def syncTailscaleCerts(ctx: JobContext, magicDNSSuffix: String)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val domains      = env.proxyState
      .allRoutes()
      .filter(_.frontend.domains.exists(_.domainLowerCase.endsWith(s".${magicDNSSuffix.toLowerCase()}")))
      .flatMap(_.frontend.domains.map(_.domainLowerCase))
      .filter(_.endsWith(s".${magicDNSSuffix.toLowerCase()}"))
      .distinct
    Source(domains.toList)
      .filterNot(certAlreadyExistsFor)
      .mapAsync(1) { domain =>
        client(env)
          .fetchCert(domain)
          .map(resp => (domain, resp))
          .andThen {
            case Failure(e)              => logger.error(s"error while fetching tailscale cert for '$domain'", e)
            case Success((_, Left(err))) =>
              logger.error(s"error while fetching tailscale cert for '$domain': ${err.status} - ${err.body}")
          }
      }
      .collect { case (domain, Right(cert)) =>
        (domain, cert)
      }
      .mapAsync(1) { case (domain, cert) =>
        Cert(s"tailscale cert for '$domain'", cert.chain, cert.key)
          .copy(entityMetadata = Map("otoroshi-provider" -> "tailscale-cert-job"))
          .save()
      }
      .runWith(Sink.ignore)
      .map(_ => ())
  }

  def syncSelf(ctx: JobContext, self: TailscaleStatusPeer)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (!certAlreadyExistsFor(self.dnsname)) {
      client(env)
        .fetchCert(self.dnsname)
        .andThen {
          case Failure(e)         => logger.error(s"error while fetching tailscale self cert for '${self.dnsname}'", e)
          case Success(Left(err)) =>
            logger.error(s"error while fetching tailscale self cert for '${self.dnsname}': ${err.status} - ${err.body}")
        }
        .collect { case Right(value) =>
          value
        }
        .flatMap { cert =>
          Cert(s"tailscale cert for '${self.dnsname}'", cert.chain, cert.key)
            .copy(entityMetadata = Map("otoroshi-provider" -> "tailscale-cert-job"))
            .save()
        }
        .map(_ => ())
    } else {
      ().vfuture
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val cli = client(env)
    cli.status().flatMap { status =>
      status.magicDNSSuffix match {
        case None                 => ().vfuture
        case Some(magicDNSSuffix) => {
          for {
            _ <- syncTailscaleCerts(ctx, magicDNSSuffix)
            _ <- status.self match {
                   case None       => ().vfuture
                   case Some(self) => syncSelf(ctx, self)
                 }
          } yield ()
        }
      }
    } andThen { case Failure(e) =>
      logger.error("error while fetching tailscale status", e)
    } map (_ => ())
  }
}
