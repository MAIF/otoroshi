# Built-in plugins

Otoroshi next provides some plugins out of the box. Here is the available plugins with their documentation and reference configuration


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.AdditionalHeadersIn }

## Additional headers in

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.AdditionalHeadersIn`

### Description

This plugin adds headers in the incoming otoroshi request



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.AdditionalHeadersOut }

## Additional headers out

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.AdditionalHeadersOut`

### Description

This plugin adds headers in the otoroshi response



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.AllowHttpMethods }

## Allowed HTTP methods

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.AllowHttpMethods`

### Description

This plugin verifies the current request only uses allowed http methods



### Default configuration

```json
{
  "allowed" : [ ],
  "forbidden" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ApikeyAuthModule }

## Apikey auth module

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.ApikeyAuthModule`

### Description

This plugin adds basic auth on service where credentials are valid apikeys on the current service.



### Default configuration

```json
{
  "realm" : "apikey-auth-module-realm",
  "matcher" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ApikeyCalls }

## Apikeys

### Defined on steps

  - `MatchRoute`
  - `ValidateAccess`
  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.ApikeyCalls`

### Description

This plugin expects to find an apikey to allow the request to pass



### Default configuration

```json
{
  "extractors" : {
    "basic" : {
      "enabled" : true,
      "header_name" : null,
      "query_name" : null
    },
    "custom_headers" : {
      "enabled" : true,
      "client_id_header_name" : null,
      "client_secret_header_name" : null
    },
    "client_id" : {
      "enabled" : true,
      "header_name" : null,
      "query_name" : null
    },
    "jwt" : {
      "enabled" : true,
      "secret_signed" : true,
      "keypair_signed" : true,
      "include_request_attrs" : false,
      "max_jwt_lifespan_sec" : null,
      "header_name" : null,
      "query_name" : null,
      "cookie_name" : null
    }
  },
  "routing" : {
    "enabled" : false
  },
  "validate" : true,
  "mandatory" : true,
  "pass_with_user" : false,
  "wipe_backend_request" : true,
  "update_quotas" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ApikeyQuotas }

## Apikey quotas

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.ApikeyQuotas`

### Description

Increments quotas for the currents apikey. Useful when 'legacy checks' are disabled on a service/globally or when apikey are extracted in a custom fashion.







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.AuthModule }

## Authentication

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.AuthModule`

### Description

This plugin applies an authentication module



### Default configuration

```json
{
  "pass_with_apikey" : false,
  "auth_module" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.BasicAuthCaller }

## Basic Auth. caller

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.BasicAuthCaller`

### Description

This plugin can be used to call api that are authenticated using basic auth.



### Default configuration

```json
{
  "username" : null,
  "passaword" : null,
  "headerName" : "Authorization",
  "headerValueFormat" : "Basic %s"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.BuildMode }

## Build mode

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.BuildMode`

### Description

This plugin displays a build page







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.CanaryMode }

## Canary mode

### Defined on steps

  - `PreRoute`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.CanaryMode`

### Description

This plugin can split a portion of the traffic to canary backends



### Default configuration

```json
{
  "traffic" : 0.2,
  "targets" : [ ],
  "root" : "/"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ContextValidation }

