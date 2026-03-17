# HTTP/3 support

Otoroshi supports HTTP/3 (QUIC) both as a server (accepting HTTP/3 connections from clients) and as a client (calling backends over HTTP/3). HTTP/3 runs over the QUIC protocol using UDP, providing faster connection establishment (0-RTT), better handling of packet loss, and connection migration compared to TCP-based HTTP/2.

The implementation is based on [netty-incubator-codec-quic](https://github.com/netty/netty-incubator-codec-quic) and [netty-incubator-codec-http3](https://github.com/netty/netty-incubator-codec-http3).

## HTTP/3 server

### Enable HTTP/3

HTTP/3 requires the Netty server to be enabled. Add the following configuration:

```conf
otoroshi.next.experimental.netty-server.enabled = true
otoroshi.next.experimental.netty-server.http3.enabled = true
otoroshi.next.experimental.netty-server.http3.port = 10048
```

Or via environment variables:

```sh
OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED=true
OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_ENABLED=true
OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_PORT=10048
```

On startup, the log will show the HTTP/3 listener:

```log
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/3)
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
```

The HTTP/3 server can share the same port number as the HTTPS server because QUIC uses UDP while HTTPS uses TCP.

### Server configuration

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `http3.enabled` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_ENABLED` | Enable HTTP/3 |
| `http3.port` | `10048` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_PORT` | UDP port for QUIC |
| `http3.exposedPort` | `10048` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_EXPOSED_PORT` | Externally visible HTTP/3 port (used in `alt-svc` headers) |
| `http3.maxSendUdpPayloadSize` | `1500` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_MAX_SEND_UDP_PAYLOAD_SIZE` | Maximum outgoing UDP payload size (bytes) |
| `http3.maxRecvUdpPayloadSize` | `1500` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_MAX_RECV_UDP_PAYLOAD_SIZE` | Maximum incoming UDP payload size (bytes) |
| `http3.initialMaxData` | `10000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_DATA` | Initial flow control limit per connection (bytes) |
| `http3.initialMaxStreamDataBidirectionalLocal` | `1000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL` | Initial flow control limit per locally-initiated stream (bytes) |
| `http3.initialMaxStreamDataBidirectionalRemote` | `1000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE` | Initial flow control limit per remotely-initiated stream (bytes) |
| `http3.initialMaxStreamsBidirectional` | `100000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAMS_BIDIRECTIONAL` | Maximum number of concurrent bidirectional streams |
| `http3.disableQpackDynamicTable` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_DISABLE_QPACK_DYNAMIC_TABLE` | Disable QPACK dynamic table for header compression. Disabled by default for browser compatibility |

### Full configuration example

```conf
otoroshi.next.experimental.netty-server {
  enabled = true
  http3 {
    enabled = true
    port = 10048
    exposedPort = 443
    maxSendUdpPayloadSize = 1500
    maxRecvUdpPayloadSize = 1500
    initialMaxData = 10000000
    initialMaxStreamDataBidirectionalLocal = 1000000
    initialMaxStreamDataBidirectionalRemote = 1000000
    initialMaxStreamsBidirectional = 100000
    disableQpackDynamicTable = true
  }
}
```

### How it works

The HTTP/3 server uses a dedicated UDP socket (via `NioDatagramChannel`) alongside the TCP-based HTTPS server:

1. QUIC connections are accepted using the Netty QUIC codec
2. For each incoming QUIC stream, Otoroshi creates an HTTP/3 request handler
3. TLS is mandatory for QUIC. The server uses Otoroshi's dynamic certificate engine with SNI-based certificate selection (same certificates as the HTTPS server)
4. SSL contexts are cached with a 5-second TTL for performance
5. The server supports 0-RTT early data for faster connection establishment
6. Client authentication (mTLS) is supported: `None`, `Want`, or `Need`
7. Trailer headers are supported

### Testing the server

Using [quiche-client](https://github.com/cloudflare/quiche):

```sh
cargo run --bin quiche-client -- --no-verify 'https://my-service.oto.tools:10048'
```

Using `curl` (requires curl 7.66+ with HTTP/3 support):

```sh
curl --http3 'https://my-service.oto.tools:10048'
```

## HTTP/3 client

Otoroshi can call backend targets over HTTP/3. This is useful when your backend supports QUIC and you want to benefit from its performance characteristics (reduced latency, better packet loss handling).

### Configure a backend for HTTP/3

In the route or backend target configuration, set the protocol to `HTTP/3.0`:

```json
{
  "backend": {
    "targets": [
      {
        "hostname": "backend.example.com",
        "port": 443,
        "tls": true,
        "protocol": "HTTP/3.0"
      }
    ]
  }
}
```

When `protocol` is set to `HTTP/3.0`, Otoroshi automatically uses the Netty HTTP/3 client instead of the standard HTTP client.

### Client capabilities

The HTTP/3 client supports:

* All standard HTTP methods (GET, POST, PUT, DELETE, PATCH, etc.)
* Request and response body streaming
* Custom headers
* mTLS to the backend (using Otoroshi certificate management)
* SNI (Server Name Indication)
* Trailer headers
* Connection pooling and reuse (cached channels with up to 999 entries)
* TLS trust configuration (`trustAll`, custom trusted certificates)

### Header filtering

When Otoroshi proxies a request to an HTTP/3 backend, it automatically filters out protocol-specific headers:

* `x-http2-*` headers are removed when the target is HTTP/1.x
* `x-http3-*` headers are removed when the target is HTTP/1.x or HTTP/2

## Alt-Svc header plugin

To help clients discover HTTP/3 availability, Otoroshi provides a built-in plugin that injects the `Alt-Svc` response header. This header tells HTTP/2 or HTTP/1.1 clients that the server supports HTTP/3 on a specific port, allowing them to upgrade.

### Plugin: `Http3 traffic switch`

Add the `cp:otoroshi.next.plugins.Http3Switch` plugin to a route:

```json
{
  "plugin": "cp:otoroshi.next.plugins.Http3Switch",
  "enabled": true,
  "config": {
    "ma": 3600,
    "domain": "",
    "protocols": []
  }
}
```

| Config key | Type | Default | Description |
|------------|------|---------|-------------|
| `ma` | number | `3600` | Max-Age in seconds: how long the client should remember the HTTP/3 alternative |
| `domain` | string | `""` | Domain to advertise in the `alt-svc` header. Empty string means same domain as the request |
| `protocols` | array of string | `[]` | ALPN protocols to advertise. Empty means auto-detect from `Http3.supportedApplicationProtocols()` |

The plugin adds a response header like:

```
alt-svc: h3=":10048"; ma=3600
```

This tells the client that HTTP/3 is available on port 10048 and the information is valid for 3600 seconds.

## HTTP/3 on custom HTTP listeners

@ref:[Custom HTTP listeners](./http-listeners.md) also support HTTP/3. Enable it in the listener configuration:

```json
{
  "config": {
    "enabled": true,
    "tls": true,
    "http1": true,
    "http2": true,
    "http3": true,
    "port": 8443,
    "exposedPort": 443
  }
}
```

HTTP/3 requires TLS to be enabled (`tls: true`). When enabled, the listener starts a QUIC/UDP socket on the same port as the TCP listener.

## Related

* @ref:[Netty Server](./netty-server.md) - Netty server configuration and HTTP/2 support
* @ref:[Custom HTTP Listeners](./http-listeners.md) - Dynamic listeners with HTTP/3 support
* @ref:[TLS](./tls.md) - TLS and certificate management
