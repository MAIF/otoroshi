# Managing services

Now let's create services. Services or `service descriptor` let you declare how to proxy a call from a domain name to another domain name (or multiple domain names). Let's say you have an API exposed on `http://192.168.0.42` and I want to expose it on `https://my.api.foo`. Otoroshi will proxy all calls to `https://my.api.foo` and forward them to `http://192.168.0.42`. While doing that, it will also log everyhting, control accesses, etc.

## Otoroshi entities

There are 3 major entities at the core of Otoroshi

* service groups
* **service descriptors**
* api keys

@@@ div { .centered-img }
<img src="../img/models-service.png" />
@@@

A `service descriptor` is contained in one or multiple `service group`s and is allowed to be accessed by all the `api key`s authorized on those `service group`s or apikeys directly authorized on the service itself.

## Create a service descriptor

To create a `service descriptor`, click on `Add service` on the Otoroshi sidebar. Then you will be asked to choose a name for the service and the group of the service. You also have two buttons to create a new group and assign it to the service and create a new group with a name based on the service name.

You will have a serie of toggle buttons to

* activate / deactivate a service
* display maintenance page for a service
* display contruction page for a service
* enable otoroshi custom response headers containing request id, latency, etc 
* force https usage on the exposed service
* enable read only flag : this service will only be used with `HEAD`, `OPTIONS` and `GET` http verbs. You can also active the same flag on `ApiKey`s to be more specific on who cannot use write http verbs.

Then, you will be able to choose the URL that will be used to reach your new service on Otoroshi.

