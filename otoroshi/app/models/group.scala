package models

import env.Env
import play.api.Logger
import play.api.libs.json.{JsResult, JsValue, Json}
import security.IdGenerator
import otoroshi.storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}

case class ServiceGroup(id: String = IdGenerator.token(64), name: String, description: String = "No description") {
  def services(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] =
    env.datastores.serviceDescriptorDataStore.findByGroup(id)
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.serviceGroupDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceGroupDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceGroupDataStore.exists(this)
  def toJson                                            = ServiceGroup.toJson(this)
}

object ServiceGroup {

  lazy val logger = Logger("otoroshi-service-group")

  val _fmt                                 = Json.format[ServiceGroup]
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
    description = "group for product"
  )
}