## Context validator

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.ContextValidation`

### Description

This plugin validates the current context using JSONPath validators.

This plugin let you configure a list of validators that will check if the current call can pass.
A validator is composed of a [JSONPath](https://goessner.net/articles/JsonPath/) that will tell what to check and a value that is the expected value.
The JSONPath will be applied on a document that will look like

```js
{
  "snowflake" : "1516772930422308903",
  "apikey" : { // current apikey
    "clientId" : "vrmElDerycXrofar",
    "clientName" : "default-apikey",
    "metadata" : {
      "foo" : "bar"
    },
    "tags" : [ ]
  },
  "user" : null, //  current user
  "request" : {
    "id" : 1,
    "method" : "GET",
    "headers" : {
      "Host" : "ctx-validation-next-gen.oto.tools:9999",
      "Accept" : "*/*",
      "User-Agent" : "curl/7.64.1",
      "Authorization" : "Basic dnJtRWxEZXJ5Y1hyb2ZhcjpvdDdOSTkyVGI2Q2J4bWVMYU9UNzJxamdCU2JlRHNLbkxtY1FBcXBjVjZTejh0Z3I1b2RUOHAzYjB5SEVNRzhZ",
      "Remote-Address" : "127.0.0.1:58929",
      "Timeout-Access" : "<function1>",
      "Raw-Request-URI" : "/foo",
      "Tls-Session-Info" : "Session(1650461821330|SSL_NULL_WITH_NULL_NULL)"
    },
    "cookies" : [ ],
    "tls" : false,
    "uri" : "/foo",
    "path" : "/foo",
    "version" : "HTTP/1.1",
    "has_body" : false,
    "remote" : "127.0.0.1",
    "client_cert_chain" : null
  },
  "config" : {
    "validators" : [ {
      "path" : "$.apikey.metadata.foo",
      "value" : "bar"
    } ]
  },
  "global_config" : { ... }, // global config
  "attrs" : {
    "otoroshi.core.SnowFlake" : "1516772930422308903",
    "otoroshi.core.ElCtx" : {
      "requestId" : "1516772930422308903",
      "requestSnowflake" : "1516772930422308903",
      "requestTimestamp" : "2022-04-20T15:37:01.548+02:00"
    },
    "otoroshi.next.core.Report" : "otoroshi.next.proxy.NgExecutionReport@277b44e2",
    "otoroshi.core.RequestStart" : 1650461821545,
    "otoroshi.core.RequestWebsocket" : false,
    "otoroshi.core.RequestCounterOut" : 0,
    "otoroshi.core.RemainingQuotas" : {
      "authorizedCallsPerSec" : 10000000,
      "currentCallsPerSec" : 0,
      "remainingCallsPerSec" : 10000000,
      "authorizedCallsPerDay" : 10000000,
      "currentCallsPerDay" : 2,
      "remainingCallsPerDay" : 9999998,
      "authorizedCallsPerMonth" : 10000000,
      "currentCallsPerMonth" : 269,
      "remainingCallsPerMonth" : 9999731
    },
    "otoroshi.next.core.MatchedRoutes" : "MutableList(route_022825450-e97d-42ed-8e22-b23342c1c7c8)",
    "otoroshi.core.RequestNumber" : 1,
    "otoroshi.next.core.Route" : { ... }, // current route as json
    "otoroshi.core.RequestTimestamp" : "2022-04-20T15:37:01.548+02:00",
    "otoroshi.core.ApiKey" : { ... }, // current apikey as json
    "otoroshi.core.User" : { ... }, // current user as json
    "otoroshi.core.RequestCounterIn" : 0
  },
  "route" : { ... },
  "token" : null // current valid jwt token if one
}
```

the expected value support some syntax tricks like

* `Not(value)` on a string to check if the current value does not equals another value
* `Regex(regex)` on a string to check if the current value matches the regex
* `RegexNot(regex)` on a string to check if the current value does not matches the regex
* `Wildcard(*value*)` on a string to check if the current value matches the value with wildcards
* `WildcardNot(*value*)` on a string to check if the current value does not matches the value with wildcards
* `Contains(value)` on a string to check if the current value contains a value
* `ContainsNot(value)` on a string to check if the current value does not contains a value
* `Contains(Regex(regex))` on an array to check if one of the item of the array matches the regex
* `ContainsNot(Regex(regex))` on an array to check if one of the item of the array does not matches the regex
* `Contains(Wildcard(*value*))` on an array to check if one of the item of the array matches the wildcard value
* `ContainsNot(Wildcard(*value*))` on an array to check if one of the item of the array does not matches the wildcard value
* `Contains(value)` on an array to check if the array contains a value
* `ContainsNot(value)` on an array to check if the array does not contains a value

for instance to check if the current apikey has a metadata name `foo` with a value containing `bar`, you can write the following validator

```js
{
  "path": "$.apikey.metadata.foo",
  "value": "Contains(bar)"
}
```



### Default configuration

```json
{
  "validators" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.Cors }

## CORS

### Defined on steps

  - `PreRoute`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.Cors`

### Description

This plugin applies CORS rules



### Default configuration

```json
{
  "allow_origin" : "*",
  "expose_headers" : [ ],
  "allow_headers" : [ ],
  "allow_methods" : [ ],
  "excluded_patterns" : [ ],
  "max_age" : null,
  "allow_credentials" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.DisableHttp10 }

## Disable HTTP/1.0

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.DisableHttp10`

### Description

This plugin forbids HTTP/1.0 requests







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.EndlessHttpResponse }

## Endless HTTP responses

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.EndlessHttpResponse`

### Description

This plugin returns 128 Gb of 0 to the ip addresses is in the list



### Default configuration

```json
{
  "finger" : false,
  "addresses" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.EurekaServerSink }

## Eureka instance

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.EurekaServerSink`

### Description

Eureka plugin description



### Default configuration

```json
{
  "evictionTimeout" : 300
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.EurekaTarget }

## Internal Eureka target

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.EurekaTarget`

### Description

This plugin can be used to used a target that come from an internal Eureka server.
 If you want to use a target which it locate outside of Otoroshi, you must use the External Eureka Server.



### Default configuration

```json
{
  "eureka_server" : null,
  "eureka_app" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ExternalEurekaTarget }

## External Eureka target

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.ExternalEurekaTarget`

### Description

This plugin can be used to used a target that come from an external Eureka server.
 If you want to use a target that is directly exposed by an implementation of Eureka by Otoroshi,
 you must use the Internal Eureka Server.



### Default configuration

```json
{
  "eureka_server" : null,
  "eureka_app" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ForceHttpsTraffic }

## Force HTTPS traffic

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.ForceHttpsTraffic`

### Description

This plugin verifies the current request uses HTTPS







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GlobalMaintenanceMode }

## Global Maintenance mode

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.GlobalMaintenanceMode`

### Description

This plugin displays a maintenance page for every services. Useful when 'legacy checks' are disabled on a service/globally







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GlobalPerIpAddressThrottling }

## Global per ip address throttling 

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.GlobalPerIpAddressThrottling`

### Description

Enforce global per ip address throttling. Useful when 'legacy checks' are disabled on a service/globally







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GlobalThrottling }

## Global throttling 

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.GlobalThrottling`

### Description

Enforce global throttling. Useful when 'legacy checks' are disabled on a service/globally







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GraphQLBackend }

## GraphQL Composer

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.GraphQLBackend`

