package otoroshi.models

import otoroshi.actions.{ApiActionContext, BackOfficeActionContext, BackOfficeActionContextAuth}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class UserRights(rights: Seq[UserRight]) {
  def json: JsValue                                                                   = UserRights.format.writes(this)
  private def rootOrTenantAdmin(tenant: TenantId)(f: => Boolean)(implicit env: Env): Boolean = {
    if (env.bypassUserRightsCheck || superAdmin || tenantAdmin(tenant)) {
      true
    } else {
      f
    }
  }
  def superAdmin(implicit env: Env): Boolean = {
    if (env.bypassUserRightsCheck) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.value == "*" &&
        ur.tenant.canReadWrite &&
        ur.teams.exists(t => t.wildcard && t.canRead && t.canWrite)
      )
    }
  }
  def tenantAdminStr(tenant: String)(implicit env: Env): Boolean                      = tenantAdmin(TenantId(tenant))
  def tenantAdmin(tenant: TenantId)(implicit env: Env): Boolean = {
    if (env.bypassUserRightsCheck || superAdmin) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.matches(tenant) &&
        ur.tenant.canReadWrite &&
        ur.teams.exists(t => t.wildcard && t.canRead && t.canWrite)
      )
    }
  }
  def canReadTenant(tenant: TenantId)(implicit env: Env): Boolean                     =
    rootOrTenantAdmin(tenant) {
      rights.exists(ur => ur.tenant.matches(tenant) && ur.tenant.canRead)
    }
  def canWriteTenant(tenant: TenantId)(implicit env: Env): Boolean                    =
    rootOrTenantAdmin(tenant) {
      rights.exists(ur => ur.tenant.matches(tenant) && ur.tenant.canReadWrite)
    }
  def canReadTeams(tenant: TenantId, teams: Seq[TeamId])(implicit env: Env): Boolean  =
    rootOrTenantAdmin(tenant) {
      canReadTenant(tenant) && teams.exists(ut => rights.exists(ur => ur.teams.exists(t => t.matches(ut) && t.canRead)))
    }
  def canWriteTeams(tenant: TenantId, teams: Seq[TeamId])(implicit env: Env): Boolean =
    rootOrTenantAdmin(tenant) {
      canReadTenant(tenant) && teams.exists(ut =>
        rights.exists(ur => ur.teams.exists(t => t.matches(ut) && t.canReadWrite))
      )
    }
  def oneAuthorizedTenant: TenantId                                                   =
    rights.headOption.filter(_.tenant.plain).map(_.tenant.asTenantId).getOrElse(TenantId.default)
  def oneAuthorizedTeam: TeamId                                                       =
    rights.headOption.flatMap(_.teams.headOption).filter(_.plain).map(_.asTeamId).getOrElse(TeamId.default)
}

object UserRights {
  val empty      = UserRights.apply(Seq.empty)
  val default    = UserRights.varargs(UserRight.default)
  val superAdmin = UserRights.varargs(UserRight.superAdmin)
  val format     = new Format[UserRights] {
    override def writes(o: UserRights): JsValue             = JsArray(o.rights.map(_.json))
    override def reads(json: JsValue): JsResult[UserRights] =
      Try {
        json.asArray.applyOn(readFromArray)
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }
  }

  def varargs(rights: UserRight*): UserRights = new UserRights(rights)

  def readFromArray(arr: JsArray): UserRights = {
    UserRights(
      arr.value
        .map { ur =>
          UserRight.format.reads(ur).asOpt
        }
        .collect { case Some(ur) =>
          ur
        }
    )
  }
  def readFromObject(json: JsValue): UserRights = {
    val rights: Option[UserRights] = (json \ "rights").asOpt[JsArray].map { arr =>
      readFromArray(arr)
    }
    rights.getOrElse(superAdmin)
  }
}

case class UserRight(tenant: TenantAccess, teams: Seq[TeamAccess]) {
  def json: JsValue = UserRight.format.writes(this)
}

object UserRight {
  val default    = UserRight(TenantAccess("default"), Seq(TeamAccess("default")))
  val superAdmin = UserRight(TenantAccess("*"), Seq(TeamAccess("*")))
  val format     = new Format[UserRight] {
    override def writes(o: UserRight): JsValue             =
      Json.obj(
        "tenant" -> o.tenant.raw,
        "teams"  -> JsArray(o.teams.distinct.map(t => JsString(t.raw)))
      )
    override def reads(json: JsValue): JsResult[UserRight] =
      Try {
        UserRight(
          tenant = TenantAccess((json \ "tenant").as[String]),
          teams = (json \ "teams")
            .as[JsArray]
            .value
            .map { t =>
              TeamAccess(t.as[String])
            }
            .distinct
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ur) => JsSuccess(ur)
      }
  }
}

