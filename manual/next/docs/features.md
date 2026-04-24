---
title: Features
sidebar_position: 4
---
# Features

**Traffic Management**

* Can proxy any HTTP(s) service (APIs, webapps, websocket, etc) ([routes](./entities/routes.md), [engine](./topics/engine.mdx))
* Can proxy any TCP service (app, database, etc) ([TCP services](./entities/tcp-services.md))
* Can proxy any gRPC service (using a netty listener), also gRPC-Web support ([HTTP listeners](./topics/http-listeners.mdx))
* Can proxy any GraphQL service (proxy, query composition, and schema-first backend) ([GraphQL composer](./topics/graphql-composer.mdx))
* Full WebSocket support with message validation, transformation, and mirroring
* End-to-end HTTP/1.1 support
* End-to-end HTTP/2 support (including H2C cleartext) ([Netty server](./topics/netty-server.md))
* End-to-end HTTP/3 support (QUIC) ([HTTP/3](./topics/http3.md))
* Multiple load balancing options: ([backends](./entities/backends.md))
    * Round Robin
    * Random
    * Sticky (cookie-based session affinity)
    * IP Address Hash
    * Best Response Time
    * Weighted Best Response Time
    * Least Connections
    * Power of Two Random Choices
    * Header Hash (consistent hashing on a request header)
    * Cookie Hash (consistent hashing on a cookie value)
    * Query Hash (consistent hashing on a query parameter)
* Backend Failover targets support ([backends](./entities/backends.md))
* Distributed in-flight request limiting ([built-in plugins](./plugins/built-in-plugins.mdx))
* Distributed rate limiting ([built-in plugins](./plugins/built-in-plugins.mdx))
* Per-IP, per-API key, per-route, and custom throttling and quotas ([built-in plugins](./plugins/built-in-plugins.mdx))
* Request and response bandwidth throttling
* Request and response body size limiting
* Traffic mirroring to secondary backends ([built-in plugins](./plugins/built-in-plugins.mdx))
* Traffic capture (GoReplay format) ([engine](./topics/engine.mdx))
* Canary deployments (percentage-based and time-controlled)
* Relay routing across network zones ([relay routing](./topics/relay-routing.mdx))
* Tunnels for easier network exposition (TCP, UDP, WebSocket-based) ([tunnels](./topics/tunnels.mdx))
* Custom error templates ([error templates](./entities/error-templates.md))

**Routing**

* Router can support tens of thousands of concurrent routes ([engine](./topics/engine.mdx))
* Router supports path parameter extraction (with regex validation) ([engine](./topics/engine.mdx))
* Routing based on: ([routes](./entities/routes.md))
    * HTTP method
    * hostname (exact, wildcard)
    * path (exact, prefix, wildcard)
    * header values (exact, regex, wildcard)
    * query param values (exact, regex, wildcard)
    * cookie values
* Full URL rewriting ([backends](./entities/backends.md))
* Path stripping ([routes](./entities/routes.md))
* Target predicates (route to specific backends based on geolocation, cloud region, datacenter, rack, etc) ([backends](./entities/backends.md))

**Routes customization**

