# Secure the communication between a downstream app and Otoroshi

Let's start by downloading the latest Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-dev/otoroshi.jar'
```

By default, Otoroshi starts with domain `oto.tools` that targets `127.0.0.1`

Run Otoroshi
```sh
java -Dapp.adminPassword=password -jar otoroshi.jar 
```

Log to Otoroshi at http://otoroshi.oto.tools:8080/ with `admin@otoroshi.io/password`

1. Navigate to http://otoroshi.oto.tools:8080/bo/services and create a new service
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

Try to call your service via *http://myservice.oto.tools:8080/*. This should return a successful response with all headers received by the downstream app. 

Now try to disable the middleware in the nodejs file by commenting the following line. 

```js
// app.use(OtoroshiMiddleware());
```

Try to call again your service. This time, Otoroshi breaks the return response from your downstream service, and returns.

```sh
Downstream microservice does not seems to be secured. Cancelling request !
```