
# Static Response

## Infos

* plugin type: `transformer`
* configuration root: `StaticResponse`

## Description

This plugin returns a static response for any request



## Default configuration

```json
{
  "StaticResponse" : {
    "status" : 200,
    "headers" : {
      "Content-Type" : "application/json"
    },
    "body" : "{\"message\":\"hello world!\"}",
    "bodyBase64" : null
  }
}
```