### Description

This plugin exposes a GraphQL API that you can compose with whatever you want



### Default configuration

```json
{
  "schema" : "\n   type User {\n     name: String!\n     firstname: String!\n   }\n\n   type Query {\n    users: [User] @json(data: \"[{ \\\"firstname\\\": \\\"Foo\\\", \\\"name\\\": \\\"Bar\\\" }, { \\\"firstname\\\": \\\"Bar\\\", \\\"name\\\": \\\"Foo\\\" }]\")\n   }\n  ",
  "permissions" : [ ],
  "initial_data" : null,
  "max_depth" : 15
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GraphQLProxy }

## GraphQL Proxy

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.GraphQLProxy`

### Description

This plugin can apply validations (query, schema, max depth, max complexity) on graphql endpoints



### Default configuration

```json
{
  "endpoint" : "https://countries.trevorblades.com/graphql",
  "schema" : null,
  "max_depth" : 50,
  "max_complexity" : 50000,
  "path" : "/graphql",
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GraphQLQuery }

## GraphQL Query to REST

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.GraphQLQuery`

### Description

This plugin can be used to call GraphQL query endpoints and expose it as a REST endpoint



### Default configuration

```json
{
  "url" : "https://some.graphql/endpoint",
  "headers" : { },
  "method" : "POST",
  "query" : "{\n\n}",
  "timeout" : 60000,
  "response_path" : null,
  "response_filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.GzipResponseCompressor }

## Gzip compression

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.GzipResponseCompressor`

### Description

This plugin can compress responses using gzip



### Default configuration

```json
{
  "excluded_patterns" : [ ],
  "allowed_list" : [ "text/*", "application/javascript", "application/json" ],
  "blocked_list" : [ ],
  "buffer_size" : 8192,
  "chunked_threshold" : 102400,
  "compression_level" : 5
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.HMACCaller }

## HMAC caller plugin

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.HMACCaller`

### Description

This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
 The signature string will always the content of the header list listed in the plugin configuration.



### Default configuration

```json
{
  "secret" : null,
  "algo" : "HMAC-SHA512",
  "authorizationHeader" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.HMACValidator }

## HMAC access validator

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.HMACValidator`

### Description

This plugin can be used to check if a HMAC signature is present and valid in Authorization header.



### Default configuration

```json
{
  "secret" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.HeadersValidation }

## Headers validation

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.HeadersValidation`

### Description

This plugin validates the values of incoming request headers



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.Http3Switch }

## Http3 traffic switch

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.Http3Switch`

### Description

This plugin injects additional alt-svc header to switch to the http3 server



### Default configuration

```json
{
  "ma" : 3600
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.IpAddressAllowedList }

## IP allowed list

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.IpAddressAllowedList`

### Description

This plugin verifies the current request ip address is in the allowed list



### Default configuration

```json
{
  "addresses" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.IpAddressBlockList }

## IP block list

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.IpAddressBlockList`

### Description

This plugin verifies the current request ip address is not in the blocked list



### Default configuration

```json
{
  "addresses" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JQ }

## JQ

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.JQ`

### Description

This plugin let you transform JSON bodies (in requests and responses) using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).



### Default configuration

```json
{
  "request" : ".",
  "response" : ""
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JQRequest }

## JQ transform request

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.JQRequest`

### Description

This plugin let you transform request JSON body using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).



### Default configuration

```json
{
  "filter" : "."
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JQResponse }

## JQ transform response

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.JQResponse`

### Description

This plugin let you transform JSON response using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).



### Default configuration

```json
{
  "filter" : "."
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JsonToXmlRequest }

## request body json-to-xml

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.JsonToXmlRequest`

### Description

This plugin transform incoming request body from json to xml and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JsonToXmlResponse }

## response body json-to-xml

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.JsonToXmlResponse`

### Description

This plugin transform response body from json to xml and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JwtSigner }

## Jwt signer

### Defined on steps

  - `ValidateAccess`
  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.JwtSigner`

### Description

This plugin can only generate token



### Default configuration

```json
{
  "verifier" : null,
  "replace_if_present" : true,
  "fail_if_present" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JwtVerification }

## Jwt verifiers

### Defined on steps

  - `ValidateAccess`
  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.JwtVerification`

### Description

This plugin verifies the current request with one or more jwt verifier



### Default configuration

```json
{
  "verifiers" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.JwtVerificationOnly }

## Jwt verification only

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.JwtVerificationOnly`

### Description

This plugin verifies the current request with one jwt verifier



### Default configuration

```json
{
  "verifier" : null,
  "fail_if_absent" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.MaintenanceMode }

## Maintenance mode

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.MaintenanceMode`

### Description

This plugin displays a maintenance page







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.MissingHeadersIn }

## Missing headers in

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.MissingHeadersIn`

### Description

This plugin adds headers (if missing) in the incoming otoroshi request



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.MissingHeadersOut }

## Missing headers out

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.MissingHeadersOut`

### Description

This plugin adds headers (if missing) in the otoroshi response



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.MockResponses }

## Mock Responses

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.MockResponses`

### Description

This plugin returns mock responses



### Default configuration

