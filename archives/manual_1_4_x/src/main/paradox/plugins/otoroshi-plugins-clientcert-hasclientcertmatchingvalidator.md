
@@@ div { .plugin .plugin-hidden .plugin-kind-validator }

# Client certificate matching

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `validator`
* configuration root: `HasClientCertMatchingValidator`

## Description

Check if client certificate matches the following configuration

This plugin can accept the following configuration

```json
{
  "HasClientCertMatchingValidator": {
    "serialNumbers": [],   // allowed certificated serial numbers
    "subjectDNs": [],      // allowed certificated DNs
    "issuerDNs": [],       // allowed certificated issuer DNs
    "regexSubjectDNs": [], // allowed certificated DNs matching regex
    "regexIssuerDNs": [],  // allowed certificated issuer DNs matching regex
  }
}
```



## Default configuration

```json
{
  "HasClientCertMatchingValidator" : {
    "serialNumbers" : [ ],
    "subjectDNs" : [ ],
    "issuerDNs" : [ ],
    "regexSubjectDNs" : [ ],
    "regexIssuerDNs" : [ ]
  }
}
```





@@@

