# Secure the communication between a downstream app and Otoroshi

### Cover by this tutorial
- [Otoroshi exchange protocol](#otoroshi-exchange-protocol)
- [Pratical case](#pratical-case)

### Otoroshi exchange protocol

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

### Pratical case

Let's start by start Otoroshi (@ref:[instructions are available here](./secure-with-apikey.md#download-otoroshi))

Log to Otoroshi at http://otoroshi.oto.tools:9999/ with `admin@otoroshi.io/password`

1. Navigate to http://otoroshi.oto.tools:9999/bo/services and create a new service
2. Jump to `Service exposition settings` and add *http://myservice.oto.tools* as `Exposed domain`
3. Jump to `Service targets` and add *http://localhost:8080/* as `Target 1`
4. Jump to the `URL Patterns` section
5. Enable your service as `Public UI`

We need of a simple service which handle the exchange protocol. For this tutorial, we'll use the following application, developed in NodeJS, which supports both versions of the exchange protocol.

Clone this @link:[repository](https://github.com/MAIF/otoroshi/blob/master/demos/challenge)) and run the installation of the dependencies.

```sh
git clone https://github.com/MAIF/otoroshi/blob/master/demos/challenge
cd challenge
yarn install # or npm install
node server.js
```

The last command should return : 

```sh
challenge-verifier listening on http://0.0.0.0:8080
```

This project runs an express client with one middleware. The middleware handles each request, and check if the header `State token header` is present in headers. By default, the incoming expected header is `Otoroshi-State` by the application and `Otoroshi-State-Resp` header in the headers of the return request. 

Try to call your service via *http://myservice.oto.tools:9999/*. This should return a successful response with all headers received by the downstream app. 

Now try to disable the middleware in the nodejs file. 

```js
// comment this line 
app.use(OtoroshiMiddleware());
```

Try to call again your service. This time, Otoroshi breaks the return response from your downstream service, and returns.

```sh
Downstream microservice does not seems to be secured. Cancelling request !
```