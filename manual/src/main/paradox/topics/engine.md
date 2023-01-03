# Proxy engine

Starting from the `1.5.3` release, otoroshi offers a new plugin that implements the next generation of the proxy engine. 
This engine has been designed based on our 5 years experience building, maintaining and running the previous one.
It tries to fix all the drawback we may have encountered during those years and highly improve performances, user experience, reporting and debugging capabilities. 

The new engine is fully plugin oriented in order to spend CPU cycles only on useful stuff. You can enable this plugin only on some domain names so you can easily A/B test the new engine. The new proxy engine is designed to be more reactive and more efficient generally. It is also designed to be very efficient on path routing where it wasn't the old engines strong suit.

Starting from version `16.0.0`, this engine will be enabled by default on any new otoroshi cluster. In a future version, the engine will be enabled for any new or exisiting otoroshi cluster.

## Enabling the new engine

By default, all freshly started Otoroshi instances have the new proxy engine enabled by default, for the other, to enable the new proxy engine on an otoroshi instance, just add the plugin in the `global plugins` section of the danger zone, inject the default configuration, enable it and in `domains` add the values of the desired domains (let say we want to use the new engine on `api.foo.bar`. It is possible to use `*.foo.bar` if that's what you want to do).

The next time a request hits the `api.foo.bar` domain, the new engine will handle it instead of the previous one.

```json
{
  "NextGenProxyEngine" : {
    "enabled" : true,
    "debug_headers" : false,
    "reporting": true,
    "domains" : [ "api.foo.bar" ],
    "deny_domains" : [ ],
  }
}
```

if you need to enable global plugin with the new engine, you can add the following configuration in the `global plugins` configuration object 

```javascript
{
  ...
  "ng": {
    "slots": [
      {
        "plugin": "cp:otoroshi.next.plugins.W3CTracing",
        "enabled": true,
        "include": [],
        "exclude": [],
        "config": {
          "baggage": {
            "foo": "bar"
          }
        }
      },
      {
        "plugin": "cp:otoroshi.next.plugins.wrappers.RequestSinkWrapper",
        "enabled": true,
        "include": [],
        "exclude": [],
        "config": {
          "plugin": "cp:otoroshi.plugins.apikeys.ClientCredentialService",
          "ClientCredentialService": {
            "domain": "ccs-next-gen.oto.tools",
            "expiration": 3600000,
            "defaultKeyPair": "otoroshi-jwt-signing",
            "secure": false
          }
        }
      }
    ]
  }
  ...
}
```

## Entities

This plugin introduces new entities that will replace (one day maybe) service descriptors:

 - `routes`: a unique routing rule based on hostname, path, method and headers that will execute a bunch of plugins
 - `backends`: a list of targets to contact a backend

## Entities sync

A new behavior introduced for the new proxy engine is the entities sync job. To avoid unecessary operations on the underlying datastore when routing requests, a new job has been setup in otoroshi that synchronize the content of the datastore (at least a part of it) with an in-memory cache. Because of it, the propagation of changes between an admin api call and the actual result on routing can be longer than before. When a node creates, updates, or deletes an entity via the admin api, other nodes need to wait for the next poll to purge the old cached entity and start using the new one. You can change the interval between syncs with the configuration key `otoroshi.next.state-sync-interval` or the env. variable `OTOROSHI_NEXT_STATE_SYNC_INTERVAL`. The default value is `10000` and the unit is `milliseconds`

@@@ warning
Because of entities sync, memory consumption of otoroshi will be significantly higher than previous versions. You can use `otoroshi.next.monitor-proxy-state-size=true` config (or `OTOROSHI_NEXT_MONITOR_PROXY_STATE_SIZE` env. variable) to monitor the actual memory size of the entities cache. This will produce the `ng-proxy-state-size-monitoring` metric in standard otoroshi metrics
@@@

## Automatic conversion

The new engine uses new entities for its configuration, but in order to facilitate transition between the old world and the new world, all the `service descriptors` of an otoroshi instance are automatically converted live into `routes` periodically. Any `service descriptor` should still work as expected through the new engine while enjoying all the perks.

@@@ warning
the experimental nature of the engine can imply unexpected behaviors for converted service descriptors
@@@

## Routing

the new proxy engine introduces a new router that has enhanced capabilities and performances. The router can handle thousands of routes declarations without compromising performances.

The new route allow routes to be matched on a combination of

* hostname
* path
* header values
    * where values can be `exact_value`, or `Regex(value_regex)`, or `Wildcard(value_with_*)`
* query param values
    * where values can be `exact_value`, or `Regex(value_regex)`, or `Wildcard(value_with_*)`

patch matching works 

* exactly
    * matches `/api/foo` with `/api/foo` and not with `/api/foo/bar`
* starting with value (default behavior, like the previous engine)
    * matches `/api/foo` with `/api/foo` but also with `/api/foo/bar`

path matching can also include wildcard paths and even path params

* plain old path: `subdomain.domain.tld/api/users`
* wildcard path: `subdomain.domain.tld/api/users/*/bills`
* named path params: `subdomain.domain.tld/api/users/:id/bills`
* named regex path params: `subdomain.domain.tld/api/users/$id<[0-9]+>/bills`

hostname matching works on 

* exact values
    * `subdomain.domain.tld`
* wildcard values like
    * `*.domain.tld`
    * `subdomain.*.tld`

as path matching can now include named path params, it is possible to perform a ful url rewrite on the target path like 

* input: `subdomain.domain.tld/api/users/$id<[0-9]+>/bills`
* output: `target.domain.tld/apis/v1/basic_users/${req.pathparams.id}/all_bills`

## Plugins

the new route entity defines a plugin pipline where any plugin can be enabled or not and can be active only on some paths. 
Each plugin slot in the pipeline holds the plugin id and the plugin configuration. 

You can also enable debugging only on a plugin instance instead of the whole route (see [the debugging section](#debugging))

```javascript
{ 
  ...
  "plugins" : [ {
    "enabled" : true,
    "debug" : false,
    "plugin" : "cp:otoroshi.next.plugins.OverrideHost",
    "include" : [ ],
    "exclude" : [ ],
    "config" : { }
  }, {
    "enabled" : true,
    "debug" : false,
    "plugin" : "cp:otoroshi.next.plugins.ApikeyCalls",
    "include" : [ ],
    "exclude" : [ "/openapi.json" ],
    "config" : { }
  } ]
}
```

you can find the list of built-in plugins @ref:[here](../plugins/built-in-plugins.md)

## Using legacy plugins

if you need to use legacy otoroshi plugins with the new engine, you can use several wrappers in order to do so

* `otoroshi.next.plugins.wrappers.PreRoutingWrapper`
* `otoroshi.next.plugins.wrappers.AccessValidatorWrapper`
* `otoroshi.next.plugins.wrappers.RequestSinkWrapper`
* `otoroshi.next.plugins.wrappers.RequestTransformerWrapper`
* `otoroshi.next.plugins.wrappers.CompositeWrapper`

to use it, just declare a plugin slot with the right wrapper and in the config, declare the `plugin` you want to use and its configuration like:

```javascript
{
  "plugin": "cp:otoroshi.next.plugins.wrappers.PreRoutingWrapper",
  "enabled": true,
  "include": [],
  "exclude": [],
  "config": {
    "plugin": "cp:otoroshi.plugins.jwt.JwtUserExtractor",
    "JwtUserExtractor": {
      "verifier" : "$ref",
      "strict"   : true,
      "namePath" : "name",
      "emailPath": "email",
      "metaPath" : null
    }
  }
}
```

## Reporting

by default, any request hiting the new engine will generate an execution report with informations about how the request pipeline steps were performed. It is possible to export those reports as `RequestFlowReport` events using classical data exporter. By default, exporting for reports is not enabled, you must enable the `export_reporting` flag on a `route` or `service`.

```javascript
{
  "@id": "8efac472-07bc-4a80-8d27-4236309d7d01",
  "@timestamp": "2022-02-15T09:51:25.402+01:00",
  "@type": "RequestFlowReport",
  "@product": "otoroshi",
  "@serviceId": "service_548f13bb-a809-4b1d-9008-fae3b1851092",
  "@service": "demo-service",
  "@env": "prod",
  "route": {
    "_loc" : {
      "tenant" : "default",
      "teams" : [ "default" ]
    },
    "id" : "service_dev_d54f11d0-18e2-4da4-9316-cf47733fd29a",
    "name" : "hey",
    "description" : "hey",
    "tags" : [ "env:prod" ],
    "metadata" : { },
    "enabled" : true,
    "debug_flow" : true,
    "export_reporting" : false,
    "groups" : [ "default" ],
    "frontend" : {
      "domains" : [ "hey-next-gen.oto.tools/", "hey.oto.tools/" ],
      "strip_path" : true,
      "exact" : false,
      "headers" : { },
      "methods" : [ ]
    },
    "backend" : {
      "targets" : [ {
        "id" : "127.0.0.1:8081",
        "hostname" : "127.0.0.1",
        "port" : 8081,
        "tls" : false,
        "weight" : 1,
        "protocol" : "HTTP/1.1",
        "ip_address" : null,
        "tls_config" : {
          "certs" : [ ],
          "trustedCerts" : [ ],
          "mtls" : false,
          "loose" : false,
          "trustAll" : false
        }
      } ],
      "target_refs" : [ ],
      "root" : "/",
      "rewrite" : false,
      "load_balancing" : {
        "type" : "RoundRobin"
      },
      "client" : {
        "useCircuitBreaker" : true,
        "retries" : 1,
        "maxErrors" : 20,
        "retryInitialDelay" : 50,
        "backoffFactor" : 2,
        "callTimeout" : 30000,
        "callAndStreamTimeout" : 120000,
        "connectionTimeout" : 10000,
        "idleTimeout" : 60000,
        "globalTimeout" : 30000,
        "sampleInterval" : 2000,
        "proxy" : { },
        "customTimeouts" : [ ],
        "cacheConnectionSettings" : {
          "enabled" : false,
          "queueSize" : 2048
        }
      }
    },
    "backend_ref" : null,
    "plugins" : [ ]
  },
  "report": {
    "id" : "ab73707b3-946b-4853-92d4-4c38bbaac6d6",
    "creation" : "2022-02-15T09:51:25.402+01:00",
    "termination" : "2022-02-15T09:51:25.408+01:00",
    "duration" : 5,
    "duration_ns" : 5905522,
    "overhead" : 4,
    "overhead_ns" : 4223215,
    "overhead_in" : 2,
    "overhead_in_ns" : 2687750,
    "overhead_out" : 1,
    "overhead_out_ns" : 1535465,
    "state" : "Successful",
    "steps" : [ {
      "task" : "start-handling",
      "start" : 1644915085402,
      "start_fmt" : "2022-02-15T09:51:25.402+01:00",
      "stop" : 1644915085402,
      "stop_fmt" : "2022-02-15T09:51:25.402+01:00",
      "duration" : 0,
      "duration_ns" : 177430,
      "ctx" : null
    }, {
      "task" : "check-concurrent-requests",
      "start" : 1644915085402,
      "start_fmt" : "2022-02-15T09:51:25.402+01:00",
      "stop" : 1644915085402,
      "stop_fmt" : "2022-02-15T09:51:25.402+01:00",
      "duration" : 0,
      "duration_ns" : 145242,
      "ctx" : null
    }, {
      "task" : "find-route",
      "start" : 1644915085402,
      "start_fmt" : "2022-02-15T09:51:25.402+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 497119,
      "ctx" : {
        "found_route" : {
          "_loc" : {
            "tenant" : "default",
            "teams" : [ "default" ]
          },
          "id" : "service_dev_d54f11d0-18e2-4da4-9316-cf47733fd29a",
          "name" : "hey",
          "description" : "hey",
          "tags" : [ "env:prod" ],
          "metadata" : { },
          "enabled" : true,
          "debug_flow" : true,
          "export_reporting" : false,
          "groups" : [ "default" ],
          "frontend" : {
            "domains" : [ "hey-next-gen.oto.tools/", "hey.oto.tools/" ],
            "strip_path" : true,
            "exact" : false,
            "headers" : { },
            "methods" : [ ]
          },
          "backend" : {
            "targets" : [ {
              "id" : "127.0.0.1:8081",
              "hostname" : "127.0.0.1",
              "port" : 8081,
              "tls" : false,
              "weight" : 1,
              "protocol" : "HTTP/1.1",
              "ip_address" : null,
              "tls_config" : {
                "certs" : [ ],
                "trustedCerts" : [ ],
                "mtls" : false,
                "loose" : false,
                "trustAll" : false
              }
            } ],
            "target_refs" : [ ],
            "root" : "/",
            "rewrite" : false,
            "load_balancing" : {
              "type" : "RoundRobin"
            },
            "client" : {
              "useCircuitBreaker" : true,
              "retries" : 1,
              "maxErrors" : 20,
              "retryInitialDelay" : 50,
              "backoffFactor" : 2,
              "callTimeout" : 30000,
              "callAndStreamTimeout" : 120000,
              "connectionTimeout" : 10000,
              "idleTimeout" : 60000,
              "globalTimeout" : 30000,
              "sampleInterval" : 2000,
              "proxy" : { },
              "customTimeouts" : [ ],
              "cacheConnectionSettings" : {
                "enabled" : false,
                "queueSize" : 2048
              }
            }
          },
          "backend_ref" : null,
          "plugins" : [ ]
        },
        "matched_path" : "",
        "exact" : true,
        "params" : { },
        "matched_routes" : [ "service_dev_d54f11d0-18e2-4da4-9316-cf47733fd29a" ]
      }
    }, {
      "task" : "compute-plugins",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 105151,
      "ctx" : {
        "disabled_plugins" : [ ],
        "filtered_plugins" : [ ]
      }
    }, {
      "task" : "tenant-check",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 26097,
      "ctx" : null
    }, {
      "task" : "check-global-maintenance",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 14132,
      "ctx" : null
    }, {
      "task" : "call-before-request-callbacks",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 56671,
      "ctx" : null
    }, {
      "task" : "extract-tracking-id",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 5207,
      "ctx" : null
    }, {
      "task" : "call-pre-route-plugins",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 39786,
      "ctx" : null
    }, {
      "task" : "call-access-validator-plugins",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085403,
      "stop_fmt" : "2022-02-15T09:51:25.403+01:00",
      "duration" : 0,
      "duration_ns" : 25311,
      "ctx" : null
    }, {
      "task" : "enforce-global-limits",
      "start" : 1644915085403,
      "start_fmt" : "2022-02-15T09:51:25.403+01:00",
      "stop" : 1644915085404,
      "stop_fmt" : "2022-02-15T09:51:25.404+01:00",
      "duration" : 0,
      "duration_ns" : 296617,
      "ctx" : {
        "remaining_quotas" : {
          "authorizedCallsPerSec" : 10000000,
          "currentCallsPerSec" : 10000000,
          "remainingCallsPerSec" : 10000000,
          "authorizedCallsPerDay" : 10000000,
          "currentCallsPerDay" : 10000000,
          "remainingCallsPerDay" : 10000000,
          "authorizedCallsPerMonth" : 10000000,
          "currentCallsPerMonth" : 10000000,
          "remainingCallsPerMonth" : 10000000
        }
      }
    }, {
      "task" : "choose-backend",
      "start" : 1644915085404,
      "start_fmt" : "2022-02-15T09:51:25.404+01:00",
      "stop" : 1644915085404,
      "stop_fmt" : "2022-02-15T09:51:25.404+01:00",
      "duration" : 0,
      "duration_ns" : 368899,
      "ctx" : {
        "backend" : {
          "id" : "127.0.0.1:8081",
          "hostname" : "127.0.0.1",
          "port" : 8081,
          "tls" : false,
          "weight" : 1,
          "protocol" : "HTTP/1.1",
          "ip_address" : null,
          "tls_config" : {
            "certs" : [ ],
            "trustedCerts" : [ ],
            "mtls" : false,
            "loose" : false,
            "trustAll" : false
          }
        }
      }
    }, {
      "task" : "transform-request",
      "start" : 1644915085404,
      "start_fmt" : "2022-02-15T09:51:25.404+01:00",
      "stop" : 1644915085404,
      "stop_fmt" : "2022-02-15T09:51:25.404+01:00",
      "duration" : 0,
      "duration_ns" : 506363,
      "ctx" : null
    }, {
      "task" : "call-backend",
      "start" : 1644915085404,
      "start_fmt" : "2022-02-15T09:51:25.404+01:00",
      "stop" : 1644915085407,
      "stop_fmt" : "2022-02-15T09:51:25.407+01:00",
      "duration" : 2,
      "duration_ns" : 2163470,
      "ctx" : null
    }, {
      "task" : "transform-response",
      "start" : 1644915085407,
      "start_fmt" : "2022-02-15T09:51:25.407+01:00",
      "stop" : 1644915085407,
      "stop_fmt" : "2022-02-15T09:51:25.407+01:00",
      "duration" : 0,
      "duration_ns" : 279887,
      "ctx" : null
    }, {
      "task" : "stream-response",
      "start" : 1644915085407,
      "start_fmt" : "2022-02-15T09:51:25.407+01:00",
      "stop" : 1644915085407,
      "stop_fmt" : "2022-02-15T09:51:25.407+01:00",
      "duration" : 0,
      "duration_ns" : 382952,
      "ctx" : null
    }, {
      "task" : "trigger-analytics",
      "start" : 1644915085407,
      "start_fmt" : "2022-02-15T09:51:25.407+01:00",
      "stop" : 1644915085408,
      "stop_fmt" : "2022-02-15T09:51:25.408+01:00",
      "duration" : 0,
      "duration_ns" : 812036,
      "ctx" : null
    }, {
      "task" : "request-success",
      "start" : 1644915085408,
      "start_fmt" : "2022-02-15T09:51:25.408+01:00",
      "stop" : 1644915085408,
      "stop_fmt" : "2022-02-15T09:51:25.408+01:00",
      "duration" : 0,
      "duration_ns" : 0,
      "ctx" : null
    } ]
  }
}
```

## Debugging

with the new reporting capabilities, the new engine also have debugging capabilities built in. In you enable the `debug_flow` flag on a route (or service), the resulting `RequestFlowReport` will be enriched with contextual informations between each plugins of the route plugin pipeline

@@@ note
you can also use the `Try it` feature of the new route designer UI to get debug reports automatically for a specific call
@@@

## HTTP traffic capture

using the `capture` flag, a `TrafficCaptureEvent` is generated for each http request/response. This event will contains request and response body. Those events can be exported using @ref:[data exporters](../entities/data-exporters.md) as usual. You can also use the @ref:[GoReplay file exporter](../entities/data-exporters.md#goreplay-file) that is specifically designed to ingest those events and create [GoReplay](https://goreplay.org/) files (`.gor`)

@@@ warning
this feature can have actual impact on CPU and RAM consumption
@@@

```json
{
  "@id": "d5998b0c4-cb08-43e6-9921-27472c7a56e0",
  "@timestamp": 1651828801115,
  "@type": "TrafficCaptureEvent",
  "@product": "otoroshi",
  "@serviceId": "route_2b2670879-131c-423d-b755-470c7b1c74b1",
  "@service": "test-server",
  "@env": "prod",
  "route": {
    "id": "route_2b2670879-131c-423d-b755-470c7b1c74b1",
    "name": "test-server"
  },
  "request": {
    "id": "152250645825034725600000",
    "int_id": 115,
    "method": "POST",
    "headers": {
      "Host": "test-server-next-gen.oto.tools:9999",
      "Accept": "*/*",
      "Cookie": "fifoo=fibar",
      "User-Agent": "curl/7.64.1",
      "Content-Type": "application/json",
      "Content-Length": "13",
      "Remote-Address": "127.0.0.1:57660",
      "Timeout-Access": "<function1>",
      "Raw-Request-URI": "/",
      "Tls-Session-Info": "Session(1651828041285|SSL_NULL_WITH_NULL_NULL)"
    },
    "cookies": [
      {
        "name": "fifoo",
        "value": "fibar",
        "path": "/",
        "domain": null,
        "http_only": true,
        "max_age": null,
        "secure": false,
        "same_site": null
      }
    ],
    "tls": false,
    "uri": "/",
    "path": "/",
    "version": "HTTP/1.1",
    "has_body": true,
    "remote": "127.0.0.1",
    "client_cert_chain": null,
    "body": "{\"foo\":\"bar\"}"
  },
  "backend_request": {
    "url": "http://localhost:3000/",
    "method": "POST",
    "headers": {
      "Host": "localhost",
      "Accept": "*/*",
      "Cookie": "fifoo=fibar",
      "User-Agent": "curl/7.64.1",
      "Content-Type": "application/json",
      "Content-Length": "13"
    },
    "version": "HTTP/1.1",
    "client_cert_chain": null,
    "cookies": [
      {
        "name": "fifoo",
        "value": "fibar",
        "domain": null,
        "path": "/",
        "maxAge": null,
        "secure": false,
        "httpOnly": true
      }
    ],
    "id": "152260631569472064900000",
    "int_id": 33,
    "body": "{\"foo\":\"bar\"}"
  },
  "backend_response": {
    "status": 200,
    "headers": {
      "Date": "Fri, 06 May 2022 09:20:01 GMT",
      "Connection": "keep-alive",
      "Set-Cookie": "foo=bar",
      "Content-Type": "application/json",
      "Transfer-Encoding": "chunked"
    },
    "cookies": [
      {
        "name": "foo",
        "value": "bar",
        "domain": null,
        "path": null,
        "maxAge": null,
        "secure": false,
        "httpOnly": false
      }
    ],
    "id": "152260631569472064900000",
    "status_txt": "OK",
    "http_version": "HTTP/1.1",
    "body": "{\"headers\":{\"host\":\"localhost\",\"accept\":\"*/*\",\"user-agent\":\"curl/7.64.1\",\"content-type\":\"application/json\",\"cookie\":\"fifoo=fibar\",\"content-length\":\"13\"},\"method\":\"POST\",\"path\":\"/\",\"body\":\"{\\\"foo\\\":\\\"bar\\\"}\"}"
  },
  "response": {
    "id": "152250645825034725600000",
    "status": 200,
    "headers": {
      "Date": "Fri, 06 May 2022 09:20:01 GMT",
      "Connection": "keep-alive",
      "Set-Cookie": "foo=bar",
      "Content-Type": "application/json",
      "Transfer-Encoding": "chunked"
    },
    "cookies": [
      {
        "name": "foo",
        "value": "bar",
        "domain": null,
        "path": null,
        "maxAge": null,
        "secure": false,
        "httpOnly": false
      }
    ],
    "status_txt": "OK",
    "http_version": "HTTP/1.1",
    "body": "{\"headers\":{\"host\":\"localhost\",\"accept\":\"*/*\",\"user-agent\":\"curl/7.64.1\",\"content-type\":\"application/json\",\"cookie\":\"fifoo=fibar\",\"content-length\":\"13\"},\"method\":\"POST\",\"path\":\"/\",\"body\":\"{\\\"foo\\\":\\\"bar\\\"}\"}"
  },
  "user-agent-details": null,
  "origin-details": null,
  "instance-number": 0,
  "instance-name": "dev",
  "instance-zone": "local",
  "instance-region": "local",
  "instance-dc": "local",
  "instance-provider": "local",
  "instance-rack": "local",
  "cluster-mode": "Leader",
  "cluster-name": "otoroshi-leader-9hnv5HUXpbCZD7Ee"
}
```

## openapi import

as the new router offers possibility to match exactly on a single path and a single method, and with the help of the `service` entity, it is now pretty easy to import openapi document as `route-compositions` entities. To do that, a new api has been made available to perform the translation. Be aware that this api **DOES NOT** save the entity and just return the result of the translation. 

```sh
curl -X POST \
  -H 'Content-Type: application/json' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  'http://otoroshi-api.oto.tools:8080/api/route-compositions/_openapi' \
  -d '{"domain":"oto-api-proxy.oto.tools","openapi":"https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json"}'
```

@@@ div { .centered-img }
<img src="../imgs/route_comp_openapi_import.png" />
@@@

