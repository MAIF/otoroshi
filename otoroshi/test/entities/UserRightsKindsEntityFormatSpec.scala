package entities

import otoroshi.models.*
import play.api.libs.json.*

// the rights limited to some kinds of entities, and the rights policies of the backoffice users
class UserRightsKindsEntityFormatSpec extends EntityFormatSpec {

  val backends     = EntityKind("proxy.otoroshi.io", "backends")
  val routes       = EntityKind("proxy.otoroshi.io", "routes")
  val certificates = EntityKind("pki.otoroshi.io", "certificates")

  def right(json: JsValue): UserRight = readOk(UserRight.format, json)

  "EntityKindPattern" should {

    "parse every way to write a kind" in {
      EntityKindPattern.parse("*") mustBe EntityKindPattern(None, None)
      EntityKindPattern.parse("Backends") mustBe EntityKindPattern(None, Some("backends"))
      EntityKindPattern.parse("proxy.otoroshi.io/backends") mustBe EntityKindPattern(
        Some("proxy.otoroshi.io"),
        Some("backends")
      )
      EntityKindPattern.parse("pki.otoroshi.io/*") mustBe EntityKindPattern(Some("pki.otoroshi.io"), None)
      EntityKindPattern.parse("*/backends") mustBe EntityKindPattern(None, Some("backends"))
    }

    "write back what it parsed" in {
      Seq("*", "backends", "proxy.otoroshi.io/backends", "pki.otoroshi.io/*").foreach { raw =>
        EntityKindPattern.parse(raw).raw mustBe raw
      }
    }

    "match the kinds it names" in {
      EntityKindPattern.parse("backends").matches(backends) mustBe true
      EntityKindPattern.parse("backends").matches(routes) mustBe false
      EntityKindPattern.parse("proxy.otoroshi.io/*").matches(routes) mustBe true
      EntityKindPattern.parse("proxy.otoroshi.io/*").matches(certificates) mustBe false
      EntityKindPattern.parse("pki.otoroshi.io/backends").matches(backends) mustBe false
    }

    "intersect exactly" in {
      def inter(a: String, b: String) = EntityKindPattern.parse(a).intersect(EntityKindPattern.parse(b)).map(_.raw)
      inter("*", "backends") mustBe Some("backends")
      inter("proxy.otoroshi.io/*", "backends") mustBe Some("proxy.otoroshi.io/backends")
      inter("backends", "routes") mustBe None
      inter("pki.otoroshi.io/*", "proxy.otoroshi.io/backends") mustBe None
    }
  }

