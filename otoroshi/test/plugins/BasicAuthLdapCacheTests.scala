package plugins

import functional.PluginsTestSpecBase
import otoroshi.auth.{BasicAuthModuleConfig, SessionCookieValues}
import otoroshi.models.{EntityLocation, PrivateAppsUser}
import otoroshi.next.plugins.BasicAuthWithAuthModule
import play.api.libs.json.Json

// a hit in the login cache of the "Basic auth. from auth. module" plugin skips the ldap bind. keyed on the username
// alone, the cache let any password in for a user who had logged in during the last minute
class BasicAuthLdapCacheTests(parent: PluginsTestSpecBase) {
  import parent.*

  val module = BasicAuthModuleConfig(
    id = "basic-auth-ldap-cache-module",
    name = "basic auth ldap cache module",
    desc = "basic auth ldap cache module",
    clientSideSessionEnabled = true,
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues()
  )

  val user = PrivateAppsUser(
    randomId = "basic-auth-ldap-cache-user",
    name = "john",
    email = "john@oto.tools",
    profile = Json.obj(),
    realm = "basic-auth-ldap-cache",
    authConfigId = module.id,
    otoroshiData = None,
    tags = Seq.empty,
    metadata = Map.empty,
    location = EntityLocation()
  )

  BasicAuthWithAuthModule.addLoggedIn("john", "the-real-password", user, module)

  withClue("the password the user logged in with ") {
    BasicAuthWithAuthModule.alreadyLoggedIn("john", "the-real-password", module).map(_.randomId) mustBe Some(
      user.randomId
    )
  }
  withClue("another password for the same user ") {
    BasicAuthWithAuthModule.alreadyLoggedIn("john", "anything-else", module) mustBe None
  }
  withClue("an empty password for the same user ") {
    BasicAuthWithAuthModule.alreadyLoggedIn("john", "", module) mustBe None
  }
  withClue("the same password for another user ") {
    BasicAuthWithAuthModule.alreadyLoggedIn("jane", "the-real-password", module) mustBe None
  }
}
