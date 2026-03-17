# Otoroshi's PKI

Otoroshi embeds a complete Public Key Infrastructure (PKI) based on BouncyCastle. You can create root CAs, intermediate CAs, server and client certificates, key pairs for JWT signing, and manage their full lifecycle including renewal, revocation, and OCSP status. Certificates must use PEM format for the chain and private key.

## Auto-generated certificates

An Otoroshi instance always starts with 5 auto-generated certificates, created by an internal job that runs every 24 hours and re-creates any missing certificate:

| Certificate | ID | Validity | Description |
|-------------|----|----------|-------------|
| **Otoroshi Default Root CA Certificate** | `otoroshi-root-ca` | 10 years | Root CA used to sign the intermediate CA. Should never be used directly to issue end-entity certificates |
| **Otoroshi Default Intermediate CA Certificate** | `otoroshi-intermediate-ca` | 10 years | Intermediate CA used to issue all other default certificates. This is the CA you should reference when creating new certificates |
| **Otoroshi Default Client Certificate** | `otoroshi-client` | 1 year | Default client certificate for mTLS |
| **Otoroshi Default Jwt Signing Keypair** | `otoroshi-jwt-signing` | 1 year | Default key pair for signing and verifying JWT tokens. Exposed on `/.well-known/jwks.json` |
| **Otoroshi Default Wildcard Certificate** | `otoroshi-wildcard` | 1 year | Wildcard certificate for `*.{your-domain}`. Useful during development. Can be disabled with `otoroshi.ssl.genWildcardCert=false` |

All auto-generated certificates have `autoRenew` enabled and are automatically renewed before expiration.

## Supported algorithms

### Key algorithms

| Algorithm | Sizes | Default |
|-----------|-------|---------|
| RSA | 2048, 3072, 4096 | 2048 |
| ECDSA | 256 (P-256), 384 (P-384), 521 (P-521) | 256 |

### Signature algorithms

| Algorithm | Description |
|-----------|-------------|
| `SHA256WithRSAEncryption` | RSA with SHA-256 (default) |
| `SHA384WithRSAEncryption` | RSA with SHA-384 |
| `SHA512WithRSAEncryption` | RSA with SHA-512 |
| `SHA256WithECDSA` | ECDSA with SHA-256 |
| `SHA384WithECDSA` | ECDSA with SHA-384 |
| `SHA512WithECDSA` | ECDSA with SHA-512 |

### Digest algorithms

`SHA-256` (default), `SHA-384`, `SHA-512`

## Certificate request format

Most PKI endpoints accept a JSON body following the `GenCsrQuery` model:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `hosts` | array of string | `[]` | Subject Alternative Names (SANs) - domain names for the certificate |
| `key.algo` | string | `rsa` | Key algorithm: `rsa` or `ec` |
| `key.size` | number | `2048` | Key size (2048/4096 for RSA, 256/384/521 for ECDSA) |
| `name` | object | `{}` | Subject distinguished name fields as key/value (e.g., `{"CN": "example.com", "O": "My Org"}`) |
| `subject` | string |     | Full subject DN string (alternative to `name`, e.g., `CN=example.com, O=My Org`). Takes precedence over `name` |
| `client` | boolean | `false` | Generate a client certificate (adds TLS Client Authentication extended key usage) |
| `ca` | boolean | `false` | Generate a CA certificate (adds Basic Constraints CA:TRUE) |
| `includeAIA` | boolean | `false` | Include Authority Information Access extension (OCSP responder URL + CA issuers URL) |
| `duration` | number | `31536000000` | Certificate validity duration in milliseconds (default: 365 days) |
| `signatureAlg` | string | `SHA256WithRSAEncryption` | Signature algorithm |
| `digestAlg` | string | `SHA-256` | Digest algorithm |

You can also use `keyType` and `keySize` as alternatives to the `key` object.

### Example: generate a server certificate

