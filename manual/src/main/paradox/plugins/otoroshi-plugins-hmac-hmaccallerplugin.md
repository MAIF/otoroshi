
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# HMAC caller plugin

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `HMACCallerPlugin`

## Description

This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
 The signature string will always the content of the header list listed in the plugin configuration.



## Default configuration

```json
{
  "HMACCallerPlugin" : {
    "secret" : "my-defaut-secret",
    "algo" : "HMAC-SHA512"
  }
}
```





@@@

