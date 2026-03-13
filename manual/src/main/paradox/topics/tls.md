# TLS

Otoroshi manages TLS certificates dynamically. Once a certificate is imported or created in Otoroshi, it can immediately be used to serve HTTPS requests, to perform mTLS calls to backends, or to sign and verify JWT tokens. Certificates are stored in the Otoroshi datastore and synchronized across cluster nodes.

## TLS termination

Any certificate added to Otoroshi with a valid CN and/or SANs can be used within seconds to serve HTTPS requests. Otoroshi uses a dynamic TLS engine based on SNI (Server Name Indication) to select the right certificate for each incoming connection.

If you import a certificate **without a private key**, it will be treated as a trusted CA (useful for backend mTLS verification or client certificate validation).

### Client authentication (mTLS)

By default, Otoroshi uses the `Dynamic` client authentication mode. In this mode, the actual mTLS behavior is **read from the global configuration at each TLS handshake**, which means it can be changed at runtime without restarting Otoroshi. This also works correctly in cluster mode, since the global configuration is synchronized between leader and worker nodes.

The static configuration key is:

```conf
otoroshi.ssl.fromOutside.clientAuth = "Dynamic"
```

| Mode | Env variable | Description |
|------|--------------|-------------|
| `None` | `SSL_OUTSIDE_CLIENT_AUTH=None` | No client certificate requested. Default for best performance |
| `Want` | `SSL_OUTSIDE_CLIENT_AUTH=Want` | Client certificate is requested but not required. If provided, it is validated |
| `Need` | `SSL_OUTSIDE_CLIENT_AUTH=Need` | Client certificate is required. Connections without a valid client certificate are rejected |
| `Dynamic` | `SSL_OUTSIDE_CLIENT_AUTH=Dynamic` | The actual mode (`None`, `Want`, or `Need`) is read from the global configuration `tlsSettings.clientAuth` at each handshake. This is the default |

You can also set this via:

```sh
OTOROSHI_SSL_OUTSIDE_CLIENT_AUTH=Dynamic
```

For the Netty server specifically:

```sh
OTOROSHI_SSL_OUTSIDE_NETTY_CLIENT_AUTH=Dynamic
```

### Dynamic mTLS via global configuration

When `clientAuth` is set to `Dynamic` (the default), the actual mTLS mode is controlled by the `tlsSettings` section of the global configuration entity. This can be updated at runtime via the admin API or the Danger Zone in the UI.

The global configuration `tlsSettings` object controls:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `clientAuth` | string | `None` | mTLS mode applied at TLS handshake time: `None`, `Want`, or `Need` |
| `defaultDomain` | string | `null` | Default domain used when no certificate matches the SNI. If set, the certificate for this domain is used as fallback |
| `randomIfNotFound` | boolean | `false` | If `true` and no certificate matches, a random certificate is used instead of failing |
| `includeJdkCaServer` | boolean | `true` | Include JDK built-in CAs when validating client certificates (server-side mTLS). Set to `false` to only trust Otoroshi-managed CAs |
| `includeJdkCaClient` | boolean | `true` | Include JDK built-in CAs when calling backends over TLS (client-side). Set to `false` to only trust Otoroshi-managed CAs |
| `trustedCAsServer` | array of string | `[]` | List of Otoroshi certificate IDs to present as trusted CAs during mTLS handshake. When not empty (and `includeJdkCaServer` is `false`), only these CAs are trusted for client certificate validation |
| `bannedAlpnProtocols` | object | `{}` | Map of domain names to lists of banned ALPN protocols. Useful to force HTTP/1.1 on specific domains |

#### Example: enable mTLS via admin API

```sh
curl -X PATCH 'http://otoroshi-api.oto.tools:8080/api/globalconfig' \
  -H 'Content-Type: application/json' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d '{
    "tlsSettings": {
      "clientAuth": "Need",
      "includeJdkCaServer": false,
      "trustedCAsServer": ["otoroshi-intermediate-ca", "my-custom-ca-id"]
    }
  }'
```

