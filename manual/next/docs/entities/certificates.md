---
title: Certificates
sidebar_position: 6
---
# Certificates

## Overview

In an API gateway architecture, every connection between clients, the gateway, and backend services can be secured with TLS. Managing these certificates across dozens or hundreds of services quickly becomes a burden when each backend server maintains its own certificates. Otoroshi solves this by providing **centralized certificate management**: all certificates -- whether imported, generated, or automatically provisioned -- are stored in a single place and dynamically applied at runtime without restarts.

Certificates in Otoroshi serve several purposes:

- **TLS termination** -- Routes reference certificates to serve HTTPS traffic. Otoroshi dynamically selects the right certificate based on the SNI (Server Name Indication) sent by the client, so a single gateway instance can terminate TLS for many domains simultaneously.
- **Backend mTLS** -- When backend services require mutual TLS, Otoroshi can present a client certificate during the upstream connection. This is configured at the backend level, and the client certificate is managed centrally like any other certificate.
- **Client certificate authentication** -- Otoroshi can require connecting clients to present a valid certificate signed by a trusted CA, enabling strong mutual authentication at the edge.
- **JWT signing and verification** -- Key pairs stored as certificate entities can be used by JWT verifiers and auth modules to sign or validate tokens. Public keys can be exposed on the standard `/.well-known/jwks.json` endpoint.

### Built-in PKI

One of Otoroshi's distinctive features is its **built-in Public Key Infrastructure (PKI)**. Otoroshi can act as its own Certificate Authority, which means you can:

- Generate a **root CA** and **intermediate CA** (Otoroshi creates these automatically on first startup: `otoroshi-root-ca` and `otoroshi-intermediate-ca`).
- Issue server certificates, client certificates, and sub-CAs signed by an Otoroshi CA, with full control over key type (RSA, ECDSA), key size, signature algorithm, validity period, and Subject Alternative Names.
- Sign Certificate Signing Requests (CSRs) submitted through the admin API.
- Manage the full certificate chain, including Authority Information Access (AIA) extensions.

This eliminates the need for an external CA for internal service-to-service communication and development environments.

### ACME / Let's Encrypt integration

For publicly-facing domains, Otoroshi integrates with **ACME-compatible providers** such as Let's Encrypt. Once ACME is enabled in the global configuration, you can request a trusted certificate for any hostname directly from the admin UI or the API. Let's Encrypt certificates are renewed automatically before expiration.

### OCSP responder

Otoroshi includes a built-in **OCSP responder** that can answer Online Certificate Status Protocol queries for certificates issued by its own PKI. This allows TLS clients to verify in real time whether a certificate has been revoked, without relying on CRL distribution points.

### Certificate lifecycle

Certificates in Otoroshi follow a managed lifecycle:

1. **Provisioning** -- Certificates can be imported (PEM, PKCS#12), generated from an Otoroshi CA, requested from Let's Encrypt, or created as self-signed.
2. **Active use** -- Once stored, certificates are immediately available for TLS termination, mTLS, or JWT operations. Otoroshi watches certificate validity and flags certificates that are approaching expiration.
3. **Auto-renewal** -- When the `autoRenew` flag is enabled, Otoroshi automatically renews certificates that reach the last 20% of their validity period. The previous certificate is preserved with an "[UNTIL EXPIRATION]" prefix so that in-flight connections are not disrupted.
4. **Revocation** -- Certificates can be marked as revoked. Revoked certificates are excluded from TLS selection and reported as such by the OCSP responder.
5. **Expiration** -- Expired certificates are flagged and trigger alerts (CertExpiredAlert, CertAlmostExpiredAlert) that can be forwarded through data exporters.

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
