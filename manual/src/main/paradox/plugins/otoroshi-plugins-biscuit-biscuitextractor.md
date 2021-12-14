
@@@ div { .plugin .plugin-hidden .plugin-kind-preroute }

# Apikey from Biscuit token extractor

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `preroute`
* configuration root: ``none``

## Description

This plugin extract an from a Biscuit token where the biscuit has an #authority fact 'client_id' containing
apikey client_id and an #authority fact 'client_sign' that is the HMAC256 signature of the apikey client_id with the apikey client_secret



## Default configuration

```json
{
  "publicKey" : "xxxxxx",
  "secret" : "secret",
  "checks" : [ ],
  "facts" : [ ],
  "resources" : [ ],
  "rules" : [ ],
  "revocation_ids" : [ ],
  "enforce" : false,
  "sealed" : false,
  "extractor" : {
    "type" : "header",
    "name" : "Authorization"
  }
}
```





@@@

