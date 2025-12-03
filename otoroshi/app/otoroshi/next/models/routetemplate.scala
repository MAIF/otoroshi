package next.models

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.models.NgRoute
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterJsValueReader}
import play.api.libs.json.{Format, JsError, JsResult, JsSuccess, JsValue, Json}

import scala.util.{Failure, Success, Try}

case class RouteTemplate(
    location: EntityLocation,
    id: String = IdGenerator.uuid,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    route: NgRoute
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = RouteTemplate.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object RouteTemplate {
  def defaultRouteTemplate()(implicit env: Env) = RouteTemplate(
    location = EntityLocation.default,
    id = IdGenerator.namedId("route-template", env),
    name = "New route template",
    description = "New route template description",
    metadata = Map.empty,
    tags = Seq.empty,
    route = NgRoute.empty
  )
  val format                                    = new Format[RouteTemplate] {
    override def reads(json: JsValue): JsResult[RouteTemplate] = Try {
      RouteTemplate(
        location = json.select("location").as(EntityLocation.format),
        id = json.selectAsString("id"),
        name = json.selectAsString("name"),
        description = json.selectAsString("description"),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        route = json.select("route").asOpt(NgRoute.fmt).getOrElse(NgRoute.empty)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: RouteTemplate): JsValue = Json.obj(
      "location"    -> o.location.json,
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "tags"        -> o.tags,
      "metadata"    -> o.metadata,
      "route"       -> o.route.json
    )
  }
}

trait RouteTemplateDataStore extends BasicStore[RouteTemplate] {
  def template(env: Env): RouteTemplate = {
    implicit val e = env

    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .routeTemplate
      .map { template =>
        RouteTemplate.format.reads(RouteTemplate.defaultRouteTemplate.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        RouteTemplate.defaultRouteTemplate
      }
  }
}

class KvRouteTemplateDataStore(redisCli: RedisLike, _env: Env)
    extends RouteTemplateDataStore
    with RedisLikeStore[RouteTemplate] {
  override def fmt: Format[RouteTemplate]              = RouteTemplate.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:route-templates:$id"
  override def extractId(value: RouteTemplate): String = value.id
}
