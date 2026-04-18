---
title: OAuth2 Token Exchange (RFC 8693)
sidebar_position: 30
---
# OAuth2 Token Exchange (RFC 8693)

<div style={{display: 'flex', alignItems: 'center', gap: '.5rem'}}>
<span style={{fontWeight: 'bold'}}>Plugin:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.OAuth2TokenExchange">OAuth2 Token Exchange</a>
</div>

## What is Token Exchange?

[OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693) (RFC 8693) is a mechanism that allows a service to exchange an incoming access token for a new one with different characteristics (audience, scope, token type, etc.).

This is commonly used in zero-trust and service-to-service architectures where:

- The original user token should **not** be propagated directly to the backend
- The backend expects a token with a **different audience** or **reduced scope**
- You need **audience restriction** to limit what a token can access

## How the plugin works

For each incoming request, the plugin:

1. Extracts the bearer token from the request (Authorization header by default)
2. Validates the token using the JWT verification settings from the referenced OIDC auth module (if configured)
3. Calls the IdP token endpoint with the RFC 8693 **token exchange grant**
4. Replaces the `Authorization` header with the exchanged token before forwarding upstream

The key design principle is that the plugin **reuses an existing OIDC auth module** for all provider-related settings (token endpoint, client credentials, JWT verification), avoiding configuration duplication.

```
                                          +-------------------+
Client  ──── Bearer token A ────>  Otoroshi  ── exchange ──>  │  Identity Provider │
                                      │                       │  (token endpoint)  │
                                      │  <── Bearer token B ──+-------------------+
                                      │
                                      ├──── Bearer token B ────>  Backend service
```

## Prerequisites

- A running Otoroshi instance
- An OIDC-compatible Identity Provider (Keycloak, Auth0, Azure AD, etc.) that supports token exchange
- An OIDC auth module already configured in Otoroshi pointing to your IdP

## Step 1: Configure your OIDC auth module

If you don't already have one, create an OIDC auth module in Otoroshi:

