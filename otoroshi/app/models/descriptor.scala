package otoroshi.models

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import akka.http.scaladsl.model.{HttpProtocol, HttpProtocols}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import otoroshi.auth._
import com.auth0.jwt.JWT
import com.google.common.hash.Hashing
import com.risksense.ipaddr.IpNetwork
import otoroshi.env.Env
import otoroshi.gateway.Errors
import org.joda.time.DateTime
import otoroshi.el.RedirectionExpressionLanguage
import otoroshi.plugins.oidc.{OIDCThirdPartyApiKeyConfig, ThirdPartyApiKeyConfig}
import play.api.Logger
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.libs.json._
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.{DefaultWSProxyServer, WSProxyServer}
import play.api.mvc.Results.{NotFound, TooManyRequests}
import play.api.mvc.{RequestHeader, Result, Results}
import otoroshi.script._
import otoroshi.script.plugins.Plugins
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.storage.BasicStore
import otoroshi.storage.stores.KvServiceDescriptorDataStore
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi.utils.config.ConfigUtils
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.ReplaceAllWith
import otoroshi.utils.http.MtlsConfig

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import otoroshi.utils.http.RequestImplicits._

case class ServiceDescriptorQuery(
    subdomain: String,
    line: String = "prod",
    domain: String,
    root: String = "/",
    matchingHeaders: Map[String, String] = Map.empty[String, String]
) {

  def asKey(implicit _env: Env): String = s"${_env.storageRoot}:desclookup:$line:$domain:$subdomain:$root"

  def toHost: String =
    subdomain match {
      case s if s.isEmpty && line == "prod" => s"$domain"
      case s if s.isEmpty                   => s"$line.$domain"
      case s if line == "prod"              => s"$subdomain.$domain"
      case s                                => s"$subdomain.$line.$domain"
    }

  private val existsCache     = new java.util.concurrent.ConcurrentHashMap[String, Boolean]
  private val serviceIdsCache = new java.util.concurrent.ConcurrentHashMap[String, Seq[String]]
  private val servicesCache   = new java.util.concurrent.ConcurrentHashMap[String, Seq[ServiceDescriptor]]

  def exists()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key = this.asKey
    if (!existsCache.containsKey(key)) {
      env.datastores.serviceDescriptorDataStore.fastLookupExists(this).andThen { case scala.util.Success(ex) =>
        existsCache.put(key, ex)
      }
    } else {
      env.datastores.serviceDescriptorDataStore.fastLookupExists(this).andThen { case scala.util.Success(ex) =>
        existsCache.put(key, ex)
      }
      FastFuture.successful(existsCache.get(key))
    }
  }

  def get()(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] = {
    val key = this.asKey
    if (!serviceIdsCache.containsKey(key)) {
      env.datastores.serviceDescriptorDataStore.getFastLookups(this).andThen { case scala.util.Success(ex) =>
        serviceIdsCache.put(key, ex)
      }
    } else {
      env.datastores.serviceDescriptorDataStore.getFastLookups(this).andThen { case scala.util.Success(ex) =>
        serviceIdsCache.put(key, ex)
      }
      FastFuture.successful(serviceIdsCache.get(key))
    }
  }

  def getServices(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    val key = this.asKey
    get().flatMap { ids =>
      if (!servicesCache.containsKey(key)) {
        env.datastores.serviceDescriptorDataStore.findAllById(ids, force).andThen { case scala.util.Success(ex) =>
          servicesCache.put(key, ex)
        }
      } else {
        env.datastores.serviceDescriptorDataStore.findAllById(ids, force).andThen { case scala.util.Success(ex) =>
          servicesCache.put(key, ex)
        }
        FastFuture.successful(servicesCache.get(key))
      }
    }
  }

  def addServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    if (services.isEmpty) {
      FastFuture.successful(true)
    } else {
      val key = this.asKey
      existsCache.put(key, true)
      serviceIdsCache.put(key, services.map(_.id))
      servicesCache.put(key, services)
      env.datastores.serviceDescriptorDataStore.addFastLookups(this, services)
    }
  }

  def remServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key        = this.asKey
    val servicesId = services.map(_.id)
    val resulting  =
      if (servicesCache.containsKey(key)) servicesCache.get(key).filterNot(s => servicesId.contains(s.id))
      else Seq.empty[ServiceDescriptor]
    if (resulting.isEmpty) {
      existsCache.put(key, false)
      servicesCache.remove(key)
      serviceIdsCache.remove(key)
    } else {
      existsCache.put(key, true)
      serviceIdsCache.put(key, resulting.map(_.id))
      servicesCache.put(key, resulting)
    }
    env.datastores.serviceDescriptorDataStore.removeFastLookups(this, services)
  }
}

case class ServiceLocation(domain: String, env: String, subdomain: String)

object ServiceLocation {

  def fullQuery(host: String, config: GlobalConfig): Option[ServiceLocation] = {
    val hostName = if (host.contains(":")) host.split(":")(0) else host
    hostName.split("\\.").toSeq.reverse match {
      case Seq(tld, domain, env, tail @ _*) if tail.nonEmpty && config.lines.contains(env) =>
        Some(ServiceLocation(s"$domain.$tld", env, tail.reverse.mkString(".")))
      case Seq(tld, domain, tail @ _*) if tail.nonEmpty                                    =>
        Some(ServiceLocation(s"$domain.$tld", "prod", tail.reverse.mkString(".")))
      case Seq(domain, subdomain)                                                          => Some(ServiceLocation(s"$domain", "prod", subdomain))
      case Seq(domain)                                                                     => Some(ServiceLocation(s"$domain", "prod", ""))
      case _                                                                               => None
    }
  }

  def apply(host: String, config: GlobalConfig): Option[ServiceLocation] = fullQuery(host, config)
}

case class ApiDescriptor(exposeApi: Boolean = false, openApiDescriptorUrl: Option[String] = None) {
  def toJson = ApiDescriptor.format.writes(this)
}

object ApiDescriptor {
  implicit val format = Json.format[ApiDescriptor]
}

case class BaseQuotas(
    throttlingQuota: Long = BaseQuotas.MaxValue,
    dailyQuota: Long = BaseQuotas.MaxValue,
    monthlyQuota: Long = BaseQuotas.MaxValue
) {
  def toJson = BaseQuotas.format.writes(this)
}

object BaseQuotas {
  implicit val format = Json.format[BaseQuotas]
  val MaxValue: Long  = RemainingQuotas.MaxValue
}

trait LoadBalancing {
  def needTrackingCookie: Boolean
  def toJson: JsValue
  def select(
      reqId: String,
      trackingId: String,
      requestHeader: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target
}

object LoadBalancing {
  val format: Format[LoadBalancing] = new Format[LoadBalancing] {
    override def writes(o: LoadBalancing): JsValue             = o.toJson
    override def reads(json: JsValue): JsResult[LoadBalancing] =
      (json \ "type").as[String] match {
        case "RoundRobin"               => JsSuccess(RoundRobin)
        case "Random"                   => JsSuccess(Random)
        case "Sticky"                   => JsSuccess(Sticky)
        case "IpAddressHash"            => JsSuccess(IpAddressHash)
        case "BestResponseTime"         => JsSuccess(BestResponseTime)
        case "WeightedBestResponseTime" =>
          JsSuccess(WeightedBestResponseTime((json \ "ratio").asOpt[Double].getOrElse(0.5)))
        case _                          => JsSuccess(RoundRobin)
      }
  }
}

object RoundRobin extends LoadBalancing {
  private val reqCounter                   = new AtomicInteger(0)
  override def needTrackingCookie: Boolean = false
  override def toJson: JsValue             = Json.obj("type" -> "RoundRobin")
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val index: Int = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    targets.apply(index)
  }

}

object Random extends LoadBalancing {
  private val random                       = new scala.util.Random
  override def needTrackingCookie: Boolean = false
  override def toJson: JsValue             = Json.obj("type" -> "Random")
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val index = random.nextInt(targets.length)
    targets.apply(index)
  }
}

object Sticky extends LoadBalancing {
  override def needTrackingCookie: Boolean = true
  override def toJson: JsValue             = Json.obj("type" -> "Sticky")
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val hash: Int  = Math.abs(scala.util.hashing.MurmurHash3.stringHash(trackingId))
    val index: Int = Hashing.consistentHash(hash, targets.size)
    targets.apply(index)
  }
}

object IpAddressHash extends LoadBalancing {
  override def needTrackingCookie: Boolean = false
  override def toJson: JsValue             = Json.obj("type" -> "IpAddressHash")
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val remoteAddress = req.theIpAddress
    val hash: Int     = Math.abs(scala.util.hashing.MurmurHash3.stringHash(remoteAddress))
    val index: Int    = Hashing.consistentHash(hash, targets.size)
    targets.apply(index)
  }
}

case class AtomicAverage(count: AtomicLong, sum: AtomicLong) {
  def incrBy(v: Long): Unit = {
    count.incrementAndGet()
    sum.addAndGet(v)
  }
  def average: Long = sum.get / count.get
}

object BestResponseTime extends LoadBalancing {

  private[models] val random        = new scala.util.Random
  private[models] val responseTimes = new TrieMap[String, AtomicAverage]()

  def incrementAverage(desc: ServiceDescriptor, target: Target, responseTime: Long): Unit = {
    val key = s"${desc.id}-${target.asKey}"
    val avg = responseTimes.getOrElseUpdate(key, AtomicAverage(new AtomicLong(0), new AtomicLong(0)))
    avg.incrBy(responseTime)
  }

  override def needTrackingCookie: Boolean = false
  override def toJson: JsValue             = Json.obj("type" -> "BestResponseTime")
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val keys                     = targets.map(t => s"${desc.id}-${t.asKey}")
    val existing                 = responseTimes.toSeq.filter(t => keys.exists(k => t._1 == k))
    val nonExisting: Seq[String] = keys.filterNot(k => responseTimes.contains(k))
    if (existing.size != targets.size) {
      nonExisting.headOption.flatMap(h => targets.find(t => s"${desc.id}-${t.asKey}" == h)).getOrElse {
        val index = random.nextInt(targets.length)
        targets.apply(index)
      }
    } else {
      val possibleTargets: Seq[(String, Long)] = existing.map(t => (t._1, t._2.average))
      val (key, _)                             = possibleTargets.minBy(_._2)
      targets.find(t => s"${desc.id}-${t.asKey}" == key).getOrElse {
        val index = random.nextInt(targets.length)
        targets.apply(index)
      }
    }
  }
}

case class WeightedBestResponseTime(ratio: Double) extends LoadBalancing {
  override def needTrackingCookie: Boolean = false
  override def toJson: JsValue             = Json.obj("type" -> "WeightedBestResponseTime", "ratio" -> ratio)
  override def select(
      reqId: String,
      trackingId: String,
      req: RequestHeader,
      targets: Seq[Target],
      desc: ServiceDescriptor
  )(implicit env: Env): Target = {
    val keys                     = targets.map(t => s"${desc.id}-${t.asKey}")
    val existing                 = BestResponseTime.responseTimes.toSeq.filter(t => keys.exists(k => t._1 == k))
    val nonExisting: Seq[String] = keys.filterNot(k => BestResponseTime.responseTimes.contains(k))
    if (existing.size != targets.size) {
      nonExisting.headOption.flatMap(h => targets.find(t => s"${desc.id}-${t.asKey}" == h)).getOrElse {
        val index: Int = BestResponseTime.random.nextInt(targets.length)
        targets.apply(index)
      }
    } else {
      val possibleTargets: Seq[(String, Long)] = existing.map(t => (t._1, t._2.average))
      val (key, _)                             = possibleTargets.minBy(_._2)
      val cleanRatio: Double                   = if (ratio < 0.0) 0.0 else if (ratio > 0.99) 0.99 else ratio
      val times: Int                           = Math.round(targets.size / (1 - cleanRatio)).toInt - targets.size
      val bestTarget: Option[Target]           = targets.find(t => s"${desc.id}-${t.asKey}" == key)
      val fill: Seq[Target]                    = bestTarget.map(t => Seq.fill(times)(t)).getOrElse(Seq.empty[Target])
      val newTargets: Seq[Target]              = targets ++ fill
      val index: Int                           = BestResponseTime.random.nextInt(newTargets.length)
      newTargets.apply(index)
    }
  }
}

