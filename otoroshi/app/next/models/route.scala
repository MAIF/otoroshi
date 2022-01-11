package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.models.{ClientConfig, EntityLocation, EntityLocationSupport, HealthCheck, Key, LoadBalancing, RoundRobin, ServiceDescriptor, ServiceGroup, ServiceGroupDataStore}
import otoroshi.script.{Job, JobId}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{Format, JsArray, JsError, JsLookupResult, JsObject, JsResult, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.RequestHeader

import scala.util.{Failure, Success, Try}

case class DomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class Frontend(domains: Seq[DomainAndPath], headers: Map[String, String], stripPath: Boolean) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "headers" -> headers
  )
}

object Frontend {
  def readFrom(lookup: JsLookupResult): Frontend = {
    lookup.asOpt[JsObject] match {
      case None => Frontend(Seq.empty, Map.empty, true)
      case Some(obj) => Frontend(
        domains = obj.select("domains").asOpt[Seq[String]].map(_.map(DomainAndPath.apply)).getOrElse(Seq.empty),
        stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
        headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
      )
    }
  }
}

case class Backends(targets: Seq[Backend], root: String, loadBalancing: LoadBalancing) {
  def json: JsValue = Json.obj(
    "targets" -> JsArray(targets.map(_.json)),
    "root" -> root,
    "load_balancing" -> loadBalancing.toJson
  )
}

object Backends {
  def readFrom(lookup: JsLookupResult): Backends = {
    lookup.asOpt[JsObject] match {
      case None => Backends(Seq.empty, "/", RoundRobin)
      case Some(obj) => Backends(
        targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(Backend.readFrom)).getOrElse(Seq.empty),
        root = obj.select("root").asOpt[String].getOrElse("/"),
        loadBalancing = LoadBalancing.format.reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(RoundRobin)
      )
    }
  }
}

case class Route(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  debugFlow: Boolean,
  frontend: Frontend,
  backends: Backends,
  client: ClientConfig,
  healthCheck: HealthCheck,
  plugins: Plugins
) extends EntityLocationSupport {
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
    "enabled" -> enabled,
    "debug_flow" -> debugFlow,
    "frontend" -> frontend.json,
    "backend" -> backends.json,
    "client" -> client.toJson,
    "health_check" -> healthCheck.toJson,
    "plugins" -> plugins.json
  )

  def matches(request: RequestHeader)(implicit env: Env): Boolean = {
    if (enabled) {
      val path = request.thePath
      val domain = request.theDomain
      frontend.domains
        .filter(d => d.domain == domain || RegexPool(d.domain).matches(domain))
        .filter(d => path.startsWith(d.path) || RegexPool(d.path).matches(path))
        .nonEmpty
        .applyOnIf(frontend.headers.nonEmpty) { firstRes =>
          val headers = request.headers.toSimpleMap.map(t => (t._1.toLowerCase, t._2))
          val secondRes = frontend.headers.map(t => (t._1.toLowerCase, t._2)).forall {
            case (key, value) => headers.get(key).contains(value)
          }
          firstRes && secondRes
        }
    } else {
      false
    }
  }
  // TODO: implements. not a complete one, just for compatibility purposes
  lazy val toServiceDescriptor: ServiceDescriptor = ???
}

object Route {

  val fake = Route(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "Fake route",
    description = "A fake route to tryout the new engine",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = true,
    frontend = Frontend(
      domains = Seq(DomainAndPath("fake-next-gen.oto.tools")),
      headers = Map.empty,
      stripPath = true,
    ),
    backends = Backends(
      targets = Seq(Backend(
        id = "tls://mirror.otoroshi.io:443",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )),
      root = "/",
      loadBalancing = RoundRobin
    ),
    client = ClientConfig(),
    healthCheck = HealthCheck(false, "/"),
    plugins = Plugins(Seq(
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.ForceHttpsTraffic",
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.OverrideHost",
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.HeadersValidation",
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "foo" -> "bar"
          )
        ))
      ),
      // PluginInstance(
      //   plugin = "cp:otoroshi.next.plugins.TestBodyTransformation",
      // ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar" -> "foo"
          )
        ))
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar2" -> "foo2"
          )
        ))
      )
    ))
  )

  val fmt = new Format[Route] {
    override def writes(o: Route): JsValue = o.json
    override def reads(json: JsValue): JsResult[Route] = Try {
      Route(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        frontend = Frontend.readFrom(json.select("frontend")),
        backends = Backends.readFrom(json.select("backend")),
        healthCheck = (json \ "health_check").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
        client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = Plugins.readFrom(json.select("plugins")),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }

  def fromServiceDescriptor(service: ServiceDescriptor): Route = {
    Route(
      location = service.location,
      id = service.id,
      name = service.name,
      description = service.description,
      tags = service.tags,
      metadata = service.metadata,
      enabled = service.enabled,
      debugFlow = true,
      frontend = Frontend(
        domains = {
          val dap = if (service.allPaths.isEmpty) {
            service.allHosts.map(h => s"$h${service.matchingRoot.getOrElse("/")}")
          } else {
            service.allPaths.flatMap(path => service.allHosts.map(host => s"$host$path"))
          }
          dap.map(DomainAndPath.apply)
        },
        headers = service.matchingHeaders,
        stripPath = service.stripPath,
      ),
      backends = Backends(
        targets = service.targets.map(Backend.fromTarget),
        root = service.root,
        loadBalancing = service.targetsLoadBalancing
      ),
      client = service.clientConfig,
      healthCheck = service.healthCheck,
      plugins = Plugins(
        Seq.empty[PluginInstance]
          .applyOnIf(service.forceHttps) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.ForceHttpsTraffic",
            )
          }
          .applyOnIf(service.overrideHost) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.OverrideHost",
            )
          }
          .applyOnIf(service.headersVerification.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.HeadersValidation",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.headersVerification.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.additionalHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
              enabled = true,
              include = Seq.empty,
              exclude = Seq.empty,
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.maintenanceMode) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.MaintenanceMode"
            )
          }
          .applyOnIf(service.buildMode) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.BuildMode"
            )
          }
          .applyOnIf(!service.allowHttp10) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.DisableHttp10"
            )
          }
      )
    )
  }
}

trait RouteDataStore extends BasicStore[Route]

class KvRouteDataStore(redisCli: RedisLike, _env: Env)
  extends RouteDataStore
    with RedisLikeStore[Route] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[Route]               = Route.fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "routes" / id
  override def extractId(value: Route): String  = value.id
}
