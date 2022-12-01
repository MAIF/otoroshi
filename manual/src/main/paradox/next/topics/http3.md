# HTTP3

@@include[experimental.md](../../includes/experimental.md) { .experimental-feature }

HTTP3 server and client previews are available in otoroshi since version 1.5.14


## Server

to enable http3 server preview, you need to enable the following flags

```conf
otoroshi.next.experimental.netty-server.enabled = true
otoroshi.next.experimental.netty-server.http3.enabled = true
otoroshi.next.experimental.netty-server.http3.port = 10048
```

then you will be able to send HTTP3 request on port 10048. For instance, using [quiche-client](https://github.com/cloudflare/quiche)

```sh
cargo run --bin quiche-client -- --no-verify 'https://my-service.oto.tools:10048'
```

## Client

to consume services exposed with HTTP3, just select the `HTTP/3.0` protocol in the backend target.