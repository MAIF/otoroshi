package otoroshi.models

import models.BackOfficeUser
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import utils.RegexPool

import scala.util.{Failure, Success, Try}

case class UserRight(tenant: TenantAccess, teams: Seq[TeamAccess]) {
  def json: JsValue = UserRight.format.writes(this)
}

object UserRight {
  val superAdmin = UserRight(TenantAccess("*"), Seq(TeamAccess("*")))
  val superAdminSeq = Seq(superAdmin)
  val format = new Format[UserRight] {
    override def writes(o: UserRight): JsValue = Json.obj(
      "tenant" -> o.tenant.toRaw,
      "teams" -> JsArray(o.teams.map(t => JsString(t.toRaw)))
    )
    override def reads(json: JsValue): JsResult[UserRight] = Try {
      UserRight(
        tenant = TenantAccess((json \ "tenant").as[String]),
        teams = (json \ "teams").as[JsArray].value.map { t =>
          TeamAccess(t.as[String])
        }
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(ur) => JsSuccess(ur)
    }
  }
  def readFromArray(arr: JsArray): Seq[UserRight] = {
    arr.value.map { ur =>
      UserRight.format.reads(ur).asOpt
    }.collect {
      case Some(ur) => ur
    }
  }
  def readFromObject(json: JsValue): Seq[UserRight] = {
    val defaultValue = Seq(UserRight(TenantAccess("*"), Seq(TeamAccess("*"))))
    val rights: Option[Seq[UserRight]] = (json \ "rights").asOpt[JsArray].map { arr =>
      readFromArray(arr)
    }
    rights.getOrElse(defaultValue)
  }
}

case class EntityLocation(tenant: TenantId = TenantId.default, teams: Seq[TeamId] = Seq(TeamId.default)) {
  def json: JsValue = EntityLocation.format.writes(this)
  def jsonWithKey: JsObject = Json.obj(EntityLocation.keyName -> EntityLocation.format.writes(this))
}

object EntityLocation {
  val keyName = "_loc"
  val format = new Format[EntityLocation] {
    override def writes(o: EntityLocation): JsValue = Json.obj(
      "tenant" -> o.tenant.value,
      "teams" -> JsArray(o.teams.map(t => JsString(t.value))),
    )
    override def reads(json: JsValue): JsResult[EntityLocation] = Try {
      EntityLocation(
        tenant = json.select("tenant").asOpt[String].map(TenantId.apply).getOrElse(TenantId.default),
        teams = json.select("teams").asOpt[Seq[String]].map(s => s.map(TeamId.apply)).getOrElse(Seq(TeamId.default)),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
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

object TeamId {
  val default: TeamId = TeamId("default")
}

object TenantId {
  val default: TenantId = TenantId("default")
}

case class TeamId(value: String) {
  def canBeWrittenBy(user: BackOfficeUser, tenant: TenantId): Boolean = {
    user.rights.find(_.tenant.matches(tenant)).exists(_.teams.exists(v => v.canWrite && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim)))
  }
  def canBeReadBy(user: BackOfficeUser, tenant: TenantId): Boolean = {
    user.rights.find(_.tenant.matches(tenant)).exists(_.teams.exists(v => v.canRead && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim)))
  }
}

case class TenantId(value: String) {
  def canBeWrittenBy(user: BackOfficeUser): Boolean = {
    user.rights.find(_.tenant.matches(this)).exists(v => v.tenant.canWrite && (v.tenant.value.toLowerCase.trim == "*" || v.tenant.value.toLowerCase.trim == value.toLowerCase.trim))
  }
  def canBeReadBy(user: BackOfficeUser): Boolean = {
    user.rights.find(_.tenant.matches(this)).exists(v => v.tenant.canRead && (v.tenant.value.toLowerCase.trim == "*" || v.tenant.value.toLowerCase.trim == value.toLowerCase.trim))
  }
}

case class TeamAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
  def matches(team: TeamId): Boolean = {
    value == "*" || RegexPool(value).matches(team.value)
  }
}

object TeamAccess {
  def apply(raw: String): TeamAccess = {
    if (raw.contains(":")) {
      val parts = raw.toLowerCase.split(":")
      val canRead = parts.last.contains("r")
      val canWrite = parts.last.contains("w")
      TeamAccess(parts.head, canRead, canRead && canWrite)
    } else {
      TeamAccess(raw, true, true)
    }
  }
}

object TenantAccess {
  def apply(raw: String): TenantAccess = {
    if (raw.contains(":")) {
      val parts = raw.toLowerCase.split(":")
      val canRead = parts.last.contains("r")
      val canWrite = parts.last.contains("w")
      TenantAccess(parts.head, canRead, canRead && canWrite)
    } else {
      TenantAccess(raw, true, true)
    }
  }
}

case class TenantAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  def matches(tenant: TenantId): Boolean = {
    value == "*" || RegexPool(value).matches(tenant.value)
  }
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
}

sealed trait RightsChecker {
  def canPerform(user: BackOfficeUser, currentTenant: TenantId): Boolean
}

object RightsChecker {
  case object Anyone extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId): Boolean = true
  }
  case object SuperAdminOnly extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId): Boolean = TenantAndTeamHelper.isSuperAdmin(user)
  }
  case object TenantAdmin extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId): Boolean = {
      if (SuperAdminOnly.canPerform(user, currentTenant)) {
        true
      } else {
        TenantAndTeamHelper.isTenantAdmin(user, currentTenant)
      }
    }
  }
}

