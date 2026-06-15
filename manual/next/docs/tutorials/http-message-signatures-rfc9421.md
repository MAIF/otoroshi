---
title: Sign and verify HTTP messages (RFC 9421)
sidebar_position: 31
---
# Sign and verify HTTP messages (RFC 9421)

<div style={{display: 'flex', alignItems: 'center', gap: '.5rem', flexWrap: 'wrap'}}>
<span style={{fontWeight: 'bold'}}>Plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.HttpSignatureVerifyRequest">Verify HTTP Message Signature</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.HttpSignatureSignResponse">Add HTTP Message signature</a>
</div>

This tutorial walks through Otoroshi's two plugins that implement [RFC 9421 — HTTP Message Signatures](https://datatracker.ietf.org/doc/rfc9421/): one that **verifies** incoming signed requests at the edge, and one that **signs** outgoing responses on behalf of the upstream service.

## Why HTTP Message Signatures?

`Authorization: Bearer` tokens prove who issued a token, not who sent the request. JWT in the body is the same: it doesn't bind a token to a specific method, URI, or payload. Anyone who replays the bytes wins.

RFC 9421 fixes this by binding a cryptographic signature to the **wire shape of the message itself** — chosen request/response components (method, target URI, selected headers, the body digest, etc.) are concatenated into a canonical "signature base" string and signed. A signed request cannot be replayed against a different endpoint or with a tampered body without invalidating the signature.

Typical use cases:

- **Webhook receivers**: prove that the webhook came from the expected sender and was not modified in transit.
- **Bank-grade APIs** (FAPI, Open Banking, PSD2): mandate request signatures for sensitive operations.
- **Federated calls between trust domains**: each domain runs its own gateway and signs its outgoing traffic with its own key.
- **Audit trails**: signed responses give the receiver tamper-evident proof of what the producer answered.

## The two plugins

| Plugin | Direction | Role |
|--------|-----------|------|
| **Verify HTTP Message Signature** (`HttpSignatureVerifyRequest`) | Inbound | Validates the `Signature` / `Signature-Input` headers on requests reaching Otoroshi. Optionally verifies `Content-Digest` against the body |
| **Add HTTP Message signature** (`HttpSignatureSignResponse`) | Outbound | Signs the response from the upstream before it leaves Otoroshi. Optionally computes and injects `Content-Digest` |

Both plugins share the same algorithm and key-source codepaths, so once you understand one, the other only differs in direction.

## How the signature looks on the wire

A signed HTTP message carries two extra headers (RFC 8941 structured fields):

```http
Signature-Input: sig1=("@method" "@target-uri" "content-digest");\
                 created=1747461600;keyid="prod-key-1";alg="ed25519"
Signature:       sig1=:MEUCIQD0...:
```

`Signature-Input` says **what** was signed (the components and parameters); `Signature` carries the bytes. The label (`sig1`) lets a single message carry several signatures, e.g. one from the original client and one added by an intermediate.

When the body is part of the integrity guarantee, the sender also includes a `Content-Digest` header (RFC 9530) over the body bytes, and the signature covers the `content-digest` component:

```http
Content-Digest: sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:
```

Verifying the signature without the digest would only prove the headers haven't changed — the body would still be tamperable.

## Prerequisites

- A running Otoroshi instance with at least one route serving traffic.
- One key (HMAC shared secret, or an asymmetric keypair) that both sides agree on.
- A client capable of building the canonical signature base (any RFC 9421 library, or hand-rolled code).

## Tutorial 1 — Verify signed requests at the edge

In this scenario, an upstream business API only wants to be reached with signed requests. Otoroshi sits in front, validates the signature against a known key, and forwards only the requests that pass.

### Step 1: Choose an algorithm and a key

RFC 9421 supports several algorithms. The verify plugin understands:

| `alg` | Key type | Notes |
|-------|----------|-------|
| `hmac-sha256` | Shared symmetric secret | Simplest, both sides hold the same secret |
| `rsa-pss-sha512` | RSA 2048+ | Asymmetric, the verifier only needs the public key |
| `rsa-v1_5-sha256` | RSA 2048+ | Legacy PKCS#1 v1.5 |
| `ecdsa-p256-sha256` | EC P-256 | Compact signatures |
| `ecdsa-p384-sha384` | EC P-384 | Larger curve |
| `ed25519` | Ed25519 | Modern default, fast and deterministic |

