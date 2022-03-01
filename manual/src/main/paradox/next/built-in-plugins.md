# Built-in plugins

Otoroshi next provides some plugins out of the box. Here is the available plugins with their documentation and reference configuration


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.AdditionalHeadersIn }

## Additional headers in

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin adds headers in the incoming otoroshi request



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.AdditionalHeadersOut }

## Additional headers out

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin adds headers in the otoroshi response



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.AllowHttpMethods }

## Allowed HTTP methods

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin verifies the current request only uses allowed http methods







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.ApikeyCalls }

## Apikeys

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `MatchRoute`
  - `ValidateAccess`
  - `TransformRequest`

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
  "pass_with_user" : true,
  "wipe_backend_request" : true
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.AuthModule }

## Authentication

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.BuildMode }

## Build mode

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`

### Description

This plugin displays a build page







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.CanaryMode }

## Canary mode

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.ContextValidation }

## Context validator

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin validates the current context



### Default configuration

```json
{
  "validators" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.Cors }

## CORS

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.DisableHttp10 }

## Disable HTTP/1.0

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin forbids HTTP/1.0 requests







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.EndlessHttpResponse }

## Endless HTTP responses

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.ForceHttpsTraffic }

## Force HTTPS traffic

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`

### Description

This plugin verifies the current request uses HTTPS







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.GlobalMaintenanceMode }

## Global Maintenance mode

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`

### Description

This plugin displays a maintenance page for every services







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.GzipResponseCompressor }

## Gzip compression

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.HeadersValidation }

## Headers validation

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin validates the values of incoming request headers



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.IpAddressAllowedList }

## IP allowed list

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin verifies the current request ip address is in the allowed list



### Default configuration

```json
{
  "addresses" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.IpAddressBlockList }

## IP block list

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin verifies the current request ip address is not in the blocked list



### Default configuration

```json
{
  "addresses" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.JQ }

## JQ

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.JsonToXmlRequest }

## request body json-to-xml

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin transform incoming request body from json to xml and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.JsonToXmlResponse }

## response body json-to-xml

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin transform response body from json to xml and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.JwtVerification }

## Jwt verifiers

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`
  - `TransformRequest`

### Description

This plugin verifies the current request with one or more jwt verifier



### Default configuration

```json
{
  "verifiers" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.MaintenanceMode }

## Maintenance mode

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`

### Description

This plugin displays a maintenance page







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.MissingHeadersIn }

## Missing headers in

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin adds headers (if missing) in the incoming otoroshi request



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.MissingHeadersOut }

## Missing headers out

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin adds headers (if missing) in the otoroshi response



### Default configuration

```json
{
  "headers" : { }
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.MockResponses }

## Mock Responses

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin returns mock responses



### Default configuration

```json
{
  "responses" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.OtoroshiChallenge }

## Otoroshi challenge token

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.OtoroshiInfos }

## Otoroshi info. token

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.OverrideHost }

## Override host header

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin override the current Host header with the Host of the backend target







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.PublicPrivatePaths }

## Public/Private paths

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.QueryTransformer }

## Query param transformer

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.RBAC }

## RBAC

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.ReadOnlyCalls }

## Read only requests

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

### Description

This plugin verifies the current request only reads data







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.Redirection }

## Redirection

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `PreRoute`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.RemoveHeadersIn }

## Remove headers in

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin removes headers in the incoming otoroshi request



### Default configuration

```json
{
  "header_names" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.RemoveHeadersOut }

## Remove headers out

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin removes headers in the otoroshi response



### Default configuration

```json
{
  "header_names" : [ ]
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.Robots }

## Robots

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.RoutingRestrictions }

## Routing Restrictions

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `ValidateAccess`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.SOAPAction }

## SOAP action

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.SendOtoroshiHeadersBack }

## Send otoroshi headers back

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin adds response header containing useful informations about the current call







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.SnowMonkeyChaos }

## Snow Monkey Chaos

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.StaticResponse }

## Static Response

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.TcpTunnel }

## TCP Tunnel

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `HandlesTunnel`

### Description

This plugin creates TCP tunnels through otoroshi







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.UdpTunnel }

## UDP Tunnel

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `HandlesTunnel`

### Description

This plugin creates UDP tunnels through otoroshi







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.W3CTracing }

## W3C Trace Context

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`
  - `TransformResponse`

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


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.XForwardedHeaders }

## X-Forwarded-* headers

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin adds all the X-Forwarder-* headers to the request for the backend target







@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.XmlToJsonRequest }

## request body xml-to-json

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformRequest`

### Description

This plugin transform incoming request body from xml to json and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@


@@@ div { .plugin .plugin-hidden .foo #otoroshi.next.plugins.XmlToJsonResponse }

## response body xml-to-json

<img class="plugin-logo plugin-hidden" src=""></img>

### Defined on steps

  - `TransformResponse`

### Description

This plugin transform response body from xml to json and may apply a jq transformation



### Default configuration

```json
{
  "filter" : null
}
```





@@@




