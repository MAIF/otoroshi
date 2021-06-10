
# Envoy Control Plane (experimental)

## Infos

* plugin type: `transformer`
* configuration root: `EnvoyControlPlane`

## Description

This plugin will expose the otoroshi state to envoy instances using the xDS V3 API`.

Right now, all the features of otoroshi cannot be exposed as is through Envoy.



## Default configuration

```json
{
  "EnvoyControlPlane" : {
    "enabled" : true
  }
}
```




