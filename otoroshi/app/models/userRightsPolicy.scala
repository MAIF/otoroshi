package otoroshi.models

import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.env.Env
import otoroshi.next.proxy.NgProxyStateLoaderJob
import otoroshi.security.IdGenerator
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait UserRightsPolicyMode {
  def name: String
}

object UserRightsPolicyMode {
  // the rights of the policy are the rights of the user, whatever its auth module gives it
  case object Replace  extends UserRightsPolicyMode { val name = "replace"  }
  // the rights of the user are the ones its auth module gives it, capped by the ones of the policy: the auth module
  // cannot give what the policy keeps away
  case object Restrict extends UserRightsPolicyMode { val name = "restrict" }
  def apply(value: String): Option[UserRightsPolicyMode] = value.toLowerCase.trim match {
    case "replace"  => Some(Replace)
    case "restrict" => Some(Restrict)
    case _          => None
  }
}

// the rights of a backoffice user, whatever the auth module it logs in with. a policy is identified by the email of its
// user (a user has one policy at most). the rights are resolved each time the user calls otoroshi, from its session or
// from its personal admin apikey: a policy written or deleted takes effect on the next call, without waiting for the
// user to log in again
case class UserRightsPolicy(
    location: EntityLocation = EntityLocation(),
    email: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    mode: UserRightsPolicyMode = UserRightsPolicyMode.Replace,
    rights: UserRights = UserRights.empty
) extends EntityLocationSupport {
  def internalId: String               = email
  def json: JsValue                    = UserRightsPolicy.format.writes(this)
  def theName: String                  = name
  def theDescription: String           = description
  def theTags: Seq[String]             = tags
  def theMetadata: Map[String, String] = metadata
  def applyTo(moduleRights: UserRights): UserRights = mode match {
    case UserRightsPolicyMode.Replace  => rights
    case UserRightsPolicyMode.Restrict => moduleRights.intersect(rights)
  }
}

object UserRightsPolicy {

  def normalizeEmail(email: String): String = email.toLowerCase.trim

