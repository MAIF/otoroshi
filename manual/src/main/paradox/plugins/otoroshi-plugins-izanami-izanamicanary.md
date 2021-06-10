
# Izanami Canary Campaign

## Infos

* plugin type: `transformer`
* configuration root: `IzanamiCanary`

## Description

This plugin allow you to perform canary testing based on an izanami experiment campaign (A/B test).

This plugin can accept the following configuration

```json
{
  "IzanamiCanary" : {
    "experimentId" : "foo:bar:qix",
    "configId" : "foo:bar:qix:config",
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000,
    "mtls" : {
      "certs" : [ ],
      "trustedCerts" : [ ],
      "mtls" : false,
      "loose" : false,
      "trustAll" : false
    }
  }
}
```



## Default configuration

```json
{
  "IzanamiCanary" : {
    "experimentId" : "foo:bar:qix",
    "configId" : "foo:bar:qix:config",
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000,
    "mtls" : {
      "certs" : [ ],
      "trustedCerts" : [ ],
      "mtls" : false,
      "loose" : false,
      "trustAll" : false
    }
  }
}
```





