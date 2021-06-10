
# Geolocation header

## Infos

* plugin type: `transformer`
* configuration root: `GeolocationInfoHeader`

## Description

This plugin will send informations extracted by the Geolocation details extractor to the target service in a header.

This plugin can accept the following configuration

```json
{
  "GeolocationInfoHeader": {
    "headerName": "X-Geolocation-Info" // header in which info will be sent
  }
}
```



## Default configuration

```json
{
  "GeolocationInfoHeader" : {
    "headerName" : "X-Geolocation-Info"
  }
}
```