trait TargetPredicate {
  def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean
  def toJson: JsValue
}

object TargetPredicate {
  val format: Format[TargetPredicate] = new Format[TargetPredicate] {
    override def writes(o: TargetPredicate): JsValue = o.toJson
    override def reads(json: JsValue): JsResult[TargetPredicate] = {
      (json \ "type").as[String] match {
        // case "RegionMatch" => JsSuccess(RegionMatch(
        //   region = (json \ "region").asOpt[String].getOrElse("local")
        // ))
        // case "ZoneMatch" => JsSuccess(ZoneMatch(
        //   zone = (json \ "zone").asOpt[String].getOrElse("local")
        // ))
        // case "DataCenterMatch" => JsSuccess(ZoneMatch(
        //   zone = (json \ "dc").asOpt[String].getOrElse("local")
        // ))
        // case "InfraMatch" => JsSuccess(ZoneMatch(
        //   zone = (json \ "provider").asOpt[String].getOrElse("local")
        // ))
        // case "RackMatch" => JsSuccess(ZoneMatch(
        //   zone = (json \ "rack").asOpt[String].getOrElse("local")
        // ))
        case "AlwaysMatch"          => JsSuccess(AlwaysMatch)
        case "GeolocationMatch"     =>
          JsSuccess(
            GeolocationMatch(
              positions = (json \ "positions")
                .asOpt[Seq[String]]
                .map(_.map(_.split(";").toList.map(_.trim)).collect { case lat :: lng :: radius :: Nil =>
                  GeoPositionRadius(lat.toDouble, lng.toDouble, radius.toDouble)
                })
                .getOrElse(Seq.empty)
            )
          )
        case "NetworkLocationMatch" =>
          JsSuccess(
            NetworkLocationMatch(
              provider = (json \ "provider").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
              region = (json \ "region").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
              zone = (json \ "zone").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
              dataCenter = (json \ "dc").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
              rack = (json \ "rack").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*")
            )
          )
        case _                      => JsSuccess(AlwaysMatch)
      }
    }
  }
}

case class GeoPositionRadius(latitude: Double, longitude: Double, radius: Double) {
  def toJson: JsValue                         = JsString(s"$latitude:$longitude:$radius")
  def near(lat: Double, lng: Double): Boolean =
    Math.acos(
      Math.sin(latitude) * Math.sin(lat) + Math.cos(latitude) * Math.cos(lat) * Math.cos(lng - longitude)
    ) * 6371 <= radius
}

case class GeolocationMatch(positions: Seq[GeoPositionRadius]) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "GeolocationMatch", "positions" -> JsArray(positions.map(_.toJson)))
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
      case None         => true
      case Some(geoloc) => {
        val lat = ((geoloc \ "latitude").as[Double] * Math.PI) / 180.0
        val lng = ((geoloc \ "longitude").as[Double] * Math.PI) / 180.0
        positions.exists(_.near(lat, lng))
      }
    }
  }
}

object AlwaysMatch extends TargetPredicate {
  def toJson: JsValue                                                                                  = Json.obj("type" -> "AlwaysMatch")
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = true
}

case class RegionMatch(region: String) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "RegionMatch", "region" -> region)
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    env.region.trim.toLowerCase == region.trim.toLowerCase
  }
}

case class ZoneMatch(zone: String) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "ZoneMatch", "zone" -> zone)
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    env.zone.trim.toLowerCase == zone.trim.toLowerCase
  }
}

case class DataCenterMatch(dc: String) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "DataCenterMatch", "dc" -> dc)
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    env.dataCenter.trim.toLowerCase == dc.trim.toLowerCase
  }
}

case class InfraProviderMatch(provider: String) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "InfraProviderMatch", "provider" -> provider)
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    env.infraProvider.trim.toLowerCase == provider.trim.toLowerCase
  }
}

case class RackMatch(rack: String) extends TargetPredicate {
  def toJson: JsValue = Json.obj("type" -> "RackMatch", "rack" -> rack)
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    env.rack.trim.toLowerCase == rack.trim.toLowerCase
  }
}

case class NetworkLocationMatch(
    provider: String = "*",
    region: String = "*",
    zone: String = "*",
    dataCenter: String = "*",
    rack: String = "*"
) extends TargetPredicate {
  def toJson: JsValue =
    Json.obj(
      "type"     -> "NetworkLocationMatch",
      "provider" -> provider,
      "region"   -> region,
      "zone"     -> zone,
      "dc"       -> dataCenter,
      "rack"     -> rack
    )
  override def matches(reqId: String, req: RequestHeader, attrs: TypedMap)(implicit env: Env): Boolean = {
    otoroshi.utils.RegexPool(provider.trim.toLowerCase).matches(env.infraProvider.trim.toLowerCase) &&
    otoroshi.utils.RegexPool(region.trim.toLowerCase).matches(env.region.trim.toLowerCase) &&
    otoroshi.utils.RegexPool(zone.trim.toLowerCase).matches(env.zone.trim.toLowerCase) &&
    otoroshi.utils.RegexPool(dataCenter.trim.toLowerCase).matches(env.dataCenter.trim.toLowerCase) &&
    otoroshi.utils.RegexPool(rack.trim.toLowerCase).matches(env.rack.trim.toLowerCase)
  }
}

case class Target(
    host: String,
    scheme: String = "https",
    weight: Int = 1,
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
    predicate: TargetPredicate = AlwaysMatch,
    ipAddress: Option[String] = None,
    mtlsConfig: MtlsConfig = MtlsConfig()
) {

  def toJson        = Target.format.writes(this)
  def json          = toJson
  def asUrl         = s"${scheme}://$host"
  def asKey         = s"${protocol.value}:$scheme://$host@${ipAddress.getOrElse(host)}"
  def asTargetStr   = s"$scheme://$host@${ipAddress.getOrElse(host)}"
  def asCleanTarget = s"$scheme://$host${ipAddress.map(v => s"@$v").getOrElse("")}"

  lazy val thePort: Int = if (host.contains(":")) {
    host.split(":").last.toInt
  } else
    scheme.toLowerCase() match {
      case "http"  => 80
      case "https" => 443
      case _       => 80
    }

  lazy val theHost: String = if (host.contains(":")) {
    host.split(":").init.mkString("")
  } else host
}

object Target {
  val format = new Format[Target] {
    override def writes(o: Target): JsValue             =
      Json.obj(
        "host"       -> o.host,
        "scheme"     -> o.scheme,
        "weight"     -> o.weight,
        "mtlsConfig" -> o.mtlsConfig.json,
        // "loose"     -> o.loose,
        // "mtls"      -> o.mtls,
        // "certId"    -> o.certId.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "protocol"   -> o.protocol.value,
        "predicate"  -> o.predicate.toJson,
        "ipAddress"  -> o.ipAddress.map(JsString.apply).getOrElse(JsNull).as[JsValue]
      )
    override def reads(json: JsValue): JsResult[Target] =
      Try {
        Target(
          host = (json \ "host").as[String],
          scheme = (json \ "scheme").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("https"),
          weight = (json \ "weight").asOpt[Int].getOrElse(1),
          mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue]),
          // loose = (json \ "loose").asOpt[Boolean].getOrElse(false),
          // mtls = (json \ "mtls").asOpt[Boolean].getOrElse(false),
          // certId = (json \ "certId").asOpt[String].filter(_.trim.nonEmpty),
          protocol = (json \ "protocol")
            .asOpt[String]
            .filterNot(_.trim.isEmpty)
            .map(s => HttpProtocol.apply(s))
            .getOrElse(HttpProtocols.`HTTP/1.1`),
          predicate = (json \ "predicate").asOpt(TargetPredicate.format).getOrElse(AlwaysMatch),
          ipAddress = (json \ "ipAddress").asOpt[String].filterNot(_.trim.isEmpty)
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        JsError(t.getMessage)
      } get
  }
}

case class IpFiltering(whitelist: Seq[String] = Seq.empty[String], blacklist: Seq[String] = Seq.empty[String]) {
  def toJson = IpFiltering.format.writes(this)
  def matchesWhitelist(ipAddress: String): Boolean = {
    if (whitelist.nonEmpty) {
      whitelist.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(ipAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(ipAddress)
        }
      }
    } else {
      false
    }
  }
  def notMatchesWhitelist(ipAddress: String): Boolean = {
    if (whitelist.nonEmpty) {
      !whitelist.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(ipAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(ipAddress)
        }
      }
    } else {
      false
    }
  }
  def matchesBlacklist(ipAddress: String): Boolean = {
    if (blacklist.nonEmpty) {
      blacklist.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(ipAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(ipAddress)
        }
      }
    } else {
      false
    }
  }
}

object IpFiltering {
  implicit val format      = Json.format[IpFiltering]
  private val networkCache = new TrieMap[String, IpNetwork]()
  def network(cidr: String): IpNetwork = {
    networkCache.getOrElseUpdate(cidr, IpNetwork(cidr))
  }
}

case class HealthCheck(enabled: Boolean, url: String) {
  def toJson = HealthCheck.format.writes(this)
}

object HealthCheck {
  implicit val format = Json.format[HealthCheck]
}

case class CustomTimeouts(
    path: String = "/*",
    connectionTimeout: Long = 10000,
    idleTimeout: Long = 60000,
    callAndStreamTimeout: Long = 1.hour.toMillis,
    callTimeout: Long = 30000,
    globalTimeout: Long = 30000
) {
  def toJson: JsValue = CustomTimeouts.format.writes(this)
}

object CustomTimeouts {

  lazy val logger = Logger("otoroshi-custom-timeouts")

