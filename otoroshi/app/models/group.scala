package models

import env.Env
import play.api.Logger
import play.api.libs.json._
import security.IdGenerator
import otoroshi.storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ServiceGroup(id: String = IdGenerator.token(64), name: String, description: String = "No description", metadata: Map[String, String]) {
  def services(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] =
    env.datastores.serviceDescriptorDataStore.findByGroup(id)
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.serviceGroupDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceGroupDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceGroupDataStore.exists(this)
  def toJson                                            = ServiceGroup.toJson(this)
}

object ServiceGroup {

  lazy val logger = Logger("otoroshi-service-group")

  val _fmt = new Format[ServiceGroup] {
    override def reads(json: JsValue): JsResult[ServiceGroup] = Try {
      ServiceGroup(
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(i) => JsSuccess(i)
    }
    override def writes(o: ServiceGroup): JsValue = Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata
    )
  }
  def toJson(value: ServiceGroup): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): ServiceGroup =
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
  def initiateNewGroup(): ServiceGroup = ServiceGroup(
    id = IdGenerator.token(64),
    name = "product-group",
    description = "group for product",
    metadata = Map.empty
  )
}
