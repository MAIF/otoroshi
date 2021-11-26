# Secure an app with OAuth2 client_credential flow

Otoroshi makes it easy for your app to implement the Client Credentials Flow. Following successful authentication, the calling application will have access to an Access Token, which can be used to call your protected APIs.

## Deployed the Client Credential Service

The Client Credential Service must be enabled as a global plugin on your instance. To achieve that, navigate to your otoroshi instance (in our case `http://otoroshi.oto.tools:8080`) on the danger zone (use the cog on the right top of the page).

To enable a plugin in global on Otoroshi, you must add it in the `Global Plugins` section.

Open the `Global Plugin` section, `enabled` it (if it not already done), and search the plugin named `Client Credential Service` of type `Sink`.

To show and add the default configuration on this plugin, click on the `show config. panel` an on the `Inject default config.` button. This button is available on each plugin and it's useful when you want to inject the default configuration.

When you click on the `show config. panel`, you have the documentation of the plugin and its default configuration.

The client credential plugin has by default 4 parameters : 

* `domain`: a regex used to exposed routes on each matching domain (`default`: *)
* `expiration`: duration until the token expire (in ms) (`default`: 3600000)
* `defaultKeyPair`: a key pair used to sign the jwt token. By default, Otoroshi is deployed with an otoroshi-jwt-signing that you can visualize on the jwt verifiers certificates (`default`: "otoroshi-jwt-signing")
* `secure`: if enabled, Otoroshi will expose routes only in the https requests case (`default`: true)

In this tutorial, we will set the configuration as following : 

* `domain`: *.oto.tools
* `expiration`: 3600000
* `defaultKeyPair`:  otoroshi-jwt-signing
* `secure`: false

Now that the plugin is running, third routes are exposed on each matching domain of the regex.

* [GET] - `/.well-known/otoroshi/oauth/jwks.json` : retrieve all public keys presents in Otoroshi
* [POST] - `/.well-known/otoroshi/oauth/token/introspect` : validate and decode the token 
* [POST] - `/.well-known/otoroshi/oauth/token` : generate a token with the fields provided

Once the global configuration saved, we can deployed a simple service to test it.

Let's navigate to the services page, and create a new service with : 
1. `http://foo.oto.tools:8080` as `Exposed domain` in `Service exposition settings` section
2. `https://mirror.otoroshi.io` as `Target 1` in `Service targets` section
3. `/.*` as `Private patterns` in `URL Patterns` section (and remove all public patterns)

In `Api Keys Constraints`, disabled `From basic auth.`, `Allow client id only usage` and `From custom headers` button then saved the service.

Let's make a first call, to check if the jwks are already exposed :

```sh
curl http://foo.oto.tools:8080/.well-known/otoroshi/oauth/jwks.json
```

This should output a list of public keys : 
```sh
{
  "keys": [
    {
      "kty": "RSA",
      "e": "AQAB",
      "kid": "otoroshi-intermediate-ca",
      ...
    }
    ...
  ]
}
``` 

Let's make a call on a route of this service. 

```sh
curl http://foo.oto.tools:8080/
```

This should output the expected error: 
```json
{
  "Otoroshi-Error": "No ApiKey provided"
}
```

The first step is to generate an api key. Navigate to the api keys page, and create an item with the following values (it will be more easy to use them in the next step) :
* `my-id` as `ApiKey Id`
* `my-secret` as `ApiKey Secret`

The next step is to ask a token by calling the exposed route `/.well-known/otoroshi/oauth/jwks.json`. The required fields are the grand type, the client and the client secret corresponding to our generated api key.

```sh
curl -X POST http://foo.oto.tools:8080/.well-known/otoroshi/oauth/token \
-H "Content-Type: application/json" \
-d '{"grant_type":"client_credentials", "client_id":"my-id", "client_secret":"my-secret"}'
```

We have omit a parameter of the body which is named `scope`. This field can be used to set a bunch of scope on the generated access token.

The last command should output : 
```sh
{
  "access_token": "generated-token-xxxxx",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Once generate, we can call our api again : 
```sh
curl http://foo.oto.tools:8080/ \
-H "Authorization: Bearer generated-token-xxxxx"
```

This should output a list of headers with a field named `Authorization` containing the previous access token.


## Other possible configuration

By default, Otoroshi generate the access token with the specified key pair in the configuration. But, in some case, you want a specific key pair by client_id/client_secret.
You can achieve it when setting a `jwt-sign-keypair` metadata on your desired api key with the id of the key pair as value. 
