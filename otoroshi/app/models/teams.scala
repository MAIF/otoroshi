package otoroshi.models

import akka.http.scaladsl.model.HttpMethods
import akka.stream.Materializer
import akka.util.ByteString
import env.Env
import models.BackOfficeUser
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
case class TeamId(value: String) {
  def canBeWrittenBy(user: BackOfficeUser): Boolean = {
    user.teams.exists(v => v.canWrite && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim))
  }
  def canBeReadBy(user: BackOfficeUser): Boolean = {
    user.teams.exists(v => v.canRead && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim))
  }
}

case class TeamAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
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

case class TenantId(value: String) {
  def canBeWrittenBy(user: BackOfficeUser): Boolean = {
    user.tenants.exists(v => v.canWrite && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim))
  }
  def canBeReadBy(user: BackOfficeUser): Boolean = {
    user.tenants.exists(v => v.canRead && (v.value.toLowerCase.trim == "*" || v.value.toLowerCase.trim == value.toLowerCase.trim))
  }
}

case class TenantAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
}

sealed trait RightsChecker {
  def canPerform(user: BackOfficeUser, currentTenant: Option[String]): Boolean
}

object RightsChecker {
  case object Anyone extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: Option[String]): Boolean = true
  }
  case object SuperAdminOnly extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: Option[String]): Boolean = TenantAndTeamHelper.isSuperAdmin(user)
  }
  case object TenantAdmin extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: Option[String]): Boolean = {
      if (SuperAdminOnly.canPerform(user, currentTenant)) {
        true
      } else {
        currentTenant match {
          case None => false
          case Some(tenant) => TenantAndTeamHelper.canReadTenant(user, tenant) && TenantAndTeamHelper.canWriteTenant(user, tenant)
        }
      }
    }
  }
}

object TenantAndTeamHelper {
  def canReadAtAll(user: BackOfficeUser): Boolean = {
    user.teams.exists(_.canRead) && user.tenants.exists(_.canRead)
  }
  def canWriteAtAll(user: BackOfficeUser): Boolean = {
    user.teams.exists(_.canWrite) && user.tenants.exists(_.canWrite)
  }
  def isAllTenantAdmin(user: BackOfficeUser): Boolean = {
    user.tenants.find(_.value.trim == "*").exists(v => v.canRead && v.canWrite)
  }
  def isAllTeamAdmin(user: BackOfficeUser): Boolean = {
    user.teams.find(_.value.trim == "*").exists(v => v.canRead && v.canWrite)
  }
  def isSuperAdmin(user: BackOfficeUser): Boolean = {
    isAllTenantAdmin(user) && isAllTeamAdmin(user)
  }
  def canReadTenant(user: BackOfficeUser, tenant: String): Boolean = {
    TenantId(tenant).canBeReadBy(user)
  }
  def canWriteTenant(user: BackOfficeUser, tenant: String): Boolean = {
    TenantId(tenant).canBeWrittenBy(user)
  }
  def canReadTeam(user: BackOfficeUser, team: String): Boolean = {
    TeamId(team).canBeReadBy(user)
  }
  def canWriteTeam(user: BackOfficeUser, team: String): Boolean = {
    TeamId(team).canBeWrittenBy(user)
  }
  def canRead(user: BackOfficeUser, _tenant: Option[String], _teams: Option[Seq[String]]): Boolean = {
    val teams = _teams.getOrElse(Seq.empty[String]).map(v => TeamId(v))
    val tenant = _tenant.map(v => TenantId(v))
    if (tenant.isEmpty) {
      teams.exists(_.canBeReadBy(user))
    } else {
      tenant.exists(_.canBeReadBy(user)) && teams.exists(_.canBeReadBy(user))
    }
  }

  def canWrite(user: BackOfficeUser, _tenant: Option[String], _teams: Option[Seq[String]]): Boolean = {
    val teams = _teams.getOrElse(Seq.empty[String]).map(v => TeamId(v))
    val tenant = _tenant.map(v => TenantId(v))
    if (tenant.isEmpty) {
      teams.exists(_.canBeWrittenBy(user))
    } else {
      tenant.exists(_.canBeWrittenBy(user)) && teams.exists(_.canBeWrittenBy(user))
    }
  }
}