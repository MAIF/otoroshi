
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Izanami APIs Proxy

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `IzanamiProxy`

## Description

This plugin exposes routes to proxy Izanami configuration and features tree APIs.

This plugin can accept the following configuration

```json
{
  "IzanamiProxy" : {
    "path" : "/api/izanami",
    "featurePattern" : "*",
    "configPattern" : "*",
    "autoContext" : false,
    "featuresEnabled" : true,
    "featuresWithContextEnabled" : true,
    "configurationEnabled" : false,
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000
  }
}
```



## Default configuration

```json
{
  "IzanamiProxy" : {
    "path" : "/api/izanami",
    "featurePattern" : "*",
    "configPattern" : "*",
    "autoContext" : false,
    "featuresEnabled" : true,
    "featuresWithContextEnabled" : true,
    "configurationEnabled" : false,
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000
  }
}
```





@@@

