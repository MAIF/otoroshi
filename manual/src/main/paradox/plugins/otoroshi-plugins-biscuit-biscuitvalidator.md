
@@@ div { .plugin .plugin-hidden .plugin-kind-validator }

# Biscuit token validator

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `validator`
* configuration root: ``none``

## Description

This plugin validates a Biscuit token.



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

