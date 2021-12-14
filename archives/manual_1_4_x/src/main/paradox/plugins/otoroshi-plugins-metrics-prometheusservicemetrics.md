
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Prometheus Service Metrics

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `PrometheusServiceMetrics`

## Description

This plugin collects service metrics and can be used with the `Prometheus Endpoint` (in the Danger Zone) plugin to expose those metrics

This plugin can accept the following configuration

```json
{
  "PrometheusServiceMetrics": {
    "includeUri": false // include http uri in metrics. WARNING this could impliess performance issues, use at your own risks
  }
}
```



## Default configuration

```json
{
  "PrometheusServiceMetrics" : {
    "includeUri" : false
  }
}
```





@@@

