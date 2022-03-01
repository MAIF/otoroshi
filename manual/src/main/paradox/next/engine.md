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

## Reporting

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



