# Secure an app with OAuth2 client_credential flow

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.ApikeyCalls">Apikeys</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.NgClientCredentials">Client Credential Service</a>
</div>

Otoroshi makes it easy for your app to implement the [OAuth2 Client Credentials Flow](https://auth0.com/docs/authorization/flows/client-credentials-flow). 

With machine-to-machine (M2M) applications, the system authenticates and authorizes the app rather than a user. With the client credential flow, applications will pass along their Client ID and Client Secret to authenticate themselves and get a token.

## Deployed the Client Credential Service

The Client Credential Service must be enabled as a global plugin on your Otoroshi instance. Once enabled, it will expose three endpoints to issue and validate tokens for your routes.

Let's navigate to your otoroshi instance (in our case http://otoroshi.oto.tools:8080) on the danger zone (`top right cog icon / Danger zone` or at [/bo/dashboard/dangerzone](http://otoroshi.oto.tools:8080/bo/dashboard/dangerzone)).

To enable a plugin in global on Otoroshi, you must add it in the `Global Plugins` section.

1. Open the `Global Plugin` section 
2. Click on `enabled` (if not already done)
3. Search the plugin named `Client Credential Service` of type `Sink` (you need to enabled it on the old or new Otoroshi engine, depending on your use case)
4. Inject the default configuration by clicking on the button (if you are using the old Otoroshi engine)

If you click on the arrow near each plugin, you will have the documentation of the plugin and its default configuration.

The client credential plugin has by default 4 parameters : 

* `domain`: a regex used to expose the three endpoints (`default`: *)
* `expiration`: duration until the token expire (in ms) (`default`: 3600000)
* `defaultKeyPair`: a key pair used to sign the jwt token. By default, Otoroshi is deployed with an otoroshi-jwt-signing that you can visualize on the jwt verifiers certificates (`default`: "otoroshi-jwt-signing")
* `secure`: if enabled, Otoroshi will expose routes only in the https requests case (`default`: true)

In this tutorial, we will set the configuration as following : 

* `domain`: oauth.oto.tools
* `expiration`: 3600000
* `defaultKeyPair`:  otoroshi-jwt-signing
* `secure`: false

Now that the plugin is running, third routes are exposed on each matching domain of the regex.

* `GET  /.well-known/otoroshi/oauth/jwks.json` : retrieve all public keys presents in Otoroshi
* `POST /.well-known/otoroshi/oauth/token/introspect` : validate and decode the token 
* `POST /.well-known/otoroshi/oauth/token` : generate a token with the fields provided

Once the global configuration saved, we can deployed a simple service to test it.

Let's navigate to the routes page, and create a new route with : 

1. `foo.oto.tools` as `domain` in the frontend node
2. `mirror.otoroshi.io` as hostname in the list of targets of the backend node, and `443` as `port`.
3. Search in the list of plugins and add the `Apikeys` plugin to the flow
4. In the extractors section of the `Apikeys` plugin, disabled the `Basic`, `Client id` and `Custom headers` option.
5. Save your route

Let's make a first call, to check if the jwks are already exposed :

```sh
curl 'http://oauth.oto.tools:8080/.well-known/otoroshi/oauth/jwks.json'
```

The output should look like a list of public keys : 
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

Let's make a call to your route. 

```sh
curl 'http://foo.oto.tools:8080/'
```

This should output the expected error: 
```json
{
  "Otoroshi-Error": "No ApiKey provided"
}
```

The first step is to generate an api key. Navigate to the api keys page, and create an item with the following values (it will be more easy to use them in the next step)

* `my-id` as `ApiKey Id`
* `my-secret` as `ApiKey Secret`

The next step is to get a token by calling the endpoint `http://oauth.oto.tools:8080/.well-known/otoroshi/oauth/jwks.json`. The required fields are the grand type, the client and the client secret corresponding to our generated api key.

```sh
curl -X POST http://oauth.oto.tools:8080/.well-known/otoroshi/oauth/token \
-H "Content-Type: application/json" \
-d @- <<'EOF'
{
  "grant_type": "client_credentials",
  "client_id":"my-id",
  "client_secret":"my-secret"
}
EOF
```

This request have one more optional field, named `scope`. The scope can be used to set a bunch of scope on the generated access token.

The last command should look like : 

```sh
{
  "access_token": "generated-token-xxxxx",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Now we can call our api with the generated token

```sh
curl 'http://foo.oto.tools:8080/' \
  -H "Authorization: Bearer generated-token-xxxxx"
```

This should output a successful call with the list of headers with a field named `Authorization` containing the previous access token.

## Other possible configuration

By default, Otoroshi generate the access token with the specified key pair in the configuration. But, in some case, you want a specific key pair by client_id/client_secret.
The `jwt-sign-keypair` metadata can be set on any api key with the id of the key pair as value. 