object TenantAndTeamHelper {
  def canReadTeams(user: BackOfficeUser, teams: Seq[String], tenantId: TenantId): Boolean = {
    teams.map(TeamId.apply).exists(_.canBeReadBy(user, tenantId))
  }
  def canReadTeamsId(user: BackOfficeUser, teams: Seq[TeamId], tenantId: TenantId): Boolean = {
    teams.exists(_.canBeReadBy(user, tenantId))
  }
  def canWriteTeamsId(user: BackOfficeUser, teams: Seq[TeamId], tenantId: TenantId): Boolean = {
    teams.exists(_.canBeWrittenBy(user, tenantId))
  }
  def isSuperAdmin(user: BackOfficeUser): Boolean = {
    user.rights.exists(ur => ur.tenant.value == "*" && ur.teams.exists(_.value == "*"))
  }
  def isTenantAdmin(user: BackOfficeUser, tenant: TenantId): Boolean = {
    if (isSuperAdmin(user)) {
      true
    } else {
      tenant.canBeReadBy(user) && tenant.canBeWrittenBy(user) && user.rights.exists(ur => ur.tenant.matches(tenant) && ur.teams.exists(_.value == "*"))
    }
  }
  def canReadTenant(user: BackOfficeUser, tenant: String): Boolean = {
    TenantId(tenant).canBeReadBy(user)
  }
  def canWriteTenant(user: BackOfficeUser, tenant: String): Boolean = {
    TenantId(tenant).canBeWrittenBy(user)
  }
  def canReadTeam(user: BackOfficeUser, team: String, tenant: TenantId): Boolean = {
    TeamId(team).canBeReadBy(user, tenant)
  }
  def canWriteTeam(user: BackOfficeUser, team: String, tenant: TenantId): Boolean = {
    TeamId(team).canBeWrittenBy(user, tenant)
  }
  def canRead(user: BackOfficeUser, tenant: TenantId, _teams: Option[Seq[String]]): Boolean = {
    val teams = _teams.getOrElse(Seq.empty[String]).map(v => TeamId(v))
    tenant.canBeReadBy(user) && teams.exists(_.canBeReadBy(user, tenant))
  }

  def canWrite(user: BackOfficeUser, tenant: TenantId, _teams: Option[Seq[String]]): Boolean = {
    val teams = _teams.getOrElse(Seq.empty[String]).map(v => TeamId(v))
    tenant.canBeWrittenBy(user) && teams.exists(_.canBeWrittenBy(user, tenant))
  }
}