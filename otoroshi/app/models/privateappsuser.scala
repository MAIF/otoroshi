package models

import env.Env
import org.joda.time.DateTime
import play.api.libs.json._
import storage.BasicStore
import utils.JsonImplicits._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class PrivateAppsUser(randomId: String,
                           name: String,
                           email: String,
                           profile: JsValue,
                           realm: String,
                           createdAt: DateTime = DateTime.now(),
                           expiredAt: DateTime = DateTime.now()) {
  def otoroshiData(implicit env: Env): Option[Map[String, String]] = {
    (profile \ env.auth0AppMeta \ env.auth0UserMeta).asOpt[String].map(s => Json.parse(s)
      .as[Map[String, String]])
      .orElse(
        (profile \ env.auth0AppMeta \ env.auth0UserMeta).asOpt[Map[String, String]]
      )
  }

  def picture: Option[String]             = (profile \ "picture").asOpt[String]
  def field(name: String): Option[String] = (profile \ name).asOpt[String]
  def userId: Option[String]              = (profile \ "user_id").asOpt[String].orElse((profile \ "sub").asOpt[String])

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this.copy(expiredAt = DateTime.now().plusMillis(duration.toMillis.toInt)), Some(duration))
      .map(_ => this)

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.privateAppsUserDataStore.delete(randomId)

  def toJson: JsValue = PrivateAppsUser.fmt.writes(this)

}

object PrivateAppsUser {
  val fmt = new Format[PrivateAppsUser] {
    override def reads(json: JsValue) = Try {
      JsSuccess(
        PrivateAppsUser(
          randomId = (json \ "randomId").as[String],
          name = (json \ "name").as[String],
          email = (json \ "email").as[String],
          profile = (json \ "profile").as[JsValue],
          realm = (json \ "realm").asOpt[String].getOrElse("none"),
          createdAt = new DateTime((json \ "createdAt").as[Long]),
          expiredAt = new DateTime((json \ "expiredAt").as[Long])
        )
      )
    } recover {
      case e => JsError(e.getMessage)
    } get

    override def writes(o: PrivateAppsUser) = Json.obj(
      "randomId" -> o.randomId,
      "name" -> o.name,
      "email" -> o.email,
      "profile" -> o.profile,
      "realm" -> o.realm,
      "createdAt" -> o.createdAt.toDate.getTime,
      "expiredAt" -> o.expiredAt.toDate.getTime,
    )
  }
}

trait PrivateAppsUserDataStore extends BasicStore[PrivateAppsUser]
