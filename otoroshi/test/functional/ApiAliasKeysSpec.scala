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
    "only be minted for a shared credential of the same owner, once per api" in { aliases.sharingChecks() }
    "follow their own subscription, and the credential they share" in { aliases.lifecycle() }
    "only share a credential their author can write" in { aliases.sharingRights() }
    "only be written by hand by the users who can write their credential, which stays one" in { aliases.aliasesWrittenByHand() }
    "go with their credential" in { aliases.deletedCredentialTakesItsAliases() }
    "not share a credential written by hand for an owner, unless it is named" in { aliases.plantedCredential() }
    "not share the credential a subscription minted for an owner in other teams, unless it is named" in {
      aliases.credentialMintedInOtherTeams()
    }
    "share a credential written a moment ago with the ones who can write it" in { aliases.credentialJustCreated() }
    "share one credential when the subscriptions of an owner are created at once" in {
      aliases.subscriptionsCreatedAtOnce()
    }
    "be called with the token an oauth2-local subscription exchanges the credential for" in { aliases.oauth2Local() }
    "leave subscriptions their author still updates and deletes when their credential is deleted" in {
      aliases.subscriptionOfDeletedCredential()
    }
    "not be found by the extractors that do not resolve their credential" in { aliases.aliasesAndLegacyExtractors() }
    "be minted once when a credential is shared on one api by subscriptions created at once" in {
      aliases.sameApiAtOnce()
    }
    "keep the credential of a refused subscription that another one shares, or is about to" in {
      aliases.credentialSharedByAnother()
    }
    "go with the api keys a user deletes at once, and only with them" in { aliases.deletedAtOnce() }
    "be signed with the secret of their credential, whichever hmac validator checks the call" in {
      aliases.hmacSignedWithTheCredential()
    }
  }
}
