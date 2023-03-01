# Otoroshi TCP/UDP tunnel cli

The idea here is to provide a secure way to access any TCP/UDP resource proxied by Otoroshi through a secured, authenticated, audited TLS tunnel. 
To do that, you'll need a local client to create the tunnel from your machine to Otoroshi. The underlying connections and protocols will remain untouched, undecrypted, unchanged by Otoroshi, they will only pass through.

The client can use enterprise proxies and client certificates. This client can use Otoroshi apikeys or authentication modules (like OAuth2, with browser login) to access services.

## Install dependencies

```sh
# with node 12.x
yarn install
```

## Try it

![Schema](./schema.jpg)

Define an otoroshi service on `http://foo.oto.tools:8080` that target your local ssh server at `http://127.0.0.1:22` (here `http://` is irrelevant, it's just a UI issue) and enable the `TCP/UDP tunneling` flag. 

 Then either activate 

* public access by defining a `/.*` public pattern for the service
* apikey access by not defining public patterns or by defining private patterns
* authenticated access by enforcing user authentication in the `authentication` section

Run the local tunnel client with :

```sh
# test public access
yarn start -- --access_type=public --remote=http://http://foo.oto.tools:8080 --port=2222

# test apikey access
yarn start -- --access_type=apikey apikey=clientId:clientSecret --remote=http://http://foo.oto.tools:8080 --port=2222

# test session access
yarn start -- --access_type=session --remote=http://http://foo.oto.tools:8080 --port=2222
```

or you can specify multiple tunnels at the same time using a config file like

```json
{
  "name": "My dev tunnels",
  "tunnels": [
    {
      "enabled": true,
      "name": "Service 1",
      "access_type": "session",
      "transport": "tcp",
      "remote": "http://foo.oto.tools:9999",
      "port": 2222
    },
    {
      "enabled": false,
      "name": "Service 2",
      "access_type": "session",
      "transport": "tcp",
      "remote": "http://foo.oto.tools:9999",
      "port": 2223
    },
    {
      "enabled": true,
      "name": "Service 3",
      "access_type": "apikey",
      "transport": "tcp",
      "remote": "http://foo2.oto.tools:9999",
      "port": 2224,
      "apikey": "clientId:clientSecret"
    },
    {
      "enabled": true,
      "name": "Service 4",
      "transport": "tcp",
      "access_type": "public",
      "remote": "http://foo3.oto.tools:9999",
      "port": 2225
    },
    {
      "enabled": true,
      "name": "DNS",
      "transport": "udp",
      "access_type": "public",
      "remote": "http://dns.oto.tools:9999",
      "port": 2225
    }
  ]
}
```

and run it like

```sh
yarn start -- --config=./tunnels.json
```

if you use the `session` access type, you'll have to login in your browser, copy the session token and paste it in your terminal.

Then try to access the service from your ssh client

```sh
ssh localuser@127.0.0.1 -p 2222
```

## Docker

```sh
docker build -t otoroshi-tcp-udp-tunnel-cli .
docker run -it -p 2222:2222 -v $(pwd)/foo.json:/docker-env.json otoroshi-tcp-udp-tunnel-cli --config=/config.json
```

If you want to access a local otoroshi (on your machine, maybe in another docker container), you can use a configuration like

```json
{
  "name": "Dev config. to test tcp/udp tunnels in otoroshi from docker",
  "tunnels": [
    {
      "enabled": true,
      "name": "Service 3",
      "access_type": "apikey",
      "transport": "tcp",
      "remote": "http://host.docker.internal:9999",
      "host": "foo2.oto.tools",
      "port": 2224,
      "apikey": "cw2xpzeuqgzo3e58:ebsx37iupdjybmh1qz5hyq8hn2hbeicd4ou62csyjhc4h8755621i5okx5316648"
    }
  ]
}
```