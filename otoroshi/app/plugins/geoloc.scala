package otoroshi.plugins.geoloc

import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.maxmind.geoip2.DatabaseReader
import env.Env
import otoroshi.plugins.Keys
import otoroshi.script._
import play.api.Logger
import play.api.libs.json.{JsNumber, JsValue, Json}
import play.api.mvc.Result
import utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class MaxMindGeolocationInfoExtractor extends PreRouting {

  private val logger = Logger("MaxMindGeolocationInfo")

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val pathOpt = (ctx.config \ "GeolocationInfo" \ "path").asOpt[String]
    val log = (ctx.config \ "GeolocationInfo" \ "log").asOpt[Boolean].getOrElse(false)
    val from = ctx.request.headers.get("X-Forwarded-For").getOrElse(ctx.request.remoteAddress)
    pathOpt match {
      case None => funit
      case Some("global") => env.datastores.globalConfigDataStore.latestSafe match {
        case None => funit
        case Some(c) if !c.geolocationSettings.enabled => funit
        case Some(c) => c.geolocationSettings.find(from).map {
          case None =>  funit
          case Some(location) => {
            if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
            ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
            funit
          }
        }
      }
      case Some(path) => MaxMindGeolocationHelper.find(from, path).map {
        case None =>  funit
        case Some(location) => {
          if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
          ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
          funit
        }
      }
    }
  }
}

class IpStackGeolocationInfoExtractor extends PreRouting {

  private val logger = Logger("IpStackGeolocationInfo")

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val timeout: Long = (ctx.config \ "GeolocationInfo" \ "timeout").asOpt[Long].getOrElse(2000)
    val apiKeyOpt = (ctx.config \ "GeolocationInfo" \ "apikey").asOpt[String]
    val log = (ctx.config \ "GeolocationInfo" \ "log").asOpt[Boolean].getOrElse(false)
    val from = ctx.request.headers.get("X-Forwarded-For").getOrElse(ctx.request.remoteAddress)
    apiKeyOpt match {
      case None => funit
      case Some(apiKey) => IpStackGeolocationHelper.find(from, apiKey, timeout).map {
        case None =>  funit
        case Some(location) => {
          if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
          ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
          funit
        }
      }
    }
  }
}

class GeolocationInfoHeader extends RequestTransformer {
  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val headerName = (ctx.config \ "GeolocationInfoHeader" \ "headerName").asOpt[String].getOrElse("X-Geolocation-Info")
    val from = ctx.request.headers.get("X-Forwarded-For").getOrElse(ctx.request.remoteAddress)
    ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
      case None => Right(ctx.otoroshiRequest).future
      case Some(location) => {
        Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers ++ Map(
            headerName -> Json.stringify(location)
          )
        )).future
      }
    }
  }
}

object IpStackGeolocationHelper {

  import scala.concurrent.duration._

  private val cache = new TrieMap[String, Option[JsValue]]()

  def find(ip: String, apikey: String, timeout: Long)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    cache.get(ip) match {
      case Some(details) => FastFuture.successful(details)
      case None  => {
        env.Ws.url(s"http://api.ipstack.com/$ip?access_key=$apikey&format=1")
          .withFollowRedirects(false)
          .withRequestTimeout(timeout.millis)
          .get().map {
          case resp if resp.status == 200 && resp.header("Content-Type").exists(_.contains("application/json")) =>
            val res = Some(resp.json)
            cache.putIfAbsent(ip, res)
            res
          case _ => None
        }
      }
    }
  }
}

object MaxMindGeolocationHelper {

  private val logger = Logger("MaxMindGeolocationHelper")
  private val ipCache = new TrieMap[String, InetAddress]()
  private val cache = new TrieMap[String, Option[JsValue]]()
  private val exc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors() + 1))
  private val dbInitializing = new AtomicBoolean(false)
  private val dbInitializationDone = new AtomicBoolean(false)
  private val dbRef = new AtomicReference[DatabaseReader]()

  def find(ip: String, file: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    if (dbInitializing.compareAndSet(false, true)) {
      logger.info("Initializing Geolocation db ...")
      Future {
        val cityDbFile = new File(file)
        val cityDb = new DatabaseReader.Builder(cityDbFile).build()
        dbRef.set(cityDb)
        dbInitializationDone.set(true)
      }(exc).andThen {
        case Success(_) => logger.info("Geolocation db initialized")
        case Failure(e) => logger.error("Geolocation db initialization failed", e)
      }(exc)
    }
    cache.get(ip) match {
      case loc @ Some(_) => FastFuture.successful(loc.flatten)
      case None if dbInitializationDone.get() => {
        val inet = ipCache.getOrElseUpdate(ip, InetAddress.getByName(ip))
        Try(dbRef.get().city(inet)) match { // TODO: blocking ???
          case Failure(e) => cache.putIfAbsent(ip, None)
          case Success(city) => {
            Option(city).map { c =>
              // val asn = asnDb.asn(inet)
              // val org = asn.getAutonomousSystemOrganization // TODO: blocking ??? non free version ?
              // val asnNumber = asn.getAutonomousSystemNumber // TODO: blocking ??? non free version ?
              val ipType = if (ip.contains(":")) "ipv6" else "ipv4"
              val location = Json.obj(
                "ip" -> ip,
                "type" -> ipType,
                "continent_code" -> c.getContinent.getCode,
                "continent_name" -> c.getContinent.getName,
                "country_code" -> c.getCountry.getIsoCode,
                "country_name" -> c.getCountry.getName,
                "region_code" -> c.getPostal.getCode,
                "region_name" -> c.getMostSpecificSubdivision.getName,
                "city" -> c.getCity.getName,
                "latitude" -> JsNumber(c.getLocation.getLatitude.toDouble),
                "longitude" -> JsNumber(c.getLocation.getLongitude.toDouble),
                "location" -> Json.obj(
                  "geoname_id" -> JsNumber(c.getCountry.getGeoNameId.toInt),
                  "name" -> c.getCountry.getName,
                  "languages" -> Json.arr(),
                  "is_eu" -> c.getCountry.isInEuropeanUnion
                )
              )
              cache.putIfAbsent(ip, Some(location))
            }.getOrElse {
              cache.putIfAbsent(ip, None)
            }
          }
        }
        FastFuture.successful(cache.get(ip).flatten)
      }
      case _ => FastFuture.successful(None) // initialization in progress
    }
  }
}