This immediately changes the mTLS mode for all new connections, including on cluster worker nodes once they sync the global config.

#### Example: disable mTLS

```sh
curl -X PATCH 'http://otoroshi-api.oto.tools:8080/api/globalconfig' \
  -H 'Content-Type: application/json' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d '{
    "tlsSettings": {
      "clientAuth": "None"
    }
  }'
```

### Trusted CAs for server-side mTLS

The list of CAs presented to clients during the mTLS handshake comes from two sources combined:

1. **Global config**: `tlsSettings.trustedCAsServer` (certificate IDs, updatable at runtime)
2. **Static config**: `otoroshi.ssl.trust.server_cas` (certificate IDs in the config file)

```conf
otoroshi.ssl.trust {
  server_cas = ["my-ca-cert-id-1", "my-ca-cert-id-2"]
  # or as a comma-separated string via env variable:
  server_cas_str = ${?OTOROSHI_SSL_TRUST_SERVER_CAS}
}
```

When `includeJdkCaServer` is `true` (default), JDK built-in CAs are also included in the trust store. Set it to `false` if you want a strict, Otoroshi-only trust store.

## TLS termination configuration

You can configure TLS termination statically using the configuration file. All settings are under `otoroshi.ssl`:

```conf
otoroshi.ssl {
  # Cipher suites for TLS termination (defaults to JDK 11 suite)
  cipherSuites = ${otoroshi.ssl.cipherSuitesJDK11}

  # TLS protocol versions (defaults to modern: TLSv1.3 + TLSv1.2)
  protocols = ${otoroshi.ssl.modernProtocols}

  # Available protocol presets:
  # modernProtocols = ["TLSv1.3", "TLSv1.2"]
  # protocolsJDK11 = ["TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"]
  # protocolsJDK8 = ["SSLv2Hello", "TLSv1", "TLSv1.1", "TLSv1.2"]

  # JDK cacert trust store
  cacert {
    path = "$JAVA_HOME/lib/security/cacerts"
    password = "changeit"
  }

  # mTLS mode for incoming connections
  fromOutside {
    clientAuth = "Dynamic"
    clientAuth = ${?SSL_OUTSIDE_CLIENT_AUTH}
    clientAuth = ${?OTOROSHI_SSL_OUTSIDE_CLIENT_AUTH}
  }

  # Trust settings
  trust {
    all = false                                       # Trust ALL CAs (dangerous, for dev only)
    all = ${?OTOROSHI_SSL_TRUST_ALL}
    server_cas = []                                   # Static trusted CA IDs for mTLS
    server_cas_str = ${?OTOROSHI_SSL_TRUST_SERVER_CAS}  # Comma-separated CA IDs
  }

  # Custom root CA for Otoroshi (replaces auto-generated)
  rootCa {
    ca = ${?OTOROSHI_SSL_ROOTCA_CA}         # PEM CA certificate
    cert = ${?OTOROSHI_SSL_ROOTCA_CERT}     # PEM certificate
    key = ${?OTOROSHI_SSL_ROOTCA_KEY}       # PEM private key
    importCa = false
    importCa = ${?OTOROSHI_SSL_ROOTCA_IMPORTCA}
  }

  # Initial certificates for bootstrapping (useful for cluster workers)
  initialCacert = ${?OTOROSHI_INITIAL_CACERT}
  initialCert = ${?OTOROSHI_INITIAL_CERT}
  initialCertKey = ${?OTOROSHI_INITIAL_CERT_KEY}
  initialCertImportCa = ${?OTOROSHI_INITIAL_CERT_IMPORTCA}
}
```

### TLS termination settings in the UI

In the Danger Zone (`/bo/dashboard/dangerzone`), the **TLS Settings** section lets you configure:

* **Default domain**: the domain whose certificate is used when no SNI match is found
* **Random if not found**: use a random certificate when no SNI match is found (instead of failing)
* **Trust JDK CAs (client)**: include JDK built-in CAs when calling backends
* **Trust JDK CAs (server)**: include JDK built-in CAs when validating client certificates in mTLS
* **Trusted CAs (server)**: select specific Otoroshi CA certificates to present during mTLS handshake

