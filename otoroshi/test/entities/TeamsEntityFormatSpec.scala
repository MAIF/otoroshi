package entities

import otoroshi.models.*
import play.api.libs.json.*

class TeamsEntityFormatSpec extends EntityFormatSpec {

  "TenantId and TeamId" should {

    "lower case and trim their value" in {
      TenantId(" Tenant-1 ").value mustBe "tenant-1"
      TeamId(" Team-1 ").value mustBe "team-1"
    }

    "be equal whatever the case they are built with" in {
      TenantId("TENANT") mustBe TenantId("tenant")
      TeamId("TEAM") mustBe TeamId("team")
    }

    "name the default and the wildcard ones" in {
      TenantId.default.value mustBe "default"
      TenantId.all.value mustBe "*"
      TeamId.default.value mustBe "default"
      TeamId.all.value mustBe "*"
    }
  }

  "EntityLocation" should {

    val format = EntityLocation.format

    "be in the default tenant and the default team by default" in {
      EntityLocation() mustBe EntityLocation(TenantId.default, Seq(TeamId.default))
    }

    "write the default location" in {
      format.writes(EntityLocation()) mustBe defaultLocationJson
    }

    "write a custom location" in {
      format.writes(customLocation) mustBe customLocationJson
    }

    "be written under the _loc key" in {
      EntityLocation.keyName mustBe "_loc"
      customLocation.jsonWithKey mustBe Json.obj("_loc" -> customLocationJson)
    }

    "expose through json what the format writes" in {
      customLocation.json mustBe format.writes(customLocation)
    }

    "round trip the default location" in {
      roundTrip(format, EntityLocation())
    }

    "round trip a custom location" in {
      roundTrip(format, customLocation)
    }

    "round trip the wildcard tenant and team" in {
      roundTrip(format, EntityLocation(TenantId.all, Seq(TeamId.all)))
    }

    "round trip a location without team" in {
      roundTrip(format, EntityLocation(TenantId("tenant-1"), Seq.empty)).teams mustBe empty
    }

    "keep the order of the teams" in {
      roundTrip(format, EntityLocation(TenantId.default, Seq(TeamId("b"), TeamId("a")))).teams mustBe Seq(
        TeamId("b"),
        TeamId("a")
      )
    }

    "write duplicated teams once" in {
      format.writes(EntityLocation(TenantId.default, Seq(TeamId("a"), TeamId("a")))) mustBe Json.obj(
        "tenant" -> "default",
        "teams"  -> Json.arr("a")
      )
    }

    "write the tenant and teams lower cased" in {
      format.writes(EntityLocation(TenantId("Tenant-1"), Seq(TeamId("Team-1")))) mustBe Json.obj(
        "tenant" -> "tenant-1",
        "teams"  -> Json.arr("team-1")
      )
    }

    "read an empty object as the default location" in {
      readOk(format, Json.obj()) mustBe EntityLocation()
    }

    "read a json null as the default location" in {
      readOk(format, JsNull) mustBe EntityLocation()
    }

    "read a json string as the default location" in {
      readOk(format, JsString("tenant-1")) mustBe EntityLocation()
    }

    "read a tenant alone in the default team" in {
      readOk(format, Json.obj("tenant" -> "tenant-1")) mustBe EntityLocation(TenantId("tenant-1"), Seq(TeamId.default))
    }

    "read teams alone in the default tenant" in {
      readOk(format, Json.obj("teams" -> Json.arr("team-1"))) mustBe EntityLocation(TenantId.default, Seq(TeamId("team-1")))
    }

    "read an empty teams array as no team, not as the default team" in {
      readOk(format, Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr())).teams mustBe empty
    }

    "read the tenant and teams lower cased" in {
      readOk(format, Json.obj("tenant" -> "Tenant-1", "teams" -> Json.arr("Team-1"))) mustBe EntityLocation(
        TenantId("tenant-1"),
        Seq(TeamId("team-1"))
      )
    }

    "read duplicated teams once" in {
      readOk(format, Json.obj("teams" -> Json.arr("a", "A", "a"))).teams mustBe Seq(TeamId("a"))
    }