1. Navigate to your Otoroshi admin UI
2. Go to **Settings** (cog icon) > **Authentication configs**
3. Click **Add item** and select **OAuth2 / OIDC provider**
4. Fill in at least:
   - **Client ID**: your OAuth2 client ID
   - **Client Secret**: your OAuth2 client secret
   - **Token URL**: your IdP's token endpoint (e.g. `https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token`)
   - **JWT Verifier**: configure the algorithm and secret/key used to validate incoming tokens (e.g. HMAC 256 with your signing secret, or RSA with your IdP's public key)
5. Save the auth module and note its **ID** (visible in the URL or in the JSON view)

:::note
If your IdP issues opaque tokens (non-JWT), you can leave the JWT Verifier empty. The plugin will skip local validation and send the token directly to the IdP for exchange.
:::

## Step 2: Create a route with the plugin

1. Navigate to **Routes** and create a new route (or edit an existing one)
2. Configure the **Frontend** with your domain (e.g. `api.example.com`)
3. Configure the **Backend** with your upstream service
4. In the **Plugins** section, search for **OAuth2 token exchange** and add it to the flow
5. Configure the plugin (see below)
6. Save the route

## Step 3: Configure the plugin

Here is a typical configuration:

```json
{
  "ref": "auth_module_xxxxx",
  "mandatory": true,
  "exchange": {
    "audience": "https://backend-api.example.com",
    "scope": "api:read api:write",
    "requested_token_type": "urn:ietf:params:oauth:token-type:access_token"
  },
  "cache_ttl_ms": 30000,
  "call_timeout_ms": 10000
}
```

### Configuration reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ref` | string | required | The ID of the OIDC auth module to use as reference |
| `mandatory` | boolean | `true` | If `false`, requests without a token are allowed through |
| `source` | object | `null` | Custom JWT token source (default: `Authorization: Bearer` header and `access_token` query param) |
| `cache_ttl_ms` | number | `0` | Cache duration for exchanged tokens in ms. `0` disables caching |
| `call_timeout_ms` | number | `10000` | Timeout for the token exchange HTTP call in ms |

#### Exchange settings

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `exchange.audience` | string | `null` | The target audience for the exchanged token |
| `exchange.resource` | string | `null` | The target resource URI |
| `exchange.scope` | string | `null` | Requested scopes for the exchanged token |
| `exchange.requested_token_type` | string | `urn:ietf:params:oauth:token-type:access_token` | The desired type of the exchanged token |
| `exchange.actor_token` | string | `null` | An optional actor token (for delegation scenarios) |
| `exchange.actor_token_type` | string | `urn:ietf:params:oauth:token-type:access_token` | The type of the actor token |

#### Client credentials override

By default, the plugin uses the `client_id` and `client_secret` from the referenced OIDC auth module. You can override them if the token exchange requires different credentials (e.g. a dedicated service account):

| Field | Type | Description |
|-------|------|-------------|
| `client_credentials_override.client_id` | string | Override client ID |
| `client_credentials_override.client_secret` | string | Override client secret |

#### Custom error response

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `custom_response` | boolean | `false` | Enable custom error responses |
| `custom_response_status` | number | `401` | HTTP status for error responses |
| `custom_response_headers` | object | `{}` | Additional headers on error responses |
| `custom_response_body` | string | `{"error":"unauthorized"}` | Body of the error response |

## Step 4: Test the setup

### Without a token (should be rejected)

```sh
curl -i http://api.example.com:8080/
# Expected: 401 Unauthorized (or pass-through if mandatory=false)
```

### With a valid token

```sh
curl -i http://api.example.com:8080/ \
  -H "Authorization: Bearer <your-valid-access-token>"
# Expected: 200 OK
# The backend receives a different token (the exchanged one) in the Authorization header
```

### With an invalid token

```sh
curl -i http://api.example.com:8080/ \
  -H "Authorization: Bearer invalid-token"
# Expected: 400 Bad Request (JWT verification failed)
```

## Example: Keycloak token exchange

Keycloak supports token exchange natively. Here is a complete setup example.

### 1. Keycloak configuration

In your Keycloak realm:

1. Create a client (e.g. `otoroshi-gateway`) with **Access Type** set to `confidential`
2. In the client settings, enable the **token-exchange** fine-grained permission:
   - Go to **Permissions** tab and enable it
   - Or add the `token-exchange` scope to the client's service account roles
3. Note the client ID and secret

### 2. Otoroshi OIDC auth module

Create an auth module with:

- **Client ID**: `otoroshi-gateway`
- **Client Secret**: the secret from Keycloak
- **Token URL**: `http://keycloak:8080/realms/myrealm/protocol/openid-connect/token`
- **JWT Verifier**: configure RSA or HMAC verification matching your Keycloak realm keys

### 3. Plugin configuration

```json
{
  "ref": "your-auth-module-id",
  "exchange": {
    "audience": "target-backend-client-id"
  },
  "cache_ttl_ms": 60000,
  "call_timeout_ms": 5000
}
```

With this setup, when a user calls the route with their Keycloak access token, Otoroshi will:

1. Validate the JWT signature against Keycloak's keys
2. Call Keycloak's token endpoint with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`
3. Replace the Authorization header with the new audience-restricted token
4. Forward the request to the backend

## Token caching

When `cache_ttl_ms` is set to a value greater than 0, the plugin caches exchanged tokens to avoid calling the IdP on every request. The cache key is based on:

- The hash of the incoming subject token
- The target audience
- The requested scope

The effective cache TTL is the **minimum** of:

- The configured `cache_ttl_ms`
- The `expires_in` value returned by the IdP (converted to milliseconds)

This ensures tokens are never cached longer than their actual validity.

## Error handling

| Scenario | Default HTTP status |
|----------|-------------------|
| No token provided (mandatory=true) | `401 Unauthorized` |
| Invalid JWT token | `400 Bad Request` |
| Auth module not found | `400 Bad Request` |
| Auth module is not an OAuth2/OIDC module | `400 Bad Request` |
| IdP rejects the token exchange | `502 Bad Gateway` |
| IdP unreachable / timeout | `502 Bad Gateway` |

You can customize error responses using the `custom_response*` fields when `custom_response` is enabled.

## Opaque token mode

If the referenced OIDC auth module does **not** have a JWT verifier configured, the plugin operates in opaque token mode:

- No local JWT validation is performed
- The incoming token is sent directly to the IdP's token endpoint for exchange
- The IdP is responsible for validating the incoming token

This is useful when your IdP issues opaque (non-JWT) access tokens that can only be validated server-side.
