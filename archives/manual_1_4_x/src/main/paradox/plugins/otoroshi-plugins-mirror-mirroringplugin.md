
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Mirroring plugin

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `MirroringPlugin`

## Description

This plugin will mirror every request to other targets

This plugin can accept the following configuration

```json
{
  "MirroringPlugin": {
    "enabled": true, // enabled mirroring
    "to": "https://foo.bar.dev", // the url of the service to mirror
  }
}
```



## Default configuration

```json
{
  "MirroringPlugin" : {
    "enabled" : true,
    "to" : "https://foo.bar.dev",
    "captureResponse" : false,
    "generateEvents" : false
  }
}
```





@@@

