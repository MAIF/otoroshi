package otoroshi.next.models

import akka.http.scaladsl.util.FastFuture
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env._
import otoroshi.models._
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.ApikeyCalls
import akka.http.scaladsl.model.Uri

case class NgMinimalRoute(
    frontend: NgFrontend,
    backend: NgMinimalBackend,
    backendRef: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "frontend"    -> frontend.json,
    "backend"     -> backend.json,
    "backend_ref" -> backendRef.map(JsString.apply).getOrElse(JsNull).as[JsValue]
  )
}

object NgMinimalRoute {
  val fmt = new Format[NgMinimalRoute] {
    override def writes(o: NgMinimalRoute): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgMinimalRoute] = Try {
      val ref        = json.select("backend_ref").asOpt[String]
      val refBackend = ref.flatMap(r => OtoroshiEnvHolder.get().proxyState.backend(r)).getOrElse(NgBackend.empty)
      NgMinimalRoute(
        frontend = NgFrontend.readFrom(json.select("frontend")),
        backend = ref match {
          case None    => NgMinimalBackend.readFrom(json.select("backend"))
          case Some(r) => refBackend.minimalBackend
        },
        backendRef = ref
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
  }
}

case class NgService(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    debugFlow: Boolean,
    exportReporting: Boolean,
    groups: Seq[String] = Seq("default"),
    routes: Seq[NgMinimalRoute],
    client: ClientConfig,
    plugins: NgPlugins
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  override def json: JsValue = location.jsonWithKey ++ Json.obj(
    "id"               -> id,
    "name"             -> name,
    "description"      -> description,
    "tags"             -> tags,
    "metadata"         -> metadata,
    "enabled"          -> enabled,
    "debug_flow"       -> debugFlow,
    "export_reporting" -> exportReporting,
    "groups"           -> groups,
    "routes"           -> JsArray(routes.map(_.json)),
    "client"           -> client.toJson,
    "plugins"          -> plugins.json
  )

  lazy val toRoutes: Seq[NgRoute] = {
    if (enabled) {
      routes.zipWithIndex.map { case (route, idx) =>
        NgRoute(
          location = location,
          id = id + "-" + idx,
          name = name + " - " + route.frontend.methods
            .mkString(", ") + " - " + route.frontend.domains.map(_.path).mkString(", "),
          description = description,
          tags = tags,
          metadata = metadata ++ Map("otoroshi-core-original-route-id" -> id),
          enabled = enabled,
          debugFlow = debugFlow,
          exportReporting = exportReporting,
          groups = groups,
          frontend = route.frontend,
          backend = route.backend.toBackend(client, None),
          backendRef = route.backendRef,
          plugins = plugins
        )
      }
    } else {
      Seq.empty
    }
  }
}

object NgService {
  val fmt = new Format[NgService] {
    override def writes(o: NgService): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgService] = Try {
      NgService(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        exportReporting = json.select("export_reporting").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        routes = json
          .select("routes")
          .asOpt[Seq[JsValue]]
          .map(seq => seq.flatMap(json => NgMinimalRoute.fmt.reads(json).asOpt))
          .getOrElse(Seq.empty),
        client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = NgPlugins.readFrom(json.select("plugins"))
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
  }

  def fromOpenApi(domain: String, openapi: String)(implicit ec: ExecutionContext, env: Env): Future[NgService] = {
    val codef: Future[String] = if (openapi.startsWith("http://") || openapi.startsWith("https://")) {
      env.Ws.url(openapi).get().map(_.body)
    } else {
      FastFuture.successful(openapi)
    }
    codef.map { code =>
      val json                        = Json.parse(code)
      val name                        = json.select("info").select("title").as[String]
      val description                 = json.select("info").select("description").asOpt[String].getOrElse("")
      val version                     = json.select("info").select("version").asOpt[String].getOrElse("")
      val targets                     = json.select("servers").as[Seq[JsObject]].map { server =>
        val serverUrl    = server.select("url").asString
        val serverUri    = Uri(serverUrl)
        val serverDomain = serverUri.authority.host.toString()
        val tls          = serverUri.scheme.toLowerCase().contains("https")
        val port         = if (serverUri.authority.port == 0) (if (tls) 443 else 80) else serverUri.authority.port
        NgTarget(
          id = serverUrl,
          hostname = serverDomain,
          port = port,
          tls = tls
        )
      }
      val paths                       = json.select("paths").asOpt[JsObject].getOrElse(Json.obj())
      val routes: Seq[NgMinimalRoute] = paths.value.toSeq.map { case (path, obj) =>
        val cleanPath = path.replace("{", ":").replace("}", "")
        val methods   = obj.as[JsObject].value.toSeq.map(_._1.toUpperCase())
        NgMinimalRoute(
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"${domain}${cleanPath}")),
            headers = Map.empty,
            methods = methods,
            stripPath = false,
            exact = true
          ),
          backend = NgMinimalBackend(
            targets = targets,
            targetRefs = Seq.empty,
            root = "/",
            rewrite = false,
            loadBalancing = RoundRobin
          )
        )
      }
      NgService(
        location = EntityLocation.default,
        id = "ng-service_" + IdGenerator.uuid,
        name = name,
        description = description,
        tags = Seq("env:prod"),
        metadata = Map("version" -> version),
        enabled = true,
        debugFlow = false,
        exportReporting = false,
        groups = Seq("default"),
        routes = routes,
        client = ClientConfig(),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[OverrideHost]
            ),
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[ApikeyCalls]
            )
          )
        )
      )
    }
  }
}

trait NgServiceDataStore extends BasicStore[NgService]

class KvNgServiceDataStore(redisCli: RedisLike, _env: Env) extends NgServiceDataStore with RedisLikeStore[NgService] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[NgService]                  = NgService.fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "ngservices" / id
  override def extractId(value: NgService): String     = value.id
}
