---
title: Certificates
sidebar_position: 6
---
# Certificates

Certificates are used across Otoroshi for TLS termination, mTLS, JWT token signing and verification, and more. All generated and imported certificates are managed in a central store and can be referenced by routes, backends, auth modules, and other entities.

## UI page

You can find all certificates [here](http://otoroshi.oto.tools:8080/bo/dashboard/certificates)

The available actions from the UI are:

* **Add item**: Manually add an existing certificate (e.g., from a PEM file)
* **Let's Encrypt certificate**: Request a certificate from Let's Encrypt for a given hostname
* **Create certificate**: Issue a new certificate signed by an existing Otoroshi CA
* **Import .p12 file**: Load a PKCS#12 file as a certificate

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string |     | Unique identifier of the certificate |
| `name` | string |     | Display name of the certificate |
| `description` | string |     | Description |
| `chain` | string |     | PEM-encoded certificate chain (full chain) |
| `privateKey` | string |     | PEM-encoded private key (can be empty for trust-only certificates) |
| `caRef` | string | `null` | Reference to the CA certificate that signed this certificate |
| `domain` | string | `"--"` | Primary domain of the certificate |
| `selfSigned` | boolean | `false` | Whether the certificate is self-signed |
| `ca` | boolean | `false` | Whether this certificate is a Certificate Authority |
| `valid` | boolean | `false` | Whether the certificate is currently valid (not expired, not revoked) |
| `exposed` | boolean | `false` | If `true`, the public key is exposed on `/.well-known/jwks.json` |
| `revoked` | boolean | `false` | Whether the certificate has been revoked |
| `autoRenew` | boolean | `false` | Auto-renew the certificate when it expires (requires a known CA and private key) |
| `letsEncrypt` | boolean | `false` | Certificate was issued by Let's Encrypt |
| `client` | boolean | `false` | This certificate is a client certificate (used for mTLS authentication) |
| `keypair` | boolean | `false` | This entity is a key pair (public + private key, without a certificate chain) |
| `subject` | string | `"--"` | Subject DN of the certificate |
| `from` | number |     | Validity start date (timestamp in milliseconds) |
| `to` | number |     | Validity end date (timestamp in milliseconds) |
| `sans` | array of string | `[]` | Subject Alternative Names (additional hostnames/IPs covered by this cert) |
| `password` | string | `null` | Password protecting the private key (optional) |
| `tags` | array of string | `[]` | Tags |
| `metadata` | object | `{}` | Key/value metadata |

## Certificate creation

### From Let's Encrypt

| Property | Description |
|----------|-------------|
| `host` | The hostname to request the certificate for |

Let's Encrypt must be enabled in the [Global Config](./global-config.md) with a valid ACME server URL.

### From an existing CA

When creating a certificate from an Otoroshi CA:

| Property | Description |
|----------|-------------|
| `Issuer` | The CA certificate used to sign the new certificate |
| `CA certificate` | If enabled, the new certificate will be a CA itself |
| `Client certificate` | If enabled, the certificate will be used for client authentication |
| `Include A.I.A` | Include Authority Information Access URLs in the certificate |
| `Key Type` | Type of the private key (RSA, EC) |
| `Key Size` | Size of the private key (2048, 4096, etc.) |
| `Signature Algorithm` | Algorithm used to sign the certificate |
| `Digest Algorithm` | Digest algorithm used |
| `Validity` | How long the certificate will be valid |
| `Subject DN` | Subject Distinguished Name |
| `Hosts` | Hostnames covered by the certificate (added to SAN) |

## JSON example

```json
{
  "id": "cert_api_example_com",
  "name": "api.example.com",
  "description": "TLS certificate for the public API",
  "chain": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
  "privateKey": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
  "caRef": "cert_internal_ca",
  "domain": "api.example.com",
  "selfSigned": false,
  "ca": false,
  "valid": true,
  "exposed": false,
  "revoked": false,
  "autoRenew": true,
  "letsEncrypt": false,
  "client": false,
  "keypair": false,
  "subject": "CN=api.example.com",
  "from": 1710000000000,
  "to": 1741536000000,
  "sans": ["api.example.com", "*.api.example.com"],
  "password": null,
  "tags": ["production"],
  "metadata": {}
}
```

## Admin API

```
GET    /api/certificates           # List all certificates
POST   /api/certificates           # Create/import a certificate
GET    /api/certificates/:id       # Get a certificate
PUT    /api/certificates/:id       # Update a certificate
DELETE /api/certificates/:id       # Delete a certificate
PATCH  /api/certificates/:id       # Partially update a certificate
```

Additional endpoints:

```
GET  /api/certificates/:id/valid   # Check certificate validity
POST /api/certificates/_renew      # Trigger certificate renewal
```

## Related entities

* [Routes](./routes.md) - Routes use certificates for TLS termination
* [Backends](./backends.md) - Backend targets can use client certificates for mTLS
* [Auth Modules](./auth-modules.md) - OIDC/OAuth modules can use certificates for provider communication
* [JWT Verifiers](./jwt-verifiers.md) - Key pairs can be used for JWT signing and verification
