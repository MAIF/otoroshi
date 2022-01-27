package otoroshi.next.models

import otoroshi.models._
import play.api.libs.json._
import otoroshi.storage._
import otoroshi.env._
import scala.util._

import otoroshi.utils.syntax.implicits._
import otoroshi.api.OtoroshiEnvHolder

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
          name = name,
          description = description,
          tags = tags,
          metadata = metadata,
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

