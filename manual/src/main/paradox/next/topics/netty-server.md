# Alternative HTTP server

@@include[experimental.md](../../includes//experimental.md) { .experimental-feature }

with the change of licence in Akka, we are experimenting around using Netty as http server for otoroshi (and getting rid of akka http)

in `v1.5.14` we are introducing a new alternative http server base on [`reactor-netty`](https://projectreactor.io/docs/netty/release/reference/index.html)

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
root [info] otoroshi-experimental-reactor-netty-server -
root [info] otoroshi-experimental-reactor-netty-server - Starting the experimental Reactor Netty Server !!!
root [info] otoroshi-experimental-reactor-netty-server -  - https://0.0.0.0:10048
root [info] otoroshi-experimental-reactor-netty-server -  - http://0.0.0.0:10049
root [info] otoroshi-experimental-reactor-netty-server -
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

## Env. variables

you can configure the server using the following env. variables

* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HOST`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTP_PORT`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_HTTPS_PORT`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_WIRETAP`
* `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ACCESSLOG`



