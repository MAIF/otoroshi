package otoroshi.next.proxy

import akka.Done
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.cluster.MemberView
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.ssl.SSLImplicits.EnhancedX509Certificate
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.ws.WSResponse
import play.api.mvc._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}

/**

java \
  -Dhttp.port=8080 \
  -Dhttps.port=8443 \
  -Dotoroshi.cluster.mode=leader \
  -Dapp.adminPassword=password \
  -Dapp.storage=file \
  -Dotoroshi.cluster.regionalRouting.enabled=true \
  -Dotoroshi.cluster.regionalRouting.location.region=zone1 \
  -Dotoroshi.cluster.regionalRouting.exposition.hostname=otoroshi-api-zone1.oto.tools \
  -Dotoroshi.cluster.regionalRouting.exposition.url=http://otoroshi-api-zone1.oto.tools:8080 \
  -jar otoroshi.jar


java \
  -Dhttp.port=8081 \
  -Dhttps.port=8444 \
  -Dotoroshi.cluster.mode=worker \
  -Dotoroshi.cluster.regionalRouting.enabled=true \
  -Dotoroshi.cluster.regionalRouting.location.region=zone2 \
  -Dotoroshi.cluster.regionalRouting.exposition.hostname=otoroshi-api-zone2.oto.tools \
  -Dotoroshi.cluster.regionalRouting.exposition.url=http://otoroshi-api-zone2.oto.tools:8081 \
  -jar otoroshi.jar

 */

class RegionalRoutingResult(resp: WSResponse) extends NgProxyEngineError {
  override def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val cl = resp.headers.getIgnoreCase("Content-Length").map(_.last).map(_.toLong)
    val ct = resp.headers.getIgnoreCase("Content-Type").map(_.last)
    val setCookie = resp.headers.get("Otoroshi-Regional-Routing-Response-Header-Set-Cookie").map(vs => vs.flatMap(v => Cookies.decodeSetCookieHeader(v))).getOrElse(Seq.empty[Cookie])
    val headers: Seq[(String, String)] = resp.headers
      .filterNot(_._1 == "Otoroshi-Regional-Routing-Response-Header-Set-Cookie")
      .filter(_._1.startsWith("Otoroshi-Regional-Routing-Response-Header-")).map {
        case (key, values) => (key.replace("Otoroshi-Regional-Routing-Response-Header-", ""), values.last)
      }.toSeq
    Results
      .Status(resp.status).sendEntity(HttpEntity.Streamed(resp.bodyAsSource, cl, ct))
      .withHeaders(headers: _*)
      .applyOnIf(setCookie.nonEmpty)(_.withCookies(setCookie: _*))
      .vfuture
  }
}

case class SelectedLeader(member: MemberView, route: NgRoute, counter: AtomicInteger) {
  def call(req: RequestHeader, body: Source[ByteString, _])(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport): Future[Either[NgProxyEngineError, Done]] = {
    val urls = member.regionalRouting.exposition.urls
    val index = counter.get() % (if (urls.nonEmpty) urls.size else 1)
    val url = urls.apply(index)
    val clientId = member.regionalRouting.exposition.clientId.getOrElse(env.backOfficeApiKeyClientId)
    val clientSecret = env.proxyState.apikey(clientId).map(_.clientSecret).getOrElse("secret")
    val ct = req.headers.toSimpleMap.getIgnoreCase("Content-Type")
    val cl = req.headers.toSimpleMap.getIgnoreCase("Content-Length")
    val headers: Seq[(String, String)] = (Seq(
      ("Host" -> member.regionalRouting.exposition.hostname),
      ("Otoroshi-Client-Id", clientId),
      ("Otoroshi-Client-Secret", clientSecret),
      ("Otoroshi-Regional-Routing-Remote-Addr", req.remoteAddress),
      ("Otoroshi-Regional-Routing-Method", req.method),
      ("Otoroshi-Regional-Routing-Id", req.id.toString),
      ("Otoroshi-Regional-Routing-Uri", req.relativeUri),
      ("Otoroshi-Regional-Routing-Has-Body", req.theHasBody.toString),
      ("Otoroshi-Regional-Routing-Secured", req.theSecured.toString),
    ) ++ req.headers.toSimpleMap.toSeq.map {
      case (key, value) => (s"Otoroshi-Regional-Routing-Header-${key}", value)
    }).applyOnWithOpt(ct) {
      case (seq, cty) => seq :+ ("Content-Type", cty)
    }.applyOnWithOpt(cl) {
      case (seq, clt) => seq :+ ("Content-Length", clt)
    }.applyOnWithOpt(req.clientCertificateChain) {
      case (seq, certs) => seq :+ ("Otoroshi-Regional-Routing-Certs", certs.map(c => c.encoded).mkString(","))
    }.applyOnIf(req.cookies.nonEmpty) { seq =>
      seq :+ ("Otoroshi-Regional-Routing-Cookies", Cookies.encodeCookieHeader(req.cookies.toSeq))
    }
    env.Ws.url(s"$url/api/cluster/_regional_routing")
      .withMethod("POST")
      .withRequestTimeout(route.backend.client.globalTimeout.milliseconds)
      .withBody(body)
      .withHttpHeaders(headers: _*)
      .execute()
      .map { resp =>
        Left(new RegionalRoutingResult(resp))
      }
  }
}

class PossibleLeaders(members: Seq[MemberView], route: NgRoute) {
  def chooseNext(counter: AtomicInteger): SelectedLeader = {
    val selectedMembers = members
      .filter { member =>
        if (route.hasDeploymentProviders) {
          route.deploymentProviders.contains(member.regionalRouting.location.provider)
        } else {
          true
        }
      }
      .filter { member =>
        if (route.hasDeploymentRegions) {
          route.deploymentRegions.contains(member.regionalRouting.location.region)
        } else {
          true
        }
      }
      .filter { member =>
        if (route.hasDeploymentZones) {
          route.deploymentZones.contains(member.regionalRouting.location.zone)
        } else {
          true
        }
      }
      .filter { member =>
        if (route.hasDeploymentDatacenters) {
          route.deploymentDatacenters.contains(member.regionalRouting.location.datacenter)
        } else {
          true
        }
      }
      .filter { member =>
        if (route.hasDeploymentRacks) {
          route.deploymentRacks.contains(member.regionalRouting.location.rack)
        } else {
          true
        }
      }

    val index = counter.get() % (if (selectedMembers.nonEmpty) selectedMembers.size else 1)
    val member = selectedMembers.apply(index)
    SelectedLeader(member, route, counter)
  }
}