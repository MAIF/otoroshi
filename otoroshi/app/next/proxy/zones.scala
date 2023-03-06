package otoroshi.next.proxy

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.cluster.{ClusterMode, MemberView, RelayRouting}
import otoroshi.env.Env
import otoroshi.gateway.Retry
import otoroshi.models.{Target, TargetPredicate}
import otoroshi.next.models.NgRoute
import otoroshi.ssl.SSLImplicits.EnhancedX509Certificate
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import play.api.mvc._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}

/**
 * java \
 *  -Dhttp.port=8080 \
 *  -Dhttps.port=8443 \
 *  -Dotoroshi.cluster.mode=leader \
 *  -Dapp.adminPassword=password \
 *  -Dapp.storage=file \
 *  -Dotoroshi.cluster.relayRouting.enabled=true \
 *  -Dotoroshi.cluster.relayRouting.location.region=zone1 \
 *  -Dotoroshi.cluster.relayRouting.exposition.hostname=otoroshi-api-zone1.oto.tools \
 *  -Dotoroshi.cluster.relayRouting.exposition.url=http://otoroshi-api-zone1.oto.tools:8080 \
 *  -jar otoroshi.jar
 *
 * java \
 *  -Dhttp.port=8081 \
 *  -Dhttps.port=8444 \
 *  -Dotoroshi.cluster.mode=worker \
 *  -Dotoroshi.cluster.relayRouting.enabled=true \
 *  -Dotoroshi.cluster.relayRouting.location.region=zone2 \
 *  -Dotoroshi.cluster.relayRouting.exposition.hostname=otoroshi-api-zone2.oto.tools \
 *  -Dotoroshi.cluster.relayRouting.exposition.url=http://otoroshi-api-zone2.oto.tools:8081 \
 *  -jar otoroshi.jar
 */

class RelayRoutingResult(resp: WSResponse) extends NgProxyEngineError {
  override def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val cl                             = resp.headers.getIgnoreCase("Content-Length").map(_.last).map(_.toLong)
    val ct                             = resp.headers.getIgnoreCase("Content-Type").map(_.last)
    val setCookie                      = resp.headers
      .get("Otoroshi-Relay-Routing-Response-Header-Set-Cookie")
      .map(vs => vs.flatMap(v => Cookies.decodeSetCookieHeader(v)))
      .getOrElse(Seq.empty[Cookie])
    val headers: Seq[(String, String)] = resp.headers
      .filterNot(_._1 == "Otoroshi-Relay-Routing-Response-Header-Set-Cookie")
      .filter(_._1.startsWith("Otoroshi-Relay-Routing-Response-Header-"))
      .map { case (key, values) =>
        (key.replace("Otoroshi-Relay-Routing-Response-Header-", ""), values.last)
      }
      .toSeq
    Results
      .Status(resp.status)
      .sendEntity(HttpEntity.Streamed(resp.bodyAsSource, cl, ct))
      .withHeaders(headers: _*)
      .applyOnIf(setCookie.nonEmpty)(_.withCookies(setCookie: _*))
      .vfuture
  }
}

