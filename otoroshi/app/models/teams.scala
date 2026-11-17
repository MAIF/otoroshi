package otoroshi.models

import otoroshi.actions.{ApiActionContext, BackOfficeActionContext, BackOfficeActionContextAuth}
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class UserRights(rights: Seq[UserRight]) {
  def json: JsValue                                                                   = UserRights.format.writes(this)
  private def rootOrTenantAdmin(tenant: TenantId)(f: => Boolean)(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck || superAdmin || tenantAdmin(tenant)) {
      true
    } else {
      f
    }
  }
  // a right limited to some kinds of entities never makes a super admin or a tenant admin: both skip the checks of the
  // kinds
  def superAdmin(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.value == "*" &&
        ur.tenant.canReadWrite &&
        ur.teams.exists(t => t.wildcard && t.canRead && t.canWrite) &&
        ur.entities.unrestricted
      )
    }
  }
  def tenantAdminStr(tenant: String)(using env: Env): Boolean                      = tenantAdmin(TenantId(tenant))
  def tenantAdmin(tenant: TenantId)(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck || superAdmin) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.matches(tenant) &&
        ur.tenant.canReadWrite &&
        ur.teams.exists(t => t.wildcard && t.canRead && t.canWrite) &&
        ur.entities.unrestricted
      )
    }
  }
  // most rights give access to every kind of entity, the checks of the kinds are skipped for them
  lazy val restrictsEntityKinds: Boolean = rights.exists(!_.entities.unrestricted)
  // the kinds are checked right by right: the tenant, one of the teams and the kind have to be allowed by the same
  // right. an entity without teams (a tenant) only needs its tenant
  def canReadKinds(tenant: TenantId, teams: Seq[TeamId], kinds: Seq[EntityKind])(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck || !restrictsEntityKinds) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.matches(tenant) && ur.tenant.canRead &&
        (teams.isEmpty || teams.exists(t => ur.teams.exists(ta => ta.matches(t) && ta.canRead))) &&
        ur.entities.canRead(kinds)
      )
    }
  }
  def canWriteKinds(tenant: TenantId, teams: Seq[TeamId], kinds: Seq[EntityKind])(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck || !restrictsEntityKinds) {
      true
    } else {
      rights.exists(ur =>
        ur.tenant.matches(tenant) && ur.tenant.canReadWrite &&
        (teams.isEmpty || teams.exists(t => ur.teams.exists(ta => ta.matches(t) && ta.canReadWrite))) &&
        ur.entities.canWrite(kinds)
      )
    }
  }
  def canReadTenant(tenant: TenantId)(using env: Env): Boolean                     =
    rootOrTenantAdmin(tenant) {
      rights.exists(ur => ur.tenant.matches(tenant) && ur.tenant.canRead)
    }
  def canWriteTenant(tenant: TenantId)(using env: Env): Boolean                    =
    rootOrTenantAdmin(tenant) {
      rights.exists(ur => ur.tenant.matches(tenant) && ur.tenant.canReadWrite)
    }
  def canReadTeams(tenant: TenantId, teams: Seq[TeamId])(using env: Env): Boolean  =
    rootOrTenantAdmin(tenant) {
      canReadTenant(tenant) && teams.exists(ut => rights.exists(ur => ur.teams.exists(t => t.matches(ut) && t.canRead)))
    }
  def canWriteTeams(tenant: TenantId, teams: Seq[TeamId])(using env: Env): Boolean =
    rootOrTenantAdmin(tenant) {
      canReadTenant(tenant) && teams.exists(ut =>
        rights.exists(ur => ur.teams.exists(t => t.matches(ut) && t.canReadWrite))
      )
    }
  // does this set of rights allow everything `other` allows ? entities that carry rights for their
  // holder (apikeys, users) must never be written with more than what the writer already has
  def covers(other: UserRights): Boolean = {
    other.rights.forall { requested =>
      rights.exists { mine =>
        mine.tenant.covers(requested.tenant) &&
        requested.teams.forall(requestedTeam => mine.teams.exists(_.covers(requestedTeam))) &&
        mine.entities.covers(requested.entities)
      }
    }
  }
  // what both sets of rights allow, right by right. two patterns of tenants or teams that do not cover one another
  // (team-a-* and team-*-b) have no intersection that can be written: the access is dropped, so the result never
  // allows more than either side
  def intersect(other: UserRights): UserRights = {
    UserRights(
      (for {
        mine   <- rights
        theirs <- other.rights
        tenant <- AccessCoverage
                    .intersectValue(mine.tenant.value, theirs.tenant.value)
                    .map(v => TenantAccess(v, mine.tenant.canRead && theirs.tenant.canRead, mine.tenant.canWrite && theirs.tenant.canWrite))
                    .filter(_.canRead)
      } yield {
        val teams = for {
          myTeam    <- mine.teams
          theirTeam <- theirs.teams
          team      <- AccessCoverage
                         .intersectValue(myTeam.value, theirTeam.value)
                         .map(v => TeamAccess(v, myTeam.canRead && theirTeam.canRead, myTeam.canWrite && theirTeam.canWrite))
                         .filter(_.canRead)
        } yield team
        UserRight(tenant, teams.distinct, mine.entities.intersect(theirs.entities))
      }).distinct
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
      arr.value.toSeq        .map { ur =>
          UserRight.format.reads(ur).asOpt
        }
        .collect { case Some(ur) =>
          ur
        }
    )
  }
  // missing or unreadable rights are no rights, unless the superAdminWhenNoRights flag of the global config is on:
  // the instances that predate the flag read them as super admin rights until it is turned off
  def readFromObject(json: JsValue): UserRights = readFromObject(json, superAdminWhenNoRights)
  def readFromObject(json: JsValue, superAdminWhenNoRights: Boolean): UserRights = {
    val rights: Option[UserRights] = (json \ "rights").asOpt[JsArray].map { arr =>
      readFromArray(arr)
    }
    rights.getOrElse(if (superAdminWhenNoRights) superAdmin else empty)
  }
  // read from the cached global config: off outside a running otoroshi and before the global config is loaded
  private def superAdminWhenNoRights: Boolean = {
    val env = otoroshi.api.OtoroshiEnvHolder.get()
    if (env == null) false
    else env.datastores.globalConfigDataStore.latestSafe.exists(_.superAdminWhenNoRights)
  }
}