```json
{
  "responses" : [ ],
  "pass_through" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgAuthModuleExpectedUser }

## User logged in expected

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgAuthModuleExpectedUser`

### Description

This plugin enforce that a user from any auth. module is logged in



### Default configuration

```json
{
  "only_from" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgAuthModuleUserExtractor }

## User extraction from auth. module

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgAuthModuleUserExtractor`

### Description

This plugin extracts users from an authentication module without enforcing login



### Default configuration

```json
{
  "auth_module" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgBiscuitExtractor }

## Apikey from Biscuit token extractor

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgBiscuitExtractor`

### Description

This plugin extract an from a Biscuit token where the biscuit has an #authority fact 'client_id' containing
apikey client_id and an #authority fact 'client_sign' that is the HMAC256 signature of the apikey client_id with the apikey client_secret



### Default configuration

```json
{
  "public_key" : null,
  "checks" : [ ],
  "facts" : [ ],
  "resources" : [ ],
  "rules" : [ ],
  "revocation_ids" : [ ],
  "extractor" : {
    "name" : "Authorization",
    "type" : "header"
  },
  "enforce" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgBiscuitValidator }

## Biscuit token validator

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgBiscuitValidator`

### Description

This plugin validates a Biscuit token



### Default configuration

```json
{
  "public_key" : null,
  "checks" : [ ],
  "facts" : [ ],
  "resources" : [ ],
  "rules" : [ ],
  "revocation_ids" : [ ],
  "extractor" : {
    "name" : "Authorization",
    "type" : "header"
  },
  "enforce" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgCertificateAsApikey }

## Client certificate as apikey

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgCertificateAsApikey`

### Description

This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage



### Default configuration

```json
{
  "read_only" : false,
  "allow_client_id_only" : false,
  "throttling_quota" : 100,
  "daily_quota" : 10000000,
  "monthly_quota" : 10000000,
  "constrained_services_only" : false,
  "tags" : [ ],
  "metadata" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgClientCertChainHeader }

## Client certificate header

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgClientCertChainHeader`

### Description

This plugin pass client certificate informations to the target in headers



### Default configuration

```json
{
  "send_pem" : false,
  "pem_header_name" : "X-Client-Cert-Pem",
  "send_dns" : false,
  "dns_header_name" : "X-Client-Cert-DNs",
  "send_chain" : false,
  "chain_header_name" : "X-Client-Cert-Chain"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgClientCredentials }

## Client Credential Service

### Defined on steps

  - `Sink`

### Plugin reference

`cp:otoroshi.next.plugins.NgClientCredentials`

### Description

This plugin add an an oauth client credentials service (`https://unhandleddomain/.well-known/otoroshi/oauth/token`) to create an access_token given a client id and secret



### Default configuration

```json
{
  "expiration" : 3600000,
  "default_key_pair" : "otoroshi-jwt-signing",
  "domain" : "*",
  "secure" : true,
  "biscuit" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgDefaultRequestBody }

## Default request body

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgDefaultRequestBody`

### Description

This plugin adds a default request body if none specified



### Default configuration

```json
{
  "bodyBinary" : "",
  "contentType" : "text/plain",
  "contentEncoding" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgDeferPlugin }

## Defer Responses

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgDeferPlugin`

### Description

This plugin will expect a `X-Defer` header or a `defer` query param and defer the response according to the value in milliseconds.
This plugin is some kind of inside joke as one a our customer ask us to make slower apis.



### Default configuration

```json
{
  "duration" : 0
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgDiscoverySelfRegistrationSink }

## Global self registration endpoints (service discovery)

### Defined on steps

  - `Sink`

### Plugin reference

`cp:otoroshi.next.plugins.NgDiscoverySelfRegistrationSink`

### Description

This plugin add support for self registration endpoint on specific hostnames



### Default configuration

```json
{ }
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgDiscoverySelfRegistrationTransformer }

## Self registration endpoints (service discovery)

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgDiscoverySelfRegistrationTransformer`

### Description

This plugin add support for self registration endpoint on a specific service



### Default configuration

```json
{ }
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgDiscoveryTargetsSelector }

## Service discovery target selector (service discovery)

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgDiscoveryTargetsSelector`

### Description

This plugin select a target in the pool of discovered targets for this service.
Use in combination with either `DiscoverySelfRegistrationSink` or `DiscoverySelfRegistrationTransformer` to make it work using the `self registration` pattern.
Or use an implementation of `DiscoveryJob` for the `third party registration pattern`.



### Default configuration

```json
{ }
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgErrorRewriter }

## Error response rewrite

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgErrorRewriter`

### Description

This plugin catch http response with specific statuses and rewrite the response



### Default configuration

```json
{
  "ranges" : [ {
    "from" : 500,
    "to" : 599
  } ],
  "templates" : {
    "default" : "<html>\n  <body style=\"background-color: #333; color: #eee; display: flex; flex-direction: column; justify-content: center; align-items: center; font-size: 40px\">\n    <p>An error occurred with id: <span style=\"color: red\">${error_id}</span></p>\n    <p>please contact your administrator with this error id !</p>\n  </body>\n</html>"
  },
  "log" : true,
  "export" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgGeolocationInfoEndpoint }

