
# Prometheus Endpoint

## Infos

* plugin type: `sink`
* configuration root: `PrometheusEndpoint`

## Description

This plugin exposes metrics collected by `Prometheus Service Metrics` on a `/prometheus` endpoint.
You can protect it with an access key defined in the configuration

This plugin can accept the following configuration

```json
{
  "PrometheusEndpoint": {
    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
    "accessKeyQuery": "access_key",
    "includeMetrics": false
  }
}
```



## Default configuration

```json
{
  "PrometheusEndpoint" : {
    "accessKeyValue" : "${config.app.health.accessKey}",
    "accessKeyQuery" : "access_key",
    "includeMetrics" : false
  }
}
```




