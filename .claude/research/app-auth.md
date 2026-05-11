# Directory `app/auth/`

## Overview

This directory contains Otoroshi's **authentication modules**. Each authentication type (OAuth2, LDAP, SAML, etc.) is implemented as a configurable module.

## Files

### `api.scala` (~400+ lines)
**Role**: Base API and common traits for all authentication modules.

**Main classes**:
- `AuthModuleConfig` - Base trait for all auth module configurations
- `ValidableUser` - Trait for users that can be validated
- `RemoteUserValidatorSettings` - Configuration for user validation via external API
- `ErrorReason` - Authentication error representation

**Features**:
- User validation (allowed/denied lists)
- JSONPath validation on user attributes
- Remote validation via external HTTP API
- Expression support: `Regex()`, `Wildcard()`, `Contains()`, etc.

---

### `oauth.scala` (~800+ lines)
**Role**: Generic OAuth 2.0 / OpenID Connect implementation.

**Main classes**:
- `GenericOauth2ModuleConfig` - Complete OAuth2/OIDC configuration
- `GenericOauth2Module` - OAuth2 authentication logic

**Configuration**:
```scala
GenericOauth2ModuleConfig(
  clientId,
  clientSecret,
  authorizeUrl,      // Authorization URL
  tokenUrl,          // Token exchange URL
  userInfoUrl,       // Userinfo URL
  introspectionUrl,  // Token introspection URL
  loginUrl,          // IDP login URL
  logoutUrl,         // IDP logout URL
  scope,             // Requested scopes
  claims,            // Requested claims
  refreshTokens,     // Enable refresh
  pkce,              // PKCE configuration
  useJson,           // Send as JSON vs form-encoded
  readProfileFromToken, // Read profile from JWT
  jwtVerifier,       // JWT verifier
  oidConfig,         // .well-known/openid-configuration URL
  mtlsConfig,        // mTLS configuration
  proxy              // Optional HTTP proxy
)
```

**Features**:
- Authorization Code flow
- PKCE support (Proof Key for Code Exchange)
- Automatic token refresh
- Profile reading from token or userinfo
- Auto-configuration via .well-known/openid-configuration
- mTLS support for IDP communication

---

### `ldap.scala` (~600+ lines)
**Role**: LDAP / Active Directory authentication.

**Main classes**:
- `LdapAuthModuleConfig` - LDAP configuration
- `LdapAuthUser` - LDAP user representation

**Configuration**:
```scala
LdapAuthModuleConfig(
  serverUrls,           // LDAP server URLs
  searchBase,           // Search base DN
  userBase,             // User base DN
  groupFilter,          // Group filter
  searchFilter,         // Search filter
  adminUsername,        // Admin bind DN
  adminPassword,        // Admin password
  nameField,            // Name field (cn, displayName)
  emailField,           // Email field
  extraMetadata,        // Additional metadata to extract
  groupRights           // Group to Otoroshi rights mapping
)
```

**Features**:
- LDAP bind with user credentials
- User and group search
- Custom attribute extraction
- LDAP group to Otoroshi rights mapping
- SSL/TLS support

---

### `basic.scala` (~500+ lines)
**Role**: Local user authentication (stored in Otoroshi).

**Main classes**:
- `BasicAuthModuleConfig` - Basic module configuration
- `BasicAuthUser` - Local user definition
- `WebAuthnDetails` - WebAuthn/FIDO2 support

**User configuration**:
```scala
BasicAuthUser(
  name,
  email,
  password,      // BCrypt hash
  webauthn,      // WebAuthn credentials
  metadata,
  tags,
  rights,        // Otoroshi rights
  adminEntityValidators
)
```

**Features**:
- User storage in Otoroshi
- BCrypt password hashing
- WebAuthn/FIDO2 support for passwordless authentication
- Per-user rights management

---

### `oauth1.scala`
**Role**: OAuth 1.0a authentication (legacy).

**Main classes**:
- `Oauth1ModuleConfig` - OAuth 1.0a configuration

**Features**:
- 3-legged OAuth 1.0a flow
- HMAC-SHA1 signature
- Twitter support, etc.

---

### `saml/` (subdirectory)
**Role**: SAML 2.0 authentication.

**Files**:
- `SAMLClient.scala` - SAML client for SSO
- `ValidatorUtils.scala` - Signature validation utilities

**Features**:
- SAML SSO (Service Provider)
- SAML assertion validation
- XML signature support
- SAML response parsing

---

### `session.scala` (~130 lines)
**Role**: Session management for private apps.

**Main classes**:
- `PrivateAppsSessionManager` - Private session manager

**Features**:
- Configurable session cookies
- JWT support for sessions
- Configuration: domain, secure, httpOnly, sameSite, maxAge
- Helpers for encoding/decoding sessions

**Implicit classes**:
- `RequestHeaderWithPrivateAppSession` - Extension to read session
- `ResultWithPrivateAppSession` - Extension to write session

---

### `wasm.scala` (~200+ lines)
**Role**: Custom authentication module via WebAssembly.

**Main classes**:
- `WasmAuthModuleConfig` - WASM module configuration
- `WasmAuthModule` - Authentication execution via WASM plugin

**Configuration**:
```scala
WasmAuthModuleConfig(
  wasmRef  // Reference to WASM plugin
)
```

**Features**:
- Fully custom authentication via WASM
- WASM plugin receives the request and returns the authenticated user
- Allows implementing any auth protocol

---

## Module Architecture

```
AuthModuleConfig (trait)
    │
    ├── GenericOauth2ModuleConfig  # OAuth2/OIDC
    ├── LdapAuthModuleConfig       # LDAP/AD
    ├── BasicAuthModuleConfig      # Local users
    ├── Oauth1ModuleConfig         # OAuth 1.0a
    ├── SAMLModuleConfig           # SAML 2.0
    └── WasmAuthModuleConfig       # Custom WASM
```

## Relationships with Other Components

```
auth/
    │
    ├── Used by → controllers/Auth0Controller.scala (login/logout)
    ├── Used by → controllers/PrivateAppsController.scala
    ├── Used by → actions/backoffice.scala (token refresh)
    ├── Used by → plugins/oidc.scala, plugins/oauth1.scala
    │
    ├── Stored in → storage/ (auth module configs)
    └── Configured via → Admin UI or REST API
```

## Key Points

1. **Modular**: Each auth type is an independent module
2. **Extensible**: WASM support for custom auth
3. **Validation**: Local and remote validators for fine control
4. **WebAuthn**: Modern passwordless authentication support
5. **Session**: Flexible session management (cookies, JWT)
