---
title: Features
sidebar_position: 4
---
# Features

**Traffic Management**

* Can proxy any HTTP(s) service (APIs, webapps, websocket, etc) ([routes](./entities/routes.md), [engine](./topics/engine.md))
* Can proxy any TCP service (app, database, etc) ([TCP services](./entities/tcp-services.md))
* Can proxy any gRPC service (using a netty listener), also gRPC-Web support ([HTTP listeners](./topics/http-listeners.md))
* Can proxy any GraphQL service (proxy, query composition, and schema-first backend) ([GraphQL composer](./topics/graphql-composer.md))
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
* Distributed in-flight request limiting ([built-in plugins](./plugins/built-in-plugins.md))
* Distributed rate limiting ([built-in plugins](./plugins/built-in-plugins.md))
* Per-IP, per-API key, per-route, and custom throttling and quotas ([built-in plugins](./plugins/built-in-plugins.md))
* Request and response bandwidth throttling
* Request and response body size limiting
* Traffic mirroring to secondary backends ([built-in plugins](./plugins/built-in-plugins.md))
* Traffic capture (GoReplay format) ([engine](./topics/engine.md))
* Canary deployments (percentage-based and time-controlled)
* Relay routing across network zones ([relay routing](./topics/relay-routing.md))
* Tunnels for easier network exposition (TCP, UDP, WebSocket-based) ([tunnels](./topics/tunnels.md))
* Custom error templates ([error templates](./entities/error-templates.md))

**Routing**

* Router can support tens of thousands of concurrent routes ([engine](./topics/engine.md))
* Router supports path parameter extraction (with regex validation) ([engine](./topics/engine.md))
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

