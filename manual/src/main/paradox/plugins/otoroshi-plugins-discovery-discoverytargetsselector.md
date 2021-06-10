
# Service discovery target selector (service discovery)

## Infos

* plugin type: `preroute`
* configuration root: `DiscoverySelfRegistration`

## Description

This plugin select a target in the pool of discovered targets for this service.
Use in combination with either `DiscoverySelfRegistrationSink` or `DiscoverySelfRegistrationTransformer` to make it work using the `self registration` pattern.
Or use an implementation of `DiscoveryJob` for the `third party registration pattern`.

This plugin accepts the following configuration:



## Default configuration

```json
{
  "DiscoverySelfRegistration" : {
    "hosts" : [ ],
    "targetTemplate" : { },
    "registrationTtl" : 60000
  }
}
```





