
# External Http Validator

## Infos

* plugin type: `validator`
* configuration root: `ExternalHttpValidator`

## Description

Calls an external http service to know if a user has access or not. Uses cache for performances.

The sent payload is the following:

```json
{
  "apikey": {...},
  "user": {...},
  "service": : {...},
  "chain": "...",  // PEM cert chain
  "fingerprints": [...]
}
```

This plugin can accept the following configuration

```json
{
  "ExternalHttpValidator": {
    "url": "...",                      // url for the http call
    "host": "...",                     // value of the host header for the call. default is host of the url
    "goodTtl": 600000,                 // ttl in ms for a validated call
    "badTtl": 60000,                   // ttl in ms for a not validated call
    "method": "POST",                  // http methode
    "path": "/certificates/_validate", // http uri path
    "timeout": 10000,                  // http call timeout
    "noCache": false,                  // use cache or not
    "allowNoClientCert": false,        //
    "headers": {},                      // headers for the http call if needed
    "mtlsConfig": {
      "certId": "xxxxx",
       "mtls": false,
       "loose": false
    }
  }
}
```



## Default configuration

```json
{
  "ExternalHttpValidator" : {
    "url" : "http://foo.bar",
    "host" : "api.foo.bar",
    "goodTtl" : 600000,
    "badTtl" : 60000,
    "method" : "POST",
    "path" : "/certificates/_validate",
    "timeout" : 10000,
    "noCache" : false,
    "allowNoClientCert" : false,
    "headers" : { },
    "mtlsConfig" : {
      "certId" : "...",
      "mtls" : false,
      "loose" : false
    }
  }
}
```