```json
{
  "hosts": ["api.example.com", "*.api.example.com"],
  "key": { "algo": "rsa", "size": 2048 },
  "subject": "CN=api.example.com, OU=Engineering, O=My Company",
  "duration": 31536000000,
  "signatureAlg": "SHA256WithRSAEncryption",
  "digestAlg": "SHA-256",
  "includeAIA": true
}
```

### Example: generate a client certificate

```json
{
  "hosts": [],
  "key": { "algo": "ec", "size": 256 },
  "subject": "CN=my-service-client, OU=Engineering, O=My Company",
  "client": true,
  "duration": 31536000000
}
```

### Example: generate a CA certificate

```json
{
  "hosts": [],
  "key": { "algo": "rsa", "size": 4096 },
  "subject": "CN=My Internal CA, OU=Security, O=My Company",
  "ca": true,
  "duration": 315360000000,
  "includeAIA": true
}
```

## The PKI API

The PKI API is exposed on the admin API host (by default `https://otoroshi-api.xxxxx`). All PKI endpoints require super admin rights.

### Key pair generation

```
POST /api/pki/keys
```

Generates a public/private key pair. The key pair is also stored as a certificate entity with the `keypair` flag.

**Request body**: `GenKeyPairQuery`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `algo` | string | `rsa` | Key algorithm: `rsa` or `ec` |
| `size` | number | `2048` | Key size |

**Response**:

```json
{
  "publicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
  "privateKey": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
}
```

### CSR generation

```
POST /api/pki/csrs
POST /api/pki/csrs?ca={caId}
```

Generates a Certificate Signing Request. When the `ca` query parameter is provided, the CSR includes the authority key identifier from the referenced CA.

**Request body**: `GenCsrQuery` (see above)

**Response**:

```json
{
  "csr": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----",
  "publicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
  "privateKey": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
}
```

### Self-signed certificate

```
POST /api/pki/certs
```

Generates a self-signed certificate. Supports the `?persist=false` query parameter to return the certificate without storing it.

**Request body**: `GenCsrQuery`

**Response**: Full certificate JSON entity with a `certId` field.

### Self-signed root CA

```
POST /api/pki/cas
```

Generates a self-signed root CA certificate.

**Request body**: `GenCsrQuery` (the `ca` field is implicit)

**Response**: Full certificate JSON entity with a `certId` field.

### Certificate signed by a CA

```
POST /api/pki/cas/:ca/certs
```

Generates a certificate signed by the referenced CA. The `:ca` parameter can be the certificate ID or serial number.

**Request body**: `GenCsrQuery`

**Response**: Full certificate JSON entity with a `certId` field.

### Sub-CA (intermediate CA)

```
POST /api/pki/cas/:ca/cas
```

Generates an intermediate CA certificate signed by the parent CA.

**Request body**: `GenCsrQuery`

**Response**: Full certificate JSON entity with a `certId` field.

### Sign a CSR

```
POST /api/pki/cas/:ca/certs/_sign?duration={millis}
```

Signs an externally-provided CSR using the referenced CA. The optional `duration` query parameter sets the certificate validity in milliseconds (default: 365 days).

**Request body**: PEM-encoded CSR

**Response**:

```json
{
  "cert": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
  "csr": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----",
  "ca": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
}
```

### Let's Encrypt certificate

```
POST /api/pki/certs/_letencrypt
```

Generates a certificate using Let's Encrypt (or any ACME-compatible server). Requires Let's Encrypt settings to be configured in the global configuration. Uses the HTTP-01 challenge for domain validation.

**Request body**:

```json
{
  "host": "api.example.com"
}
```

**Response**: Full certificate JSON entity with a `certId` field.

### Import PKCS#12

```
POST /api/pki/certs/_p12?password={password}
```

Imports certificates from a PKCS#12 (.p12/.pfx) file. The optional `password` query parameter provides the file password.

**Request body**: Raw binary .p12 file content

### Import PEM bundle

```
POST /api/certificates/_bundle
```

Imports a PEM bundle containing certificates and private keys.

**Request body**: PEM-encoded bundle (certificates + private key concatenated)

### Certificate validation

```
POST /api/pki/certs/_valid
```

