package models

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.util.FastFuture._
import env.Env
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import utils.JsonImplicits._
import storage.BasicStore

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

case class BackOfficeUser(randomId: String,
                          name: String,
                          email: String,
                          profile: JsValue,
                          authorizedGroup: Option[String],
                          createdAt: DateTime = DateTime.now(),
                          expiredAt: DateTime = DateTime.now()) {

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    val withDuration = this.copy(expiredAt = expiredAt.plusMillis(duration.toMillis.toInt))
    env.datastores.backOfficeUserDataStore.set(withDuration, Some(duration)).fast.map(_ => withDuration)
  }

  def saveWithExpiration()(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    env.datastores.backOfficeUserDataStore.set(this, Some(Duration(this.expiredAt.getMillis - DateTime.now().getMillis, TimeUnit.MILLISECONDS))).fast.map(_ => this)
  }

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.backOfficeUserDataStore.delete(randomId)
}

object BackOfficeUser {
  lazy val logger = Logger("otoroshi-backoffice-user")
  val _fmt                                 = Json.format[BackOfficeUser]
  def toJson(value: BackOfficeUser): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): BackOfficeUser =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
}

trait BackOfficeUserDataStore extends BasicStore[BackOfficeUser] {
  def blacklisted(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def sessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def discardSession(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def discardAllSessions()(implicit ec: ExecutionContext, env: Env): Future[Long]
}