For this tutorial we use **HMAC-SHA256** with a shared secret because it's the easiest to test from a shell. The secret is 32 random bytes, base64-encoded:

```sh
openssl rand -base64 32
# => uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ==
```

Keep that string — both the signer and Otoroshi will reference it.

### Step 2: Configure the route

Edit a route (e.g. `signed-api.oto.tools`) and add the **Verify HTTP Message Signature** plugin. Minimal JSON:

```json
{
  "plugin": "cp:otoroshi.next.plugins.HttpSignatureVerifyRequest",
  "config": {
    "mandatory": true,
    "require_keyid": true,
    "allowed_algorithms": ["hmac-sha256"],
    "required_components": ["@method", "@target-uri"],
    "max_age_seconds": 300,
    "clock_skew_seconds": 30,
    "keys": [
      {
        "kind": "inline",
        "secret_or_pem": "base64:uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ==",
        "keyid": "prod-key-1",
        "alg": "hmac-sha256"
      }
    ]
  }
}
```

What each setting does:

- `mandatory: true` — reject any request that does not carry `Signature` / `Signature-Input`. Set to `false` while you roll out the feature: invalid signatures are still rejected, but unsigned requests are allowed through.
- `require_keyid: true` — refuse signatures without a `keyid` parameter, so the verifier always knows which configured key to try.
- `allowed_algorithms` — whitelist of algorithms accepted on this route. Everything outside the list is rejected even if the key would otherwise work — defense against algorithm-confusion attacks.
- `required_components` — components that **must** appear in the signature base. If you also list `content-digest` here, the body is hashed and the digest is verified against the request bytes.
- `max_age_seconds` / `clock_skew_seconds` — bound the validity window so a captured signature cannot be replayed forever.
- `keys` — list of accepted keys. The `kind` can be `inline` (shared secret or PEM in the config), `cert` (a reference to an Otoroshi certificate entity), or `jwks` (fetched from a remote JWKS endpoint).

:::tip Inline secret prefixes
The `secret_or_pem` value supports prefixes for explicit decoding: `base64:...`, `base64url:...`, `hex:...`. Without a prefix the raw UTF-8 bytes are used. For HMAC keys always prefer one of the prefixes — otherwise a base64-looking secret is silently re-encoded.
:::

### Step 3: Sign a request from a client

The canonical signature base for `GET https://signed-api.oto.tools:8080/orders` with the components `@method` and `@target-uri` is:

```
"@method": GET
"@target-uri": https://signed-api.oto.tools:8080/orders
"@signature-params": ("@method" "@target-uri");created=1747461600;keyid="prod-key-1";alg="hmac-sha256"
```

Compute HMAC-SHA256 of that exact byte string with the shared secret, base64-encode the result, and put it in the headers. Bash equivalent:

```sh
SECRET_B64="uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ=="
CREATED=$(date +%s)
URL="https://signed-api.oto.tools:8080/orders"

BASE=$(printf '"@method": GET\n"@target-uri": %s\n"@signature-params": ("@method" "@target-uri");created=%s;keyid="prod-key-1";alg="hmac-sha256"' "$URL" "$CREATED")

SIG=$(printf '%s' "$BASE" | openssl dgst -sha256 -mac HMAC -macopt hexkey:$(echo -n "$SECRET_B64" | base64 -d | xxd -p -c 256) -binary | base64)

curl -i "$URL" \
  -H "Signature-Input: sig1=(\"@method\" \"@target-uri\");created=${CREATED};keyid=\"prod-key-1\";alg=\"hmac-sha256\"" \
  -H "Signature: sig1=:${SIG}:"
```

If the signature is valid, Otoroshi forwards the request to the backend and you get the upstream response. If it's invalid (wrong secret, tampered headers, expired `created`), Otoroshi returns:

```json
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{ "error": "invalid_http_signature", "details": "no candidate signature verified" }
```

