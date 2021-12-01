# The Otoroshi communication protocol

The exchange protocol secure the communication with an app. When it's enabled, Otoroshi will send for each request a value in pre-selected token header, and will check the same header in the return request.

#### V1 challenge

If you enable secure communication for a given service with `V1 - simple values exchange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header and return it in a header named `Otoroshi-State-Resp`. 

@@@ div { .centered-img }
<img src="../imgs/exchange.png" />
@@@

#### V2 challenge

If you enable secure communication for a given service with `V2 - signed JWT token exhange` activated, you will have to add a filter on the target application that will take the `Otoroshi-State` header value containing a JWT token, verify it's content signature then extract a claim named `state` and return a new JWT token in a header named `Otoroshi-State-Resp` with the `state` value in a claim named `state-resp`. By default, the signature algorithm is HMAC+SHA512 but can you can choose your own. The sent and returned JWT tokens have short TTL to avoid being replayed. You must be validate the tokens TTL. The audience of the response token must be `Otoroshi` and you have to specify `iat`, `nbf` and `exp`.

@@@ div { .centered-img }
<img src="../imgs/exchange-2.png" />
@@@