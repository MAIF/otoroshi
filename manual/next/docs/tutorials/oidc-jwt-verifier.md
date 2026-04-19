---
title: Validate JWT tokens with an OIDC auth module
sidebar_position: 15
---
# Validate JWT tokens with an OIDC auth module

<div style={{display: 'flex', alignItems: 'center', gap: '.5rem'}}>
<span style={{fontWeight: 'bold'}}>Plugin:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.OIDCJwtVerifier">OIDC JWT verification</a>
</div>

## Why this plugin?

Otoroshi already provides [JWT verifiers](./secure-an-app-with-jwt-verifiers.mdx) as standalone entities, but they require you to manually configure the algorithm, secret/keys, and verification settings for each verifier.

The **OIDC JWT verification** plugin takes a different approach: it **reuses the JWT verification settings from an existing OIDC auth module**. This means:

- No need to duplicate algorithm/key configuration between your auth module and a separate JWT verifier
- When the auth module keys rotate, the verification follows automatically
- Simpler setup when you already have an OIDC auth module configured for your Identity Provider

This plugin is ideal when your API receives JWT tokens issued by an IdP that you already have configured as an OIDC auth module in Otoroshi.

## How it works

```
                                 +--------------------+
Client  ──── Bearer JWT ────────>│      Otoroshi      │
                                 │                    │
                                 │  1. Extract token  │
                                 │  2. Verify JWT     │──── uses jwtVerifier settings
                                 │     signature      │     from OIDC auth module
                                 │  3. Allow/Deny     │
                                 +--------------------+
                                      │
                          (if valid)  ├──── forward request ────>  Backend service
```

The plugin:

1. Extracts the JWT token from the `Authorization: Bearer` header (or custom source)
2. Verifies the token signature using the `jwtVerifier` algorithm configured in the referenced OIDC auth module
3. If valid, allows the request through; if invalid, denies with a `400` error
4. Optionally extracts the token payload as a connected user session

## Prerequisites

- A running Otoroshi instance
- An OIDC auth module configured with JWT verification settings (the `jwtVerifier` field)

## Step 1: Configure your OIDC auth module

If you don't already have an auth module, create one:

1. Navigate to **Settings** (cog icon) > **Authentication configs**
2. Click **Add item** and select **OAuth2 / OIDC provider**
3. Fill in:
   - **Name**: e.g. `My Keycloak`
   - **Client ID** / **Client Secret**: your OAuth2 credentials
   - **Token URL**, **Authorize URL**, etc.: your IdP endpoints
   - **JWT Verifier**: this is the key setting. Configure the algorithm that matches how your IdP signs tokens:
     - **HMAC + SHA** (256, 384 or 512) with the shared secret
     - **RSA + SHA** with the IdP's public key
     - **JWKS from URL** pointing to your IdP's JWKS endpoint (e.g. `https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs`)
4. Save the auth module

:::note
The `jwtVerifier` setting in the auth module is mandatory for this plugin. Without it, the plugin cannot verify token signatures and will reject requests.
:::

## Step 2: Add the plugin to a route

1. Navigate to **Routes** and create or edit a route
2. Configure the **Frontend** with your domain
3. Configure the **Backend** with your upstream service
4. In the **Plugins** section, search for **OIDC JWT verification** and add it to the flow
5. Configure the plugin:
   - **Auth. module**: select your OIDC auth module from the dropdown
   - **Mandatory**: leave `true` (default) to reject requests without a valid token
6. Save the route

### Minimal configuration

```json
{
  "ref": "your-auth-module-id"
}
```

That's it. The plugin will use the `jwtVerifier` settings from the auth module to validate incoming tokens.

## Step 3: Test it

### Call without a token

```sh
curl -i http://myapi.oto.tools:8080/
```

Expected response:

```json
{
  "error": "token not found"
}
```

### Call with an invalid token

```sh
curl -i http://myapi.oto.tools:8080/ \
  -H "Authorization: Bearer invalid-token"
```

Expected: `400 Bad Request` (signature verification fails).

### Call with a valid token

```sh
# Use a token signed with the same algorithm/key configured in your auth module
curl -i http://myapi.oto.tools:8080/ \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

Expected: `200 OK`, the request is forwarded to the backend.

### Token in query parameter

The plugin also accepts tokens via the `access_token` query parameter by default:

```sh
curl -i 'http://myapi.oto.tools:8080/?access_token=eyJhbGciOiJIUzI1NiIs...'
```

## Configuration reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ref` | string | required | The ID of the OIDC auth module to use |
| `mandatory` | boolean | `true` | If `false`, requests without a token are allowed through |
| `user` | boolean | `false` | If `true`, extracts the token payload as a connected user session (useful for downstream plugins that need user identity) |
| `source` | object | `null` | Custom token source. Default: `Authorization: Bearer` header + `access_token` query param |
| `custom_response` | boolean | `false` | Enable custom error responses |
| `custom_response_status` | number | `401` | HTTP status for error responses |
| `custom_response_headers` | object | `{}` | Additional headers on error responses |
| `custom_response_body` | string | `{"error":"unauthorized"}` | Body of the error response |

## Token source configuration

By default, the plugin looks for the token in two places (in order):

1. The `Authorization` header, stripping the `Bearer ` prefix
2. The `access_token` query parameter

You can override this with a custom source in the `source` field:

### Header source

```json
{
  "source": {
    "type": "InHeader",
    "name": "X-API-Token",
    "remove": ""
  }
}
```

### Query parameter source

```json
{
  "source": {
    "type": "InQueryParam",
    "name": "token"
  }
}
```

### Cookie source

```json
{
  "source": {
    "type": "InCookie",
    "name": "session_token"
  }
}
```

## Using the connected user feature

When `user` is set to `true`, the plugin decodes the JWT payload and creates a connected user session. This is useful when other plugins in the chain need user identity information (e.g. for RBAC, header injection, logging).

```json
{
  "ref": "your-auth-module-id",
  "user": true
}
```

With this option enabled, downstream plugins and the backend can access user claims that Otoroshi extracts from the token.

## Custom error response

By default, the plugin returns standard Otoroshi error responses. You can customize them:

```json
{
  "ref": "your-auth-module-id",
  "custom_response": true,
  "custom_response_status": 403,
  "custom_response_headers": {
    "Content-Type": "application/json",
    "X-Custom-Header": "denied"
  },
  "custom_response_body": "{\"error\": \"access_denied\", \"message\": \"Invalid or missing JWT token\"}"
}
```

## Non-mandatory mode

Setting `mandatory` to `false` makes the token optional. Requests without a token (or with an invalid one) are allowed through:

```json
{
  "ref": "your-auth-module-id",
  "mandatory": false
}
```

This is useful when:

- Some endpoints on the route are public and some are protected (combine with other plugins)
- You want to extract user identity when available but not require it

## Difference with other JWT plugins

| Plugin | Use case |
|--------|----------|
| **Jwt verification only** | Uses a standalone JWT verifier entity. You configure algorithm/keys independently. |
| **Jwt verifiers** | Uses standalone verifier entities, supports verification + re-signing + transformation of tokens. |
| **OIDC JWT verification** | Reuses JWT settings from an existing OIDC auth module. Simpler when you already have the auth module. No token transformation, pure validation. |

Choose **OIDC JWT verification** when you already have an OIDC auth module configured for your IdP and just want to validate incoming tokens against it without duplicating configuration.