case class EntityLocation(tenant: TenantId = TenantId.default, teams: Seq[TeamId] = Seq(TeamId.default)) {
  def json: JsValue         = EntityLocation.format.writes(this)
  def jsonWithKey: JsObject = Json.obj(EntityLocation.keyName -> EntityLocation.format.writes(this))
}

object EntityLocation {
  val default = EntityLocation()
  def ownEntityLocation(rawCtx: Option[ApiActionContext[_]])(implicit env: Env): EntityLocation = {
    rawCtx.map(ctx =>
      getOwnEntityLocation(ctx.currentTenant, ctx.canUserRead))
      .getOrElse(EntityLocation.default)
  }
  private def getOwnEntityLocation[T <: EntityLocationSupport](currentTenant: TenantId, canUserRead: T => Boolean)
                                                              (implicit env: Env) = {
    EntityLocation(
        tenant = currentTenant,
        teams = env.proxyState.allTeams()
          .filter(item => currentTenant.value == item.location.tenant.value || currentTenant == TenantId.all)
          .filter(item => canUserRead(item.asInstanceOf[T]))
          .map(_.id)
          .slice(0, 1)
      )
  }
  def fromBackOffice(ctx: BackOfficeActionContextAuth[JsValue])(implicit env: Env): EntityLocation = {
    getOwnEntityLocation(ctx.currentTenant, ctx.canUserRead)
  }
  val keyName = "_loc"
  val format  = new Format[EntityLocation] {
    override def writes(o: EntityLocation): JsValue             =
      Json.obj(
        "tenant" -> o.tenant.value,
        "teams"  -> JsArray(o.teams.distinct.map(t => JsString(t.value)))
      )
    override def reads(json: JsValue): JsResult[EntityLocation] =
      Try {
        val teamsAsStringList: Option[Seq[TeamId]] = json
          .select("teams")
          .asOpt[Seq[String]]
          .map(s => s.map(TeamId.apply).distinct)

        val teamsAsJsonArray = json
          .select("teams")
          .asOpt[JsArray]
          .map {
            _.value
              .map(e => Team.format.reads(e))
              .collect { case JsSuccess(team, _) => team.id }
          }
          .getOrElse(Seq.empty)

        val teams = teamsAsStringList
          .map(teams => teams ++ teamsAsJsonArray)

        EntityLocation(
          tenant = json.select("tenant").asOpt[String].map(TenantId.apply).getOrElse(TenantId.default),
          teams = teams match {
            case Some(value) => value
            case None => if (teamsAsJsonArray.isEmpty)
              Seq(TeamId.default)
            else
              teamsAsJsonArray
          }
        )
      } match {
        case Failure(e)   => JsError(e.getMessage)
        case Success(loc) => JsSuccess(loc)
      }
  }
  def readFromKey(json: JsValue): EntityLocation = {
    (json \ keyName).asOpt(format).getOrElse(EntityLocation())
  }
}

trait EntityLocationSupport extends Entity {
  def location: EntityLocation
}

case class FakeEntityLocationSupport(location: EntityLocation) extends EntityLocationSupport {
  override def internalId: String               = ""
  override def json: JsValue                    = Json.obj("_loc" -> location.json)
  override def theName: String                  = ""
  override def theDescription: String           = ""
  override def theTags: Seq[String]             = Seq.empty
  override def theMetadata: Map[String, String] = Map.empty
}

case class TeamId(rawValue: String) {
  lazy val value: String = rawValue.toLowerCase.trim
}
object TeamId                       {
  val default: TeamId              = TeamId("default")
  val all: TeamId                  = TeamId("*")
  def apply(value: String): TeamId = new TeamId(value.toLowerCase.trim)
}

case class TenantId(rawValue: String) {
  lazy val value: String = rawValue.toLowerCase.trim
}
object TenantId                       {
  val default: TenantId              = TenantId("default")
  val all: TenantId                  = TenantId("*")
  def apply(value: String): TenantId = new TenantId(value.toLowerCase.trim)
}

