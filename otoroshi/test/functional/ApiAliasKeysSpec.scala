package functional

import plugins.ApiAliasKeysTests

// one apikey for several apis, each through a subscription of its own (#2964): sbt "testOnly functional.ApiAliasKeysSpec"
class ApiAliasKeysSpec extends PluginsTestSpecBase {
  lazy val aliases = new ApiAliasKeysTests(this)
  s"alias keys" should {
    "be indexed by credential and by api" in { aliases.aliasesIndex() }
    "let one credential call several apis, each with the quotas of its own plan" in {
      aliases.oneCredentialSeveralApis()
    }
    "follow the secret of the credential on every api" in { aliases.newSecretCallsEveryApi() }
    "only be minted for a key of the same owner, once per api" in { aliases.sharingChecks() }
    "follow their own subscription, and keep the credential for its aliases" in { aliases.lifecycle() }
    "only share a key its author can write" in { aliases.sharingRights() }
  }
}
