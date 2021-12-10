
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Defer Responses

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `DeferPlugin`

## Description

This plugin will expect a `X-Defer` header or a `defer` query param and defer the response according to the value in milliseconds.
This plugin is some kind of inside joke as one a our customer ask us to make slower apis.

This plugin can accept the following configuration

```json
{
  "DeferPlugin": {
    "defaultDefer": 0 // default defer in millis
  }
}
```



## Default configuration

```json
{
  "DeferPlugin" : {
    "defaultDefer" : 0
  }
}
```





@@@

