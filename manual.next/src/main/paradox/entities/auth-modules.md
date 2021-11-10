# Authentication modules

The authentication modules are the resources to manage the the access to Otoroshi UI and to protect a service.

An authentication module can be as well use on guard to access to the Otoroshi UI as to protect an app.

A protected app is a Otoroshi service with an authentication module.

The list of supported authentication are : 
* OAuth 2.0/2.1
* OAuth 1.0a
* In memory
* LDAP
* Saml V2

All authentication modules have a unique `id`, a `name` and a `description`.

## OAuth 2.0 / OIDC provider

| Field | Description |
|---|---|
| Use cookie | If your OAuth2 provider does not support query param in redirect uri, you can use cookies instead |
| Use json payloads |  |
| Enabled PKCE flow | This way, a malicious attacker can only intercept the Authorization Code, and they cannot exchange it for a token without the Code Verifier. |
| Disable wildcard on redirect URIs | As of OAuth 2.1, query parameters on redirect URIs are no longer allowed |
| Refresh tokens | Automatically refresh access token using the refresh token if available |
| Read profile from token |  |
| Super admins only |  |
| Client ID |  |
| Client Secret |  |
| Authorize URL |  |
| Token URL |  |
| Introspection URL |  |
| Userinfo URL |  |
| Login URL |  |
| Logout URL |  |
| Callback URL |  |
| Access token field name |  |
| Scope |  |
| Claims |  |
| Name field name |  |
| Email field name |  |
| Otoroshi metadata field name |  |
| Otoroshi rights field name |  |
| Extra metadata |  |
| Data override |  |
| Rights override |  |
| Api key metadata field name |  |
| Api key tags field name |  |
| Proxy host |  |
| Proxy port |  |
| Proxy principal |  |
| Proxy password |  |
| OIDC config url |  |
| Token verification |  |
| SHA Size |  |
| Hmac secret |  |
| Base64 encoded secret |  |
| Custom TLS Settings |  |
| TLS loose |  |
| Trust all |  |
| Client certificates |  |
| Trusted certificates |  |
| Tags |  |
| Metadata |  |
| Session max. age |  |
| HttpOnly |  |
| Secure |  |