case class UserRight(
    tenant: TenantAccess,
    teams: Seq[TeamAccess],
    entities: EntityKindsAccess = EntityKindsAccess.all
) {
  def json: JsValue = UserRight.format.writes(this)
}

object UserRight {
  val default    = UserRight(TenantAccess("default"), Seq(TeamAccess("default")))
  val superAdmin = UserRight(TenantAccess("*"), Seq(TeamAccess("*")))
  val format     = new Format[UserRight] {
    // the kinds are only written for a right limited to some of them: the rights giving access to every kind keep
    // the json they always had
    override def writes(o: UserRight): JsValue             =
      Json
        .obj(
          "tenant" -> o.tenant.raw,
          "teams"  -> JsArray(o.teams.distinct.map(t => JsString(t.raw)))
        )
        .applyOnIf(!o.entities.unrestricted)(_ ++ o.entities.json)
    override def reads(json: JsValue): JsResult[UserRight] =
      Try {
        UserRight(
          tenant = TenantAccess((json \ "tenant").as[String]),
          teams = (json \ "teams")
            .as[JsArray]
            .value.toSeq
            .map { t =>
              TeamAccess(t.as[String])
            }
            .distinct.toSeq,
          entities = EntityKindsAccess.readFrom(json)
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
  def ownEntityLocation(rawCtx: Option[ApiActionContext[?]])(using env: Env): EntityLocation = {
    rawCtx
      .map(ctx => getOwnEntityLocation(ctx.currentTenant, ctx.canUserRead))
      .getOrElse(EntityLocation.default)
  }
  private def getOwnEntityLocation[T <: EntityLocationSupport](currentTenant: TenantId, canUserRead: T => Boolean)(
      using env: Env
  ) = {
    EntityLocation(
      tenant = currentTenant,
      teams = env.proxyState
        .allTeams()
        .filter(item => currentTenant.value == item.location.tenant.value || currentTenant == TenantId.all)
        .filter(item => canUserRead(item.asInstanceOf[T]))
        .map(_.id)
        .slice(0, 1)
    )
  }
  def fromBackOffice(ctx: BackOfficeActionContextAuth[JsValue])(using env: Env): EntityLocation = {
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
          .getOrElse(Seq.empty).toSeq

        val teams = teamsAsStringList
          .map(teams => teams ++ teamsAsJsonArray)

        EntityLocation(
          tenant = json.select("tenant").asOpt[String].map(TenantId.apply).getOrElse(TenantId.default),
          teams = teams match {
            case Some(value) => value
            case None        =>
              if (teamsAsJsonArray.isEmpty)
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
    (json \ keyName).asOpt(using format).getOrElse(EntityLocation())
  }
}

trait EntityLocationSupport extends Entity {
  def location: EntityLocation
}

// the kinds are the ones of the entity the location was read from, when the caller knows them: without them, only a
// right giving access to every kind reaches it
case class FakeEntityLocationSupport(location: EntityLocation, kinds: Seq[EntityKind] = Seq.empty)
    extends EntityLocationSupport {
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

object AccessCoverage {
  // a plain value is covered by itself, by "*", or by a pattern matching it. a pattern is only covered
  // by itself or by "*", so asking for a wildcard never widens the scope of the one who grants it
  def coversValue(mine: String, requested: String): Boolean = {
    if (mine == "*") true
    else if (requested.contains("*")) mine == requested
    else RegexPool(mine).matches(requested)
  }
  def coversPermissions(mine: (Boolean, Boolean), requested: (Boolean, Boolean)): Boolean = {
    (!requested._1 || mine._1) && (!requested._2 || mine._2)
  }
  // the values matched by both: the narrower one when one covers the other, nothing otherwise (even when two patterns
  // overlap, their intersection cannot be written as a pattern)
  def intersectValue(a: String, b: String): Option[String] = {
    if (coversValue(a, b)) Some(b)
    else if (coversValue(b, a)) Some(a)
    else None
  }
}

// a kind of entity, as the generic admin api names it: its group and its plural name
case class EntityKind(group: String, plural: String)

object EntityKind {

  private val classKinds = new scala.collection.concurrent.TrieMap[Class[?], Seq[EntityKind]]()

  def of(resource: otoroshi.api.Resource): Seq[EntityKind] =
    Seq(EntityKind(resource.group.toLowerCase.trim, resource.pluralName.toLowerCase.trim))

  // the kinds of an entity come from the resources of the generic admin api sharing its class (organizations and
  // tenants share one). an entity whose class is not the one of a resource has no kind: only a right giving access to
  // every kind reaches it
  def of(item: EntityLocationSupport)(using env: Env): Seq[EntityKind] = item match {
    case fake: FakeEntityLocationSupport => fake.kinds
    // the typed controllers check the rights of a route on its legacy service descriptor
    case _: ServiceDescriptor            => Seq(EntityKind("proxy.otoroshi.io", "routes"))
    case _                               => ofClass(item.getClass)
  }

  def ofClass(clazz: Class[?])(using env: Env): Seq[EntityKind] = classKinds.getOrElseUpdate(clazz, fromClass(clazz))

  private def fromClass(clazz: Class[?])(using env: Env): Seq[EntityKind] = {
    val resources = env.allResources.resources.filter(r => r.access != null && r.access.clazz != null)
    val exact     = resources.filter(_.access.clazz == clazz)
    // the auth modules are stored under their trait, each kind of module being its own class
    val found     = if (exact.nonEmpty) exact else resources.filter(_.access.clazz.isAssignableFrom(clazz))
    found.flatMap(of).distinct
  }
}

// a pattern of kinds of entities: `*` (every kind), `<plural>` (the kind of this plural name, in any group),
// `<group>/<plural>` or `<group>/*` (every kind of the group). None stands for any group or any plural name
case class EntityKindPattern(group: Option[String], plural: Option[String]) {
  lazy val all: Boolean = group.isEmpty && plural.isEmpty
  lazy val raw: String  = (group, plural) match {
    case (None, None)       => "*"
    case (None, Some(p))    => p
    case (Some(g), None)    => s"$g/*"
    case (Some(g), Some(p)) => s"$g/$p"
  }
  def matches(kind: EntityKind): Boolean            = group.forall(_ == kind.group) && plural.forall(_ == kind.plural)
  def covers(other: EntityKindPattern): Boolean     =
    group.forall(g => other.group.contains(g)) && plural.forall(p => other.plural.contains(p))
  // unlike the patterns of tenants and teams, two patterns of kinds always have an intersection that can be written
  def intersect(other: EntityKindPattern): Option[EntityKindPattern] = {
    def both(a: Option[String], b: Option[String]): Option[Option[String]] = (a, b) match {
      case (None, _)                => Some(b)
      case (_, None)                => Some(a)
      case (Some(x), Some(y)) if x == y => Some(a)
      case _                        => None
    }
    for {
      g <- both(group, other.group)
      p <- both(plural, other.plural)
    } yield EntityKindPattern(g, p)
  }
}

object EntityKindPattern {
  def parse(raw: String): EntityKindPattern = {
    val value = raw.toLowerCase.trim
    val index = value.lastIndexOf('/')
    if (value == "*") {
      EntityKindPattern(None, None)
    } else if (index < 0) {
      EntityKindPattern(None, Some(value))
    } else {
      val group  = value.substring(0, index).trim
      val plural = value.substring(index + 1).trim
      EntityKindPattern(
        if (group == "*" || group.isEmpty) None else Some(group),
        if (plural == "*" || plural.isEmpty) None else Some(plural)
      )
    }
  }
  // the patterns covered by another one of the list add nothing to it
  def simplify(patterns: Seq[EntityKindPattern]): Seq[EntityKindPattern] = {
    val distinct = patterns.distinct
    distinct.filterNot(p => distinct.exists(o => o != p && o.covers(p)))
  }
}

// the kinds of entities a right gives access to, on top of its tenant and its teams. the kinds a right can write are
// readable too. a right without kinds gives access to every kind, like it always did
case class EntityKindsAccess(read: Seq[String], write: Seq[String]) {
  private lazy val writePatterns: Seq[EntityKindPattern] = EntityKindPattern.simplify(write.map(EntityKindPattern.parse))
  private lazy val readPatterns: Seq[EntityKindPattern]  =
    EntityKindPattern.simplify((read ++ write).map(EntityKindPattern.parse))
  lazy val unrestricted: Boolean                         = writePatterns.exists(_.all)
  def canRead(kinds: Seq[EntityKind]): Boolean           =
    unrestricted || readPatterns.exists(p => p.all || kinds.exists(p.matches))
  def canWrite(kinds: Seq[EntityKind]): Boolean          =
    unrestricted || writePatterns.exists(p => p.all || kinds.exists(p.matches))
  def covers(other: EntityKindsAccess): Boolean          = {
    other.writePatterns.forall(r => writePatterns.exists(_.covers(r))) &&
    other.readPatterns.forall(r => readPatterns.exists(_.covers(r)))
  }
  def intersect(other: EntityKindsAccess): EntityKindsAccess = {
    def cross(a: Seq[EntityKindPattern], b: Seq[EntityKindPattern]): Seq[EntityKindPattern] =
      EntityKindPattern.simplify(for {
        x <- a
        y <- b
        z <- x.intersect(y)
      } yield z)
    val writable = cross(writePatterns, other.writePatterns)
    if (writable.exists(_.all)) {
      EntityKindsAccess.all
    } else {
      // what is writable is readable, no need to repeat it in the readable kinds
      val readable = cross(readPatterns, other.readPatterns).filterNot(p => writable.exists(_.covers(p)))
      EntityKindsAccess(readable.map(_.raw), writable.map(_.raw))
    }
  }
  def json: JsObject = Json.obj(
    "authorized_entities_read"  -> JsArray(read.map(JsString.apply)),
    "authorized_entities_write" -> JsArray(write.map(JsString.apply))
  )
}

object EntityKindsAccess {
  val all: EntityKindsAccess = EntityKindsAccess(Seq("*"), Seq("*"))
  // without any of the two lists, every kind. with one of them only, the other one is empty: a list of readable kinds
  // alone must not let the right write everything. a list that cannot be read fails the whole right, and an unreadable
  // right is dropped: it never falls back to every kind
  def readFrom(json: JsValue): EntityKindsAccess = {
    def list(name: String): Option[Seq[String]] = (json \ name).toOption match {
      case None | Some(JsNull)  => None
      case Some(JsString(one))  => Some(Seq(one))
      case Some(arr: JsArray)   => Some(arr.value.toSeq.map(_.as[String]))
      case Some(other)          => throw new IllegalArgumentException(s"$name must be an array of entity kinds")
    }
    (list("authorized_entities_read"), list("authorized_entities_write")) match {
      case (None, None)          => all
      case (readOpt, writeOpt)   => EntityKindsAccess(readOpt.getOrElse(Seq.empty), writeOpt.getOrElse(Seq.empty))
    }
  }
}

case class TeamAccess(value: String, canRead: Boolean, canWrite: Boolean) {
  def covers(other: TeamAccess): Boolean = {
    AccessCoverage.coversValue(value, other.value) &&
    AccessCoverage.coversPermissions((canRead, canWrite), (other.canRead, other.canWrite))
  }
  // an access without any right is written name:not, name: would be read back with the rights found in the letters
  // of the name
  lazy val raw: String = {
    if (canRead) s"$value:r${if (canWrite) "w" else ""}" else s"$value:not"
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
      // the -1 keeps the empty permissions of name:, which used to be written for an access without any right
      val parts    = raw.toLowerCase.split(":", -1)
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
      // the -1 keeps the empty permissions of name:, which used to be written for an access without any right
      val parts    = raw.toLowerCase.split(":", -1)
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
  def covers(other: TenantAccess): Boolean = {
    AccessCoverage.coversValue(value, other.value) &&
    AccessCoverage.coversPermissions((canRead, canWrite), (other.canRead, other.canWrite))
  }
  def matches(tenant: TenantId): Boolean = {
    value == "*" || tenant == TenantId.all || RegexPool(value).matches(tenant.value)
  }
  lazy val asTenantId: TenantId      = TenantId(value)
  lazy val plain: Boolean            = !containsWildcard
  lazy val containsWildcard: Boolean = value.contains("*")
  lazy val wildcard: Boolean         = value == "*"
  lazy val canReadWrite: Boolean     = canRead && canWrite
  // an access without any right is written name:not, name: would be read back with the rights found in the letters
  // of the name
  lazy val raw: String = {
    if (canRead) s"$value:r${if (canWrite) "w" else ""}" else s"$value:not"
  }
}

sealed trait RightsChecker {
  def canPerform(user: BackOfficeUser, currentTenant: TenantId)(using env: Env): Boolean
}

object RightsChecker {
  case object Anyone          extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(using env: Env): Boolean = true
  }
  case object SuperAdminOnly  extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(using env: Env): Boolean = user.rights.superAdmin
  }
  case object TenantAdminOnly extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(using env: Env): Boolean =
      user.rights.tenantAdmin(currentTenant)
  }
  // can write one of these kinds of entities in the current tenant: a read only user cannot, nor a user limited to other
  // kinds
  case class WriterOf(kinds: Seq[EntityKind]) extends RightsChecker {
    def canPerform(user: BackOfficeUser, currentTenant: TenantId)(using env: Env): Boolean =
      user.rights.tenantAdmin(currentTenant) || user.rights.rights.exists(ur =>
        ur.tenant.matches(currentTenant) && ur.tenant.canReadWrite && ur.teams.exists(_.canReadWrite) &&
        ur.entities.canWrite(kinds)
      )
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
        "tags"        -> JsArray(o.tags.map(JsString.apply)),
        // not read back, the location of a tenant is its id: the generic admin api checks the rights on this field
        "_loc"        -> o.location.json
      )
    override def reads(json: JsValue): JsResult[Tenant] =
      Try {
        Tenant(
          id = TenantId((json \ "id").as[String]),
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq
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
  def save()(using ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.tenantDataStore.set(this)
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
        "tags"        -> JsArray(o.tags.map(JsString.apply)),
        // not read back, the location of a team is its tenant and itself: the generic admin api checks the rights on
        // this field
        "_loc"        -> o.location.json
      )
    override def reads(json: JsValue): JsResult[Team] =
      Try {
        Team(
          id = TeamId((json \ "id").as[String]),
          tenant = TenantId((json \ "tenant").as[String]),
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq
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
  def save()(using ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.teamDataStore.set(this)

  def theDescription: String           = description
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags
}