  implicit val format = new Format[CustomTimeouts] {

    override def reads(json: JsValue): JsResult[CustomTimeouts] =
      Try {
        CustomTimeouts(
          path = (json \ "path").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
          connectionTimeout = (json \ "connectionTimeout").asOpt[Long].getOrElse(10000),
          idleTimeout = (json \ "connectionTimeout").asOpt[Long].getOrElse(60000),
          callAndStreamTimeout = (json \ "callAndStreamTimeout").asOpt[Long].getOrElse(1.hour.toMillis),
          callTimeout = (json \ "callTimeout").asOpt[Long].getOrElse(30000),
          globalTimeout = (json \ "globalTimeout").asOpt[Long].getOrElse(30000)
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading CustomTimeouts", t)
        JsError(t.getMessage)
      } get

    override def writes(o: CustomTimeouts): JsValue =
      Json.obj(
        "path"                 -> o.path,
        "callTimeout"          -> o.callTimeout,
        "callAndStreamTimeout" -> o.callAndStreamTimeout,
        "connectionTimeout"    -> o.connectionTimeout,
        "idleTimeout"          -> o.idleTimeout,
        "globalTimeout"        -> o.globalTimeout
      )
  }
}

case class ClientConfig(
    useCircuitBreaker: Boolean = true,
    retries: Int = 1,
    maxErrors: Int = 20,
    retryInitialDelay: Long = 50,
    backoffFactor: Long = 2,
    connectionTimeout: Long = 10000,
    idleTimeout: Long = 60000,
    callAndStreamTimeout: Long =
      120000, // http client timeout per call with streaming from otoroshi to client included (actually end the call)
    callTimeout: Long =
      30000,  // circuit breaker timeout per call (soft, streaming from otoroshi to client not included)
    globalTimeout: Long =
      30000,  // circuit breaker timeout around all calls (soft, streaming from otoroshi to client not included)
    sampleInterval: Long = 2000,
    proxy: Option[WSProxyServer] = None,
    customTimeouts: Seq[CustomTimeouts] = Seq.empty[CustomTimeouts]
) {
  def toJson                                                                                            = ClientConfig.format.writes(this)
  def timeouts(path: String): Option[CustomTimeouts] = {
    if (customTimeouts.isEmpty) None
    else customTimeouts.find(c => otoroshi.utils.RegexPool(c.path).matches(path))
  }
  def extractTimeout(path: String, f: CustomTimeouts => Long, f2: ClientConfig => Long): FiniteDuration =
    timeouts(path).map(f).getOrElse(f2(this)).millis
  def extractTimeoutLong(path: String, f: CustomTimeouts => Long, f2: ClientConfig => Long): Long       =
    timeouts(path).map(f).getOrElse(f2(this))
}

object WSProxyServerJson {
  def maybeProxyToJson(p: Option[WSProxyServer]): JsValue =
    p match {
      case Some(proxy) => proxyToJson(proxy)
      case None        => JsNull
    }
  def proxyToJson(p: WSProxyServer): JsValue              =
    Json.obj(
      "host"          -> p.host, // host: String
      "port"          -> p.port, // port: Int
      "protocol"      -> p.protocol.map(JsString.apply).getOrElse(JsNull).as[JsValue], // protocol: Option[String]
      "principal"     -> p.principal.map(JsString.apply).getOrElse(JsNull).as[JsValue], // principal: Option[String]
      "password"      -> p.password.map(JsString.apply).getOrElse(JsNull).as[JsValue], // password: Option[String]
      "ntlmDomain"    -> p.ntlmDomain.map(JsString.apply).getOrElse(JsNull).as[JsValue], // ntlmDomain: Option[String]
      "encoding"      -> p.encoding.map(JsString.apply).getOrElse(JsNull).as[JsValue], // encoding: Option[String]
      "nonProxyHosts" -> p.nonProxyHosts
        .map(nph => JsArray(nph.map(JsString.apply)))
        .getOrElse(JsNull)
        .as[JsValue] // nonProxyHosts: Option[Seq[String]]
    )
  def proxyFromJson(json: JsValue): Option[WSProxyServer] = {
    val maybeHost = (json \ "host").asOpt[String].filterNot(_.trim.isEmpty)
    val maybePort = (json \ "port").asOpt[Int]
    (maybeHost, maybePort) match {
      case (Some(host), Some(port)) => {
        Some(DefaultWSProxyServer(host, port))
          .map { proxy =>
            (json \ "protocol")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(protocol = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "principal")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(principal = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "password")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(password = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "ntlmDomain")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(ntlmDomain = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "encoding")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(encoding = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "nonProxyHosts").asOpt[Seq[String]].map(v => proxy.copy(nonProxyHosts = Some(v))).getOrElse(proxy)
          }
      }
      case _                        => None
    }
  }
}

object ClientConfig {

  lazy val logger = Logger("otoroshi-client-config")