## Auto-generated certificates

Otoroshi can automatically generate TLS certificates on the fly for domains that don't have one, without losing the incoming request. This is configured in the Danger Zone under **Auto Generate Certificates** or via the global config.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable automatic certificate generation |
| `caRef` | string | `null` | ID of the CA certificate used to sign auto-generated certificates. The client must trust this CA |
| `allowed` | array of string | `["*"]` | Regex patterns for domains allowed to trigger auto-generation |
| `notAllowed` | array of string | `[]` | Regex patterns for domains that must NOT trigger auto-generation (takes precedence over `allowed`) |
| `replyNicely` | boolean | `false` | When a domain is not allowed, reply with a human-readable error instead of a TLS error |

### Example: enable auto-cert in global config

```json
{
  "autoCert": {
    "enabled": true,
    "caRef": "otoroshi-intermediate-ca",
    "allowed": [".*\\.my-domain\\.com"],
    "notAllowed": ["admin\\.my-domain\\.com"],
    "replyNicely": true
  }
}
```

When a request arrives for `api.my-domain.com` and no matching certificate exists, Otoroshi generates one on the fly using the referenced CA and serves the response without dropping the connection.

## Backend TLS and mTLS

For each backend target, you can configure TLS behavior independently using the `tls_config` (or `tlsConfig`) object on the target or backend.

| Property | JSON key | Type | Default | Description |
|----------|----------|------|---------|-------------|
| `enabled` | `enabled` | boolean | `false` | Enable custom TLS settings for this backend |
| `certs` | `certs` | array of string | `[]` | Otoroshi certificate IDs to use as client certificates for mTLS to the backend |
| `trustedCerts` | `trusted_certs` | array of string | `[]` | Otoroshi certificate IDs to trust as CAs for verifying the backend's certificate |
| `loose` | `loose` | boolean | `false` | Skip hostname verification (useful for backends with self-signed certs) |
| `trustAll` | `trust_all` | boolean | `false` | Trust any CA (dangerous, for dev/test only) |

### Example: backend mTLS configuration

```json
{
  "backend": {
    "targets": [
      {
        "hostname": "secure-api.internal",
        "port": 443,
        "tls": true,
        "tls_config": {
          "enabled": true,
          "certs": ["my-client-cert-id"],
          "trusted_certs": ["internal-ca-id"],
          "loose": false,
          "trust_all": false
        }
      }
    ]
  }
}
```

This configuration tells Otoroshi to:

1. Present the certificate `my-client-cert-id` as the client certificate to the backend
2. Only trust the CA `internal-ca-id` when verifying the backend's TLS certificate
3. Perform strict hostname verification

## Key pair for JWT signing and verification

Certificates can also be used as key pairs for signing and verifying JWT tokens. To use a certificate as a key pair, mark it with the `keypair` flag on the certificate page or via the admin API.

Once marked as a key pair, it can be referenced in @ref:[JWT verifiers](../entities/jwt-verifiers.md) for token signing and verification using RSA or ECDSA algorithms.

Key pairs with the `exposed` flag set to `true` are published on the JWKS endpoint. See @ref:[PKI - Exposed public keys](./pki.md#exposed-public-keys-jwks-) for details.

## Related

* @ref:[PKI](./pki.md) - Certificate management, generation, OCSP, and JWKS
* @ref:[Certificates](../entities/certificates.md) - Certificate entity reference
* @ref:[End-to-end mTLS](../how-to-s/end-to-end-mtls.md) - Step-by-step mTLS guide
* @ref:[TLS termination with your own certificates](../how-to-s/tls-termination-using-own-certificates.md) - Custom TLS termination how-to
* @ref:[Let's Encrypt](../how-to-s/tls-using-lets-encrypt.md) - Automatic certificate provisioning
* @ref:[Custom HTTP Listeners](./http-listeners.md) - Per-listener TLS and mTLS settings
