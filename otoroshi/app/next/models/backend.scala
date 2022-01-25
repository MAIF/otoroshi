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
import otoroshi.api.OtoroshiEnvHolder

case class NgBackend(targets: Seq[NgTarget], targetRefs: Seq[String], root: String, loadBalancing: LoadBalancing) {
  // I know it's not ideal but we'll go with it for now !
  lazy val allTargets: Seq[NgTarget] = targets ++ targetRefs.map(OtoroshiEnvHolder.get().proxyState.target).collect {
    case Some(backend) => backend
  }.distinct
  def json: JsValue = Json.obj(
    "targets" -> JsArray(targets.map(_.json)),
    "target_refs" -> JsArray(targetRefs.map(JsString.apply)),
    "root" -> root,
    "load_balancing" -> loadBalancing.toJson
  )
}

object NgBackend {
  def readFrom(lookup: JsLookupResult): NgBackend = readFromJson(lookup.as[JsValue])
  def readFromJson(lookup: JsValue): NgBackend = {
    lookup.asOpt[JsObject] match {
      case None => NgBackend(Seq.empty, Seq.empty, "/", RoundRobin)
      case Some(obj) => NgBackend(
        targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(NgTarget.readFrom)).getOrElse(Seq.empty),
        targetRefs = obj.select("target_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        root = obj.select("root").asOpt[String].getOrElse("/"),
        loadBalancing = LoadBalancing.format.reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(RoundRobin)
      )
    }
  }
}

object StoredNgTarget {
  val format = new Format[StoredNgTarget] {
    override def reads(json: JsValue): JsResult[StoredNgTarget] = Try {
      StoredNgTarget(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        target = NgTarget.readFrom(json.select("target").as[JsValue]),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
    override def writes(o: StoredNgTarget): JsValue = o.json
  }
}

case class StoredNgTarget(location: EntityLocation, id: String, name: String, description: String, tags: Seq[String], metadata: Map[String, String], target: NgTarget) extends EntityLocationSupport {
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
    "target" -> target.json,
  )
}

trait StoredNgTargetDataStore extends BasicStore[StoredNgTarget]

class KvStoredNgTargetDataStore(redisCli: RedisLike, _env: Env)
  extends StoredNgTargetDataStore
    with RedisLikeStore[StoredNgTarget] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[StoredNgTarget]              = StoredNgTarget.format
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "targets" / id
  override def extractId(value: StoredNgTarget): String = value.id
}

case class NgSelectedBackendTarget(target: NgTarget, attempts: Int, alreadyFailed: AtomicBoolean, cbStart: Long)

case class NgTarget(
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

object NgTarget {
  def fromTarget(target: Target): NgTarget = {
    NgTarget(
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
  def readFrom(obj: JsValue): NgTarget = {
    NgTarget(
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

object StoredNgBackend {
  val format = new Format[StoredNgBackend] {
    override def reads(json: JsValue): JsResult[StoredNgBackend] = Try {
      StoredNgBackend(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        backend = NgBackend.readFromJson(json.select("backend").as[JsValue]),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
    override def writes(o: StoredNgBackend): JsValue = o.json
  }
}

case class StoredNgBackend(location: EntityLocation, id: String, name: String, description: String, tags: Seq[String], metadata: Map[String, String], backend: NgBackend) extends EntityLocationSupport {
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

trait StoredNgBackendDataStore extends BasicStore[StoredNgBackend]

class KvStoredNgBackendDataStore(redisCli: RedisLike, _env: Env)
  extends StoredNgBackendDataStore
    with RedisLikeStore[StoredNgBackend] {
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def fmt: Format[StoredNgBackend]              = StoredNgBackend.format
  override def key(id: String): Key                      = Key.Empty / _env.storageRoot / "backends" / id
  override def extractId(value: StoredNgBackend): String = value.id
}
