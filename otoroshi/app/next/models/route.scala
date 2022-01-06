package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.models.{ClientConfig, EntityLocation, EntityLocationSupport, HealthCheck, LoadBalancing, RoundRobin}
import otoroshi.security.IdGenerator
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsArray, JsString, JsValue, Json}
import play.api.mvc.RequestHeader

case class Domain(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class Frontend(domains: Seq[Domain], headers: Map[String, String], stripPath: Boolean) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "stripPath" -> stripPath,
    "headers" -> headers
  )
}

case class Target(backends: Seq[Backend], root: String, loadBalancing: LoadBalancing) {
  def json: JsValue = Json.obj(
    "backends" -> JsArray(backends.map(_.json)),
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
    frontend: Frontend,
    target: Target,
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
    "frontend" -> frontend.json,
    "target" -> target.json,
    "client" -> client.toJson,
    "health_check" -> healthCheck.toJson,
    "plugins" -> plugins.json
  )

  def matches(request: RequestHeader)(implicit env: Env): Boolean = {
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
  }
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
    frontend = Frontend(
      domains = Seq(Domain("fake-next-gen.oto.tools")),
      headers = Map.empty,
      stripPath = true,
    ),
    target = Target(
      backends = Seq(Backend(
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
        plugin = "cp:???",
        enabled = true,
        include = Seq.empty,
        exclude = Seq.empty,
        config = PluginInstanceConfig(Json.obj())
      ))
    )
  )
}
