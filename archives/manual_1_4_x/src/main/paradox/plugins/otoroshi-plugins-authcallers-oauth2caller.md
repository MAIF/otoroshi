
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# OAuth2 caller

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `OAuth2Caller`

## Description

This plugin can be used to call api that are authenticated using OAuth2 client_credential/password flow.
Do not forget to enable client retry to handle token generation on expire.

This plugin accepts the following configuration

{
  "kind" : "the oauth2 flow, can be 'client_credentials' or 'password'",
  "url" : "https://127.0.0.1:8080/oauth/token",
  "method" : "POST",
  "headerName" : "Authorization",
  "headerValueFormat" : "Bearer %s",
  "jsonPayload" : false,
  "clientId" : "the client_id",
  "clientSecret" : "the client_secret",
  "scope" : "an optional scope",
  "audience" : "an optional audience",
  "user" : "an optional username if using password flow",
  "password" : "an optional password if using password flow",
  "cacheTokenSeconds" : "the number of second to wait before asking for a new token",
  "tlsConfig" : "an optional TLS settings object"
}



## Default configuration

```json
{
  "kind" : "the oauth2 flow, can be 'client_credentials' or 'password'",
  "url" : "https://127.0.0.1:8080/oauth/token",
  "method" : "POST",
  "headerName" : "Authorization",
  "headerValueFormat" : "Bearer %s",
  "jsonPayload" : false,
  "clientId" : "the client_id",
  "clientSecret" : "the client_secret",
  "scope" : "an optional scope",
  "audience" : "an optional audience",
  "user" : "an optional username if using password flow",
  "password" : "an optional password if using password flow",
  "cacheTokenSeconds" : "the number of second to wait before asking for a new token",
  "tlsConfig" : "an optional TLS settings object"
}
```





@@@