    "read teams given as team objects" in {
      val team = Team(TeamId("team-1"), TenantId("tenant-1"), "Team 1", "", Seq.empty, Map.empty)
      readOk(format, Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr(team.json))).teams mustBe Seq(TeamId("team-1"))
    }

    "keep only the team objects of a mixed teams array" in {
      val team = Team(TeamId("team-1"), TenantId("tenant-1"), "Team 1", "", Seq.empty, Map.empty)
      readOk(format, Json.obj("teams" -> Json.arr("team-2", team.json))).teams mustBe Seq(TeamId("team-1"))
    }

    "read teams that are neither strings nor teams as the default team" in {
      readOk(format, Json.obj("teams" -> Json.arr(1, true))).teams mustBe Seq(TeamId.default)
    }

    "ignore a tenant that is not a string" in {
      readOk(format, Json.obj("tenant" -> 42)).tenant mustBe TenantId.default
    }

    "ignore teams given as a single string" in {
      readOk(format, Json.obj("teams" -> "team-1")).teams mustBe Seq(TeamId.default)
    }

    "read the location of an entity json from its _loc key" in {
      EntityLocation.readFromKey(Json.obj("id" -> "x", "_loc" -> customLocationJson)) mustBe customLocation
    }

    "read the default location of an entity json without _loc key" in {
      EntityLocation.readFromKey(Json.obj("id" -> "x")) mustBe EntityLocation()
    }

    "read the default location of an entity json whose _loc is not an object" in {
      EntityLocation.readFromKey(Json.obj("_loc" -> "tenant-1")) mustBe EntityLocation()
    }
  }

  "Tenant" should {

    val format = Tenant.format

    val full = Tenant(
      id = TenantId("tenant-1"),
      name = "Tenant 1",
      description = "the first tenant",
      tags = Seq("prod", "eu"),
      metadata = Map("owner" -> "ops", "cost-center" -> "42")
    )

    "write a tenant with its location" in {
      format.writes(full) mustBe Json.obj(
        "id"          -> "tenant-1",
        "name"        -> "Tenant 1",
        "description" -> "the first tenant",
        "metadata"    -> Json.obj("owner" -> "ops", "cost-center" -> "42"),
        "tags"        -> Json.arr("prod", "eu"),
        "_loc"        -> Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr())
      )
    }

    "be located in itself, with no team" in {
      full.location mustBe EntityLocation(TenantId("tenant-1"), Seq.empty)
    }

    "round trip a tenant where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a tenant without tags nor metadata" in {
      roundTrip(format, Tenant(TenantId("t"), "T", "", Seq.empty, Map.empty))
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", description = "multi\nline"))
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "read a lone id, named after itself" in {
      readOk(format, Json.obj("id" -> "tenant-1")) mustBe Tenant(TenantId("tenant-1"), "tenant-1", "", Seq.empty, Map.empty)
    }

    "read the id lower cased" in {
      readOk(format, Json.obj("id" -> "Tenant-1")).id mustBe TenantId("tenant-1")
    }

    "keep the case of the name taken from the id" in {
      readOk(format, Json.obj("id" -> "Tenant-1")).name mustBe "Tenant-1"
    }

    "not read a tenant without id" in {
      readError(format, Json.obj("name" -> "Tenant 1"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read an id that is not a string" in {
      readError(format, Json.obj("id" -> 1))
    }

    "ignore metadata holding a non string value" in {
      readOk(format, Json.obj("id" -> "t", "metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, Json.obj("id" -> "t", "tags" -> "prod")).tags mustBe empty
    }

    "not read back the written location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> customLocationJson)).location mustBe full.location
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "tenant-1", "Tenant 1", "the first tenant", Seq("prod", "eu"), Map("owner" -> "ops", "cost-center" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      Tenant.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy Tenant.fromJsons(Json.obj("name" -> "T"))
    }
  }

  "Team" should {

    val format = Team.format

    val full = Team(
      id = TeamId("team-1"),
      tenant = TenantId("tenant-1"),
      name = "Team 1",
      description = "the first team",
      tags = Seq("dev"),
      metadata = Map("slack" -> "#team-1")
    )

    "write a team with its location" in {
      format.writes(full) mustBe Json.obj(
        "id"          -> "team-1",
        "tenant"      -> "tenant-1",
        "name"        -> "Team 1",
        "description" -> "the first team",
        "metadata"    -> Json.obj("slack" -> "#team-1"),
        "tags"        -> Json.arr("dev"),
        "_loc"        -> Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr("team-1"))
      )
    }

    "be located in its tenant and itself" in {
      full.location mustBe EntityLocation(TenantId("tenant-1"), Seq(TeamId("team-1")))
    }

    "round trip a team where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a team without tags nor metadata" in {
      roundTrip(format, Team(TeamId("t"), TenantId.default, "T", "", Seq.empty, Map.empty))
    }

    "read a lone id and tenant, named after itself" in {
      readOk(format, Json.obj("id" -> "team-1", "tenant" -> "tenant-1")) mustBe Team(
        TeamId("team-1"),
        TenantId("tenant-1"),
        "team-1",
        "",
        Seq.empty,
        Map.empty
      )
    }

    "read the id and the tenant lower cased" in {
      val back = readOk(format, Json.obj("id" -> "Team-1", "tenant" -> "Tenant-1"))
      back.id mustBe TeamId("team-1")
      back.tenant mustBe TenantId("tenant-1")
    }

    "not read a team without tenant" in {
      readError(format, Json.obj("id" -> "team-1"))
    }

    "not read a team without id" in {
      readError(format, Json.obj("tenant" -> "tenant-1"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read back the written location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> customLocationJson)).location mustBe full.location
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "team-1", "Team 1", "the first team", Seq("dev"), Map("slack" -> "#team-1"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      Team.fromJsons(full.json) mustBe full
    }
  }

  "TeamAccess and TenantAccess" should {

    "give read and write rights to a plain name" in {
      TeamAccess("team-1") mustBe TeamAccess("team-1", true, true)
      TenantAccess("tenant-1") mustBe TenantAccess("tenant-1", true, true)
    }

    "give read rights only with :r" in {
      TeamAccess("team-1:r") mustBe TeamAccess("team-1", true, false)
      TenantAccess("tenant-1:r") mustBe TenantAccess("tenant-1", true, false)
    }

    "give read and write rights with :rw" in {
      TeamAccess("team-1:rw") mustBe TeamAccess("team-1", true, true)
    }

    "give no write right without the read right" in {
      TeamAccess("team-1:w") mustBe TeamAccess("team-1", false, false)
    }

    "give no right with :not" in {
      TeamAccess("team-1:not") mustBe TeamAccess("team-1", false, false)
      TenantAccess("tenant-1:not") mustBe TenantAccess("tenant-1", false, false)
    }

    "lower case the name" in {
      TeamAccess("Team-1:R") mustBe TeamAccess("team-1", true, false)
    }

    "write its raw value" in {
      TeamAccess("team-1", true, true).raw mustBe "team-1:rw"
      TeamAccess("team-1", true, false).raw mustBe "team-1:r"
      TeamAccess("team-1", false, false).raw mustBe "team-1:not"
    }

    "write a team or tenant access without any right as not" in {
      TeamAccess("team-1", false, true).raw mustBe "team-1:not"
      TenantAccess("tenant-1", false, false).raw mustBe "tenant-1:not"
    }

    "read back its raw value" in {
      TeamAccess(TeamAccess("team-1", true, true).raw) mustBe TeamAccess("team-1", true, true)
      TeamAccess(TeamAccess("team-1", true, false).raw) mustBe TeamAccess("team-1", true, false)
    }

    "read back the raw value of a team without right" in {
      TeamAccess(TeamAccess("team-1", false, false).raw) mustBe TeamAccess("team-1", false, false)
    }

    "read back the raw value of a team without right whose name holds an r or a w" in {
      TeamAccess(TeamAccess("rw-team", false, false).raw) mustBe TeamAccess("rw-team", false, false)
      TeamAccess(TeamAccess("writers", false, false).raw) mustBe TeamAccess("writers", false, false)
    }

    "read back the raw value of a tenant without right whose name holds an r or a w" in {
      TenantAccess(TenantAccess("prod", false, false).raw) mustBe TenantAccess("prod", false, false)
      TenantAccess(TenantAccess("rw-tenant", false, false).raw) mustBe TenantAccess("rw-tenant", false, false)
    }

    "read the empty permissions written by older versions as no right" in {
      TeamAccess("rw-team:") mustBe TeamAccess("rw-team", false, false)
      TenantAccess("prod:") mustBe TenantAccess("prod", false, false)
    }

    "keep the read and write rights of the other raw values" in {
      TeamAccess(TeamAccess("rw-team", true, false).raw) mustBe TeamAccess("rw-team", true, false)
      TenantAccess(TenantAccess("rw-tenant", true, true).raw) mustBe TenantAccess("rw-tenant", true, true)
    }
  }

  "UserRights" should {

    val format = UserRights.format

    val custom = UserRights.varargs(
      UserRight(TenantAccess("tenant-1"), Seq(TeamAccess("team-1"), TeamAccess("team-2:r"))),
      UserRight(TenantAccess("tenant-2:r"), Seq(TeamAccess("*:r")))
    )

    "write the default rights" in {
      format.writes(UserRights.default) mustBe Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw")))
    }

    "write the super admin rights" in {
      format.writes(UserRights.superAdmin) mustBe Json.arr(Json.obj("tenant" -> "*:rw", "teams" -> Json.arr("*:rw")))
    }

    "write no rights as an empty array" in {
      format.writes(UserRights.empty) mustBe Json.arr()
    }

    "round trip the default rights" in {
      roundTrip(format, UserRights.default)
    }

    "round trip the super admin rights" in {
      roundTrip(format, UserRights.superAdmin)
    }

    "round trip no rights" in {
      roundTrip(format, UserRights.empty)
    }

    "round trip several tenants with read only teams" in {
      roundTrip(format, custom)
    }

    "round trip a team and a tenant without any right whose names hold an r or a w" in {
      val rights = UserRights.varargs(
        UserRight(TenantAccess("prod:not"), Seq(TeamAccess("writers:not"), TeamAccess("rw-team:r")))
      )
      roundTrip(format, rights).rights.head mustBe UserRight(
        TenantAccess("prod", false, false),
        Seq(TeamAccess("writers", false, false), TeamAccess("rw-team", true, false))
      )
    }

    "write duplicated teams once" in {
      val rights = UserRights.varargs(UserRight(TenantAccess("t"), Seq(TeamAccess("a"), TeamAccess("a"))))
      format.writes(rights) mustBe Json.arr(Json.obj("tenant" -> "t:rw", "teams" -> Json.arr("a:rw")))
    }

    "read rights without permission suffix as read and write" in {
      readOk(format, Json.arr(Json.obj("tenant" -> "t", "teams" -> Json.arr("a")))) mustBe UserRights.varargs(
        UserRight(TenantAccess("t", true, true), Seq(TeamAccess("a", true, true)))
      )
    }

    "skip the rights that cannot be read" in {
      readOk(format, Json.arr(Json.obj("tenant" -> "t"), Json.obj("tenant" -> "u", "teams" -> Json.arr("a")))) mustBe
        UserRights.varargs(UserRight(TenantAccess("u"), Seq(TeamAccess("a"))))
    }

    "not read an object" in {
      readError(format, Json.obj("tenant" -> "t", "teams" -> Json.arr("a")))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read the rights of an entity from its rights key" in {
      UserRights.readFromObject(Json.obj("rights" -> format.writes(custom))) mustBe custom
    }

    "read the rights of an entity without rights key as no right" in {
      UserRights.readFromObject(Json.obj("id" -> "x")) mustBe UserRights.empty
    }

    "read the rights of an entity without rights key as super admin rights when superAdminWhenNoRights is on" in {
      UserRights.readFromObject(Json.obj("id" -> "x"), superAdminWhenNoRights = true) mustBe UserRights.superAdmin
      UserRights.readFromObject(Json.obj("rights" -> JsNull), superAdminWhenNoRights = true) mustBe UserRights.superAdmin
      UserRights.readFromObject(Json.obj("rights" -> "default:rw"), superAdminWhenNoRights = true) mustBe UserRights.superAdmin
    }

    "read the rights of an entity without rights key as no right when superAdminWhenNoRights is off" in {
      UserRights.readFromObject(Json.obj("id" -> "x"), superAdminWhenNoRights = false) mustBe UserRights.empty
      UserRights.readFromObject(Json.obj("rights" -> JsNull), superAdminWhenNoRights = false) mustBe UserRights.empty
    }

    "read the rights an entity carries whatever superAdminWhenNoRights" in {
      val json = Json.obj("rights" -> format.writes(custom))
      UserRights.readFromObject(json, superAdminWhenNoRights = true) mustBe custom
      UserRights.readFromObject(json, superAdminWhenNoRights = false) mustBe custom
    }

    "read no right outside a running otoroshi, where superAdminWhenNoRights is off" in {
      UserRights.readFromObject(Json.obj("id" -> "x")) mustBe UserRights.empty
    }

    "expose through json what the format writes" in {
      custom.json mustBe format.writes(custom)
    }
  }
}
