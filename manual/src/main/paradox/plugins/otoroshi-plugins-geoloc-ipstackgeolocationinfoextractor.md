
# Geolocation details extractor (using IpStack api)

## Infos

* plugin type: `preroute`
* configuration root: `GeolocationInfo`

## Description

This plugin extract geolocation informations from ip address using the [IpStack dbs](https://ipstack.com/).
The informations are store in plugins attrs for other plugins to use

This plugin can accept the following configuration

```json
{
  "GeolocationInfo": {
    "apikey": "xxxxxxx",
    "timeout": 2000, // timeout in ms
    "log": false // will log geolocation details
  }
}
```



## Default configuration

```json
{
  "GeolocationInfo" : {
    "apikey" : "xxxxxxx",
    "timeout" : 2000,
    "log" : false
  }
}
```





