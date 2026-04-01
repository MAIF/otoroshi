---
title: JWT Verifiers
sidebar_position: 11
---
# JWT Verifiers

## Overview

JWT verifiers provide centralized JSON Web Token validation, signing, and transformation at the gateway level. Instead of requiring each backend service to implement its own JWT verification logic -- parsing tokens, fetching public keys, validating signatures, checking claims -- Otoroshi handles all of this in a single, reusable configuration object. Backends receive requests that have already been authenticated and can trust the tokens (or enriched headers) they see.

### The problem JWT verifiers solve

In a microservices architecture, incoming requests typically carry a JWT issued by an external identity provider (Auth0, Keycloak, Azure AD, a custom OAuth2 server, etc.). Every service behind the gateway would need to:

- Fetch and cache the provider's public keys (JWKS)
- Validate token signatures with the correct algorithm
- Check that claims like `iss`, `aud`, and `exp` match expected values
- Optionally re-sign or enrich the token before passing it to internal services

Otoroshi JWT verifiers move all of this into the gateway, so backends can focus on business logic.

### Verification strategies

A JWT verifier is configured in three steps: **where** to find the token, **how** to validate its signature, and **what to do** with it after validation.

The "what to do" part is the **strategy**, and Otoroshi supports four:

- **Default token** -- If no token is present on the request, inject one with preconfigured claims. Useful for providing a baseline identity to backends.
- **Pass-through (verify only)** -- Validate the token signature and check specific claim values, but forward it to the backend unchanged. This is the most common strategy for requests carrying tokens from an external identity provider.
- **Verify and re-sign** -- Validate the incoming token, then re-sign it with a different algorithm or key before forwarding. Useful when the external provider and the internal backends use different signing keys.
- **Verify, transform, and re-sign** -- Validate, then rewrite the token: rename claims, add new fields (using the Otoroshi [expression language](../topics/expression-language.mdx)), remove sensitive claims, and place the result in a different location (header, cookie, or query parameter).

### Token validation algorithms

Otoroshi supports a wide range of signature algorithms for both verification and signing:

- **HMAC** (HS256, HS384, HS512) -- Symmetric shared secret
- **RSA** (RS256, RS384, RS512) -- Asymmetric key pair, with raw PEM keys or referencing a certificate registered in Otoroshi
- **ECDSA** (ES256, ES384, ES512) -- Elliptic curve key pair, with raw PEM keys or referencing a certificate
- **JWKS** -- Fetch public keys dynamically from a remote JWKS endpoint (typically `/.well-known/jwks.json`), with caching and mTLS support. This is the standard approach for validating tokens from OAuth2/OIDC providers.
- **Kid-based lookup** -- Automatically select the correct Otoroshi key pair by matching the `kid` header in the incoming token

### How JWT verifiers fit in the Otoroshi ecosystem

JWT verifiers are **global, reusable entities**. Once defined, they can be referenced from multiple places:

- **Routes** -- Attach one or more JWT verifiers to a route via the `JwtVerification` plugin. The plugin runs during the access validation phase and, if the strategy modifies the token, also during request transformation.
- **Auth modules** -- OAuth2 and OIDC authentication modules can use JWT verifiers to validate the tokens returned by identity providers.

Because verifiers are defined independently from routes, a single verifier (for example, one pointing at your Auth0 JWKS endpoint) can protect dozens of routes without duplicating configuration.

### When to use JWT verifiers vs other security mechanisms

| Mechanism | Best for |
|-----------|----------|
| **JWT verifiers** | Validating bearer tokens issued by an external identity provider, signing tokens for backends, transforming claims between trust boundaries |
| **API keys** | Machine-to-machine authentication with quotas, rate limiting, and usage tracking -- when there is no external identity provider involved |
| **mTLS** | Transport-level mutual authentication between services, often combined with JWT verifiers for defense in depth |

These mechanisms are not mutually exclusive. A route can require both an API key and a valid JWT, or enforce mTLS at the transport layer while validating JWT claims at the application layer.

## UI page