case class SelectedLeader(member: MemberView, route: NgRoute, counter: AtomicInteger) {
  def call(req: RequestHeader, body: Source[ByteString, _])(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport
  ): Future[Either[NgProxyEngineError, Done]] = {
    implicit val sched = env.otoroshiScheduler
    Retry.retry(
      times = env.clusterConfig.worker.retries,
      delay = env.clusterConfig.retryDelay,
      factor = env.clusterConfig.retryDelay,
      ctx = "forwarding call through a relay node"
    ) { attempt =>
      val useLeader                      = env.clusterConfig.mode.isWorker && env.clusterConfig.relay.leaderOnly
      val urls                           = if (useLeader) env.clusterConfig.leader.urls else member.relay.exposition.urls
      val index                          = counter.get() % (if (urls.nonEmpty) urls.size else 1)
      val url                            = urls.sortWith((m1, m2) => m1.compareTo(m2) < 0).apply(index)
      val clientId: String               =
        if (useLeader) env.clusterConfig.leader.clientId
        else member.relay.exposition.clientId.getOrElse(env.backOfficeApiKeyClientId)
      val clientSecret: String           = env.proxyState.apikey(clientId).map(_.clientSecret).getOrElse("secret")
      val ct: Option[String]             = req.headers.toSimpleMap.getIgnoreCase("Content-Type")
      val cl: Option[String]             = req.headers.toSimpleMap.getIgnoreCase("Content-Length")
      val host: String                   = if (useLeader) env.clusterConfig.leader.host else member.relay.exposition.hostname
      val ipAddress: Option[String]      = if (useLeader) None else member.relay.exposition.ipAddress
      val mtlsConfig: MtlsConfig         =
        if (useLeader) env.clusterConfig.mtlsConfig else member.relay.exposition.tls.getOrElse(MtlsConfig())
      val headers: Seq[(String, String)] = (Seq(
        ("Host" -> host),
        ("Otoroshi-Client-Id", clientId),
        ("Otoroshi-Client-Secret", clientSecret),
        ("Otoroshi-Relay-Routing-Remote-Addr", req.remoteAddress),
        ("Otoroshi-Relay-Routing-Method", req.method),
        ("Otoroshi-Relay-Routing-Id", req.id.toString),
        ("Otoroshi-Relay-Routing-Uri", req.relativeUri),
        ("Otoroshi-Relay-Routing-Has-Body", req.theHasBody.toString),
        ("Otoroshi-Relay-Routing-Secured", req.theSecured.toString),
        ("Otoroshi-Relay-Routing-Route-Id", route.id),
        ("Otoroshi-Relay-Routing-Route-Name", route.name),
        ("Otoroshi-Relay-Routing-Caller-Id", env.clusterConfig.id),
        ("Otoroshi-Relay-Routing-Caller-Name", env.clusterConfig.name)
      ) ++ req.headers.toSimpleMap.toSeq.map { case (key, value) =>
        (s"Otoroshi-Relay-Routing-Header-${key}", value)
      }).applyOnWithOpt(ct) { case (seq, cty) =>
        seq :+ ("Content-Type", cty)
      }.applyOnWithOpt(cl) { case (seq, clt) =>
        seq :+ ("Content-Length", clt)
      }.applyOnWithOpt(req.clientCertificateChain) { case (seq, certs) =>
        seq ++ certs.zipWithIndex.map { case (c, idx) => (s"Otoroshi-Relay-Routing-Certs-${idx}" -> c.encoded) }
      }.applyOnIf(req.cookies.nonEmpty) { seq =>
        seq :+ ("Otoroshi-Relay-Routing-Cookies", Cookies.encodeCookieHeader(req.cookies.toSeq))
      }
      val uriStr                         = s"$url/api/cluster/relay"
      val uri                            = Uri(uriStr)
      if (useLeader) {
        if (RelayRouting.logger.isDebugEnabled)
          RelayRouting.logger.debug(
            s"forwarding call to '${route.name}' through local leader '${uriStr}' (attempt ${attempt})"
          )
      } else {
        if (RelayRouting.logger.isDebugEnabled)
          RelayRouting.logger.debug(
            s"forwarding call to '${route.name}' through relay '${uriStr}' (attempt ${attempt})"
          )
      }
      env.Ws
        .akkaUrlWithTarget(
          uriStr,
          Target(
            host = uri.authority.toString(),
            scheme = uri.scheme,
            protocol = otoroshi.models.HttpProtocols.HTTP_1_1,
            predicate = TargetPredicate.AlwaysMatch,
            ipAddress = ipAddress,
            mtlsConfig = mtlsConfig
          )
        )
        .withMethod("POST")
        .withRequestTimeout(route.backend.client.globalTimeout.milliseconds)
        .withBody(body)
        .withHttpHeaders(headers: _*)
        .execute()
        .map { resp =>
          Left(new RelayRoutingResult(resp))
        }
    }
  }
}

class PossibleLeaders(members: Seq[MemberView], route: NgRoute) {
  def chooseNext(counter: AtomicInteger)(implicit env: Env): SelectedLeader = {
    val useLeader = env.clusterConfig.mode.isWorker && env.clusterConfig.relay.leaderOnly
    if (useLeader) {
      SelectedLeader(
        MemberView(
          id = "local-leader",
          name = "local-leader",
          os = env.os,
          version = env.otoroshiVersion,
          javaVersion = env.theJavaVersion,
          location = env.clusterConfig.relay.exposition.urls.headOption.getOrElse("127.0.0.1"),
          httpPort = env.exposedHttpPortInt,
          httpsPort = env.exposedHttpsPortInt,
          internalHttpPort = env.httpPort,
          internalHttpsPort = env.httpsPort,
          lastSeen = DateTime.now(),
          timeout = 10.seconds,
          memberType = ClusterMode.Leader,
          relay = env.clusterConfig.relay,
          tunnels = Seq.empty,
          stats = Json.obj()
        ),
        route,
        counter
      )
    } else {
      val selectedMembers = members
        .filter { member =>
          if (route.hasDeploymentProviders) {
            route.deploymentProviders.contains(member.relay.location.provider)
          } else {
            true
          }
        }
        .filter { member =>
          if (route.hasDeploymentRegions) {
            route.deploymentRegions.contains(member.relay.location.region)
          } else {
            true
          }
        }
        .filter { member =>
          if (route.hasDeploymentZones) {
            route.deploymentZones.contains(member.relay.location.zone)
          } else {
            true
          }
        }
        .filter { member =>
          if (route.hasDeploymentDatacenters) {
            route.deploymentDatacenters.contains(member.relay.location.datacenter)
          } else {
            true
          }
        }
        .filter { member =>
          if (route.hasDeploymentRacks) {
            route.deploymentRacks.contains(member.relay.location.rack)
          } else {
            true
          }
        }

      val index  = counter.get() % (if (selectedMembers.nonEmpty) selectedMembers.size else 1)
      val member = selectedMembers.sortWith((m1, m2) => m1.id.compareTo(m2.id) < 0).apply(index)
      SelectedLeader(member, route, counter)
    }
  }
}
