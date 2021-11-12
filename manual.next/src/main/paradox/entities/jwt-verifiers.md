# JWT verifiers

Sometimes, it can be pretty useful to verify Jwt tokens coming from other provider on some services. Otoroshi provides a tool to do that per service.

* `Name`: name of the JWT verifier
* `Description`: a simple description
* `Strict`: if not strict, request without JWT token will be allowed to pass. This option is helpful when you want to force the presence of tokens in each request on a specific service 
* `Tags`: list of tags associated to the module
* `Metadata`: list of metadata associated to the module

Each JWT verifier is configurable in three steps : the `location` where find the token in incoming requests, the `validation` step to check the signature and the presence of claims in tokens, and the last step, named `Strategy`.

## Token location

An incoming token can be found in three places.

#### In query string

* `Source`: JWT token location in query string
* `Query param name`: the name of the query param where JWT is located

#### In a header

* `Source`: JWT token location in a header
* `Header name`: the name of the header where JWT is located
* `Remove value`: when the token is read, this value will be remove of header value (example: if the header value is *Bearer xxxx*, the *remove value* could be Bearer&nbsp; don't forget the space at the end of the string)

#### In a cookie

* `Source`: JWT token location in a cookie
* `Cookie name`: the name of the cookie where JWT is located

## Token validation

This section is used to verify the extracted token from specified location.

* `Algo.`: What kind of algorithm you want to use to verify/sign your JWT token with

According to the selected algorithm, the validation form will change.

#### Hmac + SHA
* `SHA Size`: Word size for the SHA-2 hash function used
* `Hmac secret`: used to verify the token
* `Base64 encoded secret`: if enabled, the extracted token will be base64 decoded before it is verifier

#### RSASSA-PKCS1 + SHA
* `SHA Size`: Word size for the SHA-2 hash function used
* `Public key`: the RSA public key
* `Private key`: the RSA private key that can be empty if not used for JWT token signing

#### ECDSA + SHA
* `SHA Size`: Word size for the SHA-2 hash function used
* `Public key`: the ECDSA public key
* `Private key`: the ECDSA private key that can be empty if not used for JWT token signing

#### RSASSA-PKCS1 + SHA from KeyPair
* `SHA Size`: Word size for the SHA-2 hash function used
* `KeyPair`: used to sign/verify token. The displayed list represents the key pair registered in the Certificates page
  
#### ECDSA + SHA from KeyPair
* `SHA Size`: Word size for the SHA-2 hash function used
* `KeyPair`: used to sign/verify token. The displayed list represents the key pair registered in the Certificates page

#### Otoroshi KeyPair from token kid (only for verification)
* `Use only exposed keypairs`: if enabled, Otoroshi will only use the key pairs that are exposed on the well-known. If disabled, it will search on any registered key pairs.

#### JWK Set (only for verification)

* `URL`: the JWK set URL where the public keys are exposed
* `HTTP call timeout`: timeout for fetching the keyset
* `TTL`: cache TTL for the keyset
* `HTTP Headers`: the HTTP headers passed
* `Key type`: type of the key searched in the jwks

*TLS settings for JWKS fetching*

* `Custom TLS Settings`: TLS settings for JWKS fetching
* `TLS loose`: if enabled, will block all untrustful ssl configs
* `Trust all`: allows any server certificates even the self-signed ones
* `Client certificates`: list of client certificates used to communicate with JWKS server
* `Trusted certificates`: list of trusted certificates received from JWKS server

*Proxy*

* `Proxy host`: host of proxy behind the identify provider
* `Proxy port`: port of proxy behind the identify provider
* `Proxy principal`: user of proxy 
* `Proxy password`: password of proxy

## Strategy

The first step is to select the verifier strategy. Otoroshi supports 4 types of JWT verifiers : 
* `Default JWT token` will add a token if no present. 
* `Verify JWT token` will only verifiy token signing and fields values if provided. 
* `Verify and re-sign JWT token` will verify the token and will re-sign the JWT token with the provided algo. settings. 
* `Verify, re-sign and transform JWT token` will verify the token, re-sign and will be able to transform the token.

All verifiers has the following properties: 

* `Verify token fields`: when the JWT token is checked, each field specified here will be verified with the provided value
* `Verify token array value`: when the JWT token is checked, each field specified here will be verified if the provided value is contained in the array


#### Default JWT token

* `Strict`: if token is already present, the call will fail
* `Default value`: list of claims of the generated token. These fields support raw values or language expressions. See the documentation on the expression language 
@@@ warning
TODO - set the link to the expression language documentation)
@@@

#### Verify JWT token

No specific values needed. This kind of verifier needs only the two fields `Verify token fields` and `Verify token array value`.

#### Verify and re-sign JWT token

When `Verify and re-sign JWT token` is chosen, the `Re-sign settings` appear. All fields of `Re-sign settings` are the same of the `Token validation` section. The only difference is that the values are used to sign the new token and not to validate the token.


#### Verify, re-sign and transform JWT token

When `Verify, re-sign and transform JWT token` is chosen, the `Re-sign settings` and `Transformation settings` appear.

The `Re-sign settings` are used to sign the new token and has the same fields than the `Token validation` section.

For the `Transformation settings` section, the fields are:
* `Token location`: the location where to find/set the JWT token
* `Header name`: the name of the header where JWT is located
* `Prepend value`: remove a value inside the header value
* `Rename token fields`: when the JWT token is transformed, it is possible to change a field name, just specify origin field name and target field name
* `Set token fields`: when the JWT token is transformed, it is possible to add new field with static values, just specify field name and value
* `Remove token fields`: when the JWT token is transformed, it is possible to remove fields