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

  def checkUserRights(request: RequestHeader, user: BackOfficeUser)(f: Future[Result])(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[Result] = {

    import kaleidoscope._
    import play.api.mvc.Results._

    val isRead = request.method == HttpMethods.GET.name() || request.method == HttpMethods.HEAD.name() || request.method == HttpMethods.OPTIONS.name()
    val isWrite = !isRead
    val userIsAdmin = TenantAndTeamHelper.isSuperAdmin(user)
    request match {
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/") && req.path.endsWith("/_bulk") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/apps-sessions") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/admin-sessions") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/admins/simple") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/admins/webauthn") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/pki/") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/globalconfig") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/otoroshi.json") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/import") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/snowmonkey") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if req.path.startsWith("/bo/api/proxy/api/cluster") && !userIsAdmin => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if !userIsAdmin && req.path == "/bo/api/proxy/api/audit/events" => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if !userIsAdmin && req.path == "/bo/api/proxy/api/alert/events" => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if !userIsAdmin && req.path == "/bo/api/proxy/api/stats/global" => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if !userIsAdmin && req.path == "/bo/api/proxy/api/stats"        => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if !userIsAdmin && req.path == "/bo/api/proxy/api/events"       => Unauthorized(Json.obj("error" -> "unauthorized")).future
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/apikeys") && !userIsAdmin => {
        // DELETE  /bo/api/proxy/api/apikeys/:id
        // PUT     /bo/api/proxy/api/apikeys/:id
        // PATCH   /bo/api/proxy/api/apikeys/:id
        // POST    /bo/api/proxy/api/apikeys
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/groups") && !userIsAdmin => {
        // GET     /bo/api/proxy/api/groups/:groupId/apikeys/:clientId/quotas
        // DELETE  /bo/api/proxy/api/groups/:groupId/apikeys/:clientId/quotas
        // PUT     /bo/api/proxy/api/groups/:groupId/apikeys/:clientId
        // PATCH   /bo/api/proxy/api/groups/:groupId/apikeys/:clientId
        // DELETE  /bo/api/proxy/api/groups/:groupId/apikeys/:clientId
        // POST    /bo/api/proxy/api/groups/:groupId/apikeys
        // DELETE  /bo/api/proxy/api/groups/:id
        // PUT     /bo/api/proxy/api/groups/:id
        // PATCH   /bo/api/proxy/api/groups/:id
        // POST    /bo/api/proxy/api/groups
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/services") && !userIsAdmin => {
        // GET     /bo/api/proxy/api/services/:serviceId/apikeys/:clientId/quotas
        // DELETE  /bo/api/proxy/api/services/:serviceId/apikeys/:clientId/quotas
        //
        // PUT     /bo/api/proxy/api/services/:serviceId/apikeys/:clientId/group
        // PUT     /bo/api/proxy/api/services/:serviceId/apikeys/:clientId
        // PATCH   /bo/api/proxy/api/services/:serviceId/apikeys/:clientId
        // DELETE  /bo/api/proxy/api/services/:serviceId/apikeys/:clientId
        // POST    /bo/api/proxy/api/services/:serviceId/apikeys
        //
        // GET     /bo/api/proxy/api/services/:serviceId/template
        // PUT     /bo/api/proxy/api/services/:serviceId/template
        // POST    /bo/api/proxy/api/services/:serviceId/template
        // DELETE  /bo/api/proxy/api/services/:serviceId/template
        // GET     /bo/api/proxy/api/services/:serviceId/targets
        // POST    /bo/api/proxy/api/services/:serviceId/targets
        // DELETE  /bo/api/proxy/api/services/:serviceId/targets
        // PATCH   /bo/api/proxy/api/services/:serviceId/targets
        // GET     /bo/api/proxy/api/services/:serviceId/live
        // GET     /bo/api/proxy/api/services/:serviceId/stats
        // GET     /bo/api/proxy/api/services/:serviceId/events
        // GET     /bo/api/proxy/api/services/:serviceId/health
        // GET     /bo/api/proxy/api/services/:serviceId/canary
        // DELETE  /bo/api/proxy/api/services/:serviceId/canary
        // GET     /bo/api/proxy/api/services/:id
        // DELETE  /bo/api/proxy/api/services/:id
        // PUT     /bo/api/proxy/api/services/:id
        // PATCH   /bo/api/proxy/api/services/:id
        // GET     /bo/api/proxy/api/services
        // POST    /bo/api/proxy/api/services
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/certificates") && !userIsAdmin => {
        req.path match {
          case r"/bo/api/proxy/api/certificates/$id@([^/]+)" =>
        }
        //POST    /bo/api/proxy/api/certificates/:certId/_renew
        //DELETE  /bo/api/proxy/api/certificates/:id
        //PUT     /bo/api/proxy/api/certificates/:id
        //PATCH   /bo/api/proxy/api/certificates/:id
        //POST    /bo/api/proxy/api/certificates
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/verifiers") && !userIsAdmin => {
        // DELETE  /bo/api/proxy/api/verifiers/:id
        // PUT     /bo/api/proxy/api/verifiers/:id
        // PATCH   /bo/api/proxy/api/verifiers/:id
        // POST    /bo/api/proxy/api/verifiers
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/auths") && !userIsAdmin => {
        // DELETE  /bo/api/proxy/api/auths/:id
        // PUT     /bo/api/proxy/api/auths/:id
        // PATCH   /bo/api/proxy/api/auths/:id
        // POST    /bo/api/proxy/api/auths
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/scripts") && !userIsAdmin => {
        // DELETE  /bo/api/proxy/api/scripts/:id
        // PUT     /bo/api/proxy/api/scripts/:id
        // PATCH   /bo/api/proxy/api/scripts/:id
        // POST    /bo/api/proxy/api/scripts
        ???
      }
      case req if isWrite && req.path.startsWith("/bo/api/proxy/api/tcp/services") && !userIsAdmin => {
        // DELETE  /bo/api/proxy/api/tcp/services/:id
        // PUT     /bo/api/proxy/api/tcp/services/:id
        // PATCH   /bo/api/proxy/api/tcp/services/:id
        // POST    /bo/api/proxy/api/tcp/services
        ???
      }
      case _ => f.flatMap { res =>
        if (TenantAndTeamHelper.isSuperAdmin(user)) {
          res.future
        } else {

          val ctype = res.header.headers.getOrElse("Content-Type", "application/json")

          def sendResult(result: ByteString, status: Option[Int] = None): Result = {
            Status(status.getOrElse(res.header.status))
              .apply(result)
              .withHeaders(
                res.header.headers
                  .toSeq
                  .filter(_._1 != "Content-Type")
                  .filter(_._1 != "Content-Length")
                  .filter(_._1 != "Transfer-Encoding"): _*
              )
              .as(ctype)
          }

          res.body.dataStream.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
            val bodyStr = bodyRaw.utf8String
            Try(Json.parse(bodyStr)) match {
              case Failure(e) => sendResult(bodyRaw)
              case Success(_) if isWrite => {
                sendResult(bodyRaw)
              }
              case Success(jsonBody) if isRead => {
                request match {
                  case req if req.path.endsWith("_template") => sendResult(bodyRaw)
                  case req if req.path.startsWith("/bo/api/proxy/api/new/") => sendResult(bodyRaw)
                  case req if userIsAdmin && req.path == "/bo/api/proxy/api/audit/events"  => sendResult(bodyRaw)
                  case req if userIsAdmin && req.path == "/bo/api/proxy/api/alert/events"  => sendResult(bodyRaw)
                  case req if userIsAdmin && req.path == "/bo/api/proxy/api/stats/global"  => sendResult(bodyRaw)
                  case req if userIsAdmin && req.path == "/bo/api/proxy/api/stats"         => sendResult(bodyRaw)
                  case req if userIsAdmin && req.path == "/bo/api/proxy/api/events"        => sendResult(bodyRaw)
                  case req if req.path == "/bo/api/proxy/api/lines"           => sendResult(bodyRaw)
                  case req if req.path.startsWith("/bo/api/proxy/api/live")   => sendResult(bodyRaw)
                  case req if req.path == "/bo/api/proxy/api/cluster/members" => sendResult(bodyRaw)
                  case req if req.path == "/bo/api/proxy/api/cluster/live"    => sendResult(bodyRaw)
                  case _ => {
                    jsonBody match {
                      case obj@JsObject(_) => {
                        val tenantOpt = (obj \ "tenant").asOpt[String]
                        val teamsOpt = (obj \ "teams").asOpt[Seq[String]]
                        if (TenantAndTeamHelper.canRead(user, tenantOpt, teamsOpt)) {
                          sendResult(bodyRaw)
                        } else {
                          sendResult(Json.obj("error" -> "unauthorized").stringify.byteString, 401.some)
                        }
                      }
                      case arr@JsArray(_) => sendResult(JsArray(
                        arr.value.map {
                          case obj@JsObject(_) => {
                            val tenantOpt = (obj \ "tenant").asOpt[String]
                            val teamsOpt = (obj \ "teams").asOpt[Seq[String]]
                            if (TenantAndTeamHelper.canRead(user, tenantOpt, teamsOpt)) {
                              obj.some
                            } else {
                              None
                            }
                          }
                          case item => item.some
                        }.collect {
                          case Some(i) => i
                        }
                      ).stringify.byteString)
                      case _ => sendResult(bodyRaw)
                    }
                  }
                }
              }
              case _ => sendResult(bodyRaw)
            }
          }
        }
      }
    }
  }
}