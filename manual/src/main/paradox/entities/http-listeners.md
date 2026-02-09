# HTTP Listeners

HTTP Listeners allow you to create custom HTTP listeners on specific ports, enabling Otoroshi to serve traffic on multiple ports with different protocol configurations. Each listener can be configured independently for TLS, HTTP/1.1, HTTP/2, H2C, and HTTP/3 support.

## UI page

You can find all HTTP listeners [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/cloud-apim/http-listeners)

## Properties

* `id`: unique identifier of the HTTP listener
* `name`: display name of the listener
* `description`: description of the listener
* `tags`: list of tags associated to the listener
* `metadata`: list of metadata associated to the listener
* `config`: the listener configuration (see below)

## Configuration

* `enabled`: is the listener enabled
* `exclusive`: if enabled, this listener will only handle routes that are explicitly bound to it. Routes not bound to this listener will not be served on its port
* `tls`: enable TLS/HTTPS on this listener (default: true)
* `http1`: enable HTTP/1.1 protocol support (default: true)
* `http2`: enable HTTP/2 protocol support (default: true)
* `h2c`: enable H2C (HTTP/2 Cleartext, without TLS) protocol support (default: false)
* `http3`: enable HTTP/3 protocol support (default: false)
* `port`: the port on which the listener will accept connections (default: 7890)
* `exposedPort`: the externally exposed port, useful when running behind a load balancer or inside a container (default: 7890)
* `host`: the host address to bind to (default: `0.0.0.0`)
* `accessLog`: enable access logging for this listener (default: false)
* `clientAuth`: mTLS client authentication mode. Possible values are `None`, `Want`, and `Need` (default: `None`)

## JSON example

```json
{
  "id": "http-listener_abc123",
  "name": "My custom listener",
  "description": "A custom HTTPS listener on port 9443",
  "tags": [],
  "metadata": {},
  "config": {
    "enabled": true,
    "exclusive": false,
    "tls": true,
    "http1": true,
    "http2": true,
    "h2c": false,
    "http3": false,
    "port": 9443,
    "exposedPort": 9443,
    "host": "0.0.0.0",
    "accessLog": false,
    "clientAuth": "None"
  }
}
```

## Learn more

For detailed information about setting up HTTP listeners, binding routes, and using static vs dynamic listeners, see @ref:[Custom HTTP Listeners](../topics/http-listeners.md).

You can also check:

* @ref:[HTTP3 support](../topics/http3.md) for HTTP/3 protocol configuration
* @ref:[Alternative HTTP server](../topics/netty-server.md) for information about the Netty-based HTTP server
