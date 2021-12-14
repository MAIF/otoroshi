
@@@ div { .plugin .plugin-hidden .plugin-kind-preroute }

# Jwt user extractor

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `preroute`
* configuration root: `JwtUserExtractor`

## Description

This plugin extract a user from a JWT token



## Default configuration

```json
{
  "JwtUserExtractor" : {
    "verifier" : "",
    "strict" : true,
    "namePath" : "name",
    "emailPath" : "email",
    "metaPath" : null
  }
}
```





@@@