case class TeamAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val raw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
  lazy val asTeamId: TeamId          = TeamId(value)
  lazy val plain: Boolean            = !containsWildcard
  lazy val containsWildcard: Boolean = value.contains("*")
  lazy val wildcard: Boolean         = value == "*"
  lazy val canReadWrite: Boolean     = canRead && canWrite
  def matches(team: TeamId): Boolean = {
    value == "*" || team == TeamId.all || RegexPool(value).matches(team.value)
  }
}

object TeamAccess {
  def apply(value: String, canRead: Boolean, canWrite: Boolean): TeamAccess =
    new TeamAccess(value.toLowerCase.trim, canRead, canWrite)
  def apply(_raw: String): TeamAccess = {
    val raw = _raw.toLowerCase.trim
    if (raw.contains(":")) {
      val parts    = raw.toLowerCase.split(":")
      val not      = parts.last == "not"
      val canRead  = if (not) false else parts.last.contains("r")
      val canWrite = if (not) false else parts.last.contains("w")
      TeamAccess(parts.head.toLowerCase.trim, canRead, canRead && canWrite)
    } else {
      TeamAccess(raw.toLowerCase.trim, true, true)
    }
  }
}

object TenantAccess {
  def apply(_raw: String): TenantAccess = {
    val raw = _raw.toLowerCase.trim
    if (raw.contains(":")) {
      val parts    = raw.toLowerCase.split(":")
      val not      = parts.last == "not"
      val canRead  = if (not) false else parts.last.contains("r")
      val canWrite = if (not) false else parts.last.contains("w")
      TenantAccess(parts.head.toLowerCase.trim, canRead, canRead && canWrite)
    } else {
      TenantAccess(raw.toLowerCase.trim, true, true)
    }
  }
}

case class TenantAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  def matches(tenant: TenantId): Boolean = {
    value == "*" || tenant == TenantId.all || RegexPool(value).matches(tenant.value)
  }
  lazy val asTenantId: TenantId      = TenantId(value)
  lazy val plain: Boolean            = !containsWildcard
  lazy val containsWildcard: Boolean = value.contains("*")
  lazy val wildcard: Boolean         = value == "*"
  lazy val canReadWrite: Boolean     = canRead && canWrite
  lazy val raw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
}

sealed trait RightsChecker {
  def canPerform(user: BackOfficeUser, currentTenant: TenantId)(implicit env: Env): Boolean
}

object RightsChecker {
  case object Anyone          extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(implicit env: Env): Boolean = true
  }
  case object SuperAdminOnly  extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(implicit env: Env): Boolean = user.rights.superAdmin
  }
  case object TenantAdminOnly extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(implicit env: Env): Boolean =
      user.rights.tenantAdmin(currentTenant)
  }
}

object Tenant {
  val format                            = new Format[Tenant] {
    override def writes(o: Tenant): JsValue             =
      Json.obj(
        "id"          -> o.id.value,
        "name"        -> o.name,
        "description" -> o.description,
        "metadata"    -> o.metadata,
        "tags"        -> JsArray(o.tags.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[Tenant] =
      Try {
        Tenant(
          id = TenantId((json \ "id").as[String]),
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }
  }
  def fromJsons(value: JsValue): Tenant =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
}
case class Tenant(
    id: TenantId,
    name: String,
    description: String,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String]
) extends EntityLocationSupport {
  override def internalId: String                                      = id.value
  override def json: JsValue                                           = Tenant.format.writes(this)
  override def location: EntityLocation                                = EntityLocation(id, Seq.empty)
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.tenantDataStore.set(this)
  def theDescription: String                                           = description
  def theMetadata: Map[String, String]                                 = metadata
  def theName: String                                                  = name
  def theTags: Seq[String]                                             = tags
}
object Team   {
  val format                          = new Format[Team] {
    override def writes(o: Team): JsValue             =
      Json.obj(
        "id"          -> o.id.value,
        "tenant"      -> o.tenant.value,
        "name"        -> o.name,
        "description" -> o.description,
        "metadata"    -> o.metadata,
        "tags"        -> JsArray(o.tags.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[Team] =
      Try {
        Team(
          id = TeamId((json \ "id").as[String]),
          tenant = TenantId((json \ "tenant").as[String]),
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }
  }
  def fromJsons(value: JsValue): Team =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
}
case class Team(
    id: TeamId,
    tenant: TenantId,
    name: String,
    description: String,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String]
) extends EntityLocationSupport {
  override def internalId: String                                      = id.value
  override def json: JsValue                                           = Team.format.writes(this)
  override def location: EntityLocation                                = EntityLocation(tenant, Seq(id))
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.teamDataStore.set(this)

  def theDescription: String           = description
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags
}
