
# Client certificate as apikey

## Infos

* plugin type: `preroute`
* configuration root: `CertificateAsApikey`

## Description

This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage



## Default configuration

```json
{
  "CertificateAsApikey" : {
    "readOnly" : false,
    "allowClientIdOnly" : false,
    "throttlingQuota" : 100,
    "dailyQuota" : 10000000,
    "monthlyQuota" : 10000000,
    "constrainedServicesOnly" : false,
    "tags" : [ ],
    "metadata" : { }
  }
}
```




