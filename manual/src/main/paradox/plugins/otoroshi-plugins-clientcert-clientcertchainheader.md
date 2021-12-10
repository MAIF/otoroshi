
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Client certificate header

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `ClientCertChain`

## Description

This plugin pass client certificate informations to the target in headers.

This plugin can accept the following configuration

```json
{
  "ClientCertChain": {
    "pem": { // send client cert as PEM format in a header
      "send": false,
      "header": "X-Client-Cert-Pem"
    },
    "dns": { // send JSON array of DNs in a header
      "send": false,
      "header": "X-Client-Cert-DNs"
    },
    "chain": { // send JSON representation of client cert chain in a header
      "send": true,
      "header": "X-Client-Cert-Chain"
    },
    "claims": { // pass JSON representation of client cert chain in the otoroshi JWT token
      "send": false,
      "name": "clientCertChain"
    }
  }
}
```



## Default configuration

```json
{
  "ClientCertChain" : {
    "pem" : {
      "send" : false,
      "header" : "X-Client-Cert-Pem"
    },
    "dns" : {
      "send" : false,
      "header" : "X-Client-Cert-DNs"
    },
    "chain" : {
      "send" : true,
      "header" : "X-Client-Cert-Chain"
    },
    "claims" : {
      "send" : false,
      "name" : "clientCertChain"
    }
  }
}
```





@@@

