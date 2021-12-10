
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Service Metrics

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `ServiceMetrics`

## Description

This plugin expose service metrics in Otoroshi global metrics or on a special URL of the service `/.well-known/otoroshi/metrics`.
Metrics are exposed in json or prometheus format depending on the accept header. You can protect it with an access key defined in the configuration

This plugin can accept the following configuration

```json
{
  "ServiceMetrics": {
    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
    "accessKeyQuery": "access_key"
  }
}
```



## Default configuration

```json
{
  "ServiceMetrics" : {
    "accessKeyValue" : "${config.app.health.accessKey}",
    "accessKeyQuery" : "access_key"
  }
}
```





@@@

