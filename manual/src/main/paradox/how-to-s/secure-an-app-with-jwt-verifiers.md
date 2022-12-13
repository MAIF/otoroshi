# Secure an api with jwt verifiers

A Jwt verifier is the guard that verifies the signature of tokens in requests. 

A verifier can obvisouly verify or generate.

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Your first jwt verifier

Let's start by validating all incoming request tokens tokens on our simple route created in the @ref:[Before you start](#before-you-start) section.

1. Navigate to the simple route
2. Search in the list of plugins and add the `Jwt verification only` plugin on the flow
3. Click on `Start by select or create a JWT Verifier`
4. Create a new JWT verifier
5. Set `simple-jwt-verifier` as `Name`
6. Select `Hmac + SHA` as `Algo` (for this example, we expect tokens with a symetric signature), `512` as `SHA size` and `otoroshi` as `HMAC secret`
7. Confirm the creation 

Save your route and try to call it

```sh
curl -X GET 'http://myservice.oto.tools:8080/' --include
```

This should output : 
```json
{
    "Otoroshi-Error": "error.expected.token.not.found"
}
```

A simple way to generate a token is to use @link:[jwt.io](http://jwt.io) { open=new }. Once navigate, define `HS512` as `alg` in header section, add the claim `iss` with the `The Issuer` vaue and insert `otoroshi` as verify signature secret. 

Once created, copy-paste the token from jwt.io to the Authorization header and call our service.

```sh
# replace xxxx by the generated token
curl -X GET \
  -H "X-JWT-Token: xxxx" \
  'http://myservice.oto.tools:8080'
```

This should output a json with `X-JWT-Token` in headers field. Its value is exactly the same as the passed token.

```json
{
  "method": "GET",
  "path": "/",
  "headers": {
    "host": "mirror.otoroshi.io",
    "X-JWT-Token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.ipDFgkww51mSaSg_199BMRj4gK20LGz_czozu3u8rCFFO1X20MwcabSqEzUc0q4qQ4rjTxjoR4HeUDVcw8BxoQ",
    ...
  }
}
```

### Verify and generate a new token

An other feature is to verify the incomings tokens and generate new ones, with a different signature and claims. 

Let's start by extending the @link:[previous verifier](http://otoroshi.oto.tools:8080/bo/dashboard/jwt-verifiers) { open=new }.

1. Jump to the `Verif Strategy` field and select `Verify and re-sign JWT token`. 
2. Edit the name with `jwt-verify-and-resign`
3. Remove the default field in `Verify token fields` array
4. Change the second `Hmac secret` in `Re-sign settings` section with `otoroshi-internal-secret`
5. Save your verifier.

> Note : the name of the verifier doesn't impact the identifier. So you can save the changes of your verifier without modifying the identifier used in your call.  

```sh
# replace xxxx by the generated token
curl -X GET \
  -H "Authorization: xxxx" \
  'http://myservice.oto.tools:8080'
```

This should output a json with `authorization` in headers field. This time, the value are different and you can check his signature on @link:[jwt.io](https://jwt.io) { open=new } (the expected secret of the generated token is **otoroshi-internal-secret**)

<img src="../imgs/secure-an-app-with-jwt-verifiers-jwtio.png" height="300px">

### Verify, transform and generate a new token

The most advanced verifier is able to do the same as the previous ones, with the ability to configure the token generation (claims, output header name).

Let's start by extending the @link:[previous verifier](http://otoroshi.oto.tools:8080/bo/dashboard/jwt-verifiers) { open=new }.

1. Jump to the `Verif Strategy` field and select `Verify, transform and re-sign JWT token`. 

2. Edit the name with `jwt-verify-transform-and-resign`
3. Remove the default field in `Verify token fields` array
4. Change the second `Hmac secret` in `Re-sign settings` section with `otoroshi-internal-secret`
5. Set `Internal-Authorization` as `Header name`
6. Set `key` on first field of `Rename token fields` and `from-otoroshi-verifier` on second field
7. Set `generated-key` and `generated-value` as `Set token fields`
8. Add `generated_at` and `${date}` as second field of `Set token fields` (Otoroshi supports an @ref:[expression language](../topics/expression-language.md))
9. Save your verifier and try to call your service again.

This should output a json with `authorization` in headers field and our generate token in `Internal-Authorization`.
Once paste in @link:[jwt.io](https://jwt.io) { open=new }, you should have :

<img src="../imgs/secure-an-app-with-jwt-verifiers-transform-jwtio.png">

You can see, in the payload of your token, the two claims **from-otoroshi-verifier** and **generated-key** added during the generation of the token by the JWT verifier.