Checks if a certificate is valid (chain validity, key matching, expiration, revocation).

**Request body**: Full certificate JSON entity

**Response**:

```json
{
  "valid": true
}
```

### Certificate data extraction

```
POST /api/pki/certs/_data
```

Extracts metadata from a PEM certificate (subject, issuer, validity dates, SANs, serial number, signature algorithm, etc.).

**Request body**: PEM-encoded certificate

**Response**: JSON object with certificate metadata.

### Certificate renewal

```
POST /api/certificates/:id/_renew
```

Renews an existing certificate. For Let's Encrypt certificates, triggers a new ACME flow. For other certificates, generates a new certificate with the same parameters.

## Certificate management API

Standard CRUD operations for certificate entities:

```
GET    /api/certificates              # List all certificates
POST   /api/certificates              # Create a certificate
GET    /api/certificates/:id          # Get a certificate
PUT    /api/certificates/:id          # Update a certificate
PATCH  /api/certificates/:id          # Partially update a certificate
DELETE /api/certificates/:id          # Delete a certificate
POST   /api/certificates/_bulk        # Bulk create
PUT    /api/certificates/_bulk        # Bulk update
PATCH  /api/certificates/_bulk        # Bulk patch
DELETE /api/certificates/_bulk        # Bulk delete
GET    /api/certificates/_template    # Get a certificate template
```

## The PKI UI

All certificates are listed in the back-office at `https://xxxxxx/bo/dashboard/certificates`. Certificates can be used to serve TLS traffic, perform mTLS calls, and sign/verify JWT tokens.

The UI provides these actions:

* **Add item**: manually create a certificate entity by pasting PEM content
* **Let's Encrypt certificate**: request a certificate from Let's Encrypt for a given host
* **Create certificate**: issue a certificate using an existing Otoroshi CA. You can create a server certificate, client certificate, or key pair
* **Import .p12 file**: import a PKCS#12 file

For each certificate, the list displays:

* Name and description
* Subject DN
* Type (CA / client / keypair / certificate)
* Revocation status (if revoked, the reason is shown)
* Creation date and expiration date

## Supported certificate formats

| Format | Import | Export | Description |
|--------|--------|--------|-------------|
| PEM | Yes | Yes | Text format with `-----BEGIN/END-----` markers. Used for chains and private keys |
| PKCS#12 (.p12/.pfx) | Yes | No | Binary format with password protection |
| DER | Yes | Yes | Binary format used for AIA certificate delivery |
| PKCS#8 | Yes | No | Encrypted private key format |
| PKCS#1 | Yes | No | RSA private key format |

## Let's Encrypt / ACME

Otoroshi supports the ACME protocol (via the acme4j library) for automatic certificate provisioning from Let's Encrypt or any ACME-compatible server.

### Configuration

Let's Encrypt settings are part of the global configuration:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable Let's Encrypt integration |
| `server` | string | `acme://letsencrypt.org/staging` | ACME server URL. Use `acme://letsencrypt.org` for production |
| `emails` | array of string | `[]` | Contact email addresses for the ACME account |
| `contacts` | array of string | `[]` | Additional contact URIs |
| `publicKey` | string | `""` | ACME account public key (PEM). Auto-generated on first use |
| `privateKey` | string | `""` | ACME account private key (PEM). Auto-generated on first use |

### How it works

1. Otoroshi creates an ACME account (or reuses existing account keys)
2. An order is placed for the requested domain
3. The HTTP-01 challenge is used: Otoroshi stores the challenge token and serves it on `/.well-known/acme-challenge/{token}`
4. Once validated, the certificate is issued, stored, and marked as `letsEncrypt: true` and `autoRenew: true`
5. Certificates are automatically renewed before expiration

### Auto-issuance from routes

Routes and services with the `letsEncrypt` flag or the `otoroshi-core-issue-lets-encrypt-certificate` metadata set to `true` will automatically trigger Let's Encrypt certificate creation for their domains.

## Auto-renewal

Certificates with `autoRenew: true` are automatically renewed by Otoroshi before they expire. The renewal process:

