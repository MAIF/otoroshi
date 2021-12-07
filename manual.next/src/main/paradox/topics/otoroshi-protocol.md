# The Otoroshi communication protocol

The exchange protocol secure the communication with an app. When it's enabled, Otoroshi will send for each request a value in pre-selected token header, and will check the same header in the return request.

### V1 challenge

If you enable secure communication for a given service with `V1 - simple values exchange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header and return it in a header named `Otoroshi-State-Resp`. 

@@@ div { .centered-img }
<img src="../imgs/exchange.png" />
@@@

you can find an example project that implements V1 challenge [here](https://github.com/MAIF/otoroshi/tree/master/demos/challenge)

### V2 challenge

If you enable secure communication for a given service with `V2 - signed JWT token exhange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header value containing a JWT token, verify it's content signature then extract a claim named `state` and return a new JWT token in a header named `Otoroshi-State-Resp` with the `state` value in a claim named `state-resp`. By default, the signature algorithm is HMAC+SHA512 but can you can choose your own. The sent and returned JWT tokens have short TTL to avoid being replayed. You must be validate the tokens TTL. The audience of the response token must be `Otoroshi` and you have to specify `iat`, `nbf` and `exp`.

@@@ div { .centered-img }
<img src="../imgs/exchange-2.png" />
@@@

you can find an example project that implements V2 challenge [here](https://github.com/MAIF/otoroshi/tree/master/demos/challenge)

### Info. token

Otoroshi is also sending a JWT token in a header named `Otoroshi-Claim` that the target app can validate too.

The `Otoroshi-Claim` is a JWT token containing some informations about the service that is called and the client if available. You can choose between a legacy version of the token and a new one that is more clear and structured.

By default, the otoroshi jwt token is signed with the `app.claim.sharedKey` config property (or using the `$CLAIM_SHAREDKEY` env. variable) and uses the `HMAC512` signing algorythm. But it is possible to customize how the token is signed from the service descriptor page in the `Otoroshi exchange protocol` section. 

@@@ div { .centered-img }
<img src="../imgs/sec-com-signing-bis.png" />
@@@

using another signing algo.

@@@ div { .centered-img }
<img src="../imgs/sec-com-signing-2-bis.png" />
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