You can find all JWT verifiers [here](http://otoroshi.oto.tools:8080/bo/dashboard/jwt-verifiers)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string |     | Unique identifier |
| `name` | string |     | Display name |
| `description` | string |     | Description |
| `strict` | boolean | `true` | If not strict, requests without a JWT token are allowed through. Useful to enforce token presence |
| `tags` | array of string | `[]` | Tags |
| `metadata` | object | `{}` | Key/value metadata |
| `source` | object |     | Where to find the token in incoming requests (see [below](#token-location)) |
| `algoSettings` | object |     | Algorithm settings for token validation (see [below](#token-validation)) |
| `strategy` | object |     | Verification strategy (see [below](#strategy)) |

Each JWT verifier is configured in three steps:

1. **Location**: Where to find the token in incoming requests
2. **Validation**: How to validate the token signature
3. **Strategy**: What to do with the token (pass-through, re-sign, transform)

## Token location

An incoming token can be found in three places:

### In a query string parameter

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `InQueryParam` |
| `name` | string | Name of the query parameter containing the JWT |

### In a header

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `InHeader` |
| `name` | string | Name of the header containing the JWT |
| `remove` | string | Prefix to remove from the header value (e.g., `Bearer ` - note the trailing space) |

### In a cookie

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `InCookie` |
| `name` | string | Name of the cookie containing the JWT |

## Token validation

The validation step defines the algorithm used to verify the token signature.

### HMAC + SHA

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `HSAlgoSettings` |
| `size` | number | SHA size: `256`, `384`, or `512` |
| `secret` | string | HMAC secret key |
| `base64` | boolean | Whether the secret is base64-encoded |

### RSA + SHA

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `RSAlgoSettings` |
| `size` | number | SHA size: `256`, `384`, or `512` |
| `publicKey` | string | RSA public key (PEM) |
| `privateKey` | string | RSA private key (PEM, optional - only needed for signing) |

### ECDSA + SHA

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `ESAlgoSettings` |
| `size` | number | SHA size: `256`, `384`, or `512` |
| `publicKey` | string | ECDSA public key (PEM) |
| `privateKey` | string | ECDSA private key (PEM, optional) |

### RSA from KeyPair

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `RSAKPAlgoSettings` |
| `size` | number | SHA size |
| `certId` | string | ID of the key pair certificate registered in Otoroshi |

### ECDSA from KeyPair

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `ESKPAlgoSettings` |
| `size` | number | SHA size |
| `certId` | string | ID of the key pair certificate registered in Otoroshi |

### Otoroshi KeyPair from token kid (verification only)

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `KidAlgoSettings` |
| `onlyExposedCerts` | boolean | Only use key pairs exposed on `/.well-known/jwks.json`. If disabled, searches all registered key pairs |

### JWK Set (verification only)

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `JWKSAlgoSettings` |
| `url` | string | JWKS endpoint URL |
| `timeout` | number | HTTP call timeout (ms) |
| `ttl` | number | Cache TTL for the keyset (ms) |
| `headers` | object | HTTP headers for the JWKS request |
| `kty` | string | Key type to search for in the JWKS |
| `mtlsConfig` | object | Custom TLS settings for JWKS fetching |
| `proxy` | object | Proxy settings (host, port, principal, password) |

## Strategy

The strategy defines what happens to the token after validation. Otoroshi supports 4 strategies:

### Default JWT token

Adds a default token if none is present in the request.

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `DefaultToken` |
| `strict` | boolean | If `true` and a token is already present, the call will fail |
| `defaultValue` | object | Claims for the generated token. Supports [expression language](../topics/expression-language.mdx) |

### Pass-through (verify only)

Verifies the token signature and claim values but does not modify it.

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `PassThrough` |
| `verificationSettings.fields` | object | Token fields to verify (key-value pairs) |
| `verificationSettings.arrayFields` | object | Array fields to verify (check if value is contained in array) |

The `verificationSettings.fields` values support the following validation expressions:

* `Regex(pattern)` - Match against a regex
* `Wildcard(pattern)` - Match with wildcards
* `WildcardNot(pattern)` - Must not match wildcards
* `Contains(value)` - Must contain value
* `ContainsNot(value)` - Must not contain value
* `Not(value)` - Must not equal value
* `ContainedIn(a, b, c)` - Must be one of the listed values
* `NotContainedIn(a, b, c)` - Must not be one of the listed values
* `ContainsOneOf(a, b)` - Array must contain at least one of the values
* `ContainsNotOneOf(a, b)` - Array must not contain any of the values
* `ContainsAll(a, b)` - Array must contain all of the values
* `ContainsNotAll(a, b)` - Array must not contain all of the values

These fields also support the [expression language](../topics/expression-language.mdx).

### Verify and re-sign

Verifies the token and re-signs it with a different algorithm/key.

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `Sign` |
| `verificationSettings` | object | Same as pass-through verification |
| `algoSettings` | object | Algorithm settings for re-signing (same format as token validation) |

### Verify, re-sign and transform

Verifies the token, re-signs it, and transforms its content.

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | `Transform` |
| `verificationSettings` | object | Same as pass-through verification |
| `algoSettings` | object | Algorithm settings for re-signing |
| `transformSettings.location` | object | Where to place the transformed token |
| `transformSettings.mappingSettings.map` | object | Rename token fields (old name -> new name) |
| `transformSettings.mappingSettings.values` | object | Add new fields with static or dynamic values |
| `transformSettings.mappingSettings.remove` | array of string | Fields to remove from the token |

## JSON example

```json
{
  "id": "jwt_verifier_auth0",
  "name": "Auth0 JWT verifier",
  "description": "Verify tokens issued by Auth0",
  "strict": true,
  "tags": ["auth"],
  "metadata": {},
  "source": {
    "type": "InHeader",
    "name": "Authorization",
    "remove": "Bearer "
  },
  "algoSettings": {
    "type": "JWKSAlgoSettings",
    "url": "https://my-tenant.auth0.com/.well-known/jwks.json",
    "timeout": 5000,
    "ttl": 3600000,
    "headers": {},
    "kty": "RSA"
  },
  "strategy": {
    "type": "PassThrough",
    "verificationSettings": {
      "fields": {
        "iss": "https://my-tenant.auth0.com/"
      },
      "arrayFields": {}
    }
  }
}
```

## Admin API

```
GET    /api/verifiers           # List all JWT verifiers
POST   /api/verifiers           # Create a JWT verifier
GET    /api/verifiers/:id       # Get a JWT verifier
PUT    /api/verifiers/:id       # Update a JWT verifier
DELETE /api/verifiers/:id       # Delete a JWT verifier
PATCH  /api/verifiers/:id       # Partially update a JWT verifier
```

## Related entities

* [Routes](./routes.md) - Routes can use JWT verifiers via the JwtVerification plugin
* [Certificates](./certificates.md) - Key pairs used for token signing/verification
* [Auth Modules](./auth-modules.md) - OAuth2/OIDC modules use JWT verifiers for token validation
