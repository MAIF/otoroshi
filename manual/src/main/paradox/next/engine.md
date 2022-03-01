# New proxy engine

Starting from the `1.5.3` release, otoroshi offers a new plugin that implements the next generation of the proxy engine. 
This engine has been designed based on our 5 years experience building, maintaining and running the previous one. 
It tries to fix all the drawback we may have encountered during those years and highly improve performances, user experience, reporting and debugging capabilities. 
The new engine is fully plugin oriented in order to spend CPU cycles only on useful stuff.
This engine is **experimental** and may not work as expected !
You can enable this plugin only on some domain names so you can easily A/B test the new engine.
The new proxy engine is designed to be more reactive and more efficient generally.
It is also designed to be very efficient on path routing where it wasn't the old engines strong suit.

## Enabling the new engine

To enable the new proxy engine on an otoroshi instance, just add the plugin in the `global plugins` section of the danger zone, inject the default configuration, enable it and in `domains` add the values of the desired domains (let say we want to use the new engine on `api.foo.bar`. It is possible to use `*.foo.bar` if that's what you want to do).

The next time a request hits the `api.foo.bar` domain, the new engine will handle it instead of the previous one.

```json
{
  "NextGenProxyEngine" : {
    "enabled" : true,
    "debug_headers" : false,
    "reporting": true,
    "routing_strategy" : "tree",
    "merge_sync_steps" : true,
    "domains" : [ "api.foo.bar" ],
    "deny_domains" : [ ],
  }
}
```

## Entities

This plugin introduces new entities that will replace (one day maybe) service descriptors:

 - `routes`: a unique routing rule based on hostname, path, method and headers that will execute a bunch of plugins
 - `services`: multiple routing rules based on hostname, path, method and headers that will execute the same list of plugins
 - `targets`: how to contact a backend either by using a domain name or an ip address, supports mtls
 - `backends`: a list of targets to contact a backend

## Automatic conversion

The new engine uses new entities for its configuration, but in order to facilitate transition between the old world and the new world, all the `service descriptors` of an otoroshi instance are automatically converted live into `routes` periodically. Any `service descriptor` should still work as expected through the new engine while enjoying all the perks.

## Routing

the new proxy engine introduces a new router that has enhanced capabilities and performances. The router can handle thousands of routes declarations without compromising performances.

The new route allow routes to be matched on a combination of

* hostname
* path
* header values
    * where values can be exact, or `Regex(value_regex)`, or `Wildcard(value_with_*)`
* query param values
    * where values can be exact, or `Regex(value_regex)`, or `Wildcard(value_with_*)`

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

the new route entity defines a plugin pipline where any plugin can be `enabled`/`disabled` globally or for some paths. Each plugin slot in the pipeline holds the plugin id and the plugin configuration. You can also enable debugging only on a plugin instance instead of the whole route

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

## openapi import

as the new router offers possibility to match exactly on a single path and a single method, and with the help of the `service` entity, it is now pretty easy to import openapi document as `service` entities. To do that, a new api has been made available to perform the translation. Be aware that this api **DOES NOT** save the entity and just return the result of the translation.

```sh
curl -X POST \
  -H 'Content-Type: application/json' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  'http://otoroshi-api.oto.tools:8080/api/experimental/services/_openapi' \
  -d '{"domain":"oto-api-proxy.oto.tools","openapi":"https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json"}'
```



