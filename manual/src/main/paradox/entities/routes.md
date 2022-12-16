# Routes

A route is an unique routing rule based on hostname, path, method and headers that will execute a bunch of plugins and eventually forward the request to the backend application.

## UI page

You can find all routes [here](http://otoroshi.oto.tools:8080/bo/dashboard/routes)

## Global Properties

* `location`: the location of the entity
* `id`: the id of the route
* `name`: the name of the route
* `description`: the description of the route
* `tags`: the tags of the route. can be useful for api automation
* `metadata`: the metadata of the route. can be useful for api automation. There are a few reserved metadata used by otorshi that can be found @ref[below](./routes.md#reserved-metadata)
* `enabled`: is the route enabled ? if not, the router will not consider this route
* `debugFlow`: the debug flag. If enabled, the execution report for this route will contain all input/output values through steps of the proxy engine. For more informations, check the @ref[engine documentation](../topics/engine.md#reporting)
* `capture`: if enabled, otoroshi will generate events containing the whole content of each request. Use with caution ! For more informations, check the @ref[engine documentation](../topics/engine.md#http-traffic-capture)
* `exportReporting`: if enabled, execution reports of the proxy engine will be generated for each request. Those reports are exportable using @ref[data exporters](./data-exporters.md) . For more informations, check the @ref[engine documentation](../topics/engine.md#reporting)
* `groups`: each route is attached to a group. A group can have one or more services/routes. Each API key is linked to groups/routes/services and allow access to every entities in the groups.

### Reserved metadata

some metadata are reserved for otoroshi usage. Here is the list of reserved metadata

* `otoroshi-core-user-facing`: is this a user facing app for the snow monkey
* `otoroshi-core-use-akka-http-client`: use the pure akka http client
* `otoroshi-core-use-netty-http-client`: use the pure netty http client
* `otoroshi-core-use-akka-http-ws-client`: use the modern websocket client
* `otoroshi-core-issue-lets-encrypt-certificate`: enabled let's encrypt certificate issue for this route. true or false
* `otoroshi-core-issue-certificate`: enabled certificate issue for this route. true or false
* `otoroshi-core-issue-certificate-ca`: the id of the CA cert to generate the certificate for this route
* `otoroshi-core-openapi-url`: the openapi url for this route
* `otoroshi-core-env`: the env for this route. here for legacy reasons
* `otoroshi-deployment-providers`: in the case of relay routing, the providers for this route
* `otoroshi-deployment-regions`: in the case of relay routing, the network regions for this route
* `otoroshi-deployment-zones`: in the case of relay routing, the network zone for this route 
* `otoroshi-deployment-dcs`: in the case of relay routing, the datacenter for this route 
* `otoroshi-deployment-racks`: in the case of relay routing, the  rack for this route 

## Frontend configuration

* `frontend`: the frontend of the route. It's the configuration that will configure how otoroshi router will match this route. A frontend has the following shape. 

```javascript
{
  "domains": [ // the matched domains and paths
    "new-route.oto.tools/path" // here you can use wildcard in domain and path, also you can use named path params
  ],
  "strip_path": true, // is the matched path stripped in the forwarded request
  "exact": false, // perform exact matching on path, if not, will be matched on /path*
  "headers": {}, // the matched http headers. if none provided, any header will be matched
  "query": {}, // the matched http query params. if none provided, any query params will be matched
  "methods": [] // the matched http methods. if none provided, any method will be matched
}
```

For more informations about routing, check the @ref[engine documentation](../topics/engine.md#routing)

## Backend configuration

* `backend`: a backend to forward requests to. For more informations, go to the @ref[backend documentation](./backends.md)
* `backendRef`: a reference to an existing backend id

## Plugins

the liste of plugins used on this route. Each plugin definition has the following shape:

```javascript
{
  "enabled": false, // is the plugin enabled
  "debug": false, // is debug enabled of this specific plugin
  "plugin": "cp:otoroshi.next.plugins.Redirection", // the id of the plugin
  "include": [], // included paths. if none, all paths are included
  "exclude": [], // excluded paths. if none, none paths are excluded
  "config": { // the configuration of the plugin
    "code": 303,
    "to": "https://www.otoroshi.io"
  },
  "plugin_index": { // the position of the plugin. if none provided, otoroshi will use the order in the plugin array
    "pre_route": 0
  }
}
```

for more informations about the available plugins, go @ref[here](../plugins/built-in-plugins.md)


