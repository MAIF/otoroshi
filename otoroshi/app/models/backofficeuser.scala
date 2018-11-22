package models

import akka.http.scaladsl.util.FastFuture._
import env.Env
import org.joda.time.DateTime
import play.api.libs.json._
import storage.BasicStore

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class BackOfficeUser(randomId: String,
                          name: String,
                          email: String,
                          profile: JsValue,
                          authorizedGroup: Option[String],
                          simpleLogin: Boolean,
                          createdAt: DateTime = DateTime.now(),
                          expiredAt: DateTime = DateTime.now()) {

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    val withDuration = this.copy(expiredAt = expiredAt.plusMillis(duration.toMillis.toInt))
    env.datastores.backOfficeUserDataStore.set(withDuration, Some(duration)).fast.map(_ => withDuration)
  }

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.backOfficeUserDataStore.delete(randomId)
}

object BackOfficeUser {

  val fmt = new Format[BackOfficeUser] {

    override def reads(json: JsValue): JsResult[BackOfficeUser] =
      Try {
        JsSuccess(
          BackOfficeUser(
            randomId = (json \ "randomId").as[String],
            name = (json \ "name").as[String],
            email = (json \ "email").as[String],
            profile = (json \ "profile").asOpt[JsValue].getOrElse(Json.obj()),
            authorizedGroup = (json \ "authorizedGroup").asOpt[String],
            simpleLogin = (json \ "simpleLogin").asOpt[Boolean].getOrElse(true),
            createdAt = (json \ "createdAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
            expiredAt = (json \ "expiredAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: BackOfficeUser): JsValue = Json.obj(
      "randomId"        -> o.randomId,
      "name"            -> o.name,
      "email"           -> o.email,
      "profile"         -> o.profile,
      "authorizedGroup" -> o.authorizedGroup.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "simpleLogin"     -> o.simpleLogin,
      "createdAt"       -> o.createdAt.getMillis,
      "expiredAt"       -> o.expiredAt.getMillis,
    )
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
