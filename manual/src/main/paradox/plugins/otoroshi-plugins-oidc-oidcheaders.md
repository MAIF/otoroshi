
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# OIDC headers

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `OIDCHeaders`

## Description

This plugin injects headers containing tokens and profile from current OIDC provider.



## Default configuration

```json
{
  "OIDCHeaders" : {
    "profile" : {
      "send" : true,
      "headerName" : "X-OIDC-User"
    },
    "idtoken" : {
      "send" : false,
      "name" : "id_token",
      "headerName" : "X-OIDC-Id-Token",
      "jwt" : true
    },
    "accesstoken" : {
      "send" : false,
      "name" : "access_token",
      "headerName" : "X-OIDC-Access-Token",
      "jwt" : true
    }
  }
}
```





@@@