@@@ div { .centered-img #service-flags }
<img src="../img/service-flags.png" />
@@@

In the `service targets` section, you will be able to choose where the call will be forwarded. You can use multiple targets, in that case, Otoroshi will perform a round robin load balancing between the targets. If the `override Host header` toggle is on, the host header will be changed for the host of the target. For example, if you request `http://www.oto.tools/api` with a target to `http://www-internal.service.local/api`, the target will receive a `Host: www-internal.service.local` instead of `Host: www.oto.tools`.

You can also specify a target root, if you say that the target root is `/foo/`, then any call to `https://my.api.foo` will call `http://192.168.0.42/foo/` and nay call to `https://my.api.foo/bar` will call `http://192.168.0.42/foo/bar`.

In the URL patterns section, you will be able to choose, URL by URL which is private and which is public. By default, all services are private and each call must provide an `api key`. But sometimes, you need to access a service publicly. In that case, you can provide patterns (regex) to make some or all URL public (for example with the pattern `/.*`). You also have a `private pattern` field to restrict public patterns.

@@@ div { .centered-img #targets }
<img src="../img/new-service-patterns.png" />
@@@

### Otoroshi exchange protocol

#### V1 challenge

If you enable secure communication for a given service with `V1 - simple values exchange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header and return it in a header named `Otoroshi-State-Resp`. 

@@@ div { .centered-img }
<img src="../img/exchange.png" />
@@@

you can find an example project that implements V1 challenge [here](https://github.com/MAIF/otoroshi/tree/master/demos/challenge)

#### V2 challenge

If you enable secure communication for a given service with `V2 - signed JWT token exhange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header value containing a JWT token, verify it's content signature then extract a claim named `state` and return a new JWT token in a header named `Otoroshi-State-Resp` with the `state` value in a claim named `state-resp`. By default, the signature algorithm is HMAC+SHA512 but can you can choose your own. The sent and returned JWT tokens have short TTL to avoid being replayed. You must be validate the tokens TTL. The audience of the response token must be `Otoroshi` and you have to specify `iat`, `nbf` and `exp`.

@@@ div { .centered-img }
<img src="../img/exchange-2.png" />
@@@

you can find an example project that implements V2 challenge [here](https://github.com/MAIF/otoroshi/tree/master/demos/challenge)

#### Info. token

Otoroshi is also sending a JWT token in a header named `Otoroshi-Claim` that the target app can validate too.

The `Otoroshi-Claim` is a JWT token containing some informations about the service that is called and the client if available. You can choose between a legacy version of the token and a new one that is more clear and structured.

By default, the otoroshi jwt token is signed with the `app.claim.sharedKey` config property (or using the `$CLAIM_SHAREDKEY` env. variable) and uses the `HMAC512` signing algorythm. But it is possible to customize how the token is signed from the service descriptor page in the `Otoroshi exchange protocol` section. 

@@@ div { .centered-img }
<img src="../img/sec-com-signing-bis.png" />
@@@

using another signing algo.

@@@ div { .centered-img }
<img src="../img/sec-com-signing-2-bis.png" />
@@@

here you can choose the signing algorithm and the secret/keys used. You can use syntax like `${env.MY_ENV_VAR}` or `${config.my.config.path}` to provide secret/keys values. 

For example, for a service named `my-service` with a signing key `secret` with `HMAC512` signing algorythm, the basic JWT token that will be sent should look like the following

```
eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJzdWIiOiItLSIsImF1ZCI6Im15LXNlcnZpY2UiLCJpc3MiOiJPdG9yb3NoaSIsImV4cCI6MTUyMTQ0OTkwNiwiaWF0IjoxNTIxNDQ5ODc2LCJqdGkiOiI3MTAyNWNjMTktMmFjNy00Yjk3LTljYzctMWM0ODEzYmM1OTI0In0.mRcfuFVFPLUV1FWHyL6rLHIJIu0KEpBkKQCk5xh-_cBt9cb6uD6enynDU0H1X2VpW5-bFxWCy4U4V78CbAQv4g
```

if you decode it, the payload will look something like

```json
{
  "sub": "apikey_client_id",
  "aud": "my-service",
  "iss": "Otoroshi",
  "exp": 1521449906,
  "iat": 1521449876,
  "jti": "71025cc19-2ac7-4b97-9cc7-1c4813bc5924"
}
```

If you want to validate the `Otoroshi-Claim` on the target app side to ensure that the input requests only comes from `Otoroshi`, you will have to write an HTTP filter to do the job. For instance, if you want to write a filter to make sure that requests only comes from Otoroshi, you can write something like the following (using playframework 2.6).

Scala
:   @@snip [filter.scala](../snippets/filter.scala)

Java
:   @@snip [filter.java](../snippets/filter.java)


### Canary mode

Otoroshi provides a feature called `Canary mode`. It lets you define new targets for a service, and route a percentage of the traffic on those targets. It's a good way to test a new version of a service before public release. As any client need to be routed to the same version of targets any time, Otoroshi will issue a special header and a cookie containing a `session id`. The header is named `Otoroshi-Canary-Id`.

@@@ div { .centered-img }
<img src="../img/new-service-canary.png" />
@@@

### Service health check

Otoroshi is also capable of checking the health of a service. You can define a URL that will be tested, and Otoroshi will ping that URL regularly. Will doing so, Otoroshi will pass a numeric value in a header named `Otoroshi-Health-Check-Logic-Test`. You can respond with a header named `Otoroshi-Health-Check-Logic-Test-Result` that contains the value of `Otoroshi-Health-Check-Logic-Test` + 42 to indicate that the service is working properly.

@@@ div { .centered-img }
<img src="../img/new-service-healthcheck.png" />
@@@

### Service circuit breaker

In Otoroshi, each service has its own client settings with a circuit breaker and some retry capabilities. In the `Client settings` section, you will be able to customize the client's behavior.

@@@ div { .centered-img }
<img src="../img/new-service-client.png" />
@@@

### Service settings

You can also provide some additionnal information about a given service, like an `Open API` descriptor, some metadata, a list of whitelisted/blacklisted ip addresses, etc.

@@@ div { .centered-img #service-meta }
<img src="../img/new-service-meta.png" />
@@@

### HTTP Headers

Here you can define some headers that will be added to each request to client requests or responses. 
You will also be able to define headers to route the call only if the defined header is present on the request.

@@@ div { .centered-img #service-meta }
<img src="../img/new-service-headers.png" />
@@@

### CORS 

If you enabled this section, CORS will be automatically supported on the current service provider. The pre-flight request will be handled by Otoroshi. You can customize every CORS headers :

@@@ div { .centered-img }
<img src="../img/cors.png" />
@@@

### Service authentication

See @ref:[Aauthentication](./9-auth.md)

### Custom error templates

Finally, you can define custom error templates that will be displayed when an error occurs when Otoroshi try to reach the target or when Otoroshi itself has an error. You can also define custom templates for maintenance and service pages.