  "UserRight" should {

    "give access to every kind without kinds, and keep the json it always had" in {
      val r = right(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw")))
      r.entities.unrestricted mustBe true
      UserRight.format.writes(r) mustBe Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw"))
    }

    "read and write its kinds" in {
      val json = Json.obj(
        "tenant"                    -> "default:rw",
        "teams"                     -> Json.arr("ops:rw"),
        "authorized_entities_read"  -> Json.arr("*"),
        "authorized_entities_write" -> Json.arr("backends", "certificates")
      )
      val r    = right(json)
      r.entities.unrestricted mustBe false
      UserRight.format.writes(r) mustBe json
    }

    "read everything and write only the kinds of its write list" in {
      val r = right(
        Json.obj(
          "tenant"                    -> "default:rw",
          "teams"                     -> Json.arr("ops:rw"),
          "authorized_entities_read"  -> Json.arr("*"),
          "authorized_entities_write" -> Json.arr("backends", "pki.otoroshi.io/certificates")
        )
      )
      r.entities.canRead(Seq(routes)) mustBe true
      r.entities.canWrite(Seq(routes)) mustBe false
      r.entities.canWrite(Seq(backends)) mustBe true
      r.entities.canWrite(Seq(certificates)) mustBe true
      // an entity whose kind is unknown is only reached by a right on every kind
      r.entities.canRead(Seq.empty) mustBe true
      r.entities.canWrite(Seq.empty) mustBe false
    }

    "read what it can write" in {
      val r = right(
        Json.obj(
          "tenant"                    -> "default:rw",
          "teams"                     -> Json.arr("ops:rw"),
          "authorized_entities_read"  -> Json.arr("routes"),
          "authorized_entities_write" -> Json.arr("backends")
        )
      )
      r.entities.canRead(Seq(backends)) mustBe true
      r.entities.canRead(Seq(routes)) mustBe true
      r.entities.canRead(Seq(certificates)) mustBe false
    }

    "not write anything with a list of readable kinds alone" in {
      val r = right(
        Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("ops:rw"), "authorized_entities_read" -> Json.arr("*"))
      )
      r.entities.unrestricted mustBe false
      r.entities.canRead(Seq(routes)) mustBe true
      r.entities.canWrite(Seq(routes)) mustBe false
    }

    "not read a list of kinds that is not an array of strings" in {
      readError(
        UserRight.format,
        Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("ops:rw"), "authorized_entities_write" -> 42)
      )
      readError(
        UserRight.format,
        Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("ops:rw"), "authorized_entities_write" -> Json.arr(42))
      )
    }

    "drop the rights that cannot be read rather than give every kind" in {
      UserRights.readFromArray(
        Json.arr(Json.obj("tenant" -> "*:rw", "teams" -> Json.arr("*:rw"), "authorized_entities_write" -> 42))
      ) mustBe UserRights.empty
    }
  }

  "UserRights" should {

    val all          = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("*:rw")))))
    val opsBackends  = UserRights(
      Seq(
        UserRight(
          TenantAccess("default:rw"),
          Seq(TeamAccess("ops:rw")),
          EntityKindsAccess(Seq("*"), Seq("backends", "certificates"))
        )
      )
    )
    val opsReadsOnly = UserRights(
      Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("ops:r")), EntityKindsAccess(Seq("*"), Seq.empty)))
    )

    "cover rights on fewer kinds only" in {
      all.covers(opsBackends) mustBe true
      opsBackends.covers(all) mustBe false
      opsBackends.covers(opsReadsOnly) mustBe true
      opsReadsOnly.covers(opsBackends) mustBe false
      val opsRoutes = UserRights(
        Seq(
          UserRight(TenantAccess("default:rw"), Seq(TeamAccess("ops:rw")), EntityKindsAccess(Seq("*"), Seq("routes")))
        )
      )
      opsBackends.covers(opsRoutes) mustBe false
    }

    "intersect to the rights allowed by both" in {
      all.intersect(opsBackends) mustBe opsBackends
      opsBackends.intersect(all) mustBe opsBackends
      opsBackends.intersect(opsReadsOnly) mustBe UserRights(
        Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("ops:r")), EntityKindsAccess(Seq("*"), Seq.empty)))
      )
    }

    "drop what only one side allows" in {
      val other = UserRights(Seq(UserRight(TenantAccess("other:rw"), Seq(TeamAccess("*:rw")))))
      opsBackends.intersect(other) mustBe UserRights.empty
      val devTeam = UserRights(Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("dev:rw")))))
      opsBackends.intersect(devTeam) mustBe UserRights(
        Seq(
          UserRight(TenantAccess("default:rw"), Seq.empty, EntityKindsAccess(Seq("*"), Seq("backends", "certificates")))
        )
      )
    }

    "keep the narrower pattern and drop the patterns it cannot write" in {
      val teamA  = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("team-a-*:rw")))))
      val teamB  = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("team-*-b:rw")))))
      val teamAB = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("team-a-b:rw")))))
      teamA.intersect(teamAB) mustBe teamAB
      teamA.intersect(teamB) mustBe UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq.empty)))
    }

    "never allow more than either side" in {
      val sides = Seq(all, opsBackends, opsReadsOnly)
      for {
        a <- sides
        b <- sides
      } {
        val both = a.intersect(b)
        withClue(s"${a.json} x ${b.json} = ${both.json}: ") {
          a.covers(both) mustBe true
          b.covers(both) mustBe true
        }
      }
    }
  }

  "UserRightsPolicy" should {

    val policy = UserRightsPolicy(
      location = customLocation,
      email = "jane.doe@oto.tools",
      name = "Jane",
      description = "Jane rights",
      tags = Seq("ops"),
      metadata = Map("team" -> "ops"),
      mode = UserRightsPolicyMode.Restrict,
      rights = UserRights(
        Seq(
          UserRight(TenantAccess("default:rw"), Seq(TeamAccess("ops:rw")), EntityKindsAccess(Seq("*"), Seq("backends")))
        )
      )
    )

    "round trip" in {
      roundTrip(UserRightsPolicy.format, policy)
    }

    "lower case the email and replace by default" in {
      val read = readOk(UserRightsPolicy.format, Json.obj("email" -> " Jane.Doe@Oto.Tools "))
      read.email mustBe "jane.doe@oto.tools"
      read.mode mustBe UserRightsPolicyMode.Replace
      read.rights mustBe UserRights.empty
    }

    "not read a policy without email or with an unknown mode" in {
      readError(UserRightsPolicy.format, Json.obj("name" -> "p"))
      readError(UserRightsPolicy.format, Json.obj("email" -> " "))
      readError(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "mode" -> "union"))
    }

    "replace or restrict the rights of the auth module" in {
      val module = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("*:rw")))))
      policy.copy(mode = UserRightsPolicyMode.Replace).applyTo(module) mustBe policy.rights
      policy.applyTo(module) mustBe policy.rights
      val narrowModule = UserRights(Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("ops:r")))))
      policy.applyTo(narrowModule) mustBe UserRights(
        Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("ops:r")), EntityKindsAccess(Seq("*"), Seq("backends"))))
      )
    }

    "pick the policy of an email whatever its case" in {
      val other = policy.copy(email = "john@oto.tools")
      UserRightsPolicy.pick(Seq(policy, other), "JANE.DOE@oto.tools") mustBe Some(policy)
      UserRightsPolicy.pick(Seq(policy, other), "nobody@oto.tools") mustBe None
    }
  }
}
