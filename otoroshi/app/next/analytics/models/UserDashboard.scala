package otoroshi.next.analytics.models

import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class Widget(
    id: String,
    title: String,
    query: String,
    params: JsObject = Json.obj(),
    `type`: String = "line",
    width: Int = 4,
    height: Int = 2,
    options: JsObject = Json.obj()
) {
  def json: JsValue = Widget.format.writes(this)
}

object Widget {
  val format: Format[Widget] = new Format[Widget] {
    override def reads(json: JsValue): JsResult[Widget] = Try {
      Widget(
        id = (json \ "id").asOpt[String].filterNot(_.isEmpty).getOrElse(IdGenerator.uuid),
        title = (json \ "title").asOpt[String].getOrElse(""),
        query = (json \ "query").as[String],
        params = (json \ "params").asOpt[JsObject].getOrElse(Json.obj()),
        `type` = (json \ "type").asOpt[String].getOrElse("line"),
        width = (json \ "width").asOpt[Int].getOrElse(4),
        height = (json \ "height").asOpt[Int].getOrElse(2),
        options = (json \ "options").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: Widget): JsValue = Json.obj(
      "id"      -> o.id,
      "title"   -> o.title,
      "query"   -> o.query,
      "params"  -> o.params,
      "type"    -> o.`type`,
      "width"   -> o.width,
      "height"  -> o.height,
      "options" -> o.options
    )
  }
}

case class UserDashboard(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    widgets: Seq[Widget],
    defaults: JsObject
) extends EntityLocationSupport {
  override def internalId: String          = id
  override def theDescription: String      = description
  override def theMetadata: Map[String, String] = metadata
  override def theName: String             = name
  override def theTags: Seq[String]        = tags
  override def json: JsValue               = UserDashboard.format.writes(this)
}

object UserDashboard {
  val format: Format[UserDashboard] = new Format[UserDashboard] {
    override def reads(json: JsValue): JsResult[UserDashboard] = Try {
      UserDashboard(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").asOpt[String].filterNot(_.isEmpty).getOrElse(IdGenerator.namedId("dashboard", IdGenerator.uuid)),
        name = (json \ "name").asOpt[String].getOrElse(""),
        description = (json \ "description").asOpt[String].getOrElse(""),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        widgets = (json \ "widgets").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(j => Widget.format.reads(j).asOpt),
        defaults = (json \ "defaults").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: UserDashboard): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "metadata"    -> Json.toJson(o.metadata),
      "enabled"     -> o.enabled,
      "widgets"     -> JsArray(o.widgets.map(_.json)),
      "defaults"    -> o.defaults
    )
  }
  def defaultUserDashboardTemplate(implicit env: Env): UserDashboard = UserDashboard(
    location = EntityLocation.default,
    id = IdGenerator.namedId("user-dashboard", env),
    name = "New user dashboard",
    description = "New user dashboard description",
    metadata = Map.empty,
    tags = Seq.empty,
    enabled = true,
    widgets = Seq.empty,
    defaults = Json.obj()
  )
}


trait UserDashboardDataStore extends BasicStore[UserDashboard] {
  def template(env: Env): UserDashboard = {
    implicit val e = env
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .userDashboardTemplate
      .map { template =>
        UserDashboard.format.reads(UserDashboard.defaultUserDashboardTemplate.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        UserDashboard.defaultUserDashboardTemplate
      }
  }
}

class KvUserDashboardDataStore(redisCli: RedisLike, _env: Env)
  extends UserDashboardDataStore
    with RedisLikeStore[UserDashboard] {
  override def fmt: Format[UserDashboard]              = UserDashboard.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:user-dashboards:$id"
  override def extractId(value: UserDashboard): String = value.id
}

