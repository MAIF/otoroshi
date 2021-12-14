
@@@ div { .plugin .plugin-hidden .plugin-kind-validator }

# Client certificate matching (over http)

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `validator`
* configuration root: `HasClientCertMatchingHttpValidator`

## Description

Check if client certificate matches the following configuration

expected response from http service is

```json
{
  "serialNumbers": [],   // allowed certificated serial numbers
  "subjectDNs": [],      // allowed certificated DNs
  "issuerDNs": [],       // allowed certificated issuer DNs
  "regexSubjectDNs": [], // allowed certificated DNs matching regex
  "regexIssuerDNs": [],  // allowed certificated issuer DNs matching regex
}
```

This plugin can accept the following configuration

```json
{
  "HasClientCertMatchingValidator": {
    "url": "...",   // url for the call
    "headers": {},  // http header for the call
    "ttl": 600000,  // cache ttl,
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
  "HasClientCertMatchingHttpValidator" : {
    "url" : "http://foo.bar",
    "ttl" : 600000,
    "headers" : { },
    "mtlsConfig" : {
      "certId" : "...",
      "mtls" : false,
      "loose" : false
    }
  }
}
```





@@@

