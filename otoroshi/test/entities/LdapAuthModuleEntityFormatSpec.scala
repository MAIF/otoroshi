package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.{JsonPathValidator, JsonValidator}
import play.api.libs.json.*

import scala.concurrent.duration.*

class LdapAuthModuleEntityFormatSpec extends EntityFormatSpec {

  private val rights = UserRights.varargs(UserRight(TenantAccess("tenant-1"), Seq(TeamAccess("team-1:r"))))

  "GroupFilter" should {

    val format = GroupFilter._fmt

    "write the tenant with its permissions" in {
      format.writes(GroupFilter("cn=admins,dc=oto,dc=tools", TenantAccess("tenant-1:r"), "team-1")) mustBe Json.obj(
        "group"  -> "cn=admins,dc=oto,dc=tools",
        "team"   -> "team-1",
        "tenant" -> "tenant-1:r"
      )
    }

    "round trip a read only filter" in {
      roundTrip(format, GroupFilter("cn=users", TenantAccess("tenant-1:r"), "team-1:r"))
    }

    "round trip a read write filter on every tenant" in {
      roundTrip(format, GroupFilter("cn=users", TenantAccess("*"), "*"))
    }

    "read a filter without tenant on every tenant in read and write" in {
      readOk(format, Json.obj("group" -> "cn=users", "team" -> "team-1")).tenant mustBe TenantAccess("*", true, true)
    }

    "read an empty object as an empty filter on every tenant" in {
      readOk(format, Json.obj()) mustBe GroupFilter("", TenantAccess("*:rw"), "")
    }

    "read a tenant without permission suffix in read and write" in {
      readOk(format, Json.obj("tenant" -> "tenant-1")).tenant mustBe TenantAccess("tenant-1", true, true)
    }

    "fall back to every tenant for a tenant that is not a string" in {
      readOk(format, Json.obj("tenant" -> 1)).tenant mustBe TenantAccess("*", true, true)
    }
  }

  "GroupRights" should {

    val format = GroupRights._fmt

    "write its rights and its users" in {
      format.writes(GroupRights(rights, Seq("john"))) mustBe Json.obj(
        "rights" -> Json.arr(Json.obj("tenant" -> "tenant-1:rw", "teams" -> Json.arr("team-1:r"))),
        "users"  -> Json.arr("john")
      )
    }

    "round trip group rights" in {
      roundTrip(format, GroupRights(rights, Seq("john", "jane")))
    }

    "round trip group rights without any right" in {
      roundTrip(format, GroupRights(UserRights.empty, Seq.empty))
    }

    "read missing rights as no right, not as super admin rights" in {
      readOk(format, Json.obj("users" -> Json.arr("john"))) mustBe GroupRights(UserRights.empty, Seq("john"))
    }

    "read rights that are not an array as no right" in {
      readOk(format, Json.obj("rights" -> Json.obj("tenant" -> "*", "teams" -> Json.arr("*")))).userRights mustBe UserRights.empty
    }

    "be read from an object as an option" in {
      GroupRights.reads(Json.obj("rights" -> rights.json)) mustBe Some(GroupRights(rights, Seq.empty))
    }
  }