* Over 200 built-in middlewares (plugins) covering: ([built-in plugins](./plugins/built-in-plugins.mdx))
    * circuit breakers (with configurable thresholds) ([circuit breaker](./topics/circuit-breaker.md))
    * automatic retries (with exponential backoff)
    * response caching ([built-in plugins](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.NgResponseCache))
    * gzip and brotli compression ([gzip](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.GzipResponseCompressor), [brotli](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.BrotliResponseCompressor))
    * request and response headers manipulation ([input](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.AdditionalHeadersIn), [output](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.AdditionalHeadersOut))
    * request and response cookies manipulation ([input](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.AdditionalCookieInt), [output](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.AdditionalCookieOut))
    * CORS handling ([CORS](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.Cors))
    * body transformation (jq, regex, XML/JSON conversion, SOAP) ([built-in plugins](./plugins/built-in-plugins.mdx))
    * query string transformation ([built-in plugins](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.QueryTransformer))
    * GraphQL composition ([GraphQL composer](./topics/graphql-composer.mdx#otoroshi.next.plugins.GraphQLBackend))
    * HTML patching ([built-in plugins](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.NgHtmlPatcher))
    * redirection ([built-in plugins](./plugins/built-in-plugins.mdx#otoroshi.next.plugins.Redirection))
    * maintenance and build modes ([built-in plugins](./plugins/built-in-plugins.mdx))
    * static responses and mocks ([built-in plugins](./plugins/built-in-plugins.mdx))
    * etc
* Support middlewares compiled to WASM (using Extism) ([WASM plugins](./entities/wasm-plugins.md), [WASM usage](./topics/wasm-usage.mdx))
* Support Open Policy Agent policies for traffic control (via WASM) ([WASM usage](./topics/wasm-usage.mdx))
* Visual workflow engine for building complex processing pipelines ([workflows](./entities/workflows.md), [workflow editor](./topics/workflows-editor.mdx))
* Write your own custom middlewares: ([create plugins](./plugins/create-plugins.md))
    * in Scala deployed as jar files
    * in whatever language you want that can be compiled to WASM ([WASM how-to](./tutorials/wasm-usage.mdx))

**Security**

* Coraza Web Application Firewall (WAF) with OWASP Core Rule Set support ([WAF how-to](./tutorials/instantiate-waf-coraza.mdx))
* IP address allow and block lists (with CIDR support) ([built-in plugins](./plugins/built-in-plugins.mdx))
* Domain name allow and block lists ([built-in plugins](./plugins/built-in-plugins.mdx))
* Fail2Ban-style automatic IP banning ([built-in plugins](./plugins/built-in-plugins.mdx))
* Geolocation-based access control (MaxMind, IPStack) ([built-in plugins](./plugins/built-in-plugins.mdx))
* Time-restricted access control
* Log4Shell and React2Shell vulnerability detection ([built-in plugins](./plugins/built-in-plugins.mdx))
* Security headers injection (HSTS, CSP, X-Frame-Options, X-XSS-Protection, X-Content-Type-Options)
* security.txt endpoint (RFC 9116)
* robots.txt handling

**API security**

* Access management with API keys and quotas ([API keys](./entities/apikeys.mdx), [how-to](./tutorials/secure-with-apikey.mdx))
* Multiple API key extraction methods (header, query param, cookie, bearer token, basic auth, JWT) ([API keys](./entities/apikeys.mdx))
* Automatic API key secrets rotation ([API keys](./entities/apikeys.mdx))
* Mandatory tags and metadata validation on API keys
* HTTPS and TLS ([TLS](./topics/tls.md))
* End-to-end mTLS calls ([mTLS how-to](./tutorials/end-to-end-mtls.md))
* Routing constraints and restrictions
* Public/private path separation
* JWT token validation and manipulation ([JWT verifiers](./entities/jwt-verifiers.md), [how-to](./tutorials/secure-an-app-with-jwt-verifiers.mdx))
    * Multiple validators on the same route
    * JWE (encrypted JWT) support
    * JWT signing
* HMAC request signing and validation ([Otoroshi protocol](./topics/otoroshi-protocol.mdx))
* OpenFGA fine-grained authorization ([built-in plugins](./plugins/built-in-plugins.mdx))
* Role-based access control (RBAC) ([built-in plugins](./plugins/built-in-plugins.mdx))
* Context validation (JSON path-based claims validation) ([built-in plugins](./plugins/built-in-plugins.mdx))

**Monitoring and observability**

* Active health checks (with customizable healthy/unhealthy status codes and regex body checks) ([backends](./entities/backends.md))
* Route state for the last 90 days
* Calls tracing using W3C Trace Context ([built-in plugins](./plugins/built-in-plugins.mdx))
* Real-time traffic metrics ([monitoring](./topics/monitoring.md))
* Prometheus metrics export ([monitoring](./topics/monitoring.md))
* OpenTelemetry metrics and logs export (OTLP) ([OpenTelemetry](./topics/opentelemetry.mdx))
* Datadog, StatsD metrics export ([monitoring](./topics/monitoring.md))
* Export alerts and events to external systems: ([data exporters](./entities/data-exporters.mdx), [events and analytics](./topics/events-and-analytics.mdx))
    * Elasticsearch ([how-to](./tutorials/export-events-to-elastic.mdx))
    * Apache Kafka ([how-to](./tutorials/communicate-with-kafka.mdx))
    * Apache Pulsar
    * Webhook (HTTP)
    * File
    * S3
    * Mailer (Mailgun, Mailjet, Sendgrid, generic SMTP) ([how-to](./tutorials/export-alerts-using-mailgun.md))
    * Console / Logger
    * Splunk
    * Datadog
    * New Relic
    * GoReplay (file and S3)
    * TCP / UDP / Syslog
    * JMS
    * WASM-based custom exporter ([WASM usage](./topics/wasm-usage.mdx))
    * Workflow-based custom exporter ([workflows](./topics/workflows.md))
* GreenScore: ecological scoring of API routes based on efficiency rules ([GreenScore](./topics/green-score.mdx))

**Services discovery**

* Through DNS
* Through Eureka (internal and external) ([how-to](./tutorials/working-with-eureka.mdx))
* Through Kubernetes API (namespace scanning) ([Kubernetes](./deploy/kubernetes.mdx))
* Through custom Otoroshi protocol (self-registration) ([Otoroshi protocol](./topics/otoroshi-protocol.mdx))
* Through Tailscale network ([how-to](./tutorials/tailscale-integration.md))

**Authentication**

* OAuth 2.0/2.1 authentication (with PKCE support) ([auth modules](./entities/auth-modules.md))
* OpenID Connect (OIDC) authentication ([auth modules](./entities/auth-modules.md), [Keycloak how-to](./tutorials/secure-app-with-keycloak.mdx))
* LDAP authentication (with nested groups) ([auth modules](./entities/auth-modules.md), [LDAP how-to](./tutorials/secure-app-with-ldap.mdx))
* JWT authentication ([auth modules](./entities/auth-modules.md))
* OAuth 1.0a authentication ([how-to](./tutorials/secure-with-oauth1-client.mdx))
* SAML V2 authentication ([auth modules](./entities/auth-modules.md))
* Basic authentication (username/password) ([auth modules](./entities/auth-modules.md))
* WebAuthn / FIDO2 passwordless authentication
* Auth0 passwordless flow ([Auth0 how-to](./tutorials/secure-app-with-auth0.mdx))
* WASM-based custom authentication ([auth modules](./entities/auth-modules.md), [WASM usage](./topics/wasm-usage.mdx))
* Internal users management ([Otoroshi admins](./entities/otoroshi-admins.md))
* Multi-authentication module chaining ([auth modules](./entities/auth-modules.md))
* Client credentials OAuth2 flow with token endpoint ([how-to](./tutorials/secure-with-oauth2-client-credentials.md))

**Secret vaults support** ([secrets management](./topics/secrets.md))

* Environment variables
* Hashicorp Vault
* Azure Key Vault
* AWS Secrets Manager
* Google Cloud Secret Manager
* Alibaba Cloud Secret Manager
* Kubernetes Secrets
* Izanami (v1)
* Infisical
* Spring Cloud Config
* HTTP (generic endpoint)
* Local (file-based)
* Extensible via admin extensions

**Certificates management** ([PKI](./topics/pki.md), [certificates](./entities/certificates.md))

* Dynamic TLS certificates store ([TLS](./topics/tls.md))
* Dynamic TLS termination ([TLS how-to](./tutorials/tls-termination-using-own-certificates.mdx))
* Internal PKI ([PKI](./topics/pki.md))
    * generate self-signed certificates/CAs
    * generate/sign certificates/CAs/sub-CAs
    * AIA (Authority Information Access)
    * OCSP responder
    * import P12/certificate bundles
* ACME / Let's Encrypt support ([Let's Encrypt how-to](./tutorials/tls-using-lets-encrypt.md))
* On-the-fly certificate generation based on a CA certificate without request loss
* JWKS exposition for public key pairs ([PKI](./topics/pki.md))
* Default certificate
* Customize mTLS trusted CAs in the TLS handshake ([mTLS how-to](./tutorials/end-to-end-mtls.md))
* Tailscale certificates integration ([how-to](./tutorials/tailscale-integration.md))

**Clustering** ([clustering](./deploy/clustering.mdx))

* Based on a control plane / data plane pattern
* Encrypted communication between nodes
* Backup capabilities allowing data planes to start without control plane (improved resilience)
* Relay routing to forward traffic across network zones ([relay routing](./topics/relay-routing.mdx))
* Distributed web authentication across nodes ([sessions management](./topics/sessions-mgmt.md))

**Static content and backends**

* Serve static files from local filesystem ([built-in plugins](./plugins/built-in-plugins.mdx))
* Serve static files from Amazon S3 ([built-in plugins](./plugins/built-in-plugins.mdx))
* Serve static files from ZIP archives ([ZIP backend how-to](./tutorials/zip-backend-plugin.mdx))
* Echo backend for debugging ([built-in plugins](./plugins/built-in-plugins.mdx))
* Static response / mock backends ([built-in plugins](./plugins/built-in-plugins.mdx))

**Administration UI**

* Manage and organize all resources
* Secured user access with authentication module ([auth modules](./entities/auth-modules.md))
* Audited user actions ([events and analytics](./topics/events-and-analytics.mdx))
* Dynamic changes at runtime without full reload
* Test your routes without any external tools
* Visual workflow designer with step-by-step debugger ([workflow editor](./topics/workflows-editor.mdx))
* Extensible via admin extensions (custom entities, routes, frontend modules)

**Kubernetes integration** ([Kubernetes](./deploy/kubernetes.mdx))

* Standard Ingress controller
* Custom Ingress controller with CRD support
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via admission webhook
* Kubernetes Gateway API support ([Gateway API](./topics/kubernetes-gateway-api.md))
* Sidecar injection for service mesh
* Bidirectional TLS certificate synchronization (Kubernetes secrets <-> Otoroshi certificates)

**Dynamic HTTP listeners** ([topic](./topics/http-listeners.mdx), [entity](./entities/http-listeners.md))

* Create and manage additional HTTP listeners on custom ports
* Multi-protocol support (HTTP/1.1, HTTP/2, HTTP/3, H2C)
* Dynamic configuration without restart
* TLS and mTLS support per listener

**Infrastructure as Code / GitOps**

* Remote Catalogs: declarative entity management from trusted external sources ([topic](./topics/remote-catalogs.md), [entity](./entities/remote-catalogs.md))
    * Supported sources:
        * GitHub (including GitHub Enterprise)
        * GitLab (including self-hosted)
        * Bitbucket Cloud
        * Generic Git repositories (HTTPS and SSH)
        * Amazon S3 (and S3-compatible storage)
        * HTTP endpoints
        * Consul KV
        * Local filesystem
            * with possible pre-deploy sync command
    * Full reconciliation engine (create, update, delete) with desired state convergence
    * Automatic deployment via scheduled polling (fixed interval or cron expression)
    * Webhook-triggered deployment from GitHub, GitLab, and Bitbucket push events
    * Dry-run mode to preview changes before applying
    * Undeploy to cleanly remove all entities managed by a catalog
    * Support for JSON and YAML entity definitions (including multi-document YAML)
    * Deploy listing files for fine-grained control over which files to import
    * Route plugins for programmatic deployment (single, batch, webhook)
* `otoroshictl` CLI tool
    * Sync configuration with Otoroshi clusters
    * Push-based IaC, very similar to what `kubectl apply` can do

**Storage backends** ([setup](./install/setup-otoroshi.mdx))

* Redis (via Lettuce)
* PostgreSQL (via Reactive PG)
* Cassandra
* In-memory (with file persistence)
* S3
* HTTP

**Organize**

* Multi-organizations ([organizations](./entities/organizations.mdx))
* Multi-teams ([teams](./entities/teams.mdx))
* Routes groups ([service groups](./entities/service-groups.mdx))

**Developers portal**

* Using [Daikoku](https://maif.github.io/daikoku/manual/index.html) ([dev portal](./topics/dev-portal.mdx))

**AI / LLM Gateway** ([AI Gateway](./topics/ai-gateway.md))

Available through the optional [Otoroshi LLM Extension](https://cloud-apim.github.io/otoroshi-llm-extension/) developed by [Cloud APIM](https://www.cloud-apim.com/), Otoroshi can act as a full-featured AI Gateway in front of LLM providers.

* Unified, OpenAI-compatible API in front of 50+ LLM providers (OpenAI, Anthropic, Azure OpenAI, Mistral, Groq, Cohere, Google Gemini, Ollama, DeepSeek, X.ai, Hugging Face, Cloudflare Workers AI, Scaleway, OVH, etc.)
* Multi-modal endpoints: chat completions, embeddings, image generation, audio (TTS/STT), video generation, content moderation
* Anthropic Messages API compatibility for Claude clients
* Provider load balancing across multiple LLM backends (round robin, random, best response time, weighted)
* Automatic provider fallback on failure
* Cost tracking with a built-in pricing database covering 1000+ models
* Budgets per API key, user, or service (USD or token-based, with time windows and alert thresholds)
* Token-based rate limiting (per consumer, per provider, per model)
* Caching strategies: TTL-based simple cache and semantic cache using embeddings
* Persistent conversation memory with sliding window or full history, scoped by API key, user, header, etc.
* Guardrails on prompts and responses:
    * regex allow/deny lists
    * LLM-based validation
    * moderation API (OpenAI moderation and similar)
    * webhook-based external validation
    * WASM / QuickJS custom logic
    * built-in detectors: prompt injection, PII, secrets leakage, toxic language, racial / gender bias, personal health information, gibberish, faithfulness
    * text constraints: word / sentence / character counts, contains / semantic-contains
* Reusable prompts, prompt contexts (system messages), and prompt templates with variable substitution
* Model Context Protocol (MCP) connectors (stdio, SSE, WebSocket, HTTP) for tool / function calling
* Full audit trail of every LLM call (consumer, provider, model, tokens, cost, cache status, guardrail results, latency) exported through Otoroshi's standard data exporters

**Biscuit Studio**

Available through the optional [Otoroshi Biscuit Studio](https://cloud-apim.github.io/otoroshi-biscuit-studio/) extension developed by [Cloud APIM](https://www.cloud-apim.com/) ([source](https://github.com/cloud-apim/otoroshi-biscuit-studio)), Otoroshi gains full [Biscuit token](https://www.biscuitsec.org/) lifecycle management with first-class entities and plugins.

* Entities for the complete Biscuit lifecycle:
    * **Key Pairs** -- create and manage Ed25519 key pairs used to sign, attenuate and verify tokens
    * **Forges** -- declarative token templates with facts, rules and checks for issuing tokens
    * **Verifiers** -- reusable authorizer policies (facts, rules, checks, allow / deny policies) applied to incoming tokens
    * **Attenuators** -- declarative attenuation blocks to reduce a token's capabilities at the gateway
    * **RBAC Policies** -- role-based access control expressed as Biscuit datalog and applied per route
    * **Remote Facts Loaders** -- pull additional authorization facts from external HTTP sources to enrich verifier context
* Route plugins:
    * **Biscuit Verifier** -- validate incoming Biscuit tokens against a verifier entity
    * **Biscuit Attenuator** -- attenuate the incoming token before forwarding to the backend
    * **Client Credentials** -- OAuth2 `client_credentials` flow that issues Biscuit tokens as access tokens
    * **Biscuit to User** -- extract the user identity from a Biscuit token and forward it downstream
    * **User to Biscuit** -- mint a Biscuit token from the authenticated Otoroshi user / API key and inject it downstream
    * **ApiKey Bridge** -- bridge between Otoroshi API keys and Biscuit tokens
    * **Public Keys Exposition** -- expose verifier public keys through a `.well-known/biscuit-web-keys` endpoint for external verifiers
* Centralized administration of all Biscuit material from the Otoroshi UI and admin API
* Integrates with Otoroshi's [secret vaults](./topics/secrets.md) for secure key material storage
