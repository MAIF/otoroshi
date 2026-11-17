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
  // the rights of the policy are the rights of the users of its auth module, whatever the module gives them. for the
  // users logging in another way, the policy only restricts
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

// the rights of a backoffice user. a policy is identified by the email of its user (a user has one policy at most). the
// rights are resolved each time the user calls otoroshi, from its session or from its personal admin apikey: a policy
// written or deleted takes effect on the next call, without waiting for the user to log in again.
//
// an email alone is not an identity: anyone creating a local admin chooses its username, and two auth modules can vouch
// for the same email. so a policy only gives rights to the users of one auth module (`authModuleRef`, the backoffice auth
// module when missing). every other user with the same email gets its own rights capped by the ones of the policy, which
// never gives it more than it already has
case class UserRightsPolicy(
    location: EntityLocation = EntityLocation(),
    email: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    mode: UserRightsPolicyMode = UserRightsPolicyMode.Replace,
    authModuleRef: Option[String] = None,
    rights: UserRights = UserRights.empty
) extends EntityLocationSupport {
  def internalId: String               = email
  def json: JsValue                    = UserRightsPolicy.format.writes(this)
  def theName: String                  = name
  def theDescription: String           = description
  def theTags: Seq[String]             = tags
  def theMetadata: Map[String, String] = metadata

  // does a user logged in through `authConfigId` get the rights of the policy, rather than its own capped by them. only
  // the users of the auth module of the policy get them: its own auth module, or the backoffice auth module of the
  // moment. without any (no backoffice auth module, or the global config not loaded yet), it gives rights to no one
  def givesRightsTo(authConfigId: String)(using env: Env): Boolean =
    givesRightsTo(authConfigId, env.datastores.globalConfigDataStore.latestSafe.flatMap(_.backOfficeAuthRef))

  def givesRightsTo(authConfigId: String, backOfficeAuthModule: Option[String]): Boolean =
    mode == UserRightsPolicyMode.Replace && BackOfficeUser.isAuthModule(authConfigId) &&
    authModuleRef.orElse(backOfficeAuthModule).contains(authConfigId)

  def applyTo(authConfigId: String, ownRights: UserRights)(using env: Env): UserRights =
    rightsOf(givesRightsTo(authConfigId), ownRights)

  // the rights of a user whose own rights are `ownRights`: the ones of the policy when it gives them to the user, its
  // own capped by them otherwise
  def rightsOf(givesRights: Boolean, ownRights: UserRights): UserRights =
    if (givesRights) rights else ownRights.intersect(rights)
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
      "auth_module_ref" -> o.authModuleRef.map(JsString.apply).getOrElse(JsNull).asValue,
      "rights"      -> o.rights.json
    )
    // a policy that cannot be read is not applied: its mode, its email and its auth module are required to be valid, an
    // unreadable right is dropped (the policy then gives less, never more)
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
        // read as missing, an auth module that is not a string would give the rights of the policy to the users of
        // the backoffice auth module instead of the ones meant
        authModuleRef = (json \ "auth_module_ref").toOption match {
          case None | Some(JsNull)   => None
          case Some(JsString(value)) => Option(value.trim).filter(_.nonEmpty)
          case Some(_)               => throw new IllegalArgumentException("the auth module of the policy is not a string")
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

  // the rights of a user logged in through `authConfigId`, its own rights being `ownRights`
  def effectiveRights(email: String, authConfigId: String, ownRights: UserRights)(using env: Env): UserRights = {
    of(email) match {
      case None         => ownRights
      case Some(policy) => policy.applyTo(authConfigId, ownRights)
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

  def effectiveRightsF(email: String, authConfigId: String, ownRights: UserRights)(using
      env: Env,
      ec: ExecutionContext
  ): Future[UserRights] = {
    ofF(email).map {
      case None         => ownRights
      case Some(policy) => policy.applyTo(authConfigId, ownRights)
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
      description = "The rights of a user of the backoffice auth module",
      mode = UserRightsPolicyMode.Replace,
      rights = UserRights.varargs(
        UserRight(TenantAccess("default:r"), Seq(TeamAccess("default:r")))
      )
    )
  }
}

// who a personal admin apikey belongs to. an email alone is not an identity: the owner is an email given by a source, a
// user of an auth module and a local admin with the same email are two owners, with two apikeys
sealed trait PersonalAdminApikeyOwner {
  def email: String
  // the metadata of the apikey that name its owner
  def metadata: Map[String, String]
}

object PersonalAdminApikeyOwner {

  // a user of an auth module: the apikey has the rights its rights policy gives to the users of this module
  case class AuthModuleUser(email: String, authModuleId: String) extends PersonalAdminApikeyOwner {
    def metadata: Map[String, String] = Map(
      PersonalAdminApikey.ownerMetadataKey      -> email,
      PersonalAdminApikey.authModuleMetadataKey -> authModuleId
    )
  }

  // a local admin, simple or webauthn (two admins of different types can have the same username). the apikey has the
  // rights of the admin, capped by its rights policy. the date of creation of the admin tells it apart from an admin
  // created later with the same username, which does not get the apikey
  case class LocalAdmin(email: String, webauthn: Boolean, createdAt: Long) extends PersonalAdminApikeyOwner {
    def metadata: Map[String, String] = Map(
      PersonalAdminApikey.ownerMetadataKey               -> email,
      PersonalAdminApikey.localAdminMetadataKey          -> (if (webauthn) "webauthn" else "simple"),
      PersonalAdminApikey.localAdminCreatedAtMetadataKey -> createdAt.toString
    )
    def is(admin: OtoroshiAdmin): Boolean =
      admin.username == email && admin.isWebAuthn == webauthn && admin.createdAt.getMillis == createdAt
  }

  def of(admin: OtoroshiAdmin): LocalAdmin = LocalAdmin(admin.username, admin.isWebAuthn, admin.createdAt.getMillis)
}

// the admin apikey of a backoffice user: its rights are not written in it but resolved on each call, they are the ones
// a session of its owner would have at that time:
// - for a user of an auth module, the rights of its rights policy, when the policy gives its rights to the users of this
//   module (replace mode). the rights the auth module gives at login are not stored anywhere, a call made with an
//   apikey has no login: without such a policy, the user has no personal apikey
// - for a local admin, the rights of the admin, capped by its rights policy if it has one
// its owner creates it even when the owner cannot write apikeys
object PersonalAdminApikey {

  import PersonalAdminApikeyOwner.*

  val ownerMetadataKey               = "otoroshi-personal-admin-apikey-owner"
  val authModuleMetadataKey          = "otoroshi-personal-admin-apikey-auth-module"
  val localAdminMetadataKey          = "otoroshi-personal-admin-apikey-local-admin"
  val localAdminCreatedAtMetadataKey = "otoroshi-personal-admin-apikey-local-admin-created-at"
  val metadataKeys: Seq[String]      =
    Seq(ownerMetadataKey, authModuleMetadataKey, localAdminMetadataKey, localAdminCreatedAtMetadataKey)

  val restrictModeError =
    "a personal admin apikey needs a rights policy in replace mode: the rights of a policy in restrict mode depend on the login of the user"

  val otherAuthModuleError =
    "a personal admin apikey needs a rights policy giving its rights to the users of your auth module: yours is for another one"

  val noPolicyError =
    "you have no rights policy: a super admin has to write one for you before you get an admin apikey"

  val noOwnerError = "only the users of an auth module and the local admins have a personal admin apikey"

  // an apikey is a personal one as soon as it names an owner, even one that cannot be read
  def isPersonal(metadata: Map[String, String]): Boolean = metadata.contains(ownerMetadataKey)
  def isPersonal(apikey: ApiKey): Boolean                = isPersonal(apikey.metadata)

  // the metadata naming the owner of an apikey written as json, read one by one: a metadata that is not a string does
  // not hide the others
  def metadataOfJson(json: JsValue): Map[String, String] =
    metadataKeys.flatMap(key => (json \ "metadata" \ key).asOpt[String].map(value => (key, value))).toMap

  // an owner that cannot be read (no source, two sources, an unknown type of admin, an unreadable date) is no one: the
  // apikey has no rights
  def ownerOf(metadata: Map[String, String]): Option[PersonalAdminApikeyOwner] = {
    metadata.get(ownerMetadataKey).filter(_.trim.nonEmpty).flatMap { email =>
      (metadata.get(authModuleMetadataKey), metadata.get(localAdminMetadataKey)) match {
        case (Some(module), None) if BackOfficeUser.isAuthModule(module) =>
          AuthModuleUser(UserRightsPolicy.normalizeEmail(email), module).some
        case (None, Some(typ @ ("simple" | "webauthn")))                  =>
          metadata.get(localAdminCreatedAtMetadataKey).flatMap(_.toLongOption).map { createdAt =>
            LocalAdmin(email, typ == "webauthn", createdAt)
          }
        case _                                                            => None
      }
    }
  }

  // the owner of the personal apikey of a backoffice user, as known by the proxy state: a user of an auth module, a local
  // admin, or no one (the users of an apikey)
  def ownerFor(user: BackOfficeUser)(using env: Env): Option[PersonalAdminApikeyOwner] = {
    if (user.isLocalAdmin) {
      env.proxyState.otoroshiAdmin(user.email).filter(_.isWebAuthn == user.isWebAuthnAdmin).map(PersonalAdminApikeyOwner.of)
    } else if (BackOfficeUser.isAuthModule(user.authConfigId)) {
      AuthModuleUser(UserRightsPolicy.normalizeEmail(user.email), user.authConfigId).some
    } else {
      None
    }
  }

  // the same, read from the datastore: right after its creation, a local admin is not in the proxy state yet
  def ownerForF(user: BackOfficeUser)(using env: Env, ec: ExecutionContext): Future[Option[PersonalAdminApikeyOwner]] = {
    if (user.isLocalAdmin) {
      localAdminF(user.email, user.isWebAuthnAdmin).map(_.map(PersonalAdminApikeyOwner.of))
    } else {
      ownerFor(user).vfuture
    }
  }

  private def localAdminF(username: String, webauthn: Boolean)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Option[OtoroshiAdmin]] = {
    if (webauthn) env.datastores.webAuthnAdminDataStore.findByUsername(username)
    else env.datastores.simpleAdminDataStore.findByUsername(username)
  }

  // the secret of a personal apikey gives the rights of its owner, today's and tomorrow's: only its owner and the super
  // admins can read it. `caller` is the owner the user calling would be
  def canRead(metadata: Map[String, String], caller: Option[PersonalAdminApikeyOwner], user: BackOfficeUser)(using
      env: Env
  ): Boolean =
    user.rights.superAdmin || (ownerOf(metadata).isDefined && ownerOf(metadata) == caller)

  // only its owner (through its dedicated endpoints) and the super admins write one: someone else marking an apikey as
  // the one of a user would get the rights the user gets later
  def canWrite(user: BackOfficeUser)(using env: Env): Boolean = user.rights.superAdmin

  // why an owner cannot have a personal apikey, given the rights policy of its email
  def refusal(owner: PersonalAdminApikeyOwner, policy: Option[UserRightsPolicy])(using env: Env): Option[String] = {
    owner match {
      case _: LocalAdmin                     => None
      case AuthModuleUser(_, authModuleId)   =>
        policy match {
          case None                                                    => noPolicyError.some
          case Some(p) if p.mode == UserRightsPolicyMode.Restrict      => restrictModeError.some
          case Some(p) if !p.givesRightsTo(authModuleId)               => otherAuthModuleError.some
          case Some(_)                                                 => None
        }
    }
  }

  // the rights of an apikey of this owner, read from the datastore: an admin deleted or a policy changed takes effect on
  // the next call
  def rightsF(owner: PersonalAdminApikeyOwner)(using env: Env, ec: ExecutionContext): Future[Either[String, UserRights]] = {
    owner match {
      case local: LocalAdmin             =>
        localAdminF(local.email, local.webauthn).flatMap {
          case Some(admin) if local.is(admin) =>
            UserRightsPolicy
              .effectiveRightsF(admin.username, BackOfficeUser.localAdminsAuthConfigId, admin.rights)
              .map(Right(_))
          case _                              => Left("the local admin owning this apikey does not exist anymore").vfuture
        }
      case AuthModuleUser(email, _)      =>
        UserRightsPolicy.ofF(email).map { policy =>
          refusal(owner, policy) match {
            case Some(error) => Left(error)
            case None        => Right(policy.get.rights)
          }
        }
    }
  }

  // the same, read from the proxy state, for the callers that cannot wait: an admin or a policy changed on another node
  // takes effect after the next sync. before the first sync, the policies and the admins are unknown: the apikey is
  // refused rather than given rights a policy keeps away
  def rights(owner: PersonalAdminApikeyOwner)(using env: Env): Either[String, UserRights] = {
    if (!NgProxyStateLoaderJob.firstSync.get()) {
      Left("otoroshi is starting, retry in a few seconds")
    } else {
      owner match {
        case local: LocalAdmin        =>
          env.proxyState.otoroshiAdmin(local.email).filter(local.is) match {
            case Some(admin) =>
              Right(UserRightsPolicy.effectiveRights(admin.username, BackOfficeUser.localAdminsAuthConfigId, admin.rights))
            case None        => Left("the local admin owning this apikey does not exist anymore")
          }
        case AuthModuleUser(email, _) =>
          val policy = UserRightsPolicy.of(email)
          refusal(owner, policy) match {
            case Some(error) => Left(error)
            case None        => Right(policy.get.rights)
          }
      }
    }
  }

  private def userOf(apikey: ApiKey, owner: PersonalAdminApikeyOwner, rights: UserRights, tenant: TenantId): BackOfficeUser =
    BackOfficeUser(
      randomId = IdGenerator.token,
      name = apikey.clientName,
      email = owner.email,
      profile = Json.obj(),
      authConfigId = BackOfficeUser.personalAdminApikeyAuthConfigId,
      simpleLogin = false,
      tags = Seq.empty,
      metadata = Map.empty,
      rights = rights,
      location = EntityLocation(tenant, teams = Seq(TeamId.all)),
      adminEntityValidators = Map.empty
    )

  // the identity of a call made with a personal apikey
  def userF(apikey: ApiKey, tenant: TenantId)(using env: Env, ec: ExecutionContext): Future[Either[String, BackOfficeUser]] = {
    ownerOf(apikey.metadata) match {
      case None        => Left("the owner of this apikey cannot be read").vfuture
      case Some(owner) => rightsF(owner).map(_.map(rights => userOf(apikey, owner, rights, tenant)))
    }
  }

  def user(apikey: ApiKey, tenant: TenantId)(using env: Env): Either[String, BackOfficeUser] = {
    ownerOf(apikey.metadata) match {
      case None        => Left("the owner of this apikey cannot be read")
      case Some(owner) => rights(owner).map(rights => userOf(apikey, owner, rights, tenant))
    }
  }

  def find(owner: PersonalAdminApikeyOwner)(using env: Env, ec: ExecutionContext): Future[Option[ApiKey]] = {
    env.datastores.apiKeyDataStore
      .findAll()
      .map(_.filter(k => ownerOf(k.metadata).contains(owner)).sortBy(_.clientId).headOption)
  }

  // the location of a new apikey: the one of the local admin, or the one of the rights policy of the user
  def locationF(owner: PersonalAdminApikeyOwner, policy: Option[UserRightsPolicy])(using
      env: Env,
      ec: ExecutionContext
  ): Future[EntityLocation] = {
    owner match {
      case local: LocalAdmin  => localAdminF(local.email, local.webauthn).map(_.map(_.location).getOrElse(EntityLocation()))
      case _: AuthModuleUser  => policy.map(_.location).getOrElse(EntityLocation()).vfuture
    }
  }

  def create(owner: PersonalAdminApikeyOwner, location: EntityLocation)(using
      env: Env,
      ec: ExecutionContext
  ): Future[ApiKey] = {
    val apikey = ApiKey(
      clientId = IdGenerator.lowerCaseToken(16),
      clientSecret = IdGenerator.token(64),
      clientName = s"Personal admin apikey of ${owner.email}",
      description = s"The admin apikey of ${owner.email}, with the rights a session of ${owner.email} has",
      authorizedEntities = Seq(ServiceGroupIdentifier(env.backOfficeGroupId)),
      tags = Seq("personal-admin-apikey"),
      // no rights at all: the apikey only gets the ones of its owner. without these metadata, an admin apikey would have
      // no restriction, which is what an apikey whose owner was removed by hand would become
      metadata = owner.metadata + (ApiKey.accessRightsMetadataKey -> "[]"),
      location = location
    )
    apikey.save().map(_ => apikey)
  }

  // a new secret, the same client id
  def rotate(apikey: ApiKey)(using env: Env, ec: ExecutionContext): Future[ApiKey] = {
    val updated = apikey.copy(clientSecret = IdGenerator.token(64))
    updated.save().map(_ => updated)
  }
}