* For **Let's Encrypt certificates**: a new ACME flow is triggered
* For **Otoroshi-generated certificates**: a new certificate is generated with the same parameters
* A `CertRenewalAlert` event is emitted on successful renewal
* Expiring certificates (within 15 days) trigger alert events
* Expired certificates are marked with `[EXPIRED]` in their name

## Exposed public keys (JWKS)

Certificates with the `keypair` flag and the `exposed` flag set to `true` have their public keys published as a JWK Set (JSON Web Key Set) on:

* `https://xxxxxxxxx/.well-known/otoroshi/security/jwks.json` (on any route domain)
* `https://otoroshi-api.xxxxxxx.xx/.well-known/jwks.json` (on the admin API domain)

These endpoints return the list of exposed public keys following [the JWK standard (RFC 7517)](https://datatracker.ietf.org/doc/html/rfc7517). Services can use these endpoints to verify JWT tokens signed by Otoroshi.

## OCSP Responder

Otoroshi includes a built-in OCSP (Online Certificate Status Protocol) responder for checking the revocation status of certificates issued by its PKI.

### Endpoint

```
POST /.well-known/otoroshi/security/ocsp
POST /.well-known/otoroshi/ocsp          (on admin API domain)
```

### How it works

* Accepts standard OCSP requests (binary DER format, as per RFC 6960)
* Checks certificate status against the Otoroshi certificate store
* Supports nonce extension for replay protection
* Response caching with configurable TTL (default: 3600 seconds, configurable via `app.ocsp.caching.seconds`)
* Unknown certificates are rejected with a revoked status by default (extended revoke extension)

### Testing with OpenSSL

```bash
openssl ocsp \
  -issuer ca.pem \
  -cert certificate.pem \
  -text \
  -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp \
  -header "HOST" "otoroshi-api.oto.tools"
```

### Certificate revocation

Certificates can be revoked from the UI or via the admin API. The OCSP responder will return the appropriate status and reason.

Supported revocation reasons:

| Reason | Description |
|--------|-------------|
| `UNSPECIFIED` | Revoked for an unspecified reason |
| `KEY_COMPROMISE` | The subject's private key has been compromised |
| `CA_COMPROMISE` | The CA's private key has been compromised |
| `AFFILIATION_CHANGED` | The subject's name or information has changed |
| `SUPERSEDED` | The certificate has been replaced by a new one |
| `CESSATION_OF_OPERATION` | The certificate is no longer needed |
| `CERTIFICATE_HOLD` | Temporarily revoked |
| `REMOVE_FROM_CRL` | The certificate has been unrevoked |
| `PRIVILEGE_WITH_DRAWN` | A privilege in the certificate has been withdrawn |
| `AA_COMPROMISE` | The attribute authority has been compromised |

## Authority Information Access (AIA)

Generated certificates can include the AIA extension (when `includeAIA: true`), which contains:

* **CA Issuers**: URL to download the issuing CA certificate in DER format
* **OCSP**: URL of the OCSP responder for revocation checking

The CA certificate delivery endpoint is:

```
GET /.well-known/otoroshi/security/certificates/:serialNumber
GET /.well-known/otoroshi/certificates/:serialNumber          (on admin API domain)
```

This returns the certificate in `application/pkix-cert` (DER) format. Only certificates with the `exposed` flag and issued by the Otoroshi root CA are served.

## Certificate extensions

Otoroshi automatically adds the following X.509 extensions to generated certificates:

| Extension | Description |
|-----------|-------------|
| Basic Constraints | Marks CA certificates (`CA:TRUE` with path length) |
| Key Usage | Digital signature, key encipherment, certificate signing (for CAs) |
| Extended Key Usage | TLS Server Authentication, TLS Client Authentication (based on certificate type) |
| Subject Alternative Name | DNS names from the `hosts` field |
| Subject Key Identifier | Hash of the certificate's public key |
| Authority Key Identifier | Identifier of the issuing CA's key |
| Authority Information Access | OCSP responder and CA issuers URLs (when `includeAIA: true`) |