* Over 200 built-in middlewares (plugins) covering: ([built-in plugins](./plugins/built-in-plugins.md))
    * circuit breakers (with configurable thresholds) ([circuit breaker](./topics/circuit-breaker.md))
    * automatic retries (with exponential backoff)
    * response caching ([built-in plugins](./plugins/built-in-plugins.md#otoroshi.next.plugins.NgResponseCache))
    * gzip and brotli compression ([gzip](./plugins/built-in-plugins.md#otoroshi.next.plugins.GzipResponseCompressor), [brotli](./plugins/built-in-plugins.md#otoroshi.next.plugins.BrotliResponseCompressor))
    * request and response headers manipulation ([input](./plugins/built-in-plugins.md#otoroshi.next.plugins.AdditionalHeadersIn), [output](./plugins/built-in-plugins.md#otoroshi.next.plugins.AdditionalHeadersOut))
    * request and response cookies manipulation ([input](./plugins/built-in-plugins.md#otoroshi.next.plugins.AdditionalCookieInt), [output](./plugins/built-in-plugins.md#otoroshi.next.plugins.AdditionalCookieOut))
    * CORS handling ([CORS](./plugins/built-in-plugins.md#otoroshi.next.plugins.Cors))
    * body transformation (jq, regex, XML/JSON conversion, SOAP) ([built-in plugins](./plugins/built-in-plugins.md))
    * query string transformation ([built-in plugins](./plugins/built-in-plugins.md#otoroshi.next.plugins.QueryTransformer))
    * GraphQL composition ([GraphQL composer](./topics/graphql-composer.md#otoroshi.next.plugins.GraphQLBackend))
    * HTML patching ([built-in plugins](./plugins/built-in-plugins.md#otoroshi.next.plugins.NgHtmlPatcher))
    * redirection ([built-in plugins](./plugins/built-in-plugins.md#otoroshi.next.plugins.Redirection))
    * maintenance and build modes ([built-in plugins](./plugins/built-in-plugins.md))
    * static responses and mocks ([built-in plugins](./plugins/built-in-plugins.md))
    * etc
* Support middlewares compiled to WASM (using Extism) ([WASM plugins](./entities/wasm-plugins.md), [WASM usage](./topics/wasm-usage.md))
* Support Open Policy Agent policies for traffic control (via WASM) ([WASM usage](./topics/wasm-usage.md))
* Visual workflow engine for building complex processing pipelines ([workflows](./entities/workflows.md), [workflow editor](./topics/workflows-editor.md))
* Write your own custom middlewares: ([create plugins](./plugins/create-plugins.md))
    * in Scala deployed as jar files
    * in whatever language you want that can be compiled to WASM ([WASM how-to](./how-to-s/wasm-usage.md))

**Security**

* Coraza Web Application Firewall (WAF) with OWASP Core Rule Set support ([WAF how-to](./how-to-s/instantiate-waf-coraza.md))
* IP address allow and block lists (with CIDR support) ([built-in plugins](./plugins/built-in-plugins.md))
* Domain name allow and block lists ([built-in plugins](./plugins/built-in-plugins.md))
* Fail2Ban-style automatic IP banning ([built-in plugins](./plugins/built-in-plugins.md))
* Geolocation-based access control (MaxMind, IPStack) ([built-in plugins](./plugins/built-in-plugins.md))
* Time-restricted access control
* Log4Shell and React2Shell vulnerability detection ([built-in plugins](./plugins/built-in-plugins.md))
* Security headers injection (HSTS, CSP, X-Frame-Options, X-XSS-Protection, X-Content-Type-Options)
* security.txt endpoint (RFC 9116)
* robots.txt handling

**API security**

* Access management with API keys and quotas ([API keys](./entities/apikeys.md), [how-to](./how-to-s/secure-with-apikey.md))
* Multiple API key extraction methods (header, query param, cookie, bearer token, basic auth, JWT) ([API keys](./entities/apikeys.md))
* Automatic API key secrets rotation ([API keys](./entities/apikeys.md))
* Mandatory tags and metadata validation on API keys
* HTTPS and TLS ([TLS](./topics/tls.md))
* End-to-end mTLS calls ([mTLS how-to](./how-to-s/end-to-end-mtls.md))
* Routing constraints and restrictions
* Public/private path separation
* JWT token validation and manipulation ([JWT verifiers](./entities/jwt-verifiers.md), [how-to](./how-to-s/secure-an-app-with-jwt-verifiers.md))
    * Multiple validators on the same route
    * JWE (encrypted JWT) support
    * JWT signing
* HMAC request signing and validation ([Otoroshi protocol](./topics/otoroshi-protocol.md))
* Biscuit token extraction and validation (datalog-based authorization) ([built-in plugins](./plugins/built-in-plugins.md))
* OpenFGA fine-grained authorization ([built-in plugins](./plugins/built-in-plugins.md))
* Role-based access control (RBAC) ([built-in plugins](./plugins/built-in-plugins.md))
* Context validation (JSON path-based claims validation) ([built-in plugins](./plugins/built-in-plugins.md))

**Monitoring and observability**

* Active health checks (with customizable healthy/unhealthy status codes and regex body checks) ([backends](./entities/backends.md))
* Route state for the last 90 days
* Calls tracing using W3C Trace Context ([built-in plugins](./plugins/built-in-plugins.md))
* Real-time traffic metrics ([monitoring](./topics/monitoring.md))
* Prometheus metrics export ([monitoring](./topics/monitoring.md))
* OpenTelemetry metrics and logs export (OTLP) ([OpenTelemetry](./topics/opentelemetry.md))
* Datadog, StatsD metrics export ([monitoring](./topics/monitoring.md))
* Export alerts and events to external systems: ([data exporters](./entities/data-exporters.md), [events and analytics](./topics/events-and-analytics.md))
    * Elasticsearch ([how-to](./how-to-s/export-events-to-elastic.md))
    * Apache Kafka ([how-to](./how-to-s/communicate-with-kafka.md))
    * Apache Pulsar
    * Webhook (HTTP)
    * File
    * S3
    * Mailer (Mailgun, Mailjet, Sendgrid, generic SMTP) ([how-to](./how-to-s/export-alerts-using-mailgun.md))
    * Console / Logger
    * Splunk
    * Datadog
    * New Relic
    * GoReplay (file and S3)
    * TCP / UDP / Syslog
    * JMS
    * WASM-based custom exporter ([WASM usage](./topics/wasm-usage.md))
    * Workflow-based custom exporter ([workflows](./topics/workflows.md))
* GreenScore: ecological scoring of API routes based on efficiency rules ([GreenScore](./topics/green-score.md))

**Services discovery**

* Through DNS
* Through Eureka (internal and external) ([how-to](./how-to-s/working-with-eureka.md))
* Through Kubernetes API (namespace scanning) ([Kubernetes](./deploy/kubernetes.md))
* Through custom Otoroshi protocol (self-registration) ([Otoroshi protocol](./topics/otoroshi-protocol.md))
* Through Tailscale network ([how-to](./how-to-s/tailscale-integration.md))

**Authentication**

* OAuth 2.0/2.1 authentication (with PKCE support) ([auth modules](./entities/auth-modules.md))
* OpenID Connect (OIDC) authentication ([auth modules](./entities/auth-modules.md), [Keycloak how-to](./how-to-s/secure-app-with-keycloak.md))
* LDAP authentication (with nested groups) ([auth modules](./entities/auth-modules.md), [LDAP how-to](./how-to-s/secure-app-with-ldap.md))
* JWT authentication ([auth modules](./entities/auth-modules.md))
* OAuth 1.0a authentication ([how-to](./how-to-s/secure-with-oauth1-client.md))
* SAML V2 authentication ([auth modules](./entities/auth-modules.md))
* Basic authentication (username/password) ([auth modules](./entities/auth-modules.md))
* WebAuthn / FIDO2 passwordless authentication
* Auth0 passwordless flow ([Auth0 how-to](./how-to-s/secure-app-with-auth0.md))
* WASM-based custom authentication ([auth modules](./entities/auth-modules.md), [WASM usage](./topics/wasm-usage.md))
* Internal users management ([Otoroshi admins](./entities/otoroshi-admins.md))
* Multi-authentication module chaining ([auth modules](./entities/auth-modules.md))
* Client credentials OAuth2 flow with token endpoint ([how-to](./how-to-s/secure-with-oauth2-client-credentials.md))

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
* Dynamic TLS termination ([TLS how-to](./how-to-s/tls-termination-using-own-certificates.md))
* Internal PKI ([PKI](./topics/pki.md))
    * generate self-signed certificates/CAs
    * generate/sign certificates/CAs/sub-CAs
    * AIA (Authority Information Access)
    * OCSP responder
    * import P12/certificate bundles
* ACME / Let's Encrypt support ([Let's Encrypt how-to](./how-to-s/tls-using-lets-encrypt.md))
* On-the-fly certificate generation based on a CA certificate without request loss
* JWKS exposition for public key pairs ([PKI](./topics/pki.md))
* Default certificate
* Customize mTLS trusted CAs in the TLS handshake ([mTLS how-to](./how-to-s/end-to-end-mtls.md))
* Tailscale certificates integration ([how-to](./how-to-s/tailscale-integration.md))

**Clustering** ([clustering](./deploy/clustering.md))

* Based on a control plane / data plane pattern
* Encrypted communication between nodes
* Backup capabilities allowing data planes to start without control plane (improved resilience)
* Relay routing to forward traffic across network zones ([relay routing](./topics/relay-routing.md))
* Distributed web authentication across nodes ([sessions management](./topics/sessions-mgmt.md))

**Static content and backends**

* Serve static files from local filesystem ([built-in plugins](./plugins/built-in-plugins.md))
* Serve static files from Amazon S3 ([built-in plugins](./plugins/built-in-plugins.md))
* Serve static files from ZIP archives ([ZIP backend how-to](./how-to-s/zip-backend-plugin.md))
* Echo backend for debugging ([built-in plugins](./plugins/built-in-plugins.md))
* Static response / mock backends ([built-in plugins](./plugins/built-in-plugins.md))

**Administration UI**

* Manage and organize all resources
* Secured user access with authentication module ([auth modules](./entities/auth-modules.md))
* Audited user actions ([events and analytics](./topics/events-and-analytics.md))
* Dynamic changes at runtime without full reload
* Test your routes without any external tools
* Visual workflow designer with step-by-step debugger ([workflow editor](./topics/workflows-editor.md))
* Extensible via admin extensions (custom entities, routes, frontend modules)

**Kubernetes integration** ([Kubernetes](./deploy/kubernetes.md))

* Standard Ingress controller
* Custom Ingress controller with CRD support
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via admission webhook
* Kubernetes Gateway API support ([Gateway API](./topics/kubernetes-gateway-api.md))
* Sidecar injection for service mesh
* Bidirectional TLS certificate synchronization (Kubernetes secrets <-> Otoroshi certificates)

**Dynamic HTTP listeners** ([topic](./topics/http-listeners.md), [entity](./entities/http-listeners.md))

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

**Storage backends** ([setup](./install/setup-otoroshi.md))

* Redis (via Lettuce)
* PostgreSQL (via Reactive PG)
* Cassandra
* In-memory (with file persistence)
* S3
* HTTP

**Organize**

* Multi-organizations ([organizations](./entities/organizations.md))
* Multi-teams ([teams](./entities/teams.md))
* Routes groups ([service groups](./entities/service-groups.md))

**Developers portal**

* Using [Daikoku](https://maif.github.io/daikoku/manual/index.html) ([dev portal](./topics/dev-portal.md))
