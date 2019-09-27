/*
package utils

import java.io.File
import java.net.InetAddress

import akka.http.scaladsl.util.FastFuture
import com.blueconic.browscap.UserAgentService
import com.maxmind.geoip2.DatabaseReader
import env.Env
import play.api.libs.json.{JsObject, JsString}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class LatLon(latitude: Double, longitude: Double, city: String, country: String, continent: String, asn: String)

trait GeolocationHelper {
  def start(): Future[Unit]
  def stop(): Future[Unit]
  def getLocation(ip: String): Future[Option[LatLon]]
}

class GeoLite2GeolocationHelper(env: Env) extends GeolocationHelper {

  private implicit val ec = env.otoroshiExecutionContext
  private implicit val mat = env.otoroshiMaterializer

  private val ipCache = new TrieMap[String, InetAddress]()
  private val cache = new TrieMap[String, Option[LatLon]]()

  private lazy val (cityDb, asnDb) = {
    val file1 = new File("/Users/mathieuancelin/Downloads/GeoLite2-City_20190924/GeoLite2-City.mmdb")
    val file2 = new File("/Users/mathieuancelin/Downloads/GeoLite2-ASN_20190924/GeoLite2-ASN.mmdb")
    val reader1 = new DatabaseReader.Builder(file1).build()
    val reader2 = new DatabaseReader.Builder(file2).build()
    (reader1, reader2)
  }

  def stop(): Future[Unit] = {
    FastFuture.successful(())
  }

  def start(): Future[Unit] = {
    Future {
      cityDb.toString
      asnDb.toString
      ()
    }(env.otoroshiExecutionContext)
  }

  def getLocation(ip: String): Future[Option[LatLon]] = {
    FastFuture.successful(cache.get(ip) match {
      case None => {
        val inet = ipCache.getOrElseUpdate(ip, InetAddress.getByName(ip)) // TODO: blocking ???
        Try(cityDb.city(inet)) match { // TODO: blocking ???
          case Failure(e) => cache.putIfAbsent(ip, None)
          case Success(city) => {
            Option(city).map { c =>
              val loc = c.getLocation
              val city = c.getCity.getName
              val country = c.getCountry.getName
              val continent = c.getContinent.getName
              val org = asnDb.asn(inet).getAutonomousSystemOrganization // TODO: blocking ??? non free version ?
              val location = LatLon(loc.getLatitude, loc.getLongitude, city, country, continent, org)
              cache.putIfAbsent(ip, Some(location))
            }.getOrElse {
              cache.putIfAbsent(ip, None)
            }
          }
        }
        cache.get(ip).flatten
      }
      case loc @ Some(_) => loc.flatten
    })
  }
}

class UserAgentHelper(env: Env) {

  import collection.JavaConverters._

  private lazy val parser = new UserAgentService().loadParser()
  private val cache = new TrieMap[String, Option[JsObject]]()

  def start(): Future[Unit] = {
    Future {
      parser.toString
      ()
    }(env.otoroshiExecutionContext)
  }

  def stop(): Future[Unit] = {
    FastFuture.successful(())
  }

  def userAgentDetails(ua: String): Future[Option[JsObject]] = {
    FastFuture.successful(cache.get(ua) match {
      case None => {
        Try(parser.parse(ua)) match {
          case Failure(e) =>
            cache.putIfAbsent(ua, None)
          case Success(capabilities) => {
            val details = Some(JsObject(capabilities.getValues.asScala.map {
              case (field, value) => (field.name().toLowerCase(), JsString(value))
            }.toMap))
            cache.putIfAbsent(ua, details)
          }
        }
        cache.get(ua).flatten
      }
      case details @ Some(_) => details.flatten
    })
  }
}
 */
