package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.models.{ClientConfig, EntityLocation, EntityLocationSupport, HealthCheck, LoadBalancing, RoundRobin, ServiceDescriptor}
import otoroshi.script.{Job, JobId}
import otoroshi.security.IdGenerator
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsArray, JsString, JsValue, Json}
import play.api.mvc.RequestHeader

case class DomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class Frontend(domains: Seq[DomainAndPath], headers: Map[String, String], stripPath: Boolean) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "stripPath" -> stripPath,
    "headers" -> headers
  )
}

case class Backends(targets: Seq[Backend], root: String, loadBalancing: LoadBalancing) {
  def json: JsValue = Json.obj(
    "targets" -> JsArray(targets.map(_.json)),
    "root" -> root,
    "loadBalancing" -> loadBalancing.toJson
  )
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
  // TODO: implements
  def fromServiceDescriptor(service: ServiceDescriptor): Route = ???
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
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj())
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.OverrideHost",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj())
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.HeadersValidation",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "foo" -> "bar"
          )
        ))
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.TestBodyTransformation",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj())
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar" -> "foo"
          )
        ))
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar2" -> "foo2"
          )
        ))
      )
    ))
  )
}

// TODO: implements
class RouteLoaderJob() extends Job {
  override def uniqueId: JobId = ???
}
