package otoroshi.models

import akka.http.scaladsl.util.FastFuture._
import otoroshi.auth.{AuthModuleConfig, ValidableUser}
import otoroshi.env.Env
import org.joda.time.DateTime
import play.api.libs.json._
import otoroshi.storage.BasicStore
import otoroshi.utils.{JsonPathValidator, JsonValidator}
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

case class BackOfficeUser(
    randomId: String,
    name: String,
    email: String,
    profile: JsValue,
    token: JsValue = Json.obj(),
    authConfigId: String,
    simpleLogin: Boolean,
    createdAt: DateTime = DateTime.now(),
    expiredAt: DateTime = DateTime.now(),
    lastRefresh: DateTime = DateTime.now(),
    tags: Seq[String],
    metadata: Map[String, String],
    rights: UserRights,
    adminEntityValidators: Map[String, Seq[JsonValidator]],
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends RefreshableUser
    with ValidableUser
    with EntityLocationSupport {

  def internalId: String               = randomId
  def json: JsValue                    = toJson
  def theDescription: String           = name
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags
  // def adminEntityValidators: Map[String, Seq[JsonValidator]] = Map(
  //   "all" -> Seq(
  //     JsonPathValidator("$.*", "JsonContainsNot(${env.)".json, "no el env".some),
  //     JsonPathValidator("$.*", "JsonContainsNot(${config.)".json, "no el config".some),
  //     JsonPathValidator("$.*", "JsonContainsNot(${vault://env/)".json, "no vault value".some),
  //   ),
  //   "route" -> Seq(
  //     JsonPathValidator("$.frontend.domains[*]", "StartsWith(fifoufou.oto.tools)".json, "bad exposition domain".some),
  //     JsonPathValidator("$.frontend.domains[?(@ =~ /^((?!fifoufou\\.oto\\.tools.*).)*$/i)]", "Size(0)".json, "bad exposition domain 2".some),
  //     JsonPathValidator("[?(@.enabled == true)]", JsBoolean(true), "route not enabled".some),
  //     JsonPathValidator("$.plugins[?(@.plugin == 'cp:otoroshi.next.plugins.OverrideHost')]", "Size(0)".json, "no override host".some),
  //   )
  // )

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    val withDuration = this.copy(expiredAt = expiredAt.plus(duration.toMillis))
    env.datastores.backOfficeUserDataStore.set(withDuration, Some(duration)).fast.map(_ => withDuration)
  }

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.backOfficeUserDataStore.delete(randomId)

  def toJson: JsValue = BackOfficeUser.fmt.writes(this)

  def withAuthModuleConfig[A](f: AuthModuleConfig => A)(implicit ec: ExecutionContext, env: Env): Unit = {
    // env.datastores.authConfigsDataStore.findById(authConfigId).map {
    env.proxyState.authModuleAsync(authConfigId).map {
      case None       => ()
      case Some(auth) => f(auth)
    }
  }

  override def updateToken(tok: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.backOfficeUserDataStore.set(
      copy(
        token = tok,
        lastRefresh = DateTime.now()
      ),
      Some((expiredAt.toDate.getTime - System.currentTimeMillis()).millis)
    )
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
            tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
            rights = UserRights.readFromObject(json),
            adminEntityValidators = json
              .select("adminEntityValidators")
              .asOpt[JsObject]
              .map { obj =>
                obj.value.mapValues { arr =>
                  arr.asArray.value
                    .map { item =>
                      JsonValidator.format.reads(item)
                    }
                    .collect { case JsSuccess(v, _) =>
                      v
                    }
                }.toMap
              }
              .getOrElse(Map.empty[String, Seq[JsonValidator]])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get

    override def writes(o: BackOfficeUser): JsValue =
      o.location.jsonWithKey ++ Json.obj(
        "randomId"              -> o.randomId,
        "name"                  -> o.name,
        "email"                 -> o.email,
        "authConfigId"          -> o.authConfigId,
        "profile"               -> o.profile,
        "token"                 -> o.token,
        "simpleLogin"           -> o.simpleLogin,
        "createdAt"             -> o.createdAt.getMillis,
        "expiredAt"             -> o.expiredAt.getMillis,
        "lastRefresh"           -> o.lastRefresh.getMillis,
        "metadata"              -> o.metadata,
        "tags"                  -> JsArray(o.tags.map(JsString.apply)),
        "rights"                -> o.rights.json,
        "adminEntityValidators" -> o.adminEntityValidators.mapValues(v => JsArray(v.map(_.json)))
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