  implicit val format = new Format[ClientConfig] {

    override def reads(json: JsValue): JsResult[ClientConfig] =
      Try {
        ClientConfig(
          useCircuitBreaker = (json \ "useCircuitBreaker").asOpt[Boolean].getOrElse(true),
          retries = (json \ "retries").asOpt[Int].getOrElse(1),
          maxErrors = (json \ "maxErrors").asOpt[Int].getOrElse(20),
          retryInitialDelay = (json \ "retryInitialDelay").asOpt[Long].getOrElse(50),
          backoffFactor = (json \ "backoffFactor").asOpt[Long].getOrElse(2),
          connectionTimeout = (json \ "connectionTimeout").asOpt[Long].getOrElse(10000),
          idleTimeout = (json \ "idleTimeout").asOpt[Long].getOrElse(60000),
          callAndStreamTimeout = (json \ "callAndStreamTimeout").asOpt[Long].getOrElse(120000),
          callTimeout = (json \ "callTimeout").asOpt[Long].getOrElse(30000),
          globalTimeout = (json \ "globalTimeout").asOpt[Long].getOrElse(30000),
          sampleInterval = (json \ "sampleInterval").asOpt[Long].getOrElse(2000),
          proxy = (json \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
          customTimeouts = (json \ "customTimeouts")
            .asOpt[JsArray]
            .map(_.value.map(e => CustomTimeouts.format.reads(e).get))
            .getOrElse(Seq.empty[CustomTimeouts])
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading ClientConfig", t)
        JsError(t.getMessage)
      } get

    override def writes(o: ClientConfig): JsValue =
      Json.obj(
        "useCircuitBreaker"    -> o.useCircuitBreaker,
        "retries"              -> o.retries,
        "maxErrors"            -> o.maxErrors,
        "retryInitialDelay"    -> o.retryInitialDelay,
        "backoffFactor"        -> o.backoffFactor,
        "callTimeout"          -> o.callTimeout,
        "callAndStreamTimeout" -> o.callAndStreamTimeout,
        "connectionTimeout"    -> o.connectionTimeout,
        "idleTimeout"          -> o.idleTimeout,
        "globalTimeout"        -> o.globalTimeout,
        "sampleInterval"       -> o.sampleInterval,
        "proxy"                -> o.proxy.map(p => WSProxyServerJson.proxyToJson(p)).getOrElse(Json.obj()).as[JsValue],
        "customTimeouts"       -> JsArray(o.customTimeouts.map(_.toJson))
      )
  }
}

case class Canary(
    enabled: Boolean = false,
    traffic: Double = 0.2,
    targets: Seq[Target] = Seq.empty[Target],
    root: String = "/"
) {
  def toJson = Canary.format.writes(this)
}

object Canary {

  lazy val logger = Logger("otoroshi-canary")

  implicit val format = new Format[Canary] {
    override def reads(json: JsValue): JsResult[Canary] =
      Try {
        Canary(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          traffic = (json \ "traffic").asOpt[Double].getOrElse(0.2),
          targets = (json \ "targets")
            .asOpt[JsArray]
            .map(_.value.map(e => Target.format.reads(e).get))
            .getOrElse(Seq.empty[Target]),
          root = (json \ "root").asOpt[String].getOrElse("/")
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading Canary", t)
        JsError(t.getMessage)
      } get

    override def writes(o: Canary): JsValue =
      Json.obj(
        "enabled" -> o.enabled,
        "traffic" -> o.traffic,
        "targets" -> JsArray(o.targets.map(_.toJson)),
        "root"    -> o.root
      )
  }
}

case class RedirectionSettings(enabled: Boolean = false, code: Int = 303, to: String = "https://www.otoroshi.io") {
  def toJson       = RedirectionSettings.format.writes(this)
  def hasValidCode = RedirectionSettings.validRedirectionCodes.contains(code)
  def formattedTo(
      request: RequestHeader,
      descriptor: ServiceDescriptor,
      ctx: Map[String, String],
      attrs: TypedMap,
      env: Env
  ): String        =
    RedirectionExpressionLanguage(to, Some(request), Some(descriptor), None, None, ctx, attrs, env)
}

object RedirectionSettings {

  lazy val logger = Logger("otoroshi-redirection-settings")

  val validRedirectionCodes = Seq(301, 308, 302, 303, 307)

  implicit val format = new Format[RedirectionSettings] {
    override def reads(json: JsValue): JsResult[RedirectionSettings] =
      Try {
        RedirectionSettings(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          code = (json \ "code").asOpt[Int].getOrElse(303),
          to = (json \ "to").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("https://www.otoroshi.io")
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading RedirectionSettings", t)
        JsError(t.getMessage)
      } get

    override def writes(o: RedirectionSettings): JsValue =
      Json.obj(
        "enabled" -> o.enabled,
        "code"    -> o.code,
        "to"      -> o.to
      )
  }
}

case class BasicAuthConstraints(
    enabled: Boolean = true,
    headerName: Option[String] = None,
    queryName: Option[String] = None
)                                   {
  def json: JsValue =
    Json.obj(
      "enabled"    -> enabled,
      "headerName" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "queryName"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}
object BasicAuthConstraints         {
  val format = new Format[BasicAuthConstraints] {
    override def writes(o: BasicAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[BasicAuthConstraints] =
      Try {
        JsSuccess(
          BasicAuthConstraints(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
            headerName = (json \ "headerName").asOpt[String].filterNot(_.trim.isEmpty),
            queryName = (json \ "queryName").asOpt[String].filterNot(_.trim.isEmpty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}
case class ClientIdAuthConstraints(
    enabled: Boolean = true,
    headerName: Option[String] = None,
    queryName: Option[String] = None
)                                   {
  def json: JsValue =
    Json.obj(
      "enabled"    -> enabled,
      "headerName" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "queryName"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}
object ClientIdAuthConstraints      {
  val format = new Format[ClientIdAuthConstraints] {
    override def writes(o: ClientIdAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[ClientIdAuthConstraints] =
      Try {
        JsSuccess(
          ClientIdAuthConstraints(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
            headerName = (json \ "headerName").asOpt[String].filterNot(_.trim.isEmpty),
            queryName = (json \ "queryName").asOpt[String].filterNot(_.trim.isEmpty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}
case class CustomHeadersAuthConstraints(
    enabled: Boolean = true,
    clientIdHeaderName: Option[String] = None,
    clientSecretHeaderName: Option[String] = None
)                                   {
  def json: JsValue =
    Json.obj(
      "enabled"                -> enabled,
      "clientIdHeaderName"     -> clientIdHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "clientSecretHeaderName" -> clientSecretHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}
object CustomHeadersAuthConstraints {
  val format = new Format[CustomHeadersAuthConstraints] {
    override def writes(o: CustomHeadersAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[CustomHeadersAuthConstraints] =
      Try {
        JsSuccess(
          CustomHeadersAuthConstraints(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
            clientIdHeaderName = (json \ "clientIdHeaderName").asOpt[String].filterNot(_.trim.isEmpty),
            clientSecretHeaderName = (json \ "clientSecretHeaderName").asOpt[String].filterNot(_.trim.isEmpty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}
case class JwtAuthConstraints(
    enabled: Boolean = true,
    secretSigned: Boolean = true,
    keyPairSigned: Boolean = true,
    includeRequestAttributes: Boolean = false,
    maxJwtLifespanSecs: Option[Long] = None, //Some(10 * 365 * 24 * 60 * 60),
    headerName: Option[String] = None,
    queryName: Option[String] = None,
    cookieName: Option[String] = None
)                                   {
  def json: JsValue =
    Json.obj(
      "enabled"                  -> enabled,
      "secretSigned"             -> secretSigned,
      "keyPairSigned"            -> keyPairSigned,
      "includeRequestAttributes" -> includeRequestAttributes,
      "maxJwtLifespanSecs"       -> maxJwtLifespanSecs.map(l => JsNumber(BigDecimal.exact(l))).getOrElse(JsNull).as[JsValue],
      "headerName"               -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "queryName"                -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "cookieName"               -> cookieName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}
object JwtAuthConstraints           {
  val format = new Format[JwtAuthConstraints] {
    override def writes(o: JwtAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[JwtAuthConstraints] =
      Try {
        JsSuccess(
          JwtAuthConstraints(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
            secretSigned = (json \ "secretSigned").asOpt[Boolean].getOrElse(true),
            keyPairSigned = (json \ "keyPairSigned").asOpt[Boolean].getOrElse(true),
            includeRequestAttributes = (json \ "includeRequestAttributes").asOpt[Boolean].getOrElse(false),
            maxJwtLifespanSecs =
              (json \ "maxJwtLifespanSecs").asOpt[Long].filter(_ > -1), //.getOrElse(10 * 365 * 24 * 60 * 60),
            headerName = (json \ "headerName").asOpt[String].filterNot(_.trim.isEmpty),
            queryName = (json \ "queryName").asOpt[String].filterNot(_.trim.isEmpty),
            cookieName = (json \ "cookieName").asOpt[String].filterNot(_.trim.isEmpty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class ApiKeyRouteMatcher(
    noneTagIn: Seq[String] = Seq.empty,
    oneTagIn: Seq[String] = Seq.empty,
    allTagsIn: Seq[String] = Seq.empty,
    noneMetaIn: Map[String, String] = Map.empty,
    oneMetaIn: Map[String, String] = Map.empty,
    allMetaIn: Map[String, String] = Map.empty,
    noneMetaKeysIn: Seq[String] = Seq.empty,
    oneMetaKeyIn: Seq[String] = Seq.empty,
    allMetaKeysIn: Seq[String] = Seq.empty
) extends {
  def json: JsValue = ApiKeyRouteMatcher.format.writes(this)
}

object ApiKeyRouteMatcher {
  val format = new Format[ApiKeyRouteMatcher] {
    override def writes(o: ApiKeyRouteMatcher): JsValue             =
      Json.obj(
        "noneTagIn"      -> JsArray(o.noneTagIn.map(JsString.apply)),
        "oneTagIn"       -> JsArray(o.oneTagIn.map(JsString.apply)),
        "allTagsIn"      -> JsArray(o.allTagsIn.map(JsString.apply)),
        "noneMetaIn"     -> JsObject(o.noneMetaIn.mapValues(JsString.apply)),
        "oneMetaIn"      -> JsObject(o.oneMetaIn.mapValues(JsString.apply)),
        "allMetaIn"      -> JsObject(o.allMetaIn.mapValues(JsString.apply)),
        "noneMetaKeysIn" -> JsArray(o.noneMetaKeysIn.map(JsString.apply)),
        "oneMetaKeyIn"   -> JsArray(o.oneMetaKeyIn.map(JsString.apply)),
        "allMetaKeysIn"  -> JsArray(o.allMetaKeysIn.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[ApiKeyRouteMatcher] =
      Try {
        JsSuccess(
          ApiKeyRouteMatcher(
            noneTagIn = (json \ "noneTagIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            oneTagIn = (json \ "oneTagIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            allTagsIn = (json \ "allTagsIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            noneMetaIn = (json \ "noneMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
            oneMetaIn = (json \ "oneMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
            allMetaIn = (json \ "allMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
            noneMetaKeysIn = (json \ "noneMetaKeysIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            oneMetaKeyIn = (json \ "oneMetaKeyIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            allMetaKeysIn = (json \ "allMetaKeysIn").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class ApiKeyConstraints(
    basicAuth: BasicAuthConstraints = BasicAuthConstraints(),
    customHeadersAuth: CustomHeadersAuthConstraints = CustomHeadersAuthConstraints(),
    clientIdAuth: ClientIdAuthConstraints = ClientIdAuthConstraints(),
    jwtAuth: JwtAuthConstraints = JwtAuthConstraints(),
    routing: ApiKeyRouteMatcher = ApiKeyRouteMatcher()
)                        {
  def json: JsValue =
    Json.obj(
      "basicAuth"         -> basicAuth.json,
      "customHeadersAuth" -> customHeadersAuth.json,
      "clientIdAuth"      -> clientIdAuth.json,
      "jwtAuth"           -> jwtAuth.json,
      "routing"           -> routing.json
    )
}
object ApiKeyConstraints {
  val format = new Format[ApiKeyConstraints] {
    override def writes(o: ApiKeyConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[ApiKeyConstraints] =
      Try {
        JsSuccess(
          ApiKeyConstraints(
            basicAuth = (json \ "basicAuth").as(BasicAuthConstraints.format),
            customHeadersAuth = (json \ "customHeadersAuth").as(CustomHeadersAuthConstraints.format),
            clientIdAuth = (json \ "clientIdAuth").as(ClientIdAuthConstraints.format),
            jwtAuth = (json \ "jwtAuth").as(JwtAuthConstraints.format),
            routing = (json \ "routing").as(ApiKeyRouteMatcher.format)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

sealed trait SecComVersion {
  def version: Int
  def json: JsValue
}
object SecComVersion       {
  object V1 extends SecComVersion {
    def version: Int  = 1
    def json: JsValue = JsNumber(1)
  }
  object V2 extends SecComVersion {
    def version: Int  = 2
    def json: JsValue = JsNumber(2)
  }
  def apply(version: Int): Option[SecComVersion] =
    version match {
      case 1 => Some(V1)
      case 2 => Some(V2)
      case _ => None
    }
  def apply(version: String): Option[SecComVersion] =
    version match {
      case "V1" => Some(V1)
      case "V2" => Some(V2)
      case _    => None
    }
}

sealed trait SecComInfoTokenVersion {
  def version: String
  def json: JsValue = JsString(version)
}
object SecComInfoTokenVersion       {
  object Legacy extends SecComInfoTokenVersion {
    def version: String = "Legacy"
  }
  object Latest extends SecComInfoTokenVersion {
    def version: String = "Latest"
  }
  def apply(version: String): Option[SecComInfoTokenVersion] =
    version match {
      case "Legacy" => Some(Legacy)
      case "legacy" => Some(Legacy)
      case "Latest" => Some(Latest)
      case "latest" => Some(Latest)
      case _        => None
    }
}

case class SecComHeaders(
    claimRequestName: Option[String] = None,
    stateRequestName: Option[String] = None,
    stateResponseName: Option[String] = None
) {
  def json: JsValue =
    Json.obj(
      "claimRequestName"  -> claimRequestName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "stateRequestName"  -> stateRequestName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "stateResponseName" -> stateResponseName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}

object SecComHeaders {
  val format = new Format[SecComHeaders] {
    override def writes(o: SecComHeaders): JsValue             = o.json
    override def reads(json: JsValue): JsResult[SecComHeaders] =
      Try {
        JsSuccess(
          SecComHeaders(
            claimRequestName = (json \ "claimRequestName").asOpt[String].filterNot(_.trim.isEmpty),
            stateRequestName = (json \ "stateRequestName").asOpt[String].filterNot(_.trim.isEmpty),
            stateResponseName = (json \ "stateResponseName").asOpt[String].filterNot(_.trim.isEmpty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class RestrictionPath(method: String, path: String) {
  def json: JsValue = RestrictionPath.format.writes(this)
}

object RestrictionPath {
  val format = new Format[RestrictionPath] {
    override def writes(o: RestrictionPath): JsValue             =
      Json.obj(
        "method" -> o.method,
        "path"   -> o.path
      )
    override def reads(json: JsValue): JsResult[RestrictionPath] =
      Try {
        JsSuccess(
          RestrictionPath(
            method = (json \ "method").as[String],
            path = (json \ "path").as[String]
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class Restrictions(
    enabled: Boolean = false,
    allowLast: Boolean = true,
    allowed: Seq[RestrictionPath] = Seq.empty,
    forbidden: Seq[RestrictionPath] = Seq.empty,
    notFound: Seq[RestrictionPath] = Seq.empty
) {

  def json: JsValue = Restrictions.format.writes(this)

  def isAllowed(method: String, domain: String, path: String): Boolean = {
    if (enabled) {
      matches(method, domain, path, allowed)
    } else {
      false
    }
  }

  def isNotAllowed(method: String, domain: String, path: String): Boolean = {
    if (enabled) {
      !matches(method, domain, path, allowed)
    } else {
      false
    }
  }

  def isNotFound(method: String, domain: String, path: String): Boolean = {
    if (enabled) {
      matches(method, domain, path, notFound)
    } else {
      false
    }
  }

  def isForbidden(method: String, domain: String, path: String): Boolean = {
    if (enabled) {
      matches(method, domain, path, forbidden)
    } else {
      false
    }
  }

  private def matches(method: String, domain: String, path: String, paths: Seq[RestrictionPath]): Boolean = {
    val cleanMethod = method.trim().toLowerCase()
    paths
      .map(p => p.copy(method = p.method.trim.toLowerCase()))
      .filter(p => p.method == "*" || p.method == cleanMethod)
      .exists { p =>
        if (p.path.startsWith("/")) {
          RegexPool.regex(p.path).matches(path)
        } else {
          RegexPool.regex(p.path).matches(domain + path)
        }
      }
  }

  private val cache = new TrieMap[String, (Boolean, Future[Result])]() // Not that clean but perfs matters

  def handleRestrictions(descriptor: ServiceDescriptor, apk: Option[ApiKey], req: RequestHeader, attrs: TypedMap)(
      implicit
      ec: ExecutionContext,
      env: Env
  ): (Boolean, Future[Result]) = {

    import otoroshi.utils.http.RequestImplicits._

    if (enabled) {
      val method = req.method
      val domain = req.theDomain
      val path   = req.thePath
      val key    = s"${descriptor.id}:${apk.map(_.clientId).getOrElse("none")}:$method:$domain:$path"
      cache.getOrElseUpdate(
        key, {
          if (allowLast) {
            if (isNotFound(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Not Found",
                  Results.NotFound,
                  req,
                  Some(descriptor),
                  Some("errors.not.found"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else if (isForbidden(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Forbidden",
                  Results.Forbidden,
                  req,
                  Some(descriptor),
                  Some("errors.forbidden"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else if (isNotAllowed(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Not Found", // TODO: is it the right response ?
                  Results.NotFound,
                  req,
                  Some(descriptor),
                  Some("errors.not.found"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else {
              Restrictions.failedFutureResp
            }
          } else {
            val allowed = isAllowed(method, domain, path)
            if (!allowed && isNotFound(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Not Found",
                  Results.NotFound,
                  req,
                  Some(descriptor),
                  Some("errors.not.found"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else if (!allowed && isForbidden(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Forbidden",
                  Results.Forbidden,
                  req,
                  Some(descriptor),
                  Some("errors.forbidden"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else if (isNotAllowed(method, domain, path)) {
              (
                true,
                Errors.craftResponseResult(
                  "Not Found", // TODO: is it the right response ?
                  Results.NotFound,
                  req,
                  Some(descriptor),
                  Some("errors.not.found"),
                  emptyBody = true,
                  attrs = attrs
                )
              )
            } else {
              Restrictions.failedFutureResp
            }
          }
        }
      )
    } else {
      Restrictions.failedFutureResp
    }
  }
}

object Restrictions {

  private val failedFutureResp = (false, FastFuture.failed(new RuntimeException("Should never happen")))

  val format = new Format[Restrictions] {
    override def writes(o: Restrictions): JsValue             =
      Json.obj(
        "enabled"   -> o.enabled,
        "allowLast" -> o.allowLast,
        "allowed"   -> JsArray(o.allowed.map(_.json)),
        "forbidden" -> JsArray(o.forbidden.map(_.json)),
        "notFound"  -> JsArray(o.notFound.map(_.json))
      )
    override def reads(json: JsValue): JsResult[Restrictions] =
      Try {
        JsSuccess(
          Restrictions(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            allowLast = (json \ "allowLast").asOpt[Boolean].getOrElse(true),
            allowed = (json \ "allowed")
              .asOpt[JsArray]
              .map(_.value.map(p => RestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
                rp
              })
              .getOrElse(Seq.empty),
            forbidden = (json \ "forbidden")
              .asOpt[JsArray]
              .map(_.value.map(p => RestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
                rp
              })
              .getOrElse(Seq.empty),
            notFound = (json \ "notFound")
              .asOpt[JsArray]
              .map(_.value.map(p => RestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
                rp
              })
              .getOrElse(Seq.empty)
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

case class ServiceDescriptor(
    id: String,
    groups: Seq[String] = Seq("default"),
    name: String,
    description: String = "",
    env: String,
    domain: String,
    subdomain: String,
    targetsLoadBalancing: LoadBalancing = RoundRobin,
    targets: Seq[Target] = Seq.empty[Target],
    root: String = "/",
    matchingRoot: Option[String] = None,
    stripPath: Boolean = true,
    localHost: String = "localhost:8080",
    localScheme: String = "http",
    redirectToLocal: Boolean = false,
    enabled: Boolean = true,
    userFacing: Boolean = false,
    privateApp: Boolean = false,
    forceHttps: Boolean = true,
    maintenanceMode: Boolean = false,
    buildMode: Boolean = false,
    strictlyPrivate: Boolean = false,
    sendOtoroshiHeadersBack: Boolean = true,
    readOnly: Boolean = false,
    xForwardedHeaders: Boolean = false,
    overrideHost: Boolean = true,
    allowHttp10: Boolean = true,
    logAnalyticsOnServer: Boolean = false,
    useAkkaHttpClient: Boolean = false,
    useNewWSClient: Boolean = false,
    tcpUdpTunneling: Boolean = false,
    detectApiKeySooner: Boolean = false,
    letsEncrypt: Boolean = false,
    // TODO: group secCom configs in v2, not done yet to avoid breaking stuff
    enforceSecureCommunication: Boolean = true,
    sendInfoToken: Boolean = true,
    sendStateChallenge: Boolean = true,
    secComHeaders: SecComHeaders = SecComHeaders(),
    secComTtl: FiniteDuration = 30.seconds,
    secComVersion: SecComVersion = SecComVersion.V1,
    secComInfoTokenVersion: SecComInfoTokenVersion = SecComInfoTokenVersion.Legacy,
    secComExcludedPatterns: Seq[String] = Seq.empty[String],
    secComSettings: AlgoSettings = HSAlgoSettings(
      512,
      "${config.app.claim.sharedKey}",
      false
    ),
    secComUseSameAlgo: Boolean = true,
    secComAlgoChallengeOtoToBack: AlgoSettings = HSAlgoSettings(512, "secret", false),
    secComAlgoChallengeBackToOto: AlgoSettings = HSAlgoSettings(512, "secret", false),
    secComAlgoInfoToken: AlgoSettings = HSAlgoSettings(512, "secret", false),
    ///////////////////////////////////////////////////////////
    securityExcludedPatterns: Seq[String] = Seq.empty[String],
    publicPatterns: Seq[String] = Seq.empty[String],
    privatePatterns: Seq[String] = Seq.empty[String],
    additionalHeaders: Map[String, String] = Map.empty[String, String],
    additionalHeadersOut: Map[String, String] = Map.empty[String, String],
    missingOnlyHeadersIn: Map[String, String] = Map.empty[String, String],
    missingOnlyHeadersOut: Map[String, String] = Map.empty[String, String],
    removeHeadersIn: Seq[String] = Seq.empty[String],
    removeHeadersOut: Seq[String] = Seq.empty[String],
    headersVerification: Map[String, String] = Map.empty[String, String],
    matchingHeaders: Map[String, String] = Map.empty[String, String],
    ipFiltering: IpFiltering = IpFiltering(),
    api: ApiDescriptor = ApiDescriptor(false, None),
    healthCheck: HealthCheck = HealthCheck(false, "/"),
    clientConfig: ClientConfig = ClientConfig(),
    canary: Canary = Canary(),
    metadata: Map[String, String] = Map.empty[String, String],
    tags: Seq[String] = Seq.empty,
    chaosConfig: ChaosConfig = ChaosConfig(),
    jwtVerifier: JwtVerifier = RefJwtVerifier(),
    authConfigRef: Option[String] = None,
    cors: CorsSettings = CorsSettings(false),
    redirection: RedirectionSettings = RedirectionSettings(false),
    clientValidatorRef: Option[String] = None,
    ///////////////////////////////////////////////////////////
    transformerRefs: Seq[String] = Seq.empty,
    transformerConfig: JsValue = Json.obj(),
    accessValidator: AccessValidatorRef = AccessValidatorRef(),
    preRouting: PreRoutingRef = PreRoutingRef(),
    plugins: Plugins = Plugins(),
    ///////////////////////////////////////////////////////////
    gzip: GzipConfig = GzipConfig(),
    // thirdPartyApiKey: ThirdPartyApiKeyConfig = OIDCThirdPartyApiKeyConfig(false, None),
    apiKeyConstraints: ApiKeyConstraints = ApiKeyConstraints(),
    restrictions: Restrictions = Restrictions(),
    hosts: Seq[String] = Seq.empty[String],
    paths: Seq[String] = Seq.empty[String],
    handleLegacyDomain: Boolean = true, 
    issueCert: Boolean = false,
    issueCertCA: Option[String] = None,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {

  def json: JsValue      = toJson
  def internalId: String = id

  def theDescription: String = description
  def theMetadata: Map[String,String] = metadata
  def theName: String = name
  def theTags: Seq[String] = tags

  def algoChallengeFromOtoToBack: AlgoSettings = if (secComUseSameAlgo) secComSettings else secComAlgoChallengeOtoToBack
  def algoChallengeFromBackToOto: AlgoSettings = if (secComUseSameAlgo) secComSettings else secComAlgoChallengeBackToOto
  def algoInfoFromOtoToBack: AlgoSettings      = if (secComUseSameAlgo) secComSettings else secComAlgoInfoToken

  lazy val toHost: String = subdomain match {
    case s if s.isEmpty                  => s"$env.$domain"
    case s if s.isEmpty && env == "prod" => s"$domain"
    case s if env == "prod"              => s"$subdomain.$domain"
    case s                               => s"$subdomain.$env.$domain"
  }

  lazy val allHosts: Seq[String] = hosts ++ (if (handleLegacyDomain) Seq(toHost) else Seq.empty[String])
  lazy val allPaths: Seq[String] = paths ++ (if (handleLegacyDomain) matchingRoot.toSeq else Seq.empty[String])

  def maybeStrippedUri(req: RequestHeader, rawUri: String): String = {
    val root        = req.relativeUri
    val rootMatched = allPaths match { //rootMatched was this.matchingRoot
      case ps if ps.isEmpty => None
      case ps               => ps.find(p => root.startsWith(p))
    }
    rootMatched
      .filter(m => stripPath && root.startsWith(m))
      .map(m => root.replaceFirst(m.replace(".", "\\."), ""))
      .getOrElse(rawUri)
  }

  def target: Target                                                 = targets.head
  def save()(implicit ec: ExecutionContext, env: Env)                = env.datastores.serviceDescriptorDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env)              = env.datastores.serviceDescriptorDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env)              = env.datastores.serviceDescriptorDataStore.exists(this)
  def toJson                                                         = ServiceDescriptor.toJson(this)
  def isUp(implicit ec: ExecutionContext, env: Env): Future[Boolean] = FastFuture.successful(true)
  // not useful anymore as circuit breakers should do the work
  // env.datastores.healthCheckDataStore.findLast(this).map(_.map(_.isUp).getOrElse(true))
  // TODO : check perfs
  // def isUriPublic(uri: String): Boolean = !privatePatterns.exists(p => uri.matches(p)) && publicPatterns.exists(p => uri.matches(p))
  def authorizedOnGroup(id: String): Boolean                         = groups.contains(id)

  lazy val hasNoRoutingConstraints: Boolean =
    this.apiKeyConstraints.routing.oneMetaIn.isEmpty &&
    this.apiKeyConstraints.routing.allMetaIn.isEmpty &&
    this.apiKeyConstraints.routing.oneTagIn.isEmpty &&
    this.apiKeyConstraints.routing.allTagsIn.isEmpty &&
    this.apiKeyConstraints.routing.noneTagIn.isEmpty &&
    this.apiKeyConstraints.routing.noneMetaIn.isEmpty &&
    this.apiKeyConstraints.routing.oneMetaKeyIn.isEmpty &&
    this.apiKeyConstraints.routing.allMetaKeysIn.isEmpty &&
    this.apiKeyConstraints.routing.noneMetaKeysIn.isEmpty

  def isUriPublic(uri: String): Boolean =
    !privatePatterns.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri)) && publicPatterns.exists(p =>
      otoroshi.utils.RegexPool.regex(p).matches(uri)
    )

  def isExcludedFromSecurity(uri: String): Boolean = {
    securityExcludedPatterns.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
  }

  def isUriExcludedFromSecuredCommunication(uri: String): Boolean =
    secComExcludedPatterns.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
  def isPrivate                                                   = privateApp
  def updateMetrics(
      callDuration: Long,
      callOverhead: Long,
      dataIn: Long,
      dataOut: Long,
      upstreamLatency: Long,
      config: otoroshi.models.GlobalConfig
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit]                                                 =
    env.datastores.serviceDescriptorDataStore.updateMetrics(
      id,
      callDuration,
      callOverhead,
      dataIn,
      dataOut,
      upstreamLatency,
      config
    )
  def theScheme: String                                           = if (forceHttps) "https://" else "http://"
  def theLine: String                                             = if (env == "prod") "" else s".$env"
  def theDomain                                                   = if (s"$subdomain$theLine".isEmpty) domain else s".$subdomain$theLine"
  def exposedDomain: String                                       = s"$theScheme://$subdomain$theLine.$domain"
  lazy val _domain: String                                        = s"$subdomain$theLine.$domain"

  def validateClientCertificates(
      snowflake: String,
      req: RequestHeader,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    validateClientCertificatesGen(snowflake, req, apikey, user, config, attrs)(f.map(Right.apply)).map {
      case Left(r)  => r
      case Right(r) => r
    }
  }

  import play.api.http.websocket.{Message => PlayWSMessage}

  def wsValidateClientCertificates(
      snowflake: String,
      req: RequestHeader,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    validateClientCertificatesGen(snowflake, req, apikey, user, config, attrs)(f)
  }

  def validateClientCertificatesGen[A](
      snowflake: String,
      req: RequestHeader,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(
      f: => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {

    val plugs    = plugins.accessValidators(req)
    val gScripts = env.datastores.globalConfigDataStore.latestSafe
      .filter(_.scripts.enabled)
      .map(_.scripts)
      .getOrElse(GlobalScripts())

    if (
      plugs.nonEmpty || (gScripts.enabled && gScripts.validatorRefs.nonEmpty) || (accessValidator.enabled && accessValidator.refs.nonEmpty)
    ) {
      val lScripts: Seq[String] = Some(accessValidator)
        .filter(pr =>
          pr.enabled && (pr.excludedPatterns.isEmpty || pr.excludedPatterns
            .exists(p => otoroshi.utils.RegexPool.regex(p).matches(req.path)))
        )
        .map(_.refs)
        .getOrElse(Seq.empty)
      val refs                  = (plugs ++ gScripts.validatorRefs ++ lScripts).distinct
      if (refs.nonEmpty) {
        env.metrics
          .withTimerAsync("otoroshi.core.proxy.validate-access") {
            Source(refs.toList.zipWithIndex)
              .mapAsync(1) { case (ref, index) =>
                val validator = env.scriptManager.getAnyScript[AccessValidator](ref) match {
                  case Left("compiling") => CompilingValidator
                  case Left(_)           => DefaultValidator
                  case Right(validator)  => validator
                }
                validator.access(
                  AccessContext(
                    snowflake = snowflake,
                    index = index,
                    request = req,
                    descriptor = this,
                    user = user,
                    apikey = apikey,
                    attrs = attrs,
                    globalConfig = ConfigUtils.mergeOpt(
                      gScripts.validatorConfig,
                      env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                    ),
                    config = ConfigUtils.merge(accessValidator.config, plugins.config)
                  )
                )
              }
              .takeWhile(
                a =>
                  a match {
                    case Allowed   => true
                    case Denied(_) => false
                  },
                true
              )
              .toMat(Sink.last)(Keep.right)
              .run()(env.otoroshiMaterializer)
          }
          .flatMap {
            case Allowed        => f
            case Denied(result) => FastFuture.successful(Left(result))
          }
      } else {
        f
      }
    } else {
      clientValidatorRef.map { ref =>
        env.datastores.clientCertificateValidationDataStore.findById(ref).flatMap {
          case Some(validator) => validator.validateClientCertificatesGen[A](req, this, apikey, user, config, attrs)(f)
          case None            =>
            Errors
              .craftResponseResult(
                "Validator not found",
                Results.InternalServerError,
                req,
                None,
                None,
                attrs = attrs
              )
              .map(Left.apply)
        }
      } getOrElse f
    }
  }

  def generateInfoToken(
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      requestHeader: Option[RequestHeader],
      issuer: Option[String] = None,
      sub: Option[String] = None
  )(implicit
      env: Env
  ): OtoroshiClaim = {
    import otoroshi.ssl.SSLImplicits._
    val clientCertChain = requestHeader
      .flatMap(_.clientCertificateChain)
      .map(chain =>
        JsArray(
          chain.map(c => c.asJson)
        )
      )
    secComInfoTokenVersion match {
      case SecComInfoTokenVersion.Legacy => {
        OtoroshiClaim(
          iss = issuer.getOrElse(env.Headers.OtoroshiIssuer),
          sub = sub.getOrElse(
            paUsr
              .filter(_ => this.privateApp)
              .map(k => s"pa:${k.email}")
              .orElse(apiKey.map(k => s"apikey:${k.clientId}"))
              .getOrElse("--")
          ),
          aud = this.name,
          exp = DateTime.now().plus(this.secComTtl.toMillis).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).withClaim("email", paUsr.map(_.email))
          .withClaim("name", paUsr.map(_.name).orElse(apiKey.map(_.clientName)))
          .withClaim("picture", paUsr.flatMap(_.picture))
          .withClaim("user_id", paUsr.flatMap(_.userId).orElse(apiKey.map(_.clientId)))
          .withClaim("given_name", paUsr.flatMap(_.field("given_name")))
          .withClaim("family_name", paUsr.flatMap(_.field("family_name")))
          .withClaim("gender", paUsr.flatMap(_.field("gender")))
          .withClaim("locale", paUsr.flatMap(_.field("locale")))
          .withClaim("nickname", paUsr.flatMap(_.field("nickname")))
          .withClaims(paUsr.flatMap(_.otoroshiData).orElse(apiKey.map(_.metadataJson)))
          .withJsArrayClaim("clientCertChain", clientCertChain)
          .withClaim(
            "metadata",
            paUsr
              .flatMap(_.otoroshiData)
              .orElse(apiKey.map(_.metadataJson))
              .map(m => Json.stringify(Json.toJson(m)))
          )
          .withClaim("tags", apiKey.map(a => Json.stringify(JsArray(a.tags.map(JsString.apply)))))
          .withClaim("user", paUsr.map(u => Json.stringify(u.asJsonCleaned)))
          .withClaim("apikey", apiKey.map(ak => Json.stringify(ak.lightJson)))
        //    Json.stringify(
        //      Json.obj(
        //        "clientId"   -> ak.clientId,
        //        "clientName" -> ak.clientName,
        //        "metadata"   -> ak.metadata,
        //        "tags"       -> ak.tags
        //      )
        //  )
        // ))
        // .serialize(this.secComSettings)(env)
      }
      case SecComInfoTokenVersion.Latest => {
        OtoroshiClaim(
          iss = issuer.getOrElse(env.Headers.OtoroshiIssuer),
          sub = sub.getOrElse(
            paUsr
              .filter(_ => this.privateApp)
              .map(k => k.email)
              .orElse(apiKey.map(k => k.clientName))
              .getOrElse("public")
          ),
          aud = this.name,
          exp = DateTime.now().plus(this.secComTtl.toMillis).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).withClaim(
          "access_type",
          (apiKey, paUsr) match {
            case (Some(_), Some(_)) => "both" // should never happen
            case (None, Some(_))    => "user"
            case (Some(_), None)    => "apikey"
            case (None, None)       => "public"
          }
        ).withJsObjectClaim("user", paUsr.map(_.asJsonCleaned.as[JsObject]))
          .withJsObjectClaim("apikey", apiKey.map(ak => ak.lightJson))
        // Json.obj(
        //    "clientId"   -> ak.clientId,
        //    "clientName" -> ak.clientName,
        //    "metadata"   -> ak.metadata,
        //    "tags"       -> ak.tags
        // )
        // ))
        // .withJsArrayClaim("clientCertChain", clientCertChain)
        // .serialize(this.secComSettings)(env)
      }
    }
  }

  import otoroshi.utils.http.RequestImplicits._

  def preRoute(
      snowflake: String,
      req: RequestHeader,
      attrs: TypedMap
  )(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    preRouteGen(snowflake, req, attrs)(f.map(Right.apply)).map {
      case Left(r)  => r
      case Right(r) => r
    }
  }

  def preRouteWS(snowflake: String, req: RequestHeader, attrs: TypedMap)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    preRouteGen[Flow[PlayWSMessage, PlayWSMessage, _]](snowflake, req, attrs)(f)
  }

  def preRouteGen[A](snowflake: String, req: RequestHeader, attrs: TypedMap)(
      f: => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {

    import otoroshi.utils.future.Implicits._

    val plugs    = plugins.preRoutings(req)
    val gScripts = env.datastores.globalConfigDataStore.latestSafe
      .filter(_.scripts.enabled)
      .map(_.scripts)
      .getOrElse(GlobalScripts())
    if (
      plugs.nonEmpty || (gScripts.enabled && gScripts.preRouteRefs.nonEmpty) || (preRouting.enabled && preRouting.refs.nonEmpty)
    ) {
      val lScripts: Seq[String] = Some(preRouting)
        .filter(pr =>
          pr.enabled && (pr.excludedPatterns.isEmpty || pr.excludedPatterns
            .exists(p => otoroshi.utils.RegexPool.regex(p).matches(req.path)))
        )
        .map(_.refs)
        .getOrElse(Seq.empty)
      val refs                  = (plugs ++ gScripts.preRouteRefs ++ lScripts).distinct
      if (refs.nonEmpty) {
        env.metrics
          .withTimerAsync("otoroshi.core.proxy.pre-routing") {
            Source(refs.toList.zipWithIndex)
              .mapAsync(1) { case (ref, index) =>
                val route = env.scriptManager.getAnyScript[PreRouting](ref) match {
                  case Left("compiling") => CompilingPreRouting
                  case Left(_)           => DefaultPreRouting
                  case Right(r)          => r
                }
                route.preRoute(
                  PreRoutingContext(
                    snowflake = snowflake,
                    index = index,
                    request = req,
                    descriptor = this,
                    attrs = attrs,
                    globalConfig = ConfigUtils.mergeOpt(
                      gScripts.preRouteConfig,
                      env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                    ),
                    config = ConfigUtils.merge(preRouting.config, plugins.config)
                  )
                )
              }
              .toMat(Sink.last)(Keep.right)
              .run()(env.otoroshiMaterializer)
          }
          .flatMap(_ => f)
          .recoverWith {
            case PreRoutingError(body, code, ctype, headers) =>
              FastFuture.successful(Results.Status(code)(body).as(ctype).withHeaders(headers.toSeq: _*)).map(Left.apply)
            case PreRoutingErrorWithResult(result)  =>
              FastFuture.successful(result).map(Left.apply)
            case e                                  =>
              Errors
                .craftResponseResult(
                  message = e.getMessage,
                  status = Results.Status(500),
                  req = req,
                  maybeDescriptor = Some(this),
                  attrs = attrs
                )
                .map(Left.apply)
          }
      } else {
        f
      }
    } else {
      f
    }
  }
}

object ServiceDescriptor {

  lazy val logger = Logger("otoroshi-service-descriptor")

  val _fmt: Format[ServiceDescriptor] = new Format[ServiceDescriptor] {

    override def reads(json: JsValue): JsResult[ServiceDescriptor] =
      Try {
        ServiceDescriptor(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          // groupId = (json \ "groupId").as[String],
          groups = {
            val groupId: Seq[String] =
              (json \ "groupId").asOpt[String].toSeq
            val groups: Seq[String]  =
              (json \ "groups").asOpt[Seq[String]].getOrElse(Seq.empty[String])
            (groupId ++ groups).distinct
          },
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          env = (json \ "env").asOpt[String].getOrElse("prod"),
          domain = (json \ "domain").as[String],
          subdomain = (json \ "subdomain").as[String],
          targetsLoadBalancing = (json \ "targetsLoadBalancing").asOpt(LoadBalancing.format).getOrElse(RoundRobin),
          targets = (json \ "targets")
            .asOpt[JsArray]
            .map(_.value.map(e => Target.format.reads(e).get))
            .getOrElse(Seq.empty[Target]),
          root = (json \ "root").asOpt[String].getOrElse("/"),
          matchingRoot = (json \ "matchingRoot").asOpt[String].filter(_.nonEmpty),
          localHost = (json \ "localHost").asOpt[String].getOrElse("localhost:8080"),
          localScheme = (json \ "localScheme").asOpt[String].getOrElse("http"),
          redirectToLocal = (json \ "redirectToLocal").asOpt[Boolean].getOrElse(false),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          stripPath = (json \ "stripPath").asOpt[Boolean].getOrElse(true),
          userFacing = (json \ "userFacing").asOpt[Boolean].getOrElse(false),
          privateApp = (json \ "privateApp").asOpt[Boolean].getOrElse(false),
          forceHttps = (json \ "forceHttps").asOpt[Boolean].getOrElse(true),
          logAnalyticsOnServer = (json \ "logAnalyticsOnServer").asOpt[Boolean].getOrElse(false),
          useAkkaHttpClient = (json \ "useAkkaHttpClient").asOpt[Boolean].getOrElse(false),
          useNewWSClient = (json \ "useNewWSClient").asOpt[Boolean].getOrElse(false),
          tcpUdpTunneling =
            (json \ "tcpUdpTunneling").asOpt[Boolean].orElse((json \ "tcpTunneling").asOpt[Boolean]).getOrElse(false),
          detectApiKeySooner = (json \ "detectApiKeySooner").asOpt[Boolean].getOrElse(false),
          maintenanceMode = (json \ "maintenanceMode").asOpt[Boolean].getOrElse(false),
          buildMode = (json \ "buildMode").asOpt[Boolean].getOrElse(false),
          strictlyPrivate = (json \ "strictlyPrivate").asOpt[Boolean].getOrElse(false),
          enforceSecureCommunication = (json \ "enforceSecureCommunication").asOpt[Boolean].getOrElse(true),
          sendInfoToken = (json \ "sendInfoToken").asOpt[Boolean].getOrElse(true),
          sendStateChallenge = (json \ "sendStateChallenge").asOpt[Boolean].getOrElse(true),
          sendOtoroshiHeadersBack = (json \ "sendOtoroshiHeadersBack").asOpt[Boolean].getOrElse(true),
          readOnly = (json \ "readOnly").asOpt[Boolean].getOrElse(false),
          xForwardedHeaders = (json \ "xForwardedHeaders").asOpt[Boolean].getOrElse(false),
          overrideHost = (json \ "overrideHost").asOpt[Boolean].getOrElse(true),
          allowHttp10 = (json \ "allowHttp10").asOpt[Boolean].getOrElse(true),
          letsEncrypt = (json \ "letsEncrypt").asOpt[Boolean].getOrElse(false),
          secComHeaders = (json \ "secComHeaders").asOpt(SecComHeaders.format).getOrElse(SecComHeaders()),
          secComTtl =
            (json \ "secComTtl").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds),
          secComVersion = (json \ "secComVersion").asOpt[Int].flatMap(SecComVersion.apply).getOrElse(SecComVersion.V1),
          secComInfoTokenVersion = (json \ "secComInfoTokenVersion")
            .asOpt[String]
            .flatMap(SecComInfoTokenVersion.apply)
            .getOrElse(SecComInfoTokenVersion.Legacy),
          secComExcludedPatterns = (json \ "secComExcludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          securityExcludedPatterns =
            (json \ "securityExcludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          publicPatterns = (json \ "publicPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          privatePatterns = (json \ "privatePatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          additionalHeaders =
            (json \ "additionalHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          additionalHeadersOut =
            (json \ "additionalHeadersOut").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          missingOnlyHeadersIn =
            (json \ "missingOnlyHeadersIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          missingOnlyHeadersOut =
            (json \ "missingOnlyHeadersOut").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          headersVerification =
            (json \ "headersVerification").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          matchingHeaders = (json \ "matchingHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          removeHeadersIn = (json \ "removeHeadersIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          removeHeadersOut = (json \ "removeHeadersOut").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          ipFiltering = (json \ "ipFiltering").asOpt(IpFiltering.format).getOrElse(IpFiltering()),
          api = (json \ "api").asOpt(ApiDescriptor.format).getOrElse(ApiDescriptor(false, None)),
          healthCheck = (json \ "healthCheck").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
          clientConfig = (json \ "clientConfig").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
          canary = (json \ "canary").asOpt(Canary.format).getOrElse(Canary()),
          gzip = (json \ "gzip").asOpt(GzipConfig._fmt).getOrElse(GzipConfig()),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          metadata = (json \ "metadata")
            .asOpt[Map[String, String]]
            .map(_.filter(_._1.nonEmpty))
            .getOrElse(Map.empty[String, String]),
          chaosConfig = (json \ "chaosConfig").asOpt(ChaosConfig._fmt).getOrElse(ChaosConfig()),
          jwtVerifier = JwtVerifier
            .fromJson((json \ "jwtVerifier").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(RefJwtVerifier()),
          secComSettings = AlgoSettings
            .fromJson((json \ "secComSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(HSAlgoSettings(512, "${config.app.claim.sharedKey}", false)),
          secComUseSameAlgo = (json \ "secComUseSameAlgo").asOpt[Boolean].getOrElse(true),
          secComAlgoChallengeOtoToBack = AlgoSettings
            .fromJson((json \ "secComAlgoChallengeOtoToBack").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(HSAlgoSettings(512, "secret", false)),
          secComAlgoChallengeBackToOto = AlgoSettings
            .fromJson((json \ "secComAlgoChallengeBackToOto").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(HSAlgoSettings(512, "secret", false)),
          secComAlgoInfoToken = AlgoSettings
            .fromJson((json \ "secComAlgoInfoToken").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(HSAlgoSettings(512, "secret", false)),
          authConfigRef = (json \ "authConfigRef").asOpt[String].filterNot(_.trim.isEmpty),
          clientValidatorRef = (json \ "clientValidatorRef").asOpt[String].filterNot(_.trim.isEmpty),
          transformerRefs = (json \ "transformerRefs")
            .asOpt[Seq[String]]
            .orElse((json \ "transformerRef").asOpt[String].map(r => Seq(r)))
            .map(_.filterNot(_.trim.isEmpty))
            .getOrElse(Seq.empty),
          transformerConfig = (json \ "transformerConfig").asOpt[JsObject].getOrElse(Json.obj()),
          cors = CorsSettings.fromJson((json \ "cors").asOpt[JsValue].getOrElse(JsNull)).getOrElse(CorsSettings(false)),
          redirection = RedirectionSettings.format
            .reads((json \ "redirection").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(RedirectionSettings(false)),
          // thirdPartyApiKey = ThirdPartyApiKeyConfig.format
          //   .reads((json \ "thirdPartyApiKey").asOpt[JsValue].getOrElse(JsNull))
          //   .getOrElse(OIDCThirdPartyApiKeyConfig(false, None)),
          apiKeyConstraints = ApiKeyConstraints.format
            .reads((json \ "apiKeyConstraints").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(ApiKeyConstraints()),
          restrictions = Restrictions.format
            .reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(Restrictions()),
          accessValidator = AccessValidatorRef.format
            .reads((json \ "accessValidator").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(AccessValidatorRef()),
          plugins = Plugins.format
            .reads((json \ "plugins").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(Plugins()),
          preRouting = PreRoutingRef.format
            .reads((json \ "preRouting").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(PreRoutingRef()),
          hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          paths = (json \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          handleLegacyDomain = (json \ "handleLegacyDomain").asOpt[Boolean].getOrElse(true),
          issueCert = (json \ "issueCert").asOpt[Boolean].getOrElse(false),
          issueCertCA = (json \ "issueCertCA").asOpt[String]
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading ServiceDescriptor", t)
        JsError(t.getMessage)
      } get

    override def writes(sd: ServiceDescriptor): JsValue = {
      val oldGroupId: JsValue = sd.groups.headOption.map(JsString.apply).getOrElse(JsNull) // simulate old behavior
      sd.location.jsonWithKey ++ Json.obj(
        "id"                           -> sd.id,
        "groupId"                      -> oldGroupId,
        "groups"                       -> JsArray(sd.groups.map(JsString.apply)),
        "name"                         -> sd.name,
        "description"                  -> sd.description,
        "env"                          -> sd.env,
        "domain"                       -> sd.domain,
        "subdomain"                    -> sd.subdomain,
        "targetsLoadBalancing"         -> sd.targetsLoadBalancing.toJson,
        "targets"                      -> JsArray(sd.targets.map(_.toJson)),
        "root"                         -> sd.root,
        "matchingRoot"                 -> sd.matchingRoot,
        "stripPath"                    -> sd.stripPath,
        "localHost"                    -> sd.localHost,
        "localScheme"                  -> sd.localScheme,
        "redirectToLocal"              -> sd.redirectToLocal,
        "enabled"                      -> sd.enabled,
        "userFacing"                   -> sd.userFacing,
        "privateApp"                   -> sd.privateApp,
        "forceHttps"                   -> sd.forceHttps,
        "logAnalyticsOnServer"         -> sd.logAnalyticsOnServer,
        "useAkkaHttpClient"            -> sd.useAkkaHttpClient,
        "useNewWSClient"               -> sd.useNewWSClient,
        "tcpUdpTunneling"              -> sd.tcpUdpTunneling,
        "detectApiKeySooner"           -> sd.detectApiKeySooner,
        "maintenanceMode"              -> sd.maintenanceMode,
        "buildMode"                    -> sd.buildMode,
        "strictlyPrivate"              -> sd.strictlyPrivate,
        "enforceSecureCommunication"   -> sd.enforceSecureCommunication,
        "sendInfoToken"                -> sd.sendInfoToken,
        "sendStateChallenge"           -> sd.sendStateChallenge,
        "sendOtoroshiHeadersBack"      -> sd.sendOtoroshiHeadersBack,
        "readOnly"                     -> sd.readOnly,
        "xForwardedHeaders"            -> sd.xForwardedHeaders,
        "overrideHost"                 -> sd.overrideHost,
        "allowHttp10"                  -> sd.allowHttp10,
        "letsEncrypt"                  -> sd.letsEncrypt,
        "secComHeaders"                -> sd.secComHeaders.json,
        "secComTtl"                    -> sd.secComTtl.toMillis,
        "secComVersion"                -> sd.secComVersion.json,
        "secComInfoTokenVersion"       -> sd.secComInfoTokenVersion.json,
        "secComExcludedPatterns"       -> JsArray(sd.secComExcludedPatterns.map(JsString.apply)),
        "securityExcludedPatterns"     -> JsArray(sd.securityExcludedPatterns.map(JsString.apply)),
        "publicPatterns"               -> JsArray(sd.publicPatterns.map(JsString.apply)),
        "privatePatterns"              -> JsArray(sd.privatePatterns.map(JsString.apply)),
        "additionalHeaders"            -> JsObject(sd.additionalHeaders.mapValues(JsString.apply)),
        "additionalHeadersOut"         -> JsObject(sd.additionalHeadersOut.mapValues(JsString.apply)),
        "missingOnlyHeadersIn"         -> JsObject(sd.missingOnlyHeadersIn.mapValues(JsString.apply)),
        "missingOnlyHeadersOut"        -> JsObject(sd.missingOnlyHeadersOut.mapValues(JsString.apply)),
        "removeHeadersIn"              -> JsArray(sd.removeHeadersIn.map(JsString.apply)),
        "removeHeadersOut"             -> JsArray(sd.removeHeadersOut.map(JsString.apply)),
        "headersVerification"          -> JsObject(sd.headersVerification.mapValues(JsString.apply)),
        "matchingHeaders"              -> JsObject(sd.matchingHeaders.mapValues(JsString.apply)),
        "ipFiltering"                  -> sd.ipFiltering.toJson,
        "api"                          -> sd.api.toJson,
        "healthCheck"                  -> sd.healthCheck.toJson,
        "clientConfig"                 -> sd.clientConfig.toJson,
        "canary"                       -> sd.canary.toJson,
        "gzip"                         -> sd.gzip.asJson,
        "metadata"                     -> JsObject(sd.metadata.filter(_._1.nonEmpty).mapValues(JsString.apply)),
        "tags"       -> JsArray(sd.tags.map(JsString.apply)),
        "chaosConfig"                  -> sd.chaosConfig.asJson,
        "jwtVerifier"                  -> sd.jwtVerifier.asJson,
        "secComSettings"               -> sd.secComSettings.asJson,
        "secComUseSameAlgo"            -> sd.secComUseSameAlgo,
        "secComAlgoChallengeOtoToBack" -> sd.secComAlgoChallengeOtoToBack.asJson,
        "secComAlgoChallengeBackToOto" -> sd.secComAlgoChallengeBackToOto.asJson,
        "secComAlgoInfoToken"          -> sd.secComAlgoInfoToken.asJson,
        "cors"                         -> sd.cors.asJson,
        "redirection"                  -> sd.redirection.toJson,
        "authConfigRef"                -> sd.authConfigRef,
        "clientValidatorRef"           -> sd.clientValidatorRef,
        "transformerRef"               -> sd.transformerRefs.headOption.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "transformerRefs"              -> sd.transformerRefs,
        "transformerConfig"            -> sd.transformerConfig,
        // "thirdPartyApiKey"             -> sd.thirdPartyApiKey.toJson,
        "apiKeyConstraints"            -> sd.apiKeyConstraints.json,
        "restrictions"                 -> sd.restrictions.json,
        "accessValidator"              -> sd.accessValidator.json,
        "preRouting"                   -> sd.preRouting.json,
        "plugins"                      -> sd.plugins.json,
        "hosts"                        -> JsArray(sd.hosts.map(JsString.apply)),
        "paths"                        -> JsArray(sd.paths.map(JsString.apply)),
        "handleLegacyDomain"           -> sd.handleLegacyDomain,
        "issueCert"                    -> sd.issueCert,
        "issueCertCA"                  -> sd.issueCertCA.map(JsString.apply).getOrElse(JsNull).as[JsValue]
      )
    }
  }
  def toJson(value: ServiceDescriptor): JsValue                 = _fmt.writes(value)
  def fromJsons(value: JsValue): ServiceDescriptor              =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[ServiceDescriptor] = _fmt.reads(value)
}

object ServiceDescriptorDataStore {
  val logger = Logger("otoroshi-service-descriptor-datastore")
}

trait ServiceDescriptorDataStore extends BasicStore[ServiceDescriptor] {

  def template(env: Env): ServiceDescriptor = initiateNewDescriptor()(env)

  def initiateNewDescriptor()(implicit env: Env): ServiceDescriptor = {
    val (subdomain, envir, domain) = env.staticExposedDomain.map { v =>
      ServiceLocation.fullQuery(
        v,
        env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)
      ) match {
        case None           => ("myservice", "prod", env.domain)
        case Some(location) => (location.subdomain, location.env, location.domain)
      }
    } getOrElse ("myservice", "prod", env.domain)
    ServiceDescriptor(
      id = IdGenerator.namedId("service", env),
      name = "my-service",
      description = "a service",
      groups = Seq("default"),
      env = envir,
      domain = domain,
      subdomain = subdomain,
      targets = Seq(
        Target(
          host = "changeme.cleverapps.io",
          scheme = "https",
          mtlsConfig = MtlsConfig()
        )
      ),
      detectApiKeySooner = false,
      privateApp = false,
      sendOtoroshiHeadersBack = false, // try to hide otoroshi as much as possible
      enforceSecureCommunication = false, // try to hide otoroshi as much as possible
      forceHttps = if (env.exposedRootSchemeIsHttps) true else false,
      allowHttp10 = true,
      letsEncrypt = false,
      removeHeadersIn = Seq.empty,
      removeHeadersOut = Seq.empty,
      accessValidator = AccessValidatorRef(),
      missingOnlyHeadersIn = Map.empty,
      missingOnlyHeadersOut = Map.empty,
      stripPath = true
    )
  }
  def updateMetrics(
      id: String,
      callDuration: Long,
      callOverhead: Long,
      dataIn: Long,
      dataOut: Long,
      upstreamLatency: Long,
      config: otoroshi.models.GlobalConfig
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit]
  def updateMetricsOnError(config: otoroshi.models.GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def updateIncrementableMetrics(
      id: String,
      calls: Long,
      dataIn: Long,
      dataOut: Long,
      config: otoroshi.models.GlobalConfig
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit]
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataInPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def dataOutPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCalls()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def globalCallsPerSec()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCallsDuration()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCallsOverhead()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def calls(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def callsPerSec(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def callsDuration(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def callsOverhead(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalDataIn()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def globalDataOut()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataInFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataOutFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findByEnv(env: String)(implicit ec: ExecutionContext, _env: Env): Future[Seq[ServiceDescriptor]]
  def findByGroup(id: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]]

  def getFastLookups(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]]
  def fastLookupExists(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def addFastLookups(query: ServiceDescriptorQuery, services: Seq[ServiceDescriptor])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean]
  def removeFastLookups(query: ServiceDescriptorQuery, services: Seq[ServiceDescriptor])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean]

  def cleanupFastLookups()(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[Long]

  @inline
  def matchAllHeaders(sr: ServiceDescriptor, query: ServiceDescriptorQuery): Boolean = {
    val headersSeq: Map[String, String] = query.matchingHeaders.filterNot(_._1.trim.isEmpty)
    val allHeadersMatched: Boolean      =
      sr.matchingHeaders.filterNot(_._1.trim.isEmpty).forall { case (key, value) =>
        val regex = RegexPool.regex(value)
        headersSeq.get(key).exists(h => regex.matches(h))
      }
    allHeadersMatched
  }

  @inline
  def sortServices(
      services: Seq[ServiceDescriptor],
      query: ServiceDescriptorQuery,
      requestHeader: RequestHeader,
      attrs: TypedMap
  )(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Seq[ServiceDescriptor]] = {

    /*services.exists(_.hasNoRoutingConstraints) match {
      case true => {
        val filtered1 = services.filter { sr =>
          val allHeadersMatched = matchAllHeaders(sr, query)
          val rootMatched = sr.matchingRoot match {
            case Some(matchingRoot) => query.root.startsWith(matchingRoot) //matchingRoot == query.root
            case None               => true
          }
          allHeadersMatched && rootMatched
        }
        val sersWithoutMatchingRoot = filtered1.filter(_.matchingRoot.isEmpty)
        val sersWithMatchingRoot = filtered1.filter(_.matchingRoot.isDefined).sortWith {
          case (a, b) => a.matchingRoot.get.size > b.matchingRoot.get.size
        }
        sersWithMatchingRoot ++ sersWithoutMatchingRoot
      }
      case false => {
        val filtered1 = services.filter { sr =>
          val allHeadersMatched = matchAllHeaders(sr, query)
          val rootMatched = sr.matchingRoot match {
            case Some(matchingRoot) => query.root.startsWith(matchingRoot) //matchingRoot == query.root
            case None               => true
          }
          allHeadersMatched && rootMatched
        }
        val sersWithoutMatchingRoot = filtered1.filter(_.matchingRoot.isEmpty)
        val sersWithMatchingRoot = filtered1.filter(_.matchingRoot.isDefined).sortWith {
          case (a, b) => a.matchingRoot.get.size > b.matchingRoot.get.size
        }
        val filtered = sersWithMatchingRoot ++ sersWithoutMatchingRoot
        FastFuture
          .sequence(filtered.map { sr =>
            matchApiKeyRouting(sr, requestHeader).map(m => (sr, m))
          })
          .map { s =>
            val allSers = s.filter(_._2).map(_._1)
            if (filtered.size > 0 && filtered.size > allSers.size && allSers.size == 0) {
              // let apikey check in handler produce an Unauthorized response instead of service not found
              Seq(filtered.last)
            } else {
              allSers
            }
          }
      }
    }*/

    /*
    val filtered1 = services.filter { sr =>
      val allHeadersMatched = matchAllHeaders(sr, query)
      val rootMatched = sr.matchingRoot match {
        case Some(matchingRoot) => query.root.startsWith(matchingRoot) //matchingRoot == query.root
        case None               => true
      }
      allHeadersMatched && rootMatched
    }
    val sersWithoutMatchingRoot = filtered1.filter(_.matchingRoot.isEmpty)
    val sersWithMatchingRoot = filtered1.filter(_.matchingRoot.isDefined).sortWith {
      case (a, b) => a.matchingRoot.get.size > b.matchingRoot.get.size
    }
    val filtered = sersWithMatchingRoot ++ sersWithoutMatchingRoot
    FastFuture
      .sequence(filtered.map { sr =>
        matchApiKeyRouting(sr, requestHeader).map(m => (sr, m))
      })
      .map { s =>
        val allSers = s.filter(_._2).map(_._1)
        val res1 = if (filtered.size > 0 && filtered.size > allSers.size && allSers.size == 0) {
          // let apikey check in handler produce an Unauthorized response instead of service not found
          Seq(filtered.last)
        } else {
          allSers
        }
        res1.sortWith {
          case (a, b) => b.toHost.contains("*") && !a.toHost.contains("*")
        }
      }
     */

    val matched                 = new TrieMap[String, String]()
    val filtered1               = services.filter { sr =>
      val allHeadersMatched = matchAllHeaders(sr, query)
      val rootMatched       = sr.allPaths match {
        case ps if ps.isEmpty => true
        case ps               =>
          val found = sr.allPaths.find(p => query.root.startsWith(p))
          found.foreach(p => matched.putIfAbsent(sr.id, p))
          found.isDefined
      }
      sr.enabled && allHeadersMatched && rootMatched
    }
    val sersWithoutMatchingRoot = filtered1.filter(_.allPaths.isEmpty)
    val sersWithMatchingRoot    = filtered1.filter(_.allPaths.nonEmpty).sortWith { case (a, b) =>
      val aMatchedSize = matched.get(a.id).map(_.size).getOrElse(0)
      val bMatchedSize = matched.get(b.id).map(_.size).getOrElse(0)
      aMatchedSize > bMatchedSize
    }
    val filtered                = sersWithMatchingRoot ++ sersWithoutMatchingRoot
    FastFuture
      .sequence(filtered.map { sr =>
        matchApiKeyRouting(sr, requestHeader, attrs).map(m => (sr, m))
      })
      .map { s =>
        val allSers = s.filter(_._2).map(_._1)
        val res1    = if (filtered.size > 0 && filtered.size > allSers.size && allSers.size == 0) {
          // let apikey check in handler produce an Unauthorized response instead of service not found
          Seq(filtered.last)
        } else {
          allSers
        }
        res1.sortWith { case (a, b) =>
          b.toHost.contains("*") && !a.toHost.contains("*")
        }
      }
  }

  @inline
  def matchApiKeyRouting(sr: ServiceDescriptor, requestHeader: RequestHeader, attrs: TypedMap)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {

    lazy val shouldSearchForAndApiKey =
      if (sr.isPrivate && sr.authConfigRef.isDefined && !sr.isExcludedFromSecurity(requestHeader.path)) {
        if (sr.isUriPublic(requestHeader.path)) {
          false
        } else {
          true // false positive in 33% of the cases
        }
      } else {
        if (sr.isUriPublic(requestHeader.path)) {
          false
        } else {
          true
        }
      }

    val shouldNotSearchForAnApiKey = sr.hasNoRoutingConstraints && !shouldSearchForAndApiKey

    if (shouldNotSearchForAnApiKey) {
      FastFuture.successful(true)
    } else {
      ApiKeyHelper.extractApiKey(requestHeader, sr, attrs).map {
        case None         => true
        case Some(apiKey) => apiKey.matchRouting(sr)
      }
    }
  }

  @inline
  def rawFind(query: ServiceDescriptorQuery, requestHeader: RequestHeader, attrs: TypedMap)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Seq[ServiceDescriptor]] = {
    ServiceDescriptorDataStore.logger.debug("Full scan of services, should not pass here anymore ...")
    // val redisCli = this.asInstanceOf[KvServiceDescriptorDataStore].redisLike
    // val all = if (redisCli.optimized) {
    //   redisCli.asOptimized.serviceDescriptors_findByHost(query)
    // } else {
    //   findAll()
    // }
    findAll().flatMap { descriptors =>
      val validDescriptors = descriptors.filter { sr =>
        if (!sr.enabled) {
          false
        } else {
          sr.allHosts match {
            case hosts if hosts.isEmpty   => false
            case hosts if hosts.size == 1 => otoroshi.utils.RegexPool(hosts.head).matches(query.toHost)
            case hosts                    => {
              hosts.exists(host => RegexPool(host).matches(query.toHost))
            }
          }
        }
      }
      query.addServices(validDescriptors)
      sortServices(validDescriptors, query, requestHeader, attrs)
    }
  }

  // TODO : prefill ServiceDescriptorQuery lookup set when crud service descriptors
  def find(query: ServiceDescriptorQuery, requestHeader: RequestHeader, attrs: TypedMap)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ServiceDescriptor]] = {
    val start = System.currentTimeMillis()
    query.exists().flatMap {
      case true  => {
        ServiceDescriptorDataStore.logger.debug(s"Service descriptors exists for fast lookups ${query.asKey}")
        query
          .getServices(false)
          .fast
          .flatMap {
            case services if services.isEmpty => {
              // fast lookup should not store empty results, so ...
              ServiceDescriptorDataStore.logger
                .debug(s"FastLookup false positive for ${query.toHost}, doing a fullscan instead ...")
              rawFind(query, requestHeader, attrs)
            }
            case services                     => sortServices(services, query, requestHeader, attrs)
          }
      }
      case false => {
        rawFind(query, requestHeader, attrs)
      }
    } map { filteredDescriptors =>
      filteredDescriptors.headOption
    } /* andThen {
      case _ =>
        ServiceDescriptorDataStore.logger.debug(s"Found microservice in ${System.currentTimeMillis() - start} ms.")
    }*/
  }
}

object SeqImplicits {
  implicit class BetterSeq[A](val seq: Seq[A]) extends AnyVal {
    def findOne(in: Seq[A]): Boolean = seq.intersect(in).nonEmpty
    def findAll(in: Seq[A]): Boolean = {
      // println(s"trying to find ${in} in ${seq} => ${seq.intersect(in).toSet.size == in.size}")
      seq.intersect(in).toSet.size == in.size
    }
  }
}
