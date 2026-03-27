# Netty Server

Otoroshi uses a Netty-based HTTP server built on [Reactor Netty](https://projectreactor.io/docs/netty/release/reference/index.html) as an alternative to Akka HTTP. This server supports HTTP/1.1, HTTP/2 (including H2C cleartext), and HTTP/3 (QUIC). It is also the foundation for @ref:[custom HTTP listeners](./http-listeners.md).

## Enable the server

To enable the Netty server, set the following configuration:

```conf
otoroshi.next.experimental.netty-server.enabled = true
```

or via environment variable:

```sh
OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED=true
```

On startup, you should see something like:

```log
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
```

## How it works

The Netty server starts up to **three separate server instances**:

| Server | Transport | Port (default) | Protocols | Description |
|--------|-----------|----------------|-----------|-------------|
| HTTPS | TCP | 10048 | HTTP/1.1, HTTP/2 | TLS-terminated with ALPN negotiation for HTTP/2 |
| HTTP | TCP | 10049 | HTTP/1.1, H2C | Cleartext HTTP with optional HTTP/2 cleartext upgrade |
| HTTP/3 | UDP (QUIC) | 10048 | HTTP/3 | Separate QUIC-based server (can share the same port number as HTTPS since it uses UDP) |

Each server uses its own event loop group. All servers share the same dynamic TLS engine (`DynamicSSLEngineProvider`) for automatic SNI-based certificate selection.

## Server configuration

### General settings

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `enabled` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED` | Enable the Netty server |
| `new-engine-only` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NEW_ENGINE_ONLY` | Only use the new proxy engine (skip Play routing) |
| `host` | `0.0.0.0` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HOST` | Network interface to bind to |
| `http-port` | `10049` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_PORT` | Cleartext HTTP port |
| `exposed-http-port` | same as `http-port` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_EXPOSED_HTTP_PORT` | Externally visible HTTP port (behind load balancer or in containers) |
| `https-port` | `10048` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTPS_PORT` | TLS HTTPS port |
| `exposed-https-port` | same as `https-port` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_EXPOSED_HTTPS_PORT` | Externally visible HTTPS port |
| `threads` | `0` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_THREADS` | Number of worker threads. `0` = auto (based on CPU cores) |
| `wiretap` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_WIRETAP` | Enable wire-level debug logging of all Netty channel operations |
| `accesslog` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ACCESSLOG` | Enable access logging (see [access logging](#access-logging)) |

### Example configuration

```conf
otoroshi.next.experimental.netty-server {
  enabled = true
  host = "0.0.0.0"
  http-port = 10049
  https-port = 10048
  threads = 0
  accesslog = true
  wiretap = false
  http2 {
    enabled = true
    h2c = true
  }
  http3 {
    enabled = true
    port = 10048
  }
  native {
    enabled = true
    driver = "Auto"
  }
}
```

## HTTP protocol settings

### HTTP/1.1

HTTP/1.1 is enabled by default. It supports keep-alive connections, chunked transfer encoding, and WebSocket upgrades.

### HTTP/2

HTTP/2 is enabled by default over TLS via ALPN negotiation. H2C (HTTP/2 cleartext) is also enabled by default on the HTTP port.

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `http2.enabled` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP2_ENABLED` | Enable HTTP/2 over TLS |
| `http2.h2c` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP2_H2C` | Enable HTTP/2 cleartext (H2C) on the HTTP port |

### HTTP/3 (QUIC)

HTTP/3 runs over the QUIC protocol using UDP. It uses [netty-incubator-codec-quic](https://github.com/netty/netty-incubator-codec-quic) and [netty-incubator-codec-http3](https://github.com/netty/netty-incubator-codec-http3). The HTTP/3 port can be the same as the HTTPS port since QUIC uses UDP while HTTPS uses TCP.

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `http3.enabled` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_ENABLED` | Enable HTTP/3 |
| `http3.port` | `10048` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_PORT` | UDP port for QUIC |
| `http3.exposedPort` | `10048` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_EXPOSED_PORT` | Externally visible HTTP/3 port |
| `http3.maxSendUdpPayloadSize` | `1500` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_MAX_SEND_UDP_PAYLOAD_SIZE` | Maximum outgoing UDP payload size (bytes) |
| `http3.maxRecvUdpPayloadSize` | `1500` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_MAX_RECV_UDP_PAYLOAD_SIZE` | Maximum incoming UDP payload size (bytes) |
| `http3.initialMaxData` | `10000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_DATA` | Initial flow control limit per connection (bytes) |
| `http3.initialMaxStreamDataBidirectionalLocal` | `1000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL` | Initial flow control limit per locally-initiated stream (bytes) |
| `http3.initialMaxStreamDataBidirectionalRemote` | `1000000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE` | Initial flow control limit per remotely-initiated stream (bytes) |
| `http3.initialMaxStreamsBidirectional` | `100000` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAMS_BIDIRECTIONAL` | Maximum number of concurrent bidirectional streams |
| `http3.disableQpackDynamicTable` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_DISABLE_QPACK_DYNAMIC_TABLE` | Disable QPACK dynamic table for header compression |

When HTTP/3 is enabled, the startup log will show:

```log
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/3)
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
```

## HTTP parser settings

These settings control how the Netty HTTP decoder processes incoming requests. The defaults follow Netty's `HttpDecoderSpec` values.

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `parser.allowDuplicateContentLengths` | `false` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_ALLOW_DUPLICATE_CONTENT_LENGTHS` | Allow duplicate Content-Length headers |
| `parser.validateHeaders` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_VALIDATE_HEADERS` | Validate HTTP header names and values |
| `parser.h2cMaxContentLength` | `65536` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_H_2_C_MAX_CONTENT_LENGTH` | Maximum content length for H2C upgrade requests (bytes) |
| `parser.initialBufferSize` | `1024` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_INITIAL_BUFFER_SIZE` | Initial buffer size for the HTTP decoder (bytes) |
| `parser.maxHeaderSize` | `8192` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_HEADER_SIZE` | Maximum size of all HTTP headers combined (bytes) |
| `parser.maxInitialLineLength` | `4096` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_INITIAL_LINE_LENGTH` | Maximum length of the initial HTTP request line (bytes) |
| `parser.maxChunkSize` | `8192` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_CHUNK_SIZE` | Maximum size of a single HTTP chunk (bytes) |

## Native transport

Native transport provides higher performance by using OS-specific I/O mechanisms instead of Java NIO. It is enabled by default with automatic driver detection.

| Config key | Default | Env variable | Description |
|------------|---------|--------------|-------------|
| `native.enabled` | `true` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NATIVE_ENABLED` | Enable native transport |
| `native.driver` | `Auto` | `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NATIVE_DRIVER` | Native transport driver (see below) |

### Available drivers

| Driver | Platform | Description |
|--------|----------|-------------|
| `Auto` | Any | Automatically selects the best available native transport for the current platform |
| `Epoll` | Linux | Uses Linux epoll for high-performance I/O |
| `KQueue` | macOS / BSD | Uses BSD kqueue for high-performance I/O |
| `IOUring` | Linux (5.1+) | Uses Linux io_uring for asynchronous I/O (experimental, via [netty-incubator-transport-io_uring](https://github.com/netty/netty-incubator-transport-io_uring)) |

If the selected native driver is not available on the platform, the server falls back to Java NIO.

When native transport is active, the log will show the driver being used:

```log
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   using KQueue native transport
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
```

## TLS configuration

The Netty server uses Otoroshi's dynamic TLS engine for automatic certificate selection based on SNI (Server Name Indication). TLS configuration is inherited from the global Otoroshi settings:

* **Cipher suites**: from `otoroshi.ssl.cipherSuites`
* **TLS protocols**: from `otoroshi.ssl.protocols`
* **Client authentication**: configurable per server (via custom HTTP listeners), defaults from global SSL config

ALPN negotiation is used to select between HTTP/1.1 and HTTP/2 on the HTTPS port. The server prefers HTTP/2 (`h2`) when the client supports it, and falls back to `http/1.1`.

For HTTP/3, the QUIC stack uses its own TLS context with dynamic certificate loading per SNI domain and 0-RTT early data enabled.

## Access logging

When `accesslog` is enabled, each request is logged using the following format:

```
{remote_address} - {user} [{date_time}] "{method} {uri} {protocol}" {status} {content_length} {duration_ms} {tls_version}
```

Example:

```
192.168.1.10 - - [13/Mar/2026:10:30:45 +0100] "GET /api/users HTTP/1.1" 200 1024 12 TLSv1.3
```

The TLS version field shows the negotiated TLS version (e.g., `TLSv1.2`, `TLSv1.3`) or `-` for cleartext connections.

Access logs are written via the `reactor.netty.http.server.AccessLog` logger.

## WebSocket support

The Netty server fully supports WebSocket connections over HTTP/1.1 and HTTP/2. WebSocket upgrades are detected via the `Upgrade` and `Sec-WebSocket-Version` headers and routed to the proxy engine's WebSocket handler.

## Trailer headers

The server supports HTTP/2 and HTTP/3 trailer headers. Trailers are stored in an internal cache (TTL: 10 seconds, max 1000 entries) and can be accessed asynchronously during request processing.

## Thread pool

The server creates separate event loop groups for HTTP and HTTPS connections:

* When `threads = 0` (default): Reactor Netty automatically determines the number of threads based on available CPU cores
* When `threads > 0`: the specified number of threads is used for each event loop group

The HTTP/3 server uses its own `NioEventLoopGroup` with the same thread count setting.

## Relationship with custom HTTP listeners

The Netty server described here is the "experimental" built-in server started from the configuration file. @ref:[Custom HTTP listeners](./http-listeners.md) use the same Netty infrastructure but can be created dynamically at runtime, each with its own port, protocol, and TLS settings. Custom listeners also support exclusive mode and route/plugin binding.

## Related

* @ref:[HTTP/3](./http3.md) - HTTP/3 protocol details
* @ref:[Custom HTTP Listeners](./http-listeners.md) - Dynamic listeners with route binding
* @ref:[TLS](./tls.md) - TLS and certificate management
