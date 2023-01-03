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
  "schema" : "\n   type User {\n     name: String!\n     firstname: String!\n   }\n   schema {\n    query: Query\n   }\n\n   type Query {\n    users: [User] @json(data: \"[{ \\\"firstname\\\": \\\"Foo\\\", \\\"name\\\": \\\"Bar\\\" }, { \\\"firstname\\\": \\\"Bar\\\", \\\"name\\\": \\\"Foo\\\" }]\")\n   }\n  ",
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
  "version" : 2,
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


@@@ div { .ng-plugin .plugin-hidden .pl #otoroshi.next.plugins.OtoroshiInfos }

## Otoroshi info. token

### Defined on steps

  - `TransformRequest`

### Plugin reference

`cp:otoroshi.next.plugins.OtoroshiInfos`

### Description

This plugin adds a jwt info. token to the request to a backend



### Default configuration

```json
{
  "version" : "Latest",
  "ttl" : 30,
  "header_name" : null,
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




