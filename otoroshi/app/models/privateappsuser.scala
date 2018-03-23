package models

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.util.FastFuture._
import env.Env
import play.api.libs.json._
import utils.JsonImplicits._
import storage.BasicStore
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

case class PrivateAppsUser(randomId: String,
                           name: String,
                           email: String,
                           profile: JsValue,
                           createdAt: DateTime = DateTime.now(),
                           expiredAt: DateTime = DateTime.now()) {
  def otoroshiData(implicit env: Env): Option[Map[String, String]] =
    (profile \ env.auth0AppMeta \ env.auth0UserMeta).asOpt[Map[String, String]]
  def picture: Option[String]             = (profile \ "picture").asOpt[String]
  def field(name: String): Option[String] = (profile \ "name").asOpt[String]
  def userId: Option[String]              = (profile \ "user_id").asOpt[String].orElse((profile \ "sub").asOpt[String])

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this.copy(expiredAt = DateTime.now().plusMillis(duration.toMillis.toInt)), Some(duration))
      .map(_ => this)

  def saveWithExpiration()(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this, Some(Duration(this.expiredAt.getMillis - DateTime.now().getMillis, TimeUnit.MILLISECONDS)))
      .map(_ => this)

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.privateAppsUserDataStore.delete(randomId)

  def toJson: JsValue = PrivateAppsUser.fmt.writes(this)
}

object PrivateAppsUser {
  lazy val logger = Logger("otoroshi-privateapps-user")
  val fmt = Json.format[PrivateAppsUser]
  def toJson(value: PrivateAppsUser): JsValue = fmt.writes(value)
  def fromJsons(value: JsValue): PrivateAppsUser =
    try {
      fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
}

trait PrivateAppsUserDataStore extends BasicStore[PrivateAppsUser]
