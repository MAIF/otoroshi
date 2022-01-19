package otoroshi.next.models

import akka.http.scaladsl.model.{HttpProtocol, HttpProtocols}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Success, Try}

object StoredBackend {
  val format = new Format[StoredBackend] {
    override def reads(json: JsValue): JsResult[StoredBackend] = Try {
      StoredBackend(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        backend = Backend.readFrom(json.select("backend").as[JsValue]),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
    override def writes(o: StoredBackend): JsValue = o.json
  }
}

case class StoredBackend(location: EntityLocation, id: String, name: String, description: String, tags: Seq[String], metadata: Map[String, String], backend: Backend) extends EntityLocationSupport {
  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = location.jsonWithKey ++ Json.obj(
    "id" -> id,
    "name" -> name,
    "description" -> description,
    "tags" -> tags,
    "metadata" -> metadata,
    "backend" -> backend.json,
  )
}

trait StoredBackendDataStore extends BasicStore[StoredBackend]

class KvStoredBackendDataStore(redisCli: RedisLike, _env: Env)
  extends StoredBackendDataStore
    with RedisLikeStore[StoredBackend] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[StoredBackend]              = StoredBackend.format
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "backends" / id
  override def extractId(value: StoredBackend): String = value.id
}

case class SelectedBackend(backend: Backend, attempts: Int, alreadyFailed: AtomicBoolean, cbStart: Long)

case class Backend(
  id: String,
  hostname: String,
  port: Int,
  tls: Boolean,
  weight: Int = 1,
  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  predicate: TargetPredicate = AlwaysMatch,
  ipAddress: Option[String] = None,
  tlsConfig: MtlsConfig = MtlsConfig(),
) {
  lazy val defaultPortString = port match {
    case 443 => ""
    case 80 => ""
    case _ => s":${port}"
  }
  lazy val toTarget: otoroshi.models.Target = otoroshi.models.Target(
    host = s"${hostname}${defaultPortString}",
    scheme = if (tls) "https" else "http",
    weight = weight,
    protocol = protocol,
    predicate = predicate,
    ipAddress = ipAddress,
    mtlsConfig = tlsConfig,
    tags = Seq(id),
    metadata = Map.empty,
  )
  def json: JsValue = Json.obj(
    "id" -> id,
    "hostname" -> hostname,
    "port" -> port,
    "tls" -> tls,
    "weight" -> weight,
    "protocol" -> protocol.value,
    "ip_address" -> ipAddress.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "tls_config" -> tlsConfig.json
  )
}

object Backend {
  def fromTarget(target: Target): Backend = {
    Backend(
      id = target.tags.headOption.getOrElse(target.host),
      hostname = target.theHost,
      port = target.thePort,
      tls = target.scheme.toLowerCase == "https",
      weight = target.weight,
      protocol = target.protocol,
      predicate = target.predicate,
      ipAddress = target.ipAddress,
      tlsConfig = target.mtlsConfig,
    )
  }
  def readFrom(obj: JsValue): Backend = {
    Backend(
      id = obj.select("id").as[String],
      hostname = obj.select("hostname").as[String],
      port = obj.select("port").as[Int],
      tls = obj.select("tls").asOpt[Boolean].getOrElse(false),
      weight = obj.select("weight").asOpt[Int].getOrElse(1),
      tlsConfig = MtlsConfig.read((obj \ "tlsConfig").asOpt[JsValue]),
      protocol = (obj \ "protocol")
       .asOpt[String]
       .filterNot(_.trim.isEmpty)
       .map(s => HttpProtocol.apply(s))
       .getOrElse(HttpProtocols.`HTTP/1.1`),
      predicate = (obj \ "predicate").asOpt(TargetPredicate.format).getOrElse(AlwaysMatch),
      ipAddress = (obj \ "ipAddress").asOpt[String].filterNot(_.trim.isEmpty),
    )
  }
}