### Step 4: Cover the body with Content-Digest

For POST/PUT/PATCH calls, add the body to the integrity envelope. Compute `Content-Digest` over the request body bytes, then add `content-digest` to the component list:

```http
Content-Digest: sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:
Signature-Input: sig1=("@method" "@target-uri" "content-digest");\
                 created=1747461600;keyid="prod-key-1";alg="hmac-sha256"
Signature:       sig1=:...:
```

Reflect this on the Otoroshi side too:

```json
"required_components": ["@method", "@target-uri", "content-digest"]
```

When `content-digest` is required, the plugin reads the request body, recomputes the digest, and rejects the call if it doesn't match — so a tampered body fails closed even if the headers verify. This is required by RFC 9421 §4.1 once the signer covered the digest.

## Tutorial 2 — Sign responses before they leave Otoroshi

Now suppose your business API is plain HTTP, but external partners need cryptographic proof that the response really came from your platform. The **Add HTTP Message signature** plugin signs the response on the way out using a key you control.

### Step 1: Provision a signing keypair

For asymmetric signing, generate an Ed25519 keypair and import it as an Otoroshi certificate entity (the simplest path because the private key lives inside the gateway and is rotated through the standard cert workflow):

```sh
openssl genpkey -algorithm ed25519 -out signer.key
openssl pkey -in signer.key -pubout -out signer.pub
```

Import the keypair under **Certificates** in the UI (or via the admin API), mark it as a keypair, and note its ID — say `sig-key-1`.

### Step 2: Configure the plugin

```json
{
  "plugin": "cp:otoroshi.next.plugins.HttpSignatureSignResponse",
  "config": {
    "algorithm": "ed25519",
    "keyid": "sig-key-1",
    "signature_label": "sig1",
    "components": ["@status", "content-type", "content-digest"],
    "add_content_digest": true,
    "content_digest_algorithm": "sha-256",
    "include_created": true,
    "expires_in_seconds": 300,
    "key": {
      "kind": "cert",
      "cert_id": "sig-key-1",
      "alg": "ed25519"
    }
  }
}
```

What the response will look like:

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Digest: sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:
Signature-Input: sig1=("@status" "content-type" "content-digest");\
                 created=1747461600;expires=1747461900;keyid="sig-key-1";alg="ed25519"
Signature:       sig1=:MEUCIQD0...:

{ ...body... }
```

The plugin:

1. Optionally buffers the response body to compute `Content-Digest` (only when `content-digest` is in the covered components and `add_content_digest=true`).
2. Builds the canonical signature base from the response headers + status + body digest.
3. Looks up the private key from the referenced cert.
4. Signs and injects `Signature-Input` and `Signature` headers.

### Step 3: Publish the public key

External partners need the public key to verify the signature. Two practical options:

- **JWKS endpoint** — mark the certificate as `exposed`, and Otoroshi will publish it on `/.well-known/jwks.json`. See [PKI — Exposed public keys](../topics/pki.md#exposed-public-keys-jwks-).
- **Static distribution** — give partners the PEM out-of-band.

Receivers configure their verifier with that public key (or fetch JWKS), and the `keyid` parameter in `Signature-Input` tells them which key to pick.

### Step 4: Verify the response (partner-side)

A receiver implementing RFC 9421 reconstructs the canonical base from the response headers, fetches the public key for `keyid="sig-key-1"`, and runs the algorithm verification. As long as nothing on the wire changed, the signature verifies.

## Combining both plugins — signed call, signed reply

The two plugins compose naturally. Put `HttpSignatureVerifyRequest` and `HttpSignatureSignResponse` on the same route, and every request that gets through is both validated and answered with a signed reply:

```json
{
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.HttpSignatureVerifyRequest",
      "config": {
        "mandatory": true,
        "required_components": ["@method", "@target-uri", "content-digest"],
        "allowed_algorithms": ["ed25519", "ecdsa-p256-sha256"],
        "keys": [
          { "kind": "jwks", "url": "https://partner.example.com/.well-known/jwks.json" }
        ]
      }
    },
    {
      "plugin": "cp:otoroshi.next.plugins.HttpSignatureSignResponse",
      "config": {
        "algorithm": "ed25519",
        "keyid": "sig-key-1",
        "components": ["@status", "content-type", "content-digest"],
        "add_content_digest": true,
        "key": { "kind": "cert", "cert_id": "sig-key-1", "alg": "ed25519" }
      }
    }
  ]
}
```

This is a typical setup for federated APIs: caller and callee each sign their direction of the exchange, each holding the other's public key.

## Reference — components you can sign

Anything in this table can appear in `components` (sign-response) or `required_components` (verify-request):

| Component | What it covers |
|-----------|----------------|
| `@method` | HTTP method (`GET`, `POST`, …) |
| `@target-uri` | Full request URI |
| `@authority` | Host + port |
| `@scheme` | `http` / `https` |
| `@request-target` | Path + query |
| `@path` | Path component |
| `@query` | Raw query string |
| `@query-param;name="foo"` | A single query parameter, preserving case |
| `@status` | Response status (response only) |
| any header name (lowercase) | The header value, normalized |
| `content-digest` | The digest header; triggers body integrity checks |
| `<component>;req` | When signing a response, reference the component from the originating request |

## Key source kinds

The `keys` (verify) and `key` (sign) fields accept three kinds of sources:

```json
// HMAC shared secret or PEM, inline in the config
{ "kind": "inline", "secret_or_pem": "base64:...", "keyid": "k1", "alg": "hmac-sha256" }

