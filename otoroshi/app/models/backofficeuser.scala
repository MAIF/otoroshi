package models

import akka.http.scaladsl.util.FastFuture._
import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import org.joda.time.DateTime
import otoroshi.models.{EntityLocationSupport, TeamAccess, TenantAccess, UserRight, UserRights}
import play.api.libs.json._
import otoroshi.storage.BasicStore
import otoroshi.utils.syntax.implicits._

import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait RefreshableUser {
  def token: JsValue
  def lastRefresh: DateTime
  def updateToken(tok: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
}

case class BackOfficeUser(randomId: String,
                          name: String,
                          email: String,
                          profile: JsValue,
                          token: JsValue = Json.obj(),
                          authConfigId: String,
                          simpleLogin: Boolean,
                          createdAt: DateTime = DateTime.now(),
                          expiredAt: DateTime = DateTime.now(),
                          lastRefresh: DateTime = DateTime.now(),
                          metadata: Map[String, String],
                          rights: UserRights,
                          location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
                         ) extends RefreshableUser with EntityLocationSupport {

  def internalId: String = randomId
  def json: JsValue = toJson

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    val withDuration = this.copy(expiredAt = expiredAt.plus(duration.toMillis))
    env.datastores.backOfficeUserDataStore.set(withDuration, Some(duration)).fast.map(_ => withDuration)
  }

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.backOfficeUserDataStore.delete(randomId)

  def toJson: JsValue = BackOfficeUser.fmt.writes(this)

  def withAuthModuleConfig[A](f: AuthModuleConfig => A)(implicit ec: ExecutionContext, env: Env): Unit = {
    env.datastores.authConfigsDataStore.findById(authConfigId).map {
      case None => ()
      case Some(auth) => f(auth)
    }
  }

  override def updateToken(tok: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.backOfficeUserDataStore.set(copy(
      token = tok,
      lastRefresh = DateTime.now()
    ), Some((expiredAt.toDate.getTime - System.currentTimeMillis()).millis))
  }
}

object BackOfficeUser {

  val fmt = new Format[BackOfficeUser] {

    override def reads(json: JsValue): JsResult[BackOfficeUser] =
      Try {
        JsSuccess(
          BackOfficeUser(
            location = otoroshi.models.EntityLocation.readFromKey(json),
            randomId = (json \ "randomId").as[String],
            name = (json \ "name").as[String],
            email = (json \ "email").as[String],
            authConfigId = (json \ "authConfigId").asOpt[String].getOrElse("none"),
            profile = (json \ "profile").asOpt[JsValue].getOrElse(Json.obj()),
            token = (json \ "token").asOpt[JsValue].getOrElse(Json.obj()),
            simpleLogin = (json \ "simpleLogin").asOpt[Boolean].getOrElse(true),
            createdAt = (json \ "createdAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
            expiredAt = (json \ "expiredAt").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
            lastRefresh = (json \ "lastRefresh").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            rights = UserRights.readFromObject(json)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: BackOfficeUser): JsValue = o.location.jsonWithKey ++ Json.obj(
      "randomId"        -> o.randomId,
      "name"            -> o.name,
      "email"           -> o.email,
      "authConfigId"    -> o.authConfigId,
      "profile"         -> o.profile,
      "token"           -> o.token,
      "simpleLogin"     -> o.simpleLogin,
      "createdAt"       -> o.createdAt.getMillis,
      "expiredAt"       -> o.expiredAt.getMillis,
      "lastRefresh"     -> o.lastRefresh.getMillis,
      "metadata"        -> o.metadata,
      "rights"          -> o.rights.json
    )
  }
}

trait BackOfficeUserDataStore extends BasicStore[BackOfficeUser] {
  def blacklisted(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def sessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def tsessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[BackOfficeUser]]
  def discardSession(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def discardAllSessions()(implicit ec: ExecutionContext, env: Env): Future[Long]
}
