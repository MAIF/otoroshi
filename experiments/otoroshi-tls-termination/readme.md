# otoroshi_tls_termination

a TCP proxy with TLS termination. `otoroshi_tls_termination` is supposed to run next to otoroshi and forwards HTTP/TLS traffic to the local HTTP port (127.0.0.1:8080). `otoroshi_tls_termination` uses otoroshi api to get its certificates and configure its own rustls context periodically.

```sh
otoroshi_tls_termination 0.1.0
Handles otoroshi TLS termination

USAGE:
    otoroshi_tls_termination [OPTIONS]

OPTIONS:
        --cid <CID>
            the otoroshi client-id [default: admin-api-apikey-id]

        --csec <CSEC>
            the otoroshi client-secret [default: admin-api-apikey-secret]

    -h, --help
            Print help information

        --host <HOST>
            the otoroshi api hostname [default: otoroshi-api.oto.tools]

        --input <INPUT>
            the input TCP port [default: 8443]

        --ip <IP>
            the otoroshi ip address [default: 127.0.0.1]

        --mtls
            enable mTLS want mode

        --no-refresh
            disable auto refresh

        --oto-url <OTO_URL>
            the otoroshi url to fetch certs

        --output <OUTPUT>
            the output TCP port [default: 8080]

        --refresh-every <REFRESH_EVERY>
            fetch otoroshi certificates every n seconds [default: 30]

    -V, --version
            Print version information

        --workers <WORKERS>
            the number of workers [default: 1]
```