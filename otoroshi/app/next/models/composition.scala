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
  backend: NgBackend,
  backendRef: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "frontend" -> frontend.json,
    "backend" -> backend.json,
    "backend_ref" -> backendRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
  )
}

object NgMinimalRoute {
  val fmt = new Format[NgMinimalRoute] {
    override def writes(o: NgMinimalRoute): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgMinimalRoute] = Try {
      val ref = json.select("backend_ref").asOpt[String]
      val refBackend = ref.flatMap(r => OtoroshiEnvHolder.get().proxyState.backend(r)).getOrElse(NgBackend.empty)
      NgMinimalRoute(
        frontend = NgFrontend.readFrom(json.select("frontend")),
        backend = ref match {
          case None => NgBackend.readFrom(json.select("backend"))
          case Some(r) => refBackend
        },
        backendRef = ref,
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }
}

// TODO: how to handle healthcheck
case class NgRoutesComposition(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  debugFlow: Boolean,
  groups: Seq[String] = Seq("default"),
  routes: Seq[NgMinimalRoute],
  client: ClientConfig,
  plugins: NgPlugins
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
    "groups" -> groups,
    "routes" -> JsArray(routes.map(_.json)),
    "client" -> client.toJson,
    "plugins" -> plugins.json
  )

  lazy val toRoutes: Seq[NgRoute] = {
    if (enabled) {
      routes.zipWithIndex.map {
        case (route, idx) =>
          NgRoute(
            location = location,
            id = id + "-" + idx,
            name = name + " - " + route.frontend.methods.mkString(", ") + " - " + route.frontend.domains.map(_.path).mkString(", "),
            description = description,
            tags = tags,
            metadata = metadata ++ Map("otoroshi-core-original-route-id" -> id),
            enabled = enabled,
            debugFlow = debugFlow,
            groups = groups,
            frontend = route.frontend,
            backend = route.backend,
            backendRef = route.backendRef,
            client = client,
            healthCheck = HealthCheck(false, "/"),
            plugins = plugins
          )
      }
    } else {
      Seq.empty
    }
  }
}

object NgRoutesComposition {
  val fmt = new Format[NgRoutesComposition] {
    override def writes(o: NgRoutesComposition): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgRoutesComposition] = Try {
      NgRoutesComposition(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        routes = json.select("routes").asOpt[Seq[JsValue]].map(seq => seq.flatMap(json => NgMinimalRoute.fmt.reads(json).asOpt)).getOrElse(Seq.empty),
        client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = NgPlugins.readFrom(json.select("plugins")),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }

  def fromOpenApi(domain: String, openapi: String)(implicit ec: ExecutionContext, env: Env): Future[NgRoutesComposition] = {
    val codef: Future[String] = if (openapi.startsWith("http://") || openapi.startsWith("https://")) {
      env.Ws.url(openapi).get().map(_.body)
    } else {
      FastFuture.successful(openapi)
    } 
    codef.map { code =>
      val json = Json.parse(code)
      val name = json.select("info").select("title").as[String]
      val description = json.select("info").select("description").asOpt[String].getOrElse("")
      val version = json.select("info").select("version").asOpt[String].getOrElse("")
      val targets = json.select("servers").as[Seq[JsObject]].map { server =>
        val serverUrl = server.select("url").asString
        val serverUri = Uri(serverUrl)
        val serverDomain = serverUri.authority.host.toString()
        val tls = serverUri.scheme.toLowerCase().contains("https")
        val port = if (serverUri.authority.port == 0) (if (tls) 443 else 80) else serverUri.authority.port
        NgTarget(
          id = serverUrl,
          hostname = serverDomain,
          port = port,
          tls = tls
        )
      }
      val paths = json.select("paths").asOpt[JsObject].getOrElse(Json.obj())
      val routes: Seq[NgMinimalRoute] = paths.value.toSeq.map { 
        case (path, obj) => 
          val cleanPath = path.replace("{", ":").replace("}", "")
          val methods = obj.as[JsObject].value.toSeq.map(_._1.toUpperCase())
          NgMinimalRoute(
            frontend = NgFrontend(
              domains = Seq(NgDomainAndPath(s"${domain}${cleanPath}")),
              headers = Map.empty,
              methods = methods,
              stripPath = false,
              strict = true,
            ),
            backend = NgBackend(
              targets = targets,
              targetRefs = Seq.empty,
              root = "/",
              rewrite = false,
              loadBalancing = RoundRobin
            )
          )          
      } 
      NgRoutesComposition(
        location = EntityLocation.default,
        id = "routes-comp_" + IdGenerator.uuid,
        name = name,
        description = description,
        tags = Seq("env:prod"),
        metadata = Map("version" -> version),
        enabled = true,
        debugFlow = false,
        groups = Seq("default"),
        routes = routes,
        client = ClientConfig(),
        plugins = NgPlugins(Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost],
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls],
          )
        ))
      )
    }
  } 
}

trait NgRoutesCompositionDataStore extends BasicStore[NgRoutesComposition]

class KvNgRoutesCompositionDataStore(redisCli: RedisLike, _env: Env)
  extends NgRoutesCompositionDataStore
    with RedisLikeStore[NgRoutesComposition] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[NgRoutesComposition]               = NgRoutesComposition.fmt
  override def key(id: String): Key             = Key.Empty / _env.storageRoot / "routes-comps" / id
  override def extractId(value: NgRoutesComposition): String  = value.id
}

