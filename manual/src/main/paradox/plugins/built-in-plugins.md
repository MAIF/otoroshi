# Otoroshi built-in plugins

Otoroshi provides some plugins out of the box. Here is the available plugins with their documentation and reference configuration


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.accesslog.AccessLog }

## Access log (CLF)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `AccessLog`

### Description

With this plugin, any access to a service will be logged in CLF format.

Log format is the following:

`"$service" $clientAddress - "$userId" [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referer" "$userAgent" $http $duration $errorMsg`

The plugin accepts the following configuration

```json
{
  "AccessLog": {
    "enabled": true,
    "statuses": [], // list of status to enable logs, if none, log everything
    "paths": [], // list of paths to enable logs, if none, log everything
    "methods": [], // list of http methods to enable logs, if none, log everything
    "identities": [] // list of identities to enable logs, if none, log everything
  }
}
```



### Default configuration

```json
{
  "AccessLog" : {
    "enabled" : true,
    "statuses" : [ ],
    "paths" : [ ],
    "methods" : [ ],
    "identities" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.accesslog.AccessLogJson }

## Access log (JSON)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `AccessLog`

### Description

With this plugin, any access to a service will be logged in json format.

The plugin accepts the following configuration

```json
{
  "AccessLog": {
    "enabled": true,
    "statuses": [], // list of status to enable logs, if none, log everything
    "paths": [], // list of paths to enable logs, if none, log everything
    "methods": [], // list of http methods to enable logs, if none, log everything
    "identities": [] // list of identities to enable logs, if none, log everything
  }
}
```



### Default configuration

```json
{
  "AccessLog" : {
    "enabled" : true,
    "statuses" : [ ],
    "paths" : [ ],
    "methods" : [ ],
    "identities" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.accesslog.KafkaAccessLog }

## Kafka access log

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `KafkaAccessLog`

### Description

With this plugin, any access to a service will be logged as an event in a kafka topic.

The plugin accepts the following configuration

```json
{
  "KafkaAccessLog": {
    "enabled": true,
    "topic": "otoroshi-access-log",
    "statuses": [], // list of status to enable logs, if none, log everything
    "paths": [], // list of paths to enable logs, if none, log everything
    "methods": [], // list of http methods to enable logs, if none, log everything
    "identities": [] // list of identities to enable logs, if none, log everything
  }
}
```



### Default configuration

```json
{
  "KafkaAccessLog" : {
    "enabled" : true,
    "topic" : "otoroshi-access-log",
    "statuses" : [ ],
    "paths" : [ ],
    "methods" : [ ],
    "identities" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.authcallers.BasicAuthCaller }

## Basic Auth. caller

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `BasicAuthCaller`

### Description

This plugin can be used to call api that are authenticated using basic auth.

This plugin accepts the following configuration

{
  "username" : "the_username",
  "password" : "the_password",
  "headerName" : "Authorization",
  "headerValueFormat" : "Basic %s"
}



### Default configuration

```json
{
  "username" : "the_username",
  "password" : "the_password",
  "headerName" : "Authorization",
  "headerValueFormat" : "Basic %s"
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.authcallers.OAuth2Caller }

## OAuth2 caller

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `OAuth2Caller`

### Description

This plugin can be used to call api that are authenticated using OAuth2 client_credential/password flow.
Do not forget to enable client retry to handle token generation on expire.

This plugin accepts the following configuration

{
  "kind" : "the oauth2 flow, can be 'client_credentials' or 'password'",
  "url" : "https://127.0.0.1:8080/oauth/token",
  "method" : "POST",
  "headerName" : "Authorization",
  "headerValueFormat" : "Bearer %s",
  "jsonPayload" : false,
  "clientId" : "the client_id",
  "clientSecret" : "the client_secret",
  "scope" : "an optional scope",
  "audience" : "an optional audience",
  "user" : "an optional username if using password flow",
  "password" : "an optional password if using password flow",
  "cacheTokenSeconds" : "the number of second to wait before asking for a new token",
  "tlsConfig" : "an optional TLS settings object"
}



### Default configuration

```json
{
  "kind" : "the oauth2 flow, can be 'client_credentials' or 'password'",
  "url" : "https://127.0.0.1:8080/oauth/token",
  "method" : "POST",
  "headerName" : "Authorization",
  "headerValueFormat" : "Bearer %s",
  "jsonPayload" : false,
  "clientId" : "the client_id",
  "clientSecret" : "the client_secret",
  "scope" : "an optional scope",
  "audience" : "an optional audience",
  "user" : "an optional username if using password flow",
  "password" : "an optional password if using password flow",
  "cacheTokenSeconds" : "the number of second to wait before asking for a new token",
  "tlsConfig" : "an optional TLS settings object"
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.cache.ResponseCache }

## Response Cache

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `ResponseCache`

### Description

This plugin can cache responses from target services in the otoroshi datasstore
It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.

This plugin can accept the following configuration

```json
{
  "ResponseCache": {
    "enabled": true, // enabled cache
    "ttl": 300000,  // store it for some times (5 minutes by default)
    "maxSize": 5242880, // max body size (body will be cut after that)
    "autoClean": true, // cleanup older keys when all bigger than maxSize
    "filter": { // cache only for some status, method and paths
      "statuses": [],
      "methods": [],
      "paths": [],
      "not": {
        "statuses": [],
        "methods": [],
        "paths": []
      }
    }
  }
}
```



### Default configuration

```json
{
  "ResponseCache" : {
    "enabled" : true,
    "ttl" : 3600000,
    "maxSize" : 52428800,
    "autoClean" : true,
    "filter" : {
      "statuses" : [ ],
      "methods" : [ ],
      "paths" : [ ],
      "not" : {
        "statuses" : [ ],
        "methods" : [ ],
        "paths" : [ ]
      }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.clientcert.ClientCertChainHeader }

## Client certificate header

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `ClientCertChain`

### Description

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



### Default configuration

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


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.defer.DeferPlugin }

## Defer Responses

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `DeferPlugin`

### Description

This plugin will expect a `X-Defer` header or a `defer` query param and defer the response according to the value in milliseconds.
This plugin is some kind of inside joke as one a our customer ask us to make slower apis.

This plugin can accept the following configuration

```json
{
  "DeferPlugin": {
    "defaultDefer": 0 // default defer in millis
  }
}
```



### Default configuration

```json
{
  "DeferPlugin" : {
    "defaultDefer" : 0
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.discovery.DiscoverySelfRegistrationTransformer }

## Self registration endpoints (service discovery)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `DiscoverySelfRegistration`

### Description

This plugin add support for self registration endpoint on a specific service.

This plugin accepts the following configuration:



### Default configuration

```json
{
  "DiscoverySelfRegistration" : {
    "hosts" : [ ],
    "targetTemplate" : { },
    "registrationTtl" : 60000
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.envoy.EnvoyControlPlane }

## Envoy Control Plane (experimental)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `EnvoyControlPlane`

### Description

This plugin will expose the otoroshi state to envoy instances using the xDS V3 API`.

Right now, all the features of otoroshi cannot be exposed as is through Envoy.



### Default configuration

```json
{
  "EnvoyControlPlane" : {
    "enabled" : true
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.geoloc.GeolocationInfoEndpoint }

## Geolocation endpoint

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: ``none``

### Description

This plugin will expose current geolocation informations on the following endpoint.

`/.well-known/otoroshi/plugins/geolocation`







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.geoloc.GeolocationInfoHeader }

## Geolocation header

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `GeolocationInfoHeader`

### Description

This plugin will send informations extracted by the Geolocation details extractor to the target service in a header.

This plugin can accept the following configuration

```json
{
  "GeolocationInfoHeader": {
    "headerName": "X-Geolocation-Info" // header in which info will be sent
  }
}
```



### Default configuration

```json
{
  "GeolocationInfoHeader" : {
    "headerName" : "X-Geolocation-Info"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.hmac.HMACCallerPlugin }

## HMAC caller plugin

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `HMACCallerPlugin`

### Description

This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
 The signature string will always the content of the header list listed in the plugin configuration.



### Default configuration

```json
{
  "HMACCallerPlugin" : {
    "secret" : "my-defaut-secret",
    "algo" : "HMAC-SHA512"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.izanami.IzanamiCanary }

## Izanami Canary Campaign

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `IzanamiCanary`

### Description

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



### Default configuration

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





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.izanami.IzanamiProxy }

## Izanami APIs Proxy

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `IzanamiProxy`

### Description

This plugin exposes routes to proxy Izanami configuration and features tree APIs.

This plugin can accept the following configuration

```json
{
  "IzanamiProxy" : {
    "path" : "/api/izanami",
    "featurePattern" : "*",
    "configPattern" : "*",
    "autoContext" : false,
    "featuresEnabled" : true,
    "featuresWithContextEnabled" : true,
    "configurationEnabled" : false,
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000
  }
}
```



### Default configuration

```json
{
  "IzanamiProxy" : {
    "path" : "/api/izanami",
    "featurePattern" : "*",
    "configPattern" : "*",
    "autoContext" : false,
    "featuresEnabled" : true,
    "featuresWithContextEnabled" : true,
    "configurationEnabled" : false,
    "izanamiUrl" : "https://izanami.foo.bar",
    "izanamiClientId" : "client",
    "izanamiClientSecret" : "secret",
    "timeout" : 5000
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.jq.JqBodyTransformer }

## JQ bodies transformer

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `JqBodyTransformer`

### Description

This plugin let you transform JSON bodies (in requests and responses) using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).

Some JSON variables are accessible by default :

 * `$url`: the request url
 * `$path`: the request path
 * `$domain`: the request domain
 * `$method`: the request method
 * `$headers`: the current request headers (with name in lowercase)
 * `$queryParams`: the current request query params
 * `$otoToken`: the otoroshi protocol token (if one)
 * `$inToken`: the first matched JWT token as is (from verifiers, if one)
 * `$token`: the first matched JWT token as is (from verifiers, if one)
 * `$user`: the current user (if one)
 * `$apikey`: the current apikey (if one)

This plugin can accept the following configuration

```json
{
  "JqBodyTransformer" : {
    "request" : {
      "filter" : ".",
      "included" : [ ],
      "excluded" : [ ]
    },
    "response" : {
      "filter" : ".",
      "included" : [ ],
      "excluded" : [ ]
    }
  }
}
```



### Default configuration

```json
{
  "JqBodyTransformer" : {
    "request" : {
      "filter" : ".",
      "included" : [ ],
      "excluded" : [ ]
    },
    "response" : {
      "filter" : ".",
      "included" : [ ],
      "excluded" : [ ]
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.jsoup.HtmlPatcher }

## Html Patcher

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `HtmlPatcher`

### Description

This plugin can inject elements in html pages (in the body or in the head) returned by the service



### Default configuration

```json
{
  "HtmlPatcher" : {
    "appendHead" : [ ],
    "appendBody" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.log4j.Log4ShellFilter }

## Log4Shell mitigation plugin

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `Log4ShellFilter`

### Description

This plugin try to detect Log4Shell attacks in request and block them.

This plugin can accept the following configuration

```javascript
{
  "Log4ShellFilter": {
    "status": 200, // the status send back when an attack expression is found
    "body": "", // the body send back when an attack expression is found
    "parseBody": false // enables request body parsing to find attack expression
  }
}
```



### Default configuration

```json
{
  "Log4ShellFilter" : {
    "status" : 200,
    "body" : "",
    "parseBody" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.loggers.BodyLogger }

## Body logger

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `BodyLogger`

### Description

This plugin can log body present in request and response. It can just logs it, store in in the redis store with a ttl and send it to analytics.
It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.

This plugin can accept the following configuration

```json
{
  "BodyLogger": {
    "enabled": true, // enabled logging
    "log": true, // just log it
    "store": false, // store bodies in datastore
    "ttl": 300000,  // store it for some times (5 minutes by default)
    "sendToAnalytics": false, // send bodies to analytics
    "maxSize": 5242880, // max body size (body will be cut after that)
    "password": "password", // password for the ui, if none, it's public
    "filter": { // log only for some status, method and paths
      "statuses": [],
      "methods": [],
      "paths": [],
      "not": {
        "statuses": [],
        "methods": [],
        "paths": []
      }
    }
  }
}
```



### Default configuration

```json
{
  "BodyLogger" : {
    "enabled" : true,
    "log" : true,
    "store" : false,
    "ttl" : 300000,
    "sendToAnalytics" : false,
    "maxSize" : 5242880,
    "password" : "password",
    "filter" : {
      "statuses" : [ ],
      "methods" : [ ],
      "paths" : [ ],
      "not" : {
        "statuses" : [ ],
        "methods" : [ ],
        "paths" : [ ]
      }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.metrics.PrometheusServiceMetrics }

## Prometheus Service Metrics

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `PrometheusServiceMetrics`

### Description

This plugin collects service metrics and can be used with the `Prometheus Endpoint` (in the Danger Zone) plugin to expose those metrics

This plugin can accept the following configuration

```json
{
  "PrometheusServiceMetrics": {
    "includeUri": false // include http uri in metrics. WARNING this could impliess performance issues, use at your own risks
  }
}
```



### Default configuration

```json
{
  "PrometheusServiceMetrics" : {
    "includeUri" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.metrics.ServiceMetrics }

## Service Metrics

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `ServiceMetrics`

### Description

This plugin expose service metrics in Otoroshi global metrics or on a special URL of the service `/.well-known/otoroshi/metrics`.
Metrics are exposed in json or prometheus format depending on the accept header. You can protect it with an access key defined in the configuration

This plugin can accept the following configuration

```json
{
  "ServiceMetrics": {
    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
    "accessKeyQuery": "access_key"
  }
}
```



### Default configuration

```json
{
  "ServiceMetrics" : {
    "accessKeyValue" : "${config.app.health.accessKey}",
    "accessKeyQuery" : "access_key"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.mirror.MirroringPlugin }

## Mirroring plugin

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `MirroringPlugin`

### Description

This plugin will mirror every request to other targets

This plugin can accept the following configuration

```json
{
  "MirroringPlugin": {
    "enabled": true, // enabled mirroring
    "to": "https://foo.bar.dev", // the url of the service to mirror
  }
}
```



### Default configuration

```json
{
  "MirroringPlugin" : {
    "enabled" : true,
    "to" : "https://foo.bar.dev",
    "captureResponse" : false,
    "generateEvents" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.oauth1.OAuth1CallerPlugin }

## OAuth1 caller

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `OAuth1Caller`

### Description

This plugin can be used to call api that are authenticated using OAuth1.
 Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
 or via the configuration of this plugin.



### Default configuration

```json
{
  "OAuth1Caller" : {
    "algo" : "HmacSHA512"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.oidc.OIDCHeaders }

## OIDC headers

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `OIDCHeaders`

### Description

This plugin injects headers containing tokens and profile from current OIDC provider.



### Default configuration

```json
{
  "OIDCHeaders" : {
    "profile" : {
      "send" : true,
      "headerName" : "X-OIDC-User"
    },
    "idtoken" : {
      "send" : false,
      "name" : "id_token",
      "headerName" : "X-OIDC-Id-Token",
      "jwt" : true
    },
    "accesstoken" : {
      "send" : false,
      "name" : "access_token",
      "headerName" : "X-OIDC-Access-Token",
      "jwt" : true
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.security.SecurityTxt }

## Security Txt

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `SecurityTxt`

### Description

This plugin exposes a special route `/.well-known/security.txt` as proposed at [https://securitytxt.org/](https://securitytxt.org/).

This plugin can accept the following configuration

```json
{
  "SecurityTxt": {
    "Contact": "contact@foo.bar", // mandatory, a link or e-mail address for people to contact you about security issues
    "Encryption": "http://url-to-public-key", // optional, a link to a key which security researchers should use to securely talk to you
    "Acknowledgments": "http://url", // optional, a link to a web page where you say thank you to security researchers who have helped you
    "Preferred-Languages": "en, fr, es", // optional
    "Policy": "http://url", // optional, a link to a policy detailing what security researchers should do when searching for or reporting security issues
    "Hiring": "http://url", // optional, a link to any security-related job openings in your organisation
  }
}
```



### Default configuration

```json
{
  "SecurityTxt" : {
    "Contact" : "contact@foo.bar",
    "Encryption" : "https://...",
    "Acknowledgments" : "https://...",
    "Preferred-Languages" : "en, fr",
    "Policy" : "https://...",
    "Hiring" : "https://..."
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.static.StaticResponse }

## Static Response

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `StaticResponse`

### Description

This plugin returns a static response for any request



### Default configuration

```json
{
  "StaticResponse" : {
    "status" : 200,
    "headers" : {
      "Content-Type" : "application/json"
    },
    "body" : "{\"message\":\"hello world!\"}",
    "bodyBase64" : null
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.useragent.UserAgentInfoEndpoint }

## User-Agent endpoint

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: ``none``

### Description

This plugin will expose current user-agent informations on the following endpoint.

`/.well-known/otoroshi/plugins/user-agent`







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.useragent.UserAgentInfoHeader }

## User-Agent header

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `UserAgentInfoHeader`

### Description

This plugin will sent informations extracted by the User-Agent details extractor to the target service in a header.

This plugin can accept the following configuration

```json
{
  "UserAgentInfoHeader": {
    "headerName": "X-User-Agent-Info" // header in which info will be sent
  }
}
```



### Default configuration

```json
{
  "UserAgentInfoHeader" : {
    "headerName" : "X-User-Agent-Info"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-transformer #otoroshi.plugins.workflow.WorkflowEndpoint }

## Workflow endpoint

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `transformer`
* configuration root: `WorkflowEndpoint`

### Description

This plugin runs a workflow and return the response



### Default configuration

```json
{
  "WorkflowEndpoint" : {
    "workflow" : { }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.biscuit.BiscuitValidator }

## Biscuit token validator

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: ``none``

### Description

This plugin validates a Biscuit token.



### Default configuration

```json
{
  "publicKey" : "xxxxxx",
  "checks" : [ ],
  "facts" : [ ],
  "resources" : [ ],
  "rules" : [ ],
  "revocation_ids" : [ ],
  "enforce" : false,
  "extractor" : {
    "type" : "header",
    "name" : "Authorization"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.clientcert.HasClientCertMatchingApikeyValidator }

## Client Certificate + Api Key only

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: ``none``

### Description

Check if a client certificate is present in the request and that the apikey used matches the client certificate.
You can set the client cert. DN in an apikey metadata named `allowed-client-cert-dn`







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.clientcert.HasClientCertMatchingHttpValidator }

## Client certificate matching (over http)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `HasClientCertMatchingHttpValidator`

### Description

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



### Default configuration

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


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.clientcert.HasClientCertMatchingValidator }

## Client certificate matching

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `HasClientCertMatchingValidator`

### Description

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



### Default configuration

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


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.clientcert.HasClientCertValidator }

## Client Certificate Only

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: ``none``

### Description

Check if a client certificate is present in the request







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.external.ExternalHttpValidator }

## External Http Validator

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `ExternalHttpValidator`

### Description

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



### Default configuration

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





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.hmac.HMACValidator }

## HMAC access validator

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `HMACAccessValidator`

### Description

This plugin can be used to check if a HMAC signature is present and valid in Authorization header.



### Default configuration

```json
{
  "HMACAccessValidator" : {
    "secret" : ""
  }
}
```



### Documentation


 The HMAC signature needs to be set on the `Authorization` or `Proxy-Authorization` header.
 The format of this header should be : `hmac algorithm="<ALGORITHM>", headers="<HEADER>", signature="<SIGNATURE>"`
 As example, a simple nodeJS call with the expected header
 ```js
 const crypto = require('crypto');
 const fetch = require('node-fetch');

 const date = new Date()
 const secret = "my-secret" // equal to the api key secret by default

 const algo = "sha512"
 const signature = crypto.createHmac(algo, secret)
    .update(date.getTime().toString())
    .digest('base64');

 fetch('http://myservice.oto.tools:9999/api/test', {
    headers: {
        "Otoroshi-Client-Id": "my-id",
        "Otoroshi-Client-Secret": "my-secret",
        "Date": date.getTime().toString(),
        "Authorization": `hmac algorithm="hmac-${algo}", headers="Date", signature="${signature}"`,
        "Accept": "application/json"
    }
 })
    .then(r => r.json())
    .then(console.log)
 ```
 In this example, we have an Otoroshi service deployed on http://myservice.oto.tools:9999/api/test, protected by api keys.
 The secret used is the secret of the api key (by default, but you can change it and define a secret on the plugin configuration).
 We send the base64 encoded date of the day, signed by the secret, in the Authorization header. We specify the headers signed and the type of algorithm used.
 You can sign more than one header but you have to list them in the headers fields (each one separate by a space, example : headers="Date KeyId").
 The algorithm used can be HMAC-SHA1, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512.




@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.oidc.OIDCAccessTokenValidator }

## OIDC access_token validator

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `OIDCAccessTokenValidator`

### Description

This plugin will use the third party apikey configuration and apply it while keeping the apikey mecanism of otoroshi.
Use it to combine apikey validation and OIDC access_token validation.

This plugin can accept the following configuration

```json
{
  "OIDCAccessTokenValidator": {
    "enabled": true,
    "atLeastOne": false,
    // config is optional and can be either an object config or an array of objects
    "config": {
  "enabled" : true,
  "quotasEnabled" : true,
  "uniqueApiKey" : false,
  "type" : "OIDC",
  "oidcConfigRef" : "some-oidc-auth-module-id",
  "localVerificationOnly" : false,
  "mode" : "Tmp",
  "ttl" : 0,
  "headerName" : "Authorization",
  "throttlingQuota" : 100,
  "dailyQuota" : 10000000,
  "monthlyQuota" : 10000000,
  "excludedPatterns" : [ ],
  "scopes" : [ ],
  "rolesPath" : [ ],
  "roles" : [ ]
}
  }
}
```



### Default configuration

```json
{
  "OIDCAccessTokenValidator" : {
    "enabled" : true,
    "atLeastOne" : false,
    "config" : {
      "enabled" : true,
      "quotasEnabled" : true,
      "uniqueApiKey" : false,
      "type" : "OIDC",
      "oidcConfigRef" : "some-oidc-auth-module-id",
      "localVerificationOnly" : false,
      "mode" : "Tmp",
      "ttl" : 0,
      "headerName" : "Authorization",
      "throttlingQuota" : 100,
      "dailyQuota" : 10000000,
      "monthlyQuota" : 10000000,
      "excludedPatterns" : [ ],
      "scopes" : [ ],
      "rolesPath" : [ ],
      "roles" : [ ]
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.quotas.InstanceQuotas }

## Instance quotas

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `InstanceQuotas`

### Description

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



### Default configuration

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





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.quotas.ServiceQuotas }

## Public quotas

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `ServiceQuotas`

### Description

This plugin will enforce public quotas on the current service







### Default configuration

```json
{
  "ServiceQuotas" : {
    "throttlingQuota" : 100,
    "dailyQuota" : 10000000,
    "monthlyQuota" : 10000000
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-validator #otoroshi.plugins.users.HasAllowedUsersValidator }

## Allowed users only

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `validator`
* configuration root: `HasAllowedUsersValidator`

### Description

This plugin only let allowed users pass

This plugin can accept the following configuration

```json
{
  "HasAllowedUsersValidator": {
    "usernames": [],   // allowed usernames
    "emails": [],      // allowed user email addresses
    "emailDomains": [], // allowed user email domains
    "metadataMatch": [], // json path expressions to match against user metadata. passes if one match
    "metadataNotMatch": [], // json path expressions to match against user metadata. passes if none match
    "profileMatch": [], // json path expressions to match against user profile. passes if one match
    "profileNotMatch": [], // json path expressions to match against user profile. passes if none match
  }
}
```



### Default configuration

```json
{
  "HasAllowedUsersValidator" : {
    "usernames" : [ ],
    "emails" : [ ],
    "emailDomains" : [ ],
    "metadataMatch" : [ ],
    "metadataNotMatch" : [ ],
    "profileMatch" : [ ],
    "profileNotMatch" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.apikeys.ApikeyAuthModule }

## Apikey auth module

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `ApikeyAuthModule`

### Description

This plugin adds basic auth on service where credentials are valid apikeys on the current service.



### Default configuration

```json
{
  "ApikeyAuthModule" : {
    "realm" : "apikey-auth-module-realm",
    "noneTagIn" : [ ],
    "oneTagIn" : [ ],
    "allTagsIn" : [ ],
    "noneMetaIn" : [ ],
    "oneMetaIn" : [ ],
    "allMetaIn" : [ ],
    "noneMetaKeysIn" : [ ],
    "oneMetaKeyIn" : [ ],
    "allMetaKeysIn" : [ ]
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.apikeys.CertificateAsApikey }

## Client certificate as apikey

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `CertificateAsApikey`

### Description

This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage



### Default configuration

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





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.apikeys.ClientCredentialFlowExtractor }

## Client Credential Flow ApiKey extractor

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: ``none``

### Description

This plugin can extract an apikey from an opaque access_token generate by the `ClientCredentialFlow` plugin







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.biscuit.BiscuitExtractor }

## Apikey from Biscuit token extractor

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: ``none``

### Description

This plugin extract an from a Biscuit token where the biscuit has an #authority fact 'client_id' containing
apikey client_id and an #authority fact 'client_sign' that is the HMAC256 signature of the apikey client_id with the apikey client_secret



### Default configuration

```json
{
  "publicKey" : "xxxxxx",
  "checks" : [ ],
  "facts" : [ ],
  "resources" : [ ],
  "rules" : [ ],
  "revocation_ids" : [ ],
  "enforce" : false,
  "extractor" : {
    "type" : "header",
    "name" : "Authorization"
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.discovery.DiscoveryTargetsSelector }

## Service discovery target selector (service discovery)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `DiscoverySelfRegistration`

### Description

This plugin select a target in the pool of discovered targets for this service.
Use in combination with either `DiscoverySelfRegistrationSink` or `DiscoverySelfRegistrationTransformer` to make it work using the `self registration` pattern.
Or use an implementation of `DiscoveryJob` for the `third party registration pattern`.

This plugin accepts the following configuration:



### Default configuration

```json
{
  "DiscoverySelfRegistration" : {
    "hosts" : [ ],
    "targetTemplate" : { },
    "registrationTtl" : 60000
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.geoloc.IpStackGeolocationInfoExtractor }

## Geolocation details extractor (using IpStack api)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `GeolocationInfo`

### Description

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



### Default configuration

```json
{
  "GeolocationInfo" : {
    "apikey" : "xxxxxxx",
    "timeout" : 2000,
    "log" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.geoloc.MaxMindGeolocationInfoExtractor }

## Geolocation details extractor (using Maxmind db)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `GeolocationInfo`

### Description

This plugin extract geolocation informations from ip address using the [Maxmind dbs](https://www.maxmind.com/en/geoip2-databases).
The informations are store in plugins attrs for other plugins to use

This plugin can accept the following configuration

```json
{
  "GeolocationInfo": {
    "path": "/foo/bar/cities.mmdb", // file path, can be "global"
    "log": false // will log geolocation details
  }
}
```



### Default configuration

```json
{
  "GeolocationInfo" : {
    "path" : "global",
    "log" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.jwt.JwtUserExtractor }

## Jwt user extractor

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `JwtUserExtractor`

### Description

This plugin extract a user from a JWT token



### Default configuration

```json
{
  "JwtUserExtractor" : {
    "verifier" : "",
    "strict" : true,
    "namePath" : "name",
    "emailPath" : "email",
    "metaPath" : null
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.oidc.OIDCAccessTokenAsApikey }

## OIDC access_token as apikey

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `OIDCAccessTokenAsApikey`

### Description

This plugin will use the third party apikey configuration to generate an apikey

This plugin can accept the following configuration

```json
{
  "OIDCAccessTokenValidator": {
    "enabled": true,
    "atLeastOne": false,
    // config is optional and can be either an object config or an array of objects
    "config": {
  "enabled" : true,
  "quotasEnabled" : true,
  "uniqueApiKey" : false,
  "type" : "OIDC",
  "oidcConfigRef" : "some-oidc-auth-module-id",
  "localVerificationOnly" : false,
  "mode" : "Tmp",
  "ttl" : 0,
  "headerName" : "Authorization",
  "throttlingQuota" : 100,
  "dailyQuota" : 10000000,
  "monthlyQuota" : 10000000,
  "excludedPatterns" : [ ],
  "scopes" : [ ],
  "rolesPath" : [ ],
  "roles" : [ ]
}
  }
}
```



### Default configuration

```json
{
  "OIDCAccessTokenAsApikey" : {
    "enabled" : true,
    "atLeastOne" : false,
    "config" : {
      "enabled" : true,
      "quotasEnabled" : true,
      "uniqueApiKey" : false,
      "type" : "OIDC",
      "oidcConfigRef" : "some-oidc-auth-module-id",
      "localVerificationOnly" : false,
      "mode" : "Tmp",
      "ttl" : 0,
      "headerName" : "Authorization",
      "throttlingQuota" : 100,
      "dailyQuota" : 10000000,
      "monthlyQuota" : 10000000,
      "excludedPatterns" : [ ],
      "scopes" : [ ],
      "rolesPath" : [ ],
      "roles" : [ ]
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-preroute #otoroshi.plugins.useragent.UserAgentExtractor }

## User-Agent details extractor

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `preroute`
* configuration root: `UserAgentInfo`

### Description

This plugin extract informations from User-Agent header such as browsser version, OS version, etc.
The informations are store in plugins attrs for other plugins to use

This plugin can accept the following configuration

```json
{
  "UserAgentInfo": {
    "log": false // will log user-agent details
  }
}
```



### Default configuration

```json
{
  "UserAgentInfo" : {
    "log" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-sink #otoroshi.plugins.apikeys.ClientCredentialService }

## Client Credential Service

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `sink`
* configuration root: `ClientCredentialService`

### Description

This plugin add an an oauth client credentials service (`https://unhandleddomain/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret.

```json
{
  "ClientCredentialService" : {
    "domain" : "*",
    "expiration" : 3600000,
    "defaultKeyPair" : "otoroshi-jwt-signing",
    "secure" : true
  }
}
```



### Default configuration

```json
{
  "ClientCredentialService" : {
    "domain" : "*",
    "expiration" : 3600000,
    "defaultKeyPair" : "otoroshi-jwt-signing",
    "secure" : true
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-sink #otoroshi.plugins.discovery.DiscoverySelfRegistrationSink }

## Global self registration endpoints (service discovery)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `sink`
* configuration root: `DiscoverySelfRegistration`

### Description

This plugin add support for self registration endpoint on specific hostnames.

This plugin accepts the following configuration:



### Default configuration

```json
{
  "DiscoverySelfRegistration" : {
    "hosts" : [ ],
    "targetTemplate" : { },
    "registrationTtl" : 60000
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-sink #otoroshi.plugins.jobs.kubernetes.KubernetesAdmissionWebhookCRDValidator }

## Kubernetes admission validator webhook

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `sink`
* configuration root: ``none``

### Description

This plugin exposes a webhook to kubernetes to handle manifests validation







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-sink #otoroshi.plugins.jobs.kubernetes.KubernetesAdmissionWebhookSidecarInjector }

## Kubernetes sidecar injector webhook

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `sink`
* configuration root: ``none``

### Description

This plugin exposes a webhook to kubernetes to inject otoroshi-sidecar in pods







@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-sink #otoroshi.plugins.metrics.PrometheusEndpoint }

## Prometheus Endpoint

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `sink`
* configuration root: `PrometheusEndpoint`

### Description

This plugin exposes metrics collected by `Prometheus Service Metrics` on a `/prometheus` endpoint.
You can protect it with an access key defined in the configuration

This plugin can accept the following configuration

```json
{
  "PrometheusEndpoint": {
    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
    "accessKeyQuery": "access_key",
    "includeMetrics": false
  }
}
```



### Default configuration

```json
{
  "PrometheusEndpoint" : {
    "accessKeyValue" : "${config.app.health.accessKey}",
    "accessKeyQuery" : "access_key",
    "includeMetrics" : false
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-job #otoroshi.plugins.jobs.kubernetes.KubernetesIngressControllerJob }

## Kubernetes Ingress Controller

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `job`
* configuration root: `KubernetesConfig`

### Description

This plugin enables Otoroshi as an Ingress Controller

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```



### Default configuration

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-job #otoroshi.plugins.jobs.kubernetes.KubernetesOtoroshiCRDsControllerJob }

## Kubernetes Otoroshi CRDs Controller

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `job`
* configuration root: `KubernetesConfig`

### Description

This plugin enables Otoroshi CRDs Controller

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```



### Default configuration

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-job #otoroshi.plugins.jobs.kubernetes.KubernetesToOtoroshiCertSyncJob }

## Kubernetes to Otoroshi certs. synchronizer

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `job`
* configuration root: `KubernetesConfig`

### Description

This plugin syncs. TLS secrets from Kubernetes to Otoroshi

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```



### Default configuration

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-job #otoroshi.plugins.jobs.kubernetes.OtoroshiToKubernetesCertSyncJob }

## Otoroshi certs. to Kubernetes secrets synchronizer

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `job`
* configuration root: `KubernetesConfig`

### Description

This plugin syncs. Otoroshi certs to Kubernetes TLS secrets

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```



### Default configuration

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "useProxyState" : false,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-job #otoroshi.plugins.workflow.WorkflowJob }

## Workflow job

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `job`
* configuration root: `WorkflowJob`

### Description

Periodically run a custom workflow



### Default configuration

```json
{
  "WorkflowJob" : {
    "input" : {
      "namespace" : "otoroshi",
      "service" : "otoroshi-dns"
    },
    "intervalMillis" : "60000",
    "workflow" : {
      "name" : "some-workflow",
      "description" : "a nice workflow",
      "tasks" : [ {
        "name" : "call-dns",
        "type" : "http",
        "request" : {
          "method" : "PATCH",
          "url" : "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/v1/namespaces/${input.namespace}/services/${input.service}",
          "headers" : {
            "accept" : "application/json",
            "content-type" : "application/json",
            "authorization" : "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
          },
          "tls" : {
            "mtls" : true,
            "trustAll" : true
          },
          "body" : [ {
            "op" : "replace",
            "path" : "/spec/selector",
            "value" : {
              "app" : "otoroshi",
              "component" : "dns"
            }
          } ]
        },
        "success" : {
          "statuses" : [ 200 ]
        }
      } ]
    }
  }
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-request-handler #otoroshi.next.proxy.ProxyEngine }

## Otoroshi next proxy engine (experimental)

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `request-handler`
* configuration root: `NextGenProxyEngine`

### Description

This plugin holds the next generation otoroshi proxy engine implementation. This engine is **experimental** and may not work as expected !

You can active this plugin only on some domain names so you can easily A/B test the new engine.
The new proxy engine is designed to be more reactive and more efficient generally.
It is also designed to be very efficient on path routing where it wasn't the old engines strong suit.

The idea is to only rely on plugins to work and avoid losing time with features that are not used in service descriptors.
An automated conversion happens for every service descriptor. If the exposed domain is handled by this plugin, it will be served by this plugin.
This plugin introduces new entities that will replace (one day maybe) service descriptors:

 - `routes`: a unique routing rule based on hostname, path, method and headers that will execute a bunch of plugins
 - `services`: multiple routing rules based on hostname, path, method and headers that will execute the same list of plugins
 - `targets`: how to contact a backend either by using a domain name or an ip address, supports mtls
 - `backends`: a list of targets to contact a backend

as an example, let say you want to use the new engine on your service exposed on `api.foo.bar/api`.
To do that, just add the plugin in the `global plugins` section of the danger zone, inject the default configuration,
enabled it and in `domains` add the value `api.foo.bar` (it is possible to use `*.foo.bar` if that's what you want to do).
The next time a request hits the `api.foo.bar` domain, the new engine will handle it instead of the old one.



### Default configuration

```json
{
  "enabled" : true,
  "domains" : [ "*-next-gen.oto.tools" ],
  "deny_domains" : [ ],
  "reporting" : true,
  "merge_sync_steps" : true,
  "export_reporting" : false,
  "apply_legacy_checks" : true,
  "debug" : false,
  "capture" : false,
  "captureMaxEntitySize" : 4194304,
  "debug_headers" : false,
  "routing_strategy" : "tree"
}
```





@@@


@@@ div { .plugin .plugin-hidden .plugin-kind-request-handler #otoroshi.script.ForwardTrafficHandler }

## Forward traffic

<img class="plugin-logo plugin-hidden" src=""></img>

### Infos

* plugin type: `request-handler`
* configuration root: `ForwardTrafficHandler`

### Description

This plugin can be use to perform a raw traffic forward to an URL without passing through otoroshi routing



### Default configuration

```json
{
  "ForwardTrafficHandler" : {
    "domains" : {
      "my.domain.tld" : {
        "baseUrl" : "https://my.otherdomain.tld",
        "secret" : "jwt signing secret",
        "service" : {
          "id" : "service id for analytics",
          "name" : "service name for analytics"
        }
      }
    }
  }
}
```





@@@