## Geolocation endpoint

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgGeolocationInfoEndpoint`

### Description

This plugin will expose current geolocation informations on the following endpoint `/.well-known/otoroshi/plugins/geolocation`







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgGeolocationInfoHeader }

## Geolocation header

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgGeolocationInfoHeader`

### Description

This plugin will send informations extracted by the Geolocation details extractor to the target service in a header.



### Default configuration

```json
{
  "header_name" : "X-User-Agent-Info"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHasAllowedUsersValidator }

## Allowed users only

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgHasAllowedUsersValidator`

### Description

This plugin only let allowed users pass



### Default configuration

```json
{
  "usernames" : [ ],
  "emails" : [ ],
  "email_domains" : [ ],
  "metadata_match" : [ ],
  "metadata_not_match" : [ ],
  "profile_match" : [ ],
  "profile_not_match" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHasClientCertMatchingApikeyValidator }

## Client Certificate + Api Key only

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgHasClientCertMatchingApikeyValidator`

### Description

Check if a client certificate is present in the request and that the apikey used matches the client certificate.
You can set the client cert. DN in an apikey metadata named `allowed-client-cert-dn`







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHasClientCertMatchingHttpValidator }

## Client certificate matching (over http)

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgHasClientCertMatchingHttpValidator`

### Description

Check if client certificate matches the following fetched from an http endpoint



### Default configuration

```json
{
  "serial_numbers" : [ ],
  "subject_dns" : [ ],
  "issuer_dns" : [ ],
  "regex_subject_dns" : [ ],
  "regex_issuer_dns" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHasClientCertMatchingValidator }

## Client certificate matching

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgHasClientCertMatchingValidator`

### Description

Check if client certificate matches the following configuration



### Default configuration

```json
{
  "serial_numbers" : [ ],
  "subject_dns" : [ ],
  "issuer_dns" : [ ],
  "regex_subject_dns" : [ ],
  "regex_issuer_dns" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHasClientCertValidator }

## Client Certificate Only

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgHasClientCertValidator`

### Description

Check if a client certificate is present in the request







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHtmlPatcher }

## Html Patcher

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgHtmlPatcher`

### Description

This plugin can inject elements in html pages (in the body or in the head) returned by the service



### Default configuration

```json
{
  "append_head" : [ ],
  "append_body" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgHttpClientCache }

## HTTP Client Cache

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgHttpClientCache`

### Description

This plugin add cache headers to responses



### Default configuration

```json
{
  "max_age_seconds" : 86400,
  "methods" : [ "GET" ],
  "status" : [ 200 ],
  "mime_types" : [ "text/html" ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgIpStackGeolocationInfoExtractor }

## Geolocation details extractor (using IpStack api)

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgIpStackGeolocationInfoExtractor`

### Description

