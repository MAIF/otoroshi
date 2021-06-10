
# Geolocation details extractor (using Maxmind db)

## Infos

* plugin type: `preroute`
* configuration root: `GeolocationInfo`

## Description

This plugin extract geolocation informations from ip address using the [Maxmind dbs](https://www.maxmind.com/en/geoip2-databases).
The informations are store in plugins attrs for other plugins to use

This plugin can accept the following configuration

```json
{
  "GeolocationInfo": {
    "path": "/foo/bar/cities.mmdb", // file path, can be "global"
    "log": false // will log geolocation details
  }
}
```



## Default configuration

```json
{
  "GeolocationInfo" : {
    "path" : "global",
    "log" : false
  }
}
```





