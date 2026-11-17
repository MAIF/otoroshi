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
      authModuleRef = Some("keycloak"),
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
      read.authModuleRef mustBe None
      read.rights mustBe UserRights.empty
    }

    "read its auth module, the backoffice one when missing, null or blank" in {
      readOk(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "auth_module_ref" -> " ldap ")).authModuleRef mustBe Some("ldap")
      readOk(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "auth_module_ref" -> JsNull)).authModuleRef mustBe None
      readOk(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "auth_module_ref" -> " ")).authModuleRef mustBe None
      (policy.copy(authModuleRef = None).json \ "auth_module_ref").get mustBe JsNull
    }

    "not read an auth module that is not a string" in {
      // read as missing, it would give the rights of the policy to the users of the backoffice auth module
      readError(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "auth_module_ref" -> 42))
      readError(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "auth_module_ref" -> Json.arr("ldap")))
    }

    "not read a policy without email or with an unknown mode" in {
      readError(UserRightsPolicy.format, Json.obj("name" -> "p"))
      readError(UserRightsPolicy.format, Json.obj("email" -> " "))
      readError(UserRightsPolicy.format, Json.obj("email" -> "a@b.c", "mode" -> "union"))
    }

    "replace or restrict the rights of the auth module" in {
      val module = UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("*:rw")))))
      policy.rightsOf(givesRights = true, module) mustBe policy.rights
      policy.rightsOf(givesRights = false, module) mustBe policy.rights
      val narrowModule = UserRights(Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("ops:r")))))
      val capped       = UserRights(
        Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("ops:r")), EntityKindsAccess(Seq("*"), Seq("backends"))))
      )
      policy.rightsOf(givesRights = false, narrowModule) mustBe capped
      // the policy never gives more than the user has, unless it gives its rights to the user
      policy.rightsOf(givesRights = false, UserRights.empty) mustBe UserRights.empty
      policy.rightsOf(givesRights = true, UserRights.empty) mustBe policy.rights
    }

    "only give its rights to the users of its auth module" in {
      val replace = policy.copy(mode = UserRightsPolicyMode.Replace)
      replace.givesRightsTo("keycloak", None) mustBe true
      replace.givesRightsTo("keycloak", Some("ldap")) mustBe true
      replace.givesRightsTo("ldap", Some("ldap")) mustBe false
      // the local admins, the users of an apikey, the users of a personal apikey
      replace.givesRightsTo(BackOfficeUser.localAdminsAuthConfigId, None) mustBe false
      replace.givesRightsTo(BackOfficeUser.apikeyAuthConfigId, None) mustBe false
      replace.givesRightsTo(BackOfficeUser.personalAdminApikeyAuthConfigId, None) mustBe false
      // a policy in restrict mode gives its rights to no one
      policy.givesRightsTo("keycloak", None) mustBe false
    }

    "give its rights to the users of the backoffice auth module without auth module" in {
      val replace = policy.copy(mode = UserRightsPolicyMode.Replace, authModuleRef = None)
      replace.givesRightsTo("ldap", Some("ldap")) mustBe true
      replace.givesRightsTo("keycloak", Some("ldap")) mustBe false
      // without a backoffice auth module, the policy only restricts
      replace.givesRightsTo("ldap", None) mustBe false
      // even a backoffice auth module named like the local admins does not give them the rights of the policy
      replace.givesRightsTo(BackOfficeUser.localAdminsAuthConfigId, Some(BackOfficeUser.localAdminsAuthConfigId)) mustBe false
    }

    "pick the policy of an email whatever its case" in {
      val other = policy.copy(email = "john@oto.tools")
      UserRightsPolicy.pick(Seq(policy, other), "JANE.DOE@oto.tools") mustBe Some(policy)
      UserRightsPolicy.pick(Seq(policy, other), "nobody@oto.tools") mustBe None
    }
  }

  "PersonalAdminApikey" should {

    import PersonalAdminApikeyOwner.*

    "read the owner of an apikey, an email given by a source" in {
      val module = AuthModuleUser("jane@oto.tools", "keycloak")
      PersonalAdminApikey.ownerOf(module.metadata) mustBe Some(module)
      val local  = LocalAdmin("Jane@oto.tools", webauthn = true, createdAt = 42L)
      PersonalAdminApikey.ownerOf(local.metadata) mustBe Some(local)
      // the email of a user of an auth module is compared without case, the username of a local admin as is
      PersonalAdminApikey.ownerOf(
        module.metadata + (PersonalAdminApikey.ownerMetadataKey -> "JANE@oto.tools")
      ) mustBe Some(module)
      PersonalAdminApikey.ownerOf(local.metadata) must not be Some(local.copy(email = "jane@oto.tools"))
    }

    "tell two owners with the same email apart" in {
      val module = AuthModuleUser("jane@oto.tools", "keycloak")
      module must not be AuthModuleUser("jane@oto.tools", "ldap")
      LocalAdmin("jane@oto.tools", webauthn = false, 42L) must not be LocalAdmin("jane@oto.tools", webauthn = true, 42L)
      // an admin created again with the same username
      LocalAdmin("jane@oto.tools", webauthn = false, 42L) must not be LocalAdmin("jane@oto.tools", webauthn = false, 43L)
    }

    "read no owner from metadata that name none" in {
      val owner = PersonalAdminApikey.ownerMetadataKey -> "jane@oto.tools"
      PersonalAdminApikey.ownerOf(Map(owner)) mustBe None
      PersonalAdminApikey.ownerOf(
        AuthModuleUser("jane@oto.tools", "keycloak").metadata ++ LocalAdmin("jane@oto.tools", false, 42L).metadata
      ) mustBe None
      PersonalAdminApikey.ownerOf(Map(owner, PersonalAdminApikey.authModuleMetadataKey -> BackOfficeUser.localAdminsAuthConfigId)) mustBe None
      PersonalAdminApikey.ownerOf(Map(owner, PersonalAdminApikey.localAdminMetadataKey -> "simple")) mustBe None
      PersonalAdminApikey.ownerOf(
        Map(owner, PersonalAdminApikey.localAdminMetadataKey -> "root", PersonalAdminApikey.localAdminCreatedAtMetadataKey -> "42")
      ) mustBe None
      PersonalAdminApikey.ownerOf(
        Map(owner, PersonalAdminApikey.localAdminMetadataKey -> "simple", PersonalAdminApikey.localAdminCreatedAtMetadataKey -> "now")
      ) mustBe None
      PersonalAdminApikey.ownerOf(Map(PersonalAdminApikey.ownerMetadataKey -> " ", PersonalAdminApikey.authModuleMetadataKey -> "keycloak")) mustBe None
      // still a personal apikey: one that nobody owns has no rights, it is not an admin apikey without restriction
      PersonalAdminApikey.isPersonal(Map(owner)) mustBe true
    }

    "read the metadata of its owner from json, one by one" in {
      val json = Json.obj(
        "metadata" -> Json.obj(
          PersonalAdminApikey.ownerMetadataKey      -> "jane@oto.tools",
          PersonalAdminApikey.authModuleMetadataKey -> "keycloak",
          "other"                                   -> 42
        )
      )
      PersonalAdminApikey.metadataOfJson(json) mustBe AuthModuleUser("jane@oto.tools", "keycloak").metadata
    }
  }
}