This plugin extract geolocation informations from ip address using the [IpStack dbs](https://ipstack.com/).
The informations are store in plugins attrs for other plugins to use



### Default configuration

```json
{
  "apikey" : null,
  "timeout" : 2000,
  "log" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgIzanamiV1Canary }

## Izanami V1 Canary Campaign

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgIzanamiV1Canary`

### Description

This plugin allow you to perform canary testing based on an izanami experiment campaign (A/B test)



### Default configuration

```json
{
  "experiment_id" : "foo:bar:qix",
  "config_id" : "foo:bar:qix:config",
  "izanami_url" : "https://izanami.foo.bar",
  "tls" : {
    "certs" : [ ],
    "trusted_certs" : [ ],
    "enabled" : false,
    "loose" : false,
    "trust_all" : false
  },
  "client_id" : "client",
  "client_secret" : "secret",
  "timeout" : 5000,
  "route_config" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgIzanamiV1Proxy }

## Izanami v1 APIs Proxy

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgIzanamiV1Proxy`

### Description

This plugin exposes routes to proxy Izanami configuration and features tree APIs



### Default configuration

```json
{
  "path" : "/api/izanami",
  "feature_pattern" : "*",
  "config_pattern" : "*",
  "auto_context" : false,
  "features_enabled" : true,
  "features_with_context_enabled" : true,
  "configuration_enabled" : false,
  "tls" : {
    "certs" : [ ],
    "trusted_certs" : [ ],
    "enabled" : false,
    "loose" : false,
    "trust_all" : false
  },
  "izanami_url" : "https://izanami.foo.bar",
  "client_id" : "client",
  "client_secret" : "secret",
  "timeout" : 500
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgJwtUserExtractor }

## Jwt user extractor

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgJwtUserExtractor`

### Description

This plugin extract a user from a JWT token



### Default configuration

```json
{
  "verifier" : "none",
  "strict" : true,
  "strip" : false,
  "name_path" : null,
  "email_path" : null,
  "meta_path" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgLegacyApikeyCall }

## Legacy apikeys

### Defined on steps

  - `MatchRoute`
  - `ValidateAccess`
  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgLegacyApikeyCall`

### Description

This plugin expects to find an apikey to allow the request to pass. This plugin behaves exactly like the service descriptor does



### Default configuration

```json
{
  "public_patterns" : [ ],
  "private_patterns" : [ ],
  "extractors" : {
    "basic" : {
      "enabled" : true,
      "header_name" : null,
      "query_name" : null
    },
    "custom_headers" : {
      "enabled" : true,
      "client_id_header_name" : null,
      "client_secret_header_name" : null
    },
    "client_id" : {
      "enabled" : true,
      "header_name" : null,
      "query_name" : null
    },
    "jwt" : {
      "enabled" : true,
      "secret_signed" : true,
      "keypair_signed" : true,
      "include_request_attrs" : false,
      "max_jwt_lifespan_sec" : null,
      "header_name" : null,
      "query_name" : null,
      "cookie_name" : null
    }
  },
  "routing" : {
    "enabled" : false
  },
  "validate" : true,
  "mandatory" : true,
  "pass_with_user" : false,
  "wipe_backend_request" : true,
  "update_quotas" : true
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgLegacyAuthModuleCall }

## Legacy Authentication

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgLegacyAuthModuleCall`

### Description

This plugin applies an authentication module the same way service descriptor does



### Default configuration

```json
{
  "public_patterns" : [ ],
  "private_patterns" : [ ],
  "pass_with_apikey" : false,
  "auth_module" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgLog4ShellFilter }

## Log4Shell mitigation plugin

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgLog4ShellFilter`

### Description

This plugin try to detect Log4Shell attacks in request and block them



### Default configuration

```json
{
  "status" : 200,
  "body" : "",
  "parse_body" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgMaxMindGeolocationInfoExtractor }

## Geolocation details extractor (using Maxmind db)

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgMaxMindGeolocationInfoExtractor`

### Description

This plugin extract geolocation informations from ip address using the [Maxmind dbs](https://www.maxmind.com/en/geoip2-databases).
The informations are store in plugins attrs for other plugins to use



### Default configuration

```json
{
  "path" : "global",
  "log" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgResponseCache }

## Response Cache

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgResponseCache`

### Description

This plugin can cache responses from target services in the otoroshi datasstore
It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.



### Default configuration

```json
{
  "ttl" : 3600000,
  "maxSize" : 52428800,
  "autoClean" : true,
  "filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgSecurityTxt }

## Security Txt

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgSecurityTxt`

### Description

This plugin exposes a special route `/.well-known/security.txt` as proposed at [https://securitytxt.org/](https://securitytxt.org/)



### Default configuration

```json
{
  "contact" : "contact@foo.bar"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgServiceQuotas }

## Public quotas

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.NgServiceQuotas`

### Description

This plugin will enforce public quotas on the current route



### Default configuration

```json
{
  "throttling_quota" : 10000000,
  "daily_quota" : 10000000,
  "monthly_quota" : 10000000
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgTrafficMirroring }

## Traffic Mirroring

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.NgTrafficMirroring`

### Description

This plugin will mirror every request to other targets



### Default configuration

```json
{
  "to" : "https://foo.bar.dev",
  "enabled" : true,
  "capture_response" : false,
  "generate_events" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgUserAgentExtractor }

## User-Agent details extractor

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.NgUserAgentExtractor`

### Description

This plugin extract informations from User-Agent header such as browsser version, OS version, etc.
The informations are store in plugins attrs for other plugins to use



### Default configuration

```json
{
  "log" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgUserAgentInfoEndpoint }

## User-Agent endpoint

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgUserAgentInfoEndpoint`

### Description

This plugin will expose current user-agent informations on the following endpoint: /.well-known/otoroshi/plugins/user-agent







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.NgUserAgentInfoHeader }

## User-Agent header

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.NgUserAgentInfoHeader`

### Description

This plugin will sent informations extracted by the User-Agent details extractor to the target service in a header



### Default configuration

```json
{
  "header_name" : "X-User-Agent-Info"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OAuth1Caller }

## OAuth1 caller

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.OAuth1Caller`

### Description

This plugin can be used to call api that are authenticated using OAuth1.
 Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
 or via the configuration of this plugin.



### Default configuration

```json
{
  "consumerKey" : null,
  "consumerSecret" : null,
  "token" : null,
  "tokenSecret" : null,
  "algo" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OAuth2Caller }

## OAuth2 caller

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OAuth2Caller`

### Description

This plugin can be used to call api that are authenticated using OAuth2 client_credential/password flow.
Do not forget to enable client retry to handle token generation on expire.



### Default configuration

```json
{
  "kind" : "client_credentials",
  "url" : "https://127.0.0.1:8080/oauth/token",
  "method" : "POST",
  "headerName" : "Authorization",
  "headerValueFormat" : "Bearer %s",
  "jsonPayload" : false,
  "clientId" : "the client_id",
  "clientSecret" : "the client_secret",
  "scope" : null,
  "audience" : null,
  "user" : null,
  "password" : null,
  "cacheTokenSeconds" : 600000,
  "tlsConfig" : {
    "certs" : [ ],
    "trustedCerts" : [ ],
    "mtls" : false,
    "loose" : false,
    "trustAll" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OIDCAccessTokenAsApikey }

## OIDC access_token as apikey

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.OIDCAccessTokenAsApikey`

### Description

This plugin will use the third party apikey configuration to generate an apikey



### Default configuration

```json
{
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
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OIDCAccessTokenValidator }

## OIDC access_token validator

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.OIDCAccessTokenValidator`

### Description

This plugin will use the third party apikey configuration and apply it while keeping the apikey mecanism of otoroshi.
Use it to combine apikey validation and OIDC access_token validation.



### Default configuration

```json
{
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
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OIDCHeaders }

## OIDC headers

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OIDCHeaders`

### Description

This plugin injects headers containing tokens and profile from current OIDC provider.



### Default configuration

```json
{
  "profile" : {
    "send" : false,
    "headerName" : "X-OIDC-User"
  },
  "idToken" : {
    "send" : false,
    "name" : "id_token",
    "headerName" : "X-OIDC-Id-Token",
    "jwt" : true
  },
  "accessToken" : {
    "send" : false,
    "name" : "access_token",
    "headerName" : "X-OIDC-Access-Token",
    "jwt" : true
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OtoroshiChallenge }

## Otoroshi challenge token

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.OtoroshiChallenge`

### Description

This plugin adds a jwt challenge token to the request to a backend and expects a response with a matching token



### Default configuration

```json
{
  "version" : "V2",
  "ttl" : 30,
  "request_header_name" : null,
  "response_header_name" : null,
  "algo_to_backend" : {
    "type" : "HSAlgoSettings",
    "size" : 512,
    "secret" : "secret",
    "base64" : false
  },
  "algo_from_backend" : {
    "type" : "HSAlgoSettings",
    "size" : 512,
    "secret" : "secret",
    "base64" : false
  },
  "state_resp_leeway" : 10
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OtoroshiHeadersIn }

## Otoroshi headers in

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OtoroshiHeadersIn`

### Description

This plugin adds Otoroshi specific headers to the request







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OtoroshiInfos }

## Otoroshi info. token

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OtoroshiInfos`

### Description

This plugin adds a jwt token with informations about the caller to the backend



### Default configuration

```json
{
  "version" : "Latest",
  "ttl" : 30,
  "header_name" : null,
  "add_fields" : null,
  "algo" : {
    "type" : "HSAlgoSettings",
    "size" : 512,
    "secret" : "secret",
    "base64" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OverrideHost }

## Override host header

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OverrideHost`

### Description

This plugin override the current Host header with the Host of the backend target







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.PublicPrivatePaths }

## Public/Private paths

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.PublicPrivatePaths`

### Description

This plugin allows or forbid request based on path patterns



### Default configuration

```json
{
  "strict" : false,
  "private_patterns" : [ ],
  "public_patterns" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.QueryTransformer }

## Query param transformer

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.QueryTransformer`

### Description

This plugin can modify the query params of the request



### Default configuration

```json
{
  "remove" : [ ],
  "rename" : { },
  "add" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.RBAC }

## RBAC

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.RBAC`

### Description

This plugin check if current user/apikey/jwt token has the right role



### Default configuration

```json
{
  "allow" : [ ],
  "deny" : [ ],
  "allow_all" : false,
  "deny_all" : false,
  "jwt_path" : null,
  "apikey_path" : null,
  "user_path" : null,
  "role_prefix" : null,
  "roles" : "roles"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.ReadOnlyCalls }

## Read only requests

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.ReadOnlyCalls`

### Description

This plugin verifies the current request only reads data







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.Redirection }

## Redirection

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.Redirection`

### Description

This plugin redirects the current request elsewhere



### Default configuration

```json
{
  "code" : 303,
  "to" : "https://www.otoroshi.io"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.RemoveHeadersIn }

## Remove headers in

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.RemoveHeadersIn`

### Description

This plugin removes headers in the incoming otoroshi request



### Default configuration

```json
{
  "header_names" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.RemoveHeadersOut }

## Remove headers out

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.RemoveHeadersOut`

### Description

This plugin removes headers in the otoroshi response



### Default configuration

```json
{
  "header_names" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.Robots }

## Robots

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.Robots`

### Description

This plugin provides all the necessary tool to handle search engine robots



### Default configuration

```json
{
  "robot_txt_enabled" : true,
  "robot_txt_content" : "User-agent: *\nDisallow: /\n",
  "meta_enabled" : true,
  "meta_content" : "noindex,nofollow,noarchive",
  "header_enabled" : true,
  "header_content" : "noindex, nofollow, noarchive"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.RoutingRestrictions }

## Routing Restrictions

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.RoutingRestrictions`

### Description

This plugin apply routing restriction `method domain/path` on the current request/route



### Default configuration

```json
{
  "allow_last" : true,
  "allowed" : [ ],
  "forbidden" : [ ],
  "not_found" : [ ]
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.S3Backend }

## S3 Static backend

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.S3Backend`

### Description

This plugin is able to S3 bucket with file content



### Default configuration

```json
{
  "bucket" : "",
  "endpoint" : "",
  "region" : "eu-west-1",
  "access" : "client",
  "secret" : "secret",
  "key" : "",
  "chunkSize" : 8388608,
  "v4auth" : true,
  "writeEvery" : 60000,
  "acl" : "private"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.SOAPAction }

## SOAP action

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.SOAPAction`

### Description

This plugin is able to call SOAP actions and expose it as a rest endpoint



### Default configuration

```json
{
  "url" : null,
  "envelope" : "<soap envelope />",
  "action" : null,
  "preserve_query" : true,
  "charset" : null,
  "jq_request_filter" : null,
  "jq_response_filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.SendOtoroshiHeadersBack }

## Send otoroshi headers back

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.SendOtoroshiHeadersBack`

### Description

This plugin adds response header containing useful informations about the current call







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.SnowMonkeyChaos }

## Snow Monkey Chaos

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.SnowMonkeyChaos`

### Description

This plugin introduce some chaos into you life



### Default configuration

```json
{
  "large_request_fault" : null,
  "large_response_fault" : null,
  "latency_injection_fault" : null,
  "bad_responses_fault" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.StaticBackend }

## Static backend

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.StaticBackend`

### Description

This plugin is able to serve a static folder with file content



### Default configuration

```json
{
  "root_path" : "/tmp"
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.StaticResponse }

## Static Response

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.StaticResponse`

### Description

This plugin returns static responses



### Default configuration

```json
{
  "status" : 200,
  "headers" : { },
  "body" : ""
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.TailscaleSelectTargetByName }

## Tailscale select target by name

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.TailscaleSelectTargetByName`

### Description

This plugin selects a machine instance on Tailscale network based on its name



### Default configuration

```json
{
  "machine_name" : "my-machine",
  "use_ip_address" : false
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.TcpTunnel }

## TCP Tunnel

### Defined on steps

  - `HandlesTunnel`

### Plugin reference

`cp:otoroshi.next.plugins.TcpTunnel`

### Description

This plugin creates TCP tunnels through otoroshi







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.UdpTunnel }

## UDP Tunnel

### Defined on steps

  - `HandlesTunnel`

### Plugin reference

`cp:otoroshi.next.plugins.UdpTunnel`

### Description

This plugin creates UDP tunnels through otoroshi







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.W3CTracing }