  val format: Format[UserRightsPolicy] = new Format[UserRightsPolicy] {
    override def writes(o: UserRightsPolicy): JsValue = o.location.jsonWithKey ++ Json.obj(
      "email"       -> o.email,
      "name"        -> o.name,
      "description" -> o.description,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "metadata"    -> o.metadata,
      "mode"        -> o.mode.name,
      "rights"      -> o.rights.json
    )
    // a policy that cannot be read is not applied: its mode and its email are required to be valid, an unreadable right
    // is dropped (the policy then gives less, never more)
    override def reads(json: JsValue): JsResult[UserRightsPolicy] = Try {
      val email = normalizeEmail((json \ "email").as[String])
      if (email.isEmpty) throw new IllegalArgumentException("the email of the policy is empty")
      UserRightsPolicy(
        location = EntityLocation.readFromKey(json),
        email = email,
        name = (json \ "name").asOpt[String].getOrElse(email),
        description = (json \ "description").asOpt[String].getOrElse(""),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        mode = (json \ "mode").asOpt[String] match {
          case None        => UserRightsPolicyMode.Replace
          case Some(value) =>
            UserRightsPolicyMode(value).getOrElse(throw new IllegalArgumentException(s"unknown policy mode '$value'"))
        },
        rights = (json \ "rights").asOpt[JsArray].map(UserRights.readFromArray).getOrElse(UserRights.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }

  def pick(policies: Seq[UserRightsPolicy], email: String): Option[UserRightsPolicy] = {
    val normalized = normalizeEmail(email)
    policies.find(_.email == normalized)
  }

  def of(email: String)(using env: Env): Option[UserRightsPolicy] = env.proxyState.userRightsPolicy(email)

  def effectiveRights(email: String, moduleRights: UserRights)(using env: Env): UserRights = {
    of(email) match {
      case None         => moduleRights
      case Some(policy) => policy.applyTo(moduleRights)
    }
  }

  // before the first sync of the proxy state, the policies are read from the datastore: a user calling otoroshi
  // while it starts does not get the rights a policy keeps away from it
  def ofF(email: String)(using env: Env, ec: ExecutionContext): Future[Option[UserRightsPolicy]] = {
    if (NgProxyStateLoaderJob.firstSync.get()) {
      of(email).vfuture
    } else {
      env.datastores.userRightsPolicyDataStore.findById(normalizeEmail(email))
    }
  }

  def effectiveRightsF(email: String, moduleRights: UserRights)(using
      env: Env,
      ec: ExecutionContext
  ): Future[UserRights] = {
    ofF(email).map {
      case None         => moduleRights
      case Some(policy) => policy.applyTo(moduleRights)
    }
  }

  // the proxy state of the node writing the policy is updated right away, without waiting for its next sync: the
  // policy applies on the next call of the user. the other nodes of a cluster get it on their next sync
  def writeValidator(
      entity: UserRightsPolicy,
      body: JsValue,
      oldEntity: Option[(UserRightsPolicy, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, UserRightsPolicy]] = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Env              = env
    env.datastores.userRightsPolicyDataStore.findAll().map { all =>
      env.proxyState.updateUserRightsPolicies(all.filterNot(_.email == entity.email) :+ entity)
      Right(entity)
    }
  }

  def deleteValidator(
      entity: UserRightsPolicy,
      body: JsValue,
      singularName: String,
      id: String,
      action: DeleteAction,
      env: Env
  ): Future[Either[JsValue, Unit]] = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Env              = env
    env.datastores.userRightsPolicyDataStore.findAll().map { all =>
      env.proxyState.updateUserRightsPolicies(all.filterNot(_.email == entity.email))
      Right(())
    }
  }
}

class UserRightsPolicyDataStore(redisCli: RedisLike, _env: Env) extends RedisLikeStore[UserRightsPolicy] {
  override def fmt: Format[UserRightsPolicy]               = UserRightsPolicy.format
  override def redisLike(using env: Env): RedisLike        = redisCli
  override def key(id: String): String                     =
    s"${_env.storageRoot}:user-rights-policies:${UserRightsPolicy.normalizeEmail(id)}"
  override def extractId(value: UserRightsPolicy): String = value.email
  def template(env: Env): UserRightsPolicy = {
    UserRightsPolicy(
      email = "jane.doe@oto.tools",
      name = "New user rights policy",
      description = "The rights of a user, whatever the auth module it logs in with",
      mode = UserRightsPolicyMode.Replace,
      rights = UserRights.varargs(
        UserRight(TenantAccess("default:r"), Seq(TeamAccess("default:r")))
      )
    )
  }
}

// the admin apikey of a backoffice user: its rights are not written in it but resolved on each call from the rights
// policy of its owner, so a policy changed or deleted takes effect on the next call. its owner creates it even when the
// owner cannot write apikeys. it needs a policy in replace mode: a policy in restrict mode caps the rights the auth module
// gives at login, and a call made with an apikey has no login
object PersonalAdminApikey {

  val ownerMetadataKey = "otoroshi-personal-admin-apikey-owner"

  val restrictModeError =
    "a personal admin apikey needs a rights policy in replace mode: the rights of a policy in restrict mode depend on the login of the user"

  def ownerOf(apikey: ApiKey): Option[String] = apikey.metadata.get(ownerMetadataKey).map(UserRightsPolicy.normalizeEmail)

  def ownerOfJson(json: JsValue): Option[String] =
    (json \ "metadata" \ ownerMetadataKey).asOpt[String].map(UserRightsPolicy.normalizeEmail)

  // the secret of a personal apikey gives the rights of its owner, today's and tomorrow's: only its owner and the super
  // admins can read it
  def canRead(owner: String, user: BackOfficeUser)(using env: Env): Boolean =
    user.rights.superAdmin || UserRightsPolicy.normalizeEmail(user.email) == owner

  // only its owner (through its dedicated endpoints) and the super admins write one: someone else marking an apikey as
  // the one of a user would get the rights its policy gives it later
  def canWrite(owner: String, user: BackOfficeUser)(using env: Env): Boolean = user.rights.superAdmin

  // without a policy in replace mode for its owner (deleted or switched to restrict since), the apikey has no rights at
  // all. before the first sync of the proxy state the policies are unknown: the apikey is refused rather than given
  // rights a policy keeps away
  def rights(owner: String)(using env: Env): Either[String, UserRights] = {
    if (!NgProxyStateLoaderJob.firstSync.get()) {
      Left("otoroshi is starting, retry in a few seconds")
    } else {
      UserRightsPolicy.of(owner) match {
        case None                                                          => Left("the owner of this apikey has no rights policy")
        case Some(policy) if policy.mode == UserRightsPolicyMode.Restrict => Left(restrictModeError)
        case Some(policy)                                                  => Right(policy.rights)
      }
    }
  }

  def find(email: String)(using env: Env, ec: ExecutionContext): Future[Option[ApiKey]] = {
    val owner = UserRightsPolicy.normalizeEmail(email)
    env.datastores.apiKeyDataStore.findAll().map(_.filter(k => ownerOf(k).contains(owner)).sortBy(_.clientId).headOption)
  }

  def create(policy: UserRightsPolicy)(using env: Env, ec: ExecutionContext): Future[ApiKey] = {
    val apikey = ApiKey(
      clientId = IdGenerator.lowerCaseToken(16),
      clientSecret = IdGenerator.token(64),
      clientName = s"Personal admin apikey of ${policy.email}",
      description = s"The admin apikey of ${policy.email}, with the rights its rights policy gives it",
      authorizedEntities = Seq(ServiceGroupIdentifier(env.backOfficeGroupId)),
      tags = Seq("personal-admin-apikey"),
      metadata = Map(
        ownerMetadataKey               -> policy.email,
        // no rights at all: the apikey only gets the ones of the policy of its owner. without these metadata, an admin
        // apikey would have no restriction, which is what an apikey whose owner was removed by hand would become
        ApiKey.accessRightsMetadataKey -> "[]"
      ),
      location = policy.location
    )
    apikey.save().map(_ => apikey)
  }

  // a new secret, the same client id
  def rotate(apikey: ApiKey)(using env: Env, ec: ExecutionContext): Future[ApiKey] = {
    val updated = apikey.copy(clientSecret = IdGenerator.token(64))
    updated.save().map(_ => updated)
  }
}
