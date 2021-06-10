
# OIDC access_token as apikey

## Infos

* plugin type: `preroute`
* configuration root: `OIDCAccessTokenAsApikey`

## Description

This plugin will use the third party apikey configuration to generate an apikey

This plugin can accept the following configuration

```json
{
  "OIDCAccessTokenValidator": {
    "enabled": true,
    "atLeastOne": false,
    // config is optional and can be either an object config or an array of objects
    "config": {
  "enabled" : true,
  "quotasEnabled" : true,
  "uniqueApiKey" : false,
  "type" : "OIDC",
  "oidcConfigRef" : "some-oidc-auth-module-id",
  "localVerificationOnly" : false,
  "mode" : "Tmp",
  "ttl" : 0,
  "headerName" : "Authorization",
  "throttlingQuota" : 100,
  "dailyQuota" : 10000000,
  "monthlyQuota" : 10000000,
  "excludedPatterns" : [ ],
  "scopes" : [ ],
  "rolesPath" : [ ],
  "roles" : [ ]
}
  }
}
```



## Default configuration

```json
{
  "OIDCAccessTokenAsApikey" : {
    "enabled" : true,
    "atLeastOne" : false,
    "config" : {
      "enabled" : true,
      "quotasEnabled" : true,
      "uniqueApiKey" : false,
      "type" : "OIDC",
      "oidcConfigRef" : "some-oidc-auth-module-id",
      "localVerificationOnly" : false,
      "mode" : "Tmp",
      "ttl" : 0,
      "headerName" : "Authorization",
      "throttlingQuota" : 100,
      "dailyQuota" : 10000000,
      "monthlyQuota" : 10000000,
      "excludedPatterns" : [ ],
      "scopes" : [ ],
      "rolesPath" : [ ],
      "roles" : [ ]
    }
  }
}
```