## W3C Trace Context

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.W3CTracing`

### Description

This plugin propagates W3C Trace Context spans and can export it to Jaeger or Zipkin



### Default configuration

```json
{
  "kind" : "noop",
  "endpoint" : "http://localhost:3333/spans",
  "timeout" : 30000,
  "baggage" : { }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmAccessValidator }

## Wasm Access control

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.WasmAccessValidator`

### Description

Delegate route access to a wasm plugin



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmBackend }

## Wasm Backend

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.plugins.WasmBackend`

### Description

This plugin can be used to use a wasm plugin as backend



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmOPA }

## Open Policy Agent (OPA)

### Defined on steps

  - `ValidateAccess`

### Plugin reference

`cp:otoroshi.next.plugins.WasmOPA`

### Description

Repo policies as WASM modules



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : true,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmPreRoute }

## Wasm pre-route

### Defined on steps

  - `PreRoute`

### Plugin reference

`cp:otoroshi.next.plugins.WasmPreRoute`

### Description

This plugin can be used to use a wasm plugin as in pre-route phase



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmRequestTransformer }

## Wasm Request Transformer

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.WasmRequestTransformer`

### Description

Transform the content of the request with a wasm plugin



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmResponseTransformer }

## Wasm Response Transformer

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.WasmResponseTransformer`