  "LdapAuthUser" should {

    val format = LdapAuthUser.fmt

    val full = LdapAuthUser(
      name = "John",
      email = "john@oto.tools",
      metadata = Json.obj("team" -> "ops"),
      userRights = Some(rights),
      ldapProfile = Some(Json.obj("cn" -> "John", "memberOf" -> Json.arr("admins"))),
      adminEntityValidators = Map("route" -> Seq(JsonPathValidator("$.enabled", JsBoolean(true))))
    )

    "write a user without rights nor profile" in {
      format.writes(LdapAuthUser("John", "john@oto.tools", Json.obj(), None, None, Map.empty)) mustBe Json.obj(
        "name"                  -> "John",
        "email"                 -> "john@oto.tools",
        "metadata"              -> Json.obj(),
        "ldapProfile"           -> JsNull,
        "userRights"            -> JsNull,
        "adminEntityValidators" -> Json.obj()
      )
    }

    "expose through asJson what the format writes" in {
      full.asJson mustBe format.writes(full)
    }

    "round trip a user where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a user without rights nor profile" in {
      roundTrip(format, LdapAuthUser("John", "john@oto.tools", Json.obj(), None, None, Map.empty))
    }

    "round trip a user without any right" in {
      roundTrip(format, full.copy(userRights = Some(UserRights.empty))).userRights mustBe Some(UserRights.empty)
    }

    "not read a user without email" in {
      readError(format, Json.obj("name" -> "John"))
    }

    "ignore a profile that is not an object" in {
      readOk(format, Json.obj("name" -> "John", "email" -> "e", "ldapProfile" -> Json.arr(1))).ldapProfile mustBe None
    }

    "ignore rights that are not an array" in {
      readOk(format, Json.obj("name" -> "John", "email" -> "e", "userRights" -> Json.obj())).userRights mustBe None
    }
  }

  "LdapAuthModuleConfig" should {

    val format = LdapAuthModuleConfig._fmt

    val minimal = LdapAuthModuleConfig(
      id = "auth_mod_ldap",
      name = "Ldap",
      desc = "an ldap module",
      clientSideSessionEnabled = true,
      searchBase = "dc=oto,dc=tools",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues()
    )

    val full = LdapAuthModuleConfig(
      id = "auth_mod_ldap",
      name = "Ldap",
      desc = "an ldap module",
      sessionMaxAge = 900,
      clientSideSessionEnabled = false,
      userValidators = Seq(JsonPathValidator("$.metadata.level", JsString("Regex([0-9]+)"), Some("no level"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map.empty, 2.seconds, NgTlsConfig())),
      basicAuth = true,
      allowEmptyPassword = true,
      serverUrls = Seq("ldap://ldap-1.oto.tools:389", "ldap://ldap-2.oto.tools:389"),
      searchBase = "dc=oto,dc=tools",
      userBase = Some("ou=users"),
      groupFilters = Seq(
        GroupFilter("(memberOf=cn=admins)", TenantAccess("*:rw"), "*:rw"),
        GroupFilter("(memberOf=cn=readers)", TenantAccess("tenant-1:r"), "team-1:r")
      ),
      searchFilter = "(uid=${username})",
      adminUsername = Some("cn=admin,dc=oto,dc=tools"),
      adminPassword = Some("password"),
      nameField = "displayName",
      emailField = "email",
      metadataField = Some("description"),
      extraMetadata = Json.obj("source" -> "ldap"),
      tags = Seq("corporate"),
      metadata = Map("owner" -> "it"),
      sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
      location = customLocation,
      superAdmins = true,
      extractProfile = true,
      extractProfileFilter = Seq("cn", "mail"),
      extractProfileFilterNot = Seq("userPassword"),
      rightsOverride = Map("john@oto.tools" -> rights),
      dataOverride = Map("john@oto.tools" -> Json.obj("level" -> 2)),
      adminEntityValidatorsOverride = Map("john@oto.tools" -> Map("all" -> Seq(JsonPathValidator("$.id", JsString("Not(x)"))))),
      groupRights = Map("cn=admins" -> GroupRights(UserRights.superAdmin, Seq("john")), "cn=readers" -> GroupRights(rights, Seq.empty)),
      allowedUsers = Seq("Wildcard(*@oto.tools)"),
      deniedUsers = Seq("bob@oto.tools")
    )

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                          -> defaultLocationJson,
        "type"                          -> "ldap",
        "id"                            -> "auth_mod_ldap",
        "name"                          -> "Ldap",
        "desc"                          -> "an ldap module",
        "basicAuth"                     -> false,
        "allowEmptyPassword"            -> false,
        "clientSideSessionEnabled"      -> true,
        "sessionMaxAge"                 -> 86400,
        "userValidators"                -> Json.arr(),
        "remoteValidators"              -> Json.arr(),
        "serverUrls"                    -> Json.arr(),
        "searchBase"                    -> "dc=oto,dc=tools",
        "userBase"                      -> JsNull,
        "groupFilters"                  -> Json.arr(),
        "searchFilter"                  -> "(mail=${username})",
        "adminUsername"                 -> JsNull,
        "adminPassword"                 -> JsNull,
        "nameField"                     -> "cn",
        "emailField"                    -> "mail",
        "metadataField"                 -> JsNull,
        "extraMetadata"                 -> Json.obj(),
        "metadata"                      -> Json.obj(),
        "tags"                          -> Json.arr(),
        "sessionCookieValues"           -> Json.obj("httpOnly" -> true, "secure" -> true),
        "superAdmins"                   -> false,
        "extractProfile"                -> false,
        "extractProfileFilter"          -> Json.arr(),
        "extractProfileFilterNot"       -> Json.arr(),
        "rightsOverride"                -> Json.obj(),
        "dataOverride"                  -> Json.obj(),
        "allowedUsers"                  -> Json.arr(),
        "deniedUsers"                   -> Json.arr(),
        "groupRights"                   -> Json.obj(),
        "adminEntityValidatorsOverride" -> Json.obj()
      )
    }