// Reference to an Otoroshi certificate entity (asymmetric only)
{ "kind": "cert", "cert_id": "my-signing-cert", "keyid": "k2", "alg": "ed25519" }

// Remote JWKS endpoint (asymmetric only, verify-only)
{ "kind": "jwks", "url": "https://idp.example.com/.well-known/jwks.json", "alg": "rsa-pss-sha512" }
```

Two important rules enforced by the verifier:

- If the configured key declares its own `alg`, that wins over the algorithm announced in the signature — this defeats algorithm-confusion attacks where an attacker swaps `rsa-pss-sha512` for `hmac-sha256` and uses the public key as a shared secret.
- Keys with a different `keyid` than the signature are never tried; only when the signature has no `keyid` (and `require_keyid=false`) does the verifier fall through to keys without a declared id.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `missing Signature-Input or Signature header` | `mandatory=true` but the client didn't sign |
| `no candidate signature verified` | Wrong key, wrong base construction, or the request was modified after signing |
| `signature 'sigX' uses disallowed algorithm 'Y'` | The algorithm announced in `alg=` is not in `allowed_algorithms` |
| `signature 'sigX' is older than N seconds` | `created` is outside the `max_age_seconds + clock_skew_seconds` window |
| `signature covers content-digest but Content-Digest header is missing` | Signer listed `content-digest` but didn't include the header |
| `content-digest verification failed` | The body was modified between signing and arrival, or the digest was computed with a different algorithm |

Enable the `otoroshi-plugins-httpsig-verify-request` and `otoroshi-plugins-httpsig-sign-response` loggers at `DEBUG` level to see per-candidate failure reasons; the plugins log why each key/signature combination was rejected.

## What's not in the plugins (yet)

- **Re-signing**: forwarding a verified signature, or stripping it and re-signing with Otoroshi's key, is not built in. Compose with a custom plugin or a workflow when you need that.
- **Signing requests outbound to a backend**: only the response-signing direction is wired today. To sign the request that Otoroshi forwards, write a small request-transformer plugin reusing `HttpSigBase` and `HttpSigAlgorithms` — both objects in `otoroshi.next.plugins` are public.

## Related

- [RFC 9421 — HTTP Message Signatures](https://datatracker.ietf.org/doc/rfc9421/)
- [RFC 9530 — Digest Fields](https://datatracker.ietf.org/doc/rfc9530/)
- [TLS topic](../topics/tls.md) — TLS termination, mTLS modes, client cert forwarding
- [PKI topic](../topics/pki.md) — Certificate entities, JWKS, exposed keys
- [Secure an app with JWT verifiers](./secure-an-app-with-jwt-verifiers.mdx) — alternative when only the issuer needs to be proved
