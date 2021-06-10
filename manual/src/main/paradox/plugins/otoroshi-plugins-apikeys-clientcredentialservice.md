
# Client Credential Service

## Infos

* plugin type: `sink`
* configuration root: `ClientCredentialService`

## Description

This plugin add an an oauth client credentials service (`https://unhandleddomain/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret.

```json
{
  "ClientCredentialService" : {
    "domain" : "*",
    "expiration" : 3600000,
    "defaultKeyPair" : "otoroshi-jwt-signing",
    "secure" : true
  }
}
```



## Default configuration

```json
{
  "ClientCredentialService" : {
    "domain" : "*",
    "expiration" : 3600000,
    "defaultKeyPair" : "otoroshi-jwt-signing",
    "secure" : true
  }
}
```




