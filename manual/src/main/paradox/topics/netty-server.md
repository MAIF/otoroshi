# Alternative HTTP server

@@include[experimental.md](../includes/experimental.md) { .experimental-feature }

with the change of licence in Akka, we are experimenting around using Netty as http server for otoroshi (and getting rid of akka http)

in `v1.5.14` we are introducing a new alternative http server base on [`reactor-netty`](https://projectreactor.io/docs/netty/release/reference/index.html). It also include a preview of an HTTP3 server using [netty-incubator-codec-quic](https://github.com/netty/netty-incubator-codec-quic) and [netty-incubator-codec-http3](https://github.com/netty/netty-incubator-codec-http3)

## The specs

this new server can start during otoroshi boot sequence and accept HTTP/1.1 (with and without TLS), H2C and H2 (with and without TLS) connections and supporting both standard HTTP calls and websockets calls.

## Enable the server

to enable the server, just turn on the following flag

```conf
otoroshi.next.experimental.netty-server.enabled = true
```

now you should see something like the following in the logs

```log
...
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
...
```

## Server options

you can also setup the host and ports of the server using

```conf
otoroshi.next.experimental.netty-server.host = "0.0.0.0"
otoroshi.next.experimental.netty-server.http-port = 10049
otoroshi.next.experimental.netty-server.https-port = 10048
```

you can also enable access logs using

```conf
otoroshi.next.experimental.netty-server.accesslog = true
```

and enable wiretaping using 

```conf
otoroshi.next.experimental.netty-server.wiretap = true
```

you can also custom number of worker thread using

```conf
otoroshi.next.experimental.netty-server.thread = 0 # system automatically assign the right number of threads
```

## HTTP2

you can enable or disable HTTP2 with

```conf
otoroshi.next.experimental.netty-server.http2.enabled = true
otoroshi.next.experimental.netty-server.http2.h2c = true
```

## HTTP3

you can enable or disable HTTP3 (preview ;) ) with

```conf
otoroshi.next.experimental.netty-server.http3.enabled = true
otoroshi.next.experimental.netty-server.http3.port = 10048 # yep can the the same as https because its on the UDP stack
```

the result will be something like


```log
...
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/3)
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
...
```

## Native transport

It is possible to enable native transport for the server

```conf
otoroshi.next.experimental.netty-server.native.enabled = true
otoroshi.next.experimental.netty-server.native.driver = "Auto"
```

possible values for `otoroshi.next.experimental.netty-server.native.driver` are 

- `Auto`: the server try to find the best native option available
- `Epoll`: the server uses Epoll native transport for Linux environments
- `KQueue`: the server uses KQueue native transport for MacOS environments
- `IOUring`: the server uses IOUring native transport for Linux environments that supports it (experimental, using [netty-incubator-transport-io_uring](https://github.com/netty/netty-incubator-transport-io_uring))

the result will be something like when starting on a Mac

```log
...
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server - Starting the experimental Netty Server !!!
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   using KQueue native transport
root [info] otoroshi-experimental-netty-server -
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/3)
root [info] otoroshi-experimental-netty-server -   https://0.0.0.0:10048 (HTTP/1.1, HTTP/2)
root [info] otoroshi-experimental-netty-server -   http://0.0.0.0:10049  (HTTP/1.1, HTTP/2 H2C)
root [info] otoroshi-experimental-netty-server -
...
```

## Env. variables

you can configure the server using the following env. variables

* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NEW_ENGINE_ONLY`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HOST`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_PORT`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTPS_PORT`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_WIRETAP`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ACCESSLOG`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_THREADS`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_ALLOW_DUPLICATE_CONTENT_LENGTHS`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_VALIDATE_HEADERS`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_H_2_C_MAX_CONTENT_LENGTH`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_INITIAL_BUFFER_SIZE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_HEADER_SIZE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_INITIAL_LINE_LENGTH`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_PARSER_MAX_CHUNK_SIZE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP2_ENABLED`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP2_H2C`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_ENABLED`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP3_PORT`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAMS_BIDIRECTIONAL`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_INITIAL_MAX_DATA`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_MAX_RECV_UDP_PAYLOAD_SIZE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_3_MAX_SEND_UDP_PAYLOAD_SIZE`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NATIVE_ENABLED`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_NATIVE_DRIVER`

