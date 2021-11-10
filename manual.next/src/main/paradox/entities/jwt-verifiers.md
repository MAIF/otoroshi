# JWT verifiers

Sometimes, it can be pretty useful to verify Jwt tokens coming from other provider on some services. Otoroshi provides a tool to do that per service.

* `Name`: name of the JWT verifier
* `Description`: a simple description
* `Strict`: if not strict, request without JWT token will be allowed to pass. This option is helpful when you want to force the presence of tokens in each request on a specific service 

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