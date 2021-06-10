
# Instance quotas

## Infos

* plugin type: `validator`
* configuration root: `InstanceQuotas`

## Description

This plugin will enforce global quotas on the current instance

This plugin can accept the following configuration

```json
{
  "InstanceQuotas": {
    "callsPerDay": -1,     // max allowed api calls per day
    "callsPerMonth": -1,   // max allowed api calls per month
    "maxDescriptors": -1,  // max allowed service descriptors
    "maxApiKeys": -1,      // max allowed apikeys
    "maxGroups": -1,       // max allowed service groups
    "maxScripts": -1,      // max allowed apikeys
    "maxCertificates": -1, // max allowed certificates
    "maxVerifiers": -1,    // max allowed jwt verifiers
    "maxAuthModules": -1,  // max allowed auth modules
  }
}
```



## Default configuration

```json
{
  "InstanceQuotas" : {
    "callsPerDay" : -1,
    "callsPerMonth" : -1,
    "maxDescriptors" : -1,
    "maxApiKeys" : -1,
    "maxGroups" : -1,
    "maxScripts" : -1,
    "maxCertificates" : -1,
    "maxVerifiers" : -1,
    "maxAuthModules" : -1
  }
}
```