    "write the group rights by group" in {
      (written(format, full) \ "groupRights" \ "cn=admins").as[JsValue] mustBe Json.obj(
        "rights" -> Json.arr(Json.obj("tenant" -> "*:rw", "teams" -> Json.arr("*:rw"))),
        "users"  -> Json.arr("john")
      )
    }

    "write the server urls under the plural key only" in {
      val json = written(format, full)
      (json \ "serverUrls").as[Seq[String]] mustBe full.serverUrls
      json.keys must not contain "serverUrl"
      json.keys must not contain "groupFilter"
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "keep its group filters and group rights on a round trip" in {
      val back = roundTrip(format, full)
      back.groupFilters mustBe full.groupFilters
      back.groupRights mustBe full.groupRights
    }

    "keep its rights overrides and admin entity validators overrides on a round trip" in {
      val back = roundTrip(format, full)
      back.rightsOverride mustBe Map("john@oto.tools" -> rights)
      back.adminEntityValidatorsOverride mustBe full.adminEntityValidatorsOverride
    }

    "keep the super admin and the empty password flags on a round trip" in {
      val back = roundTrip(format, full)
      back.superAdmins mustBe true
      back.allowEmptyPassword mustBe true
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    val mandatory = Json.obj(
      "id"           -> "auth_mod_ldap",
      "name"         -> "Ldap",
      "searchBase"   -> "dc=oto,dc=tools",
      "searchFilter" -> "(mail=${username})",
      "nameField"    -> "cn",
      "emailField"   -> "mail"
    )

    "read the defaults of a module with only its mandatory fields" in {
      readOk(format, mandatory) mustBe minimal.copy(desc = "--")
    }

    "not read a module without one of its mandatory fields" in {
      Seq("id", "name", "searchBase", "searchFilter", "nameField", "emailField").foreach { key =>
        withClue(s"without $key: ") {
          readError(format, mandatory - key)
        }
      }
    }

    "not give super admin rights when the super admins flag is missing" in {
      readOk(format, mandatory).superAdmins mustBe false
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read a legacy single server url" in {
      readOk(format, mandatory ++ Json.obj("serverUrl" -> "ldap://legacy:389")).serverUrls mustBe Seq("ldap://legacy:389")
    }

    "prefer the legacy single server url over the server urls" in {
      readOk(
        format,
        mandatory ++ Json.obj("serverUrl" -> "ldap://legacy:389", "serverUrls" -> Json.arr("ldap://a:389", "ldap://b:389"))
      ).serverUrls mustBe Seq("ldap://legacy:389")
    }

    "build the group filters of a legacy group filter from the location of the module" in {
      readOk(format, mandatory ++ Json.obj("groupFilter" -> "(memberOf=admins)", "_loc" -> customLocationJson)).groupFilters mustBe Seq(
        GroupFilter("(memberOf=admins)", TenantAccess("tenant-1", true, true), "team-1"),
        GroupFilter("(memberOf=admins)", TenantAccess("tenant-1", true, true), "team-2")
      )
    }

    "prefer the legacy group filter over the group filters" in {
      val json = mandatory ++ Json.obj(
        "groupFilter"  -> "(memberOf=admins)",
        "groupFilters" -> Json.arr(GroupFilter._fmt.writes(GroupFilter("(memberOf=readers)", TenantAccess("*"), "*")))
      )
      readOk(format, json).groupFilters mustBe Seq(GroupFilter("(memberOf=admins)", TenantAccess("default", true, true), "default"))
    }

    "read blank optional strings as missing" in {
      val back = readOk(
        format,
        mandatory ++ Json.obj("userBase" -> " ", "adminUsername" -> "", "adminPassword" -> "  ", "metadataField" -> "")
      )
      back.userBase mustBe None
      back.adminUsername mustBe None
      back.adminPassword mustBe None
      back.metadataField mustBe None
    }

    "ignore all the group rights when one of them is not an object" in {
      val json = mandatory ++ Json.obj("groupRights" -> Json.obj("a" -> GroupRights._fmt.writes(GroupRights(rights, Seq.empty)), "b" -> 1))
      readOk(format, json).groupRights mustBe Map.empty
    }

    "read group rights without rights as no right" in {
      readOk(format, mandatory ++ Json.obj("groupRights" -> Json.obj("a" -> Json.obj()))).groupRights mustBe Map(
        "a" -> GroupRights(UserRights.empty, Seq.empty)
      )
    }

    "read a group filter that is not an object as an empty filter on every tenant" in {
      val json = mandatory ++ Json.obj("groupFilters" -> Json.arr(Json.obj("group" -> "a"), "b"))
      readOk(format, json).groupFilters mustBe Seq(
        GroupFilter("a", TenantAccess("*", true, true), ""),
        GroupFilter("", TenantAccess("*", true, true), "")
      )
    }

    "ignore group filters that are not an array" in {
      readOk(format, mandatory ++ Json.obj("groupFilters" -> Json.obj("group" -> "a"))).groupFilters mustBe empty
    }

    "fall back to the defaults for flags that are not booleans" in {
      val back = readOk(format, mandatory ++ Json.obj("superAdmins" -> "true", "allowEmptyPassword" -> 1, "basicAuth" -> "x"))
      back.superAdmins mustBe false
      back.allowEmptyPassword mustBe false
      back.basicAuth mustBe false
    }

    "not read a module whose admin entity validators override of an entity is not an array" in {
      readError(format, mandatory ++ Json.obj("adminEntityValidatorsOverride" -> Json.obj("john" -> Json.obj("all" -> 1))))
    }

    "give the read error as a left" in {
      LdapAuthModuleConfig.fromJson(Json.obj("id" -> "a")).isLeft mustBe true
    }

    "be built from its json" in {
      LdapAuthModuleConfig.fromJsons(full.json) mustBe full
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_ldap", "Ldap", "an ldap module", Seq("corporate"), Map("owner" -> "it"))
    }

    "describe its type" in {
      full.`type` mustBe "ldap"
      full.humanName mustBe "Ldap auth. provider"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "ldap-auth-auth_mod_ldap"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }
  }

  "LdapAuthModuleConfig search filters" should {

    "escape the special characters of a filter value" in {
      LdapAuthModuleConfig.escapeFilterValue("a*b(c)d\\e\u0000f") mustBe "a\\2ab\\28c\\29d\\5ce\\00f"
    }

    "leave a plain value untouched" in {
      LdapAuthModuleConfig.escapeFilterValue("john.doe@oto.tools") mustBe "john.doe@oto.tools"
    }

    "put the escaped username in the search filter" in {
      LdapAuthModuleConfig.searchFilterFor("(mail=${username})", "*)(uid=*") mustBe "(mail=\\2a\\29\\28uid=\\2a)"
    }

    "replace every occurrence of the username" in {
      LdapAuthModuleConfig.searchFilterFor("(|(mail=${username})(uid=${username}))", "john") mustBe "(|(mail=john)(uid=john))"
    }
  }
}