### Description

Transform the content of a response with a wasm plugin



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmRouteMatcher }

## Wasm Route Matcher

### Defined on steps

  - `MatchRoute`

### Plugin reference

`cp:otoroshi.next.plugins.WasmRouteMatcher`

### Description

This plugin can be used to use a wasm plugin as route matcher



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmRouter }

## Wasm Router

### Defined on steps

  - `Router`

### Plugin reference

`cp:otoroshi.next.plugins.WasmRouter`

### Description

Can decide for routing with a wasm plugin



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.WasmSink }

## Wasm Sink

### Defined on steps

  - `Sink`

### Plugin reference

`cp:otoroshi.next.plugins.WasmSink`

### Description

Handle unmatched requests with a wasm plugin



### Default configuration

```json
{
  "source" : {
    "kind" : "Unknown",
    "path" : "",
    "opts" : { }
  },
  "memoryPages" : 4,
  "functionName" : null,
  "config" : { },
  "allowedHosts" : [ ],
  "allowedPaths" : { },
  "wasi" : false,
  "opa" : false,
  "lifetime" : "Forever",
  "authorizations" : {
    "httpAccess" : false,
    "proxyHttpCallTimeout" : 5000,
    "globalDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginDataStoreAccess" : {
      "read" : false,
      "write" : false
    },
    "globalMapAccess" : {
      "read" : false,
      "write" : false
    },
    "pluginMapAccess" : {
      "read" : false,
      "write" : false
    },
    "proxyStateAccess" : false,
    "configurationAccess" : false
  }
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.XForwardedHeaders }

## X-Forwarded-* headers

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.XForwardedHeaders`

### Description

This plugin adds all the X-Forwarder-* headers to the request for the backend target







@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.XmlToJsonRequest }

## request body xml-to-json

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.XmlToJsonRequest`

### Description

This plugin transform incoming request body from xml to json and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.XmlToJsonResponse }

## response body xml-to-json

### Defined on steps

  - `TransformResponse`

### Plugin reference

`cp:otoroshi.next.plugins.XmlToJsonResponse`

### Description

This plugin transform response body from xml to json and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.tunnel.TunnelPlugin }

## Remote tunnel calls

### Defined on steps

  - `CallBackend`

### Plugin reference

`cp:otoroshi.next.tunnel.TunnelPlugin`

### Description

This plugin can contact remote service using tunnels



### Default configuration

```json
{
  "tunnel_id" : "default"
}
```





@@@




