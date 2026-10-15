package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class RbacPluginConfigsSpec extends PluginConfigFormatSpec {

  "RBACConfig" should {

    val format = RBACConfig.format

    val full = RBACConfig(
      allow = Seq("admin", "editor"),
      deny = Seq("banned"),
      allowAll = true,
      denyAll = true,
      jwtPath = Some("realm_access.roles"),
      apikeyPath = Some("metadata.roles"),
      userPath = Some("profile.groups"),
      rolePrefix = Some("acme"),
      roles = "groups"
    )

    "allow and deny nothing by default and look for roles under roles" in {
      val config = RBACConfig()
      config.allow mustBe empty
      config.deny mustBe empty
      config.allowAll mustBe false
      config.denyAll mustBe false
      config.jwtPath mustBe None
      config.apikeyPath mustBe None
      config.userPath mustBe None
      config.rolePrefix mustBe None
      config.roles mustBe "roles"
    }

    "write the default config with null paths and prefix" in {
      format.writes(RBACConfig()) mustBe Json.obj(
        "allow"       -> Json.arr(),
        "deny"        -> Json.arr(),
        "allow_all"   -> false,
        "deny_all"    -> false,
        "jwt_path"    -> JsNull,
        "apikey_path" -> JsNull,
        "user_path"   -> JsNull,
        "role_prefix" -> JsNull,
        "roles"       -> "roles"
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "allow"       -> Json.arr("admin", "editor"),
        "deny"        -> Json.arr("banned"),
        "allow_all"   -> true,
        "deny_all"    -> true,
        "jwt_path"    -> "realm_access.roles",
        "apikey_path" -> "metadata.roles",
        "user_path"   -> "profile.groups",
        "role_prefix" -> "acme",
        "roles"       -> "groups"
      )
    }

    "round trip the default config" in {
      roundTrip(format, RBACConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip allowed roles and keep their order" in {
      roundTrip(format, RBACConfig(allow = Seq("c", "a", "b"))).allow mustBe Seq("c", "a", "b")
    }

    "round trip denied roles alone" in {
      roundTrip(format, RBACConfig(deny = Seq("guest", "banned")))
    }

    "round trip the allow_all and deny_all flags independently" in {
      roundTrip(format, RBACConfig(allowAll = true))
      roundTrip(format, RBACConfig(denyAll = true))
    }

    "round trip the jwt, apikey and user paths" in {
      roundTrip(format, RBACConfig(jwtPath = Some("a.b"), apikeyPath = Some("c.d"), userPath = Some("e.f")))
    }

    "round trip a role prefix alone" in {
      roundTrip(format, RBACConfig(rolePrefix = Some("acme")))
    }

    "round trip a custom roles field name" in {
      roundTrip(format, RBACConfig(roles = "permissions")).roles mustBe "permissions"
    }

    "round trip an empty roles field name as is" in {
      roundTrip(format, RBACConfig(roles = "")).roles mustBe ""
    }

    "round trip an empty role prefix as an empty string, not as no prefix" in {
      roundTrip(format, RBACConfig(rolePrefix = Some(""))).rolePrefix mustBe Some("")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RBACConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RBACConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("admin")) mustBe RBACConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("admin")) mustBe RBACConfig()
    }

    "read allowed roles alone" in {
      readOk(format, Json.obj("allow" -> Json.arr("admin"))) mustBe RBACConfig(allow = Seq("admin"))
    }

    "read deny_all alone" in {
      readOk(format, Json.obj("deny_all" -> true)) mustBe RBACConfig(denyAll = true)
    }

    "read a user path alone" in {
      readOk(format, Json.obj("user_path" -> "groups")) mustBe RBACConfig(userPath = Some("groups"))
    }

    "ignore allowed roles written as a single string" in {
      readOk(format, Json.obj("allow" -> "admin")) mustBe RBACConfig()
    }

    "ignore a denied roles array holding something else than strings" in {
      readOk(format, Json.obj("deny" -> Json.arr("banned", 1))) mustBe RBACConfig()
    }

    "ignore an allow_all written as a string" in {
      readOk(format, Json.obj("allow_all" -> "true")) mustBe RBACConfig()
    }

    "ignore a roles field name written as an array" in {
      readOk(format, Json.obj("roles" -> Json.arr("admin"))) mustBe RBACConfig()
    }

    "ignore a jwt path that is not a string" in {
      readOk(format, Json.obj("jwt_path" -> 42)) mustBe RBACConfig()
    }

    "read null paths and prefix as none" in {
      readOk(
        format,
        Json.obj("jwt_path" -> JsNull, "apikey_path" -> JsNull, "user_path" -> JsNull, "role_prefix" -> JsNull)
      ) mustBe RBACConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj(
          "allowAll"   -> true,
          "denyAll"    -> true,
          "jwtPath"    -> "a",
          "apikeyPath" -> "b",
          "userPath"   -> "c",
          "rolePrefix" -> "d"
        )
      ) mustBe RBACConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("allow" -> Json.arr("admin"), "foo" -> "bar")) mustBe RBACConfig(allow = Seq("admin"))
    }

    "use role: as the tag prefix without role prefix" in {
      RBACConfig().prefix mustBe "role:"
    }

    "put the role prefix in front of the tag prefix" in {
      RBACConfig(rolePrefix = Some("acme")).prefix mustBe "acme:role:"
    }

    "keep the separator with an empty role prefix" in {
      RBACConfig(rolePrefix = Some("")).prefix mustBe ":role:"
    }

    "compute the tag prefix from the role prefix read from json" in {
      readOk(format, Json.obj("role_prefix" -> "team")).prefix mustBe "team:role:"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of RBAC" in {
      defaultConfigReadsBack(new RBAC(), format) mustBe RBACConfig()
    }
  }
}
