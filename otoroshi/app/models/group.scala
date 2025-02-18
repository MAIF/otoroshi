package otoroshi.models

import otoroshi.actions.ApiActionContext
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json._
import otoroshi.security.IdGenerator
import otoroshi.storage.BasicStore
import otoroshi.utils.syntax.implicits.BetterJsReadable

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ServiceGroup(
    id: String = IdGenerator.token(64),
    name: String,
    description: String = "No description",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {
  def services(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] =
    env.datastores.serviceDescriptorDataStore.findByGroup(id)
  def save()(implicit ec: ExecutionContext, env: Env)                                   = env.datastores.serviceGroupDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env)                                 = env.datastores.serviceGroupDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env)                                 = env.datastores.serviceGroupDataStore.exists(this)
  def toJson                                                                            = ServiceGroup.toJson(this)

  def json: JsValue                    = toJson
  def internalId: String               = id
  def theDescription: String           = description
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags
}

object ServiceGroup {

  lazy val logger = Logger("otoroshi-service-group")

  val _fmt                                                 = new Format[ServiceGroup] {
    override def reads(json: JsValue): JsResult[ServiceGroup] =
      Try {
        ServiceGroup(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          description = (json \ "description").asOpt[String].getOrElse(""),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(i) => JsSuccess(i)
      }
    override def writes(o: ServiceGroup): JsValue             =
      o.location.jsonWithKey ++ Json.obj(
        "id"          -> o.id,
        "name"        -> o.name,
        "description" -> o.description,
        "tags"        -> JsArray(o.tags.map(JsString.apply)),
        "metadata"    -> o.metadata
      )
  }
  def toJson(value: ServiceGroup): JsValue                 = _fmt.writes(value)
  def fromJsons(value: JsValue): ServiceGroup              =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[ServiceGroup] = _fmt.reads(value)
}

trait ServiceGroupDataStore extends BasicStore[ServiceGroup] {
  def template(env: Env, ctx: Option[ApiActionContext[_]] = None): ServiceGroup = initiateNewGroup(env, ctx)
  def initiateNewGroup(env: Env, ctx: Option[ApiActionContext[_]] = None): ServiceGroup = {
    val defaultGroup = ServiceGroup(
      id = IdGenerator.namedId("group", env),
      name = "product-group",
      description = "group for product",
      metadata = Map.empty,
      tags = Seq.empty
    )
      .copy(location = EntityLocation.ownEntityLocation(ctx)(env))
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .group
      .map { template =>
        ServiceGroup._fmt.reads(defaultGroup.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultGroup
      }
  }
}
