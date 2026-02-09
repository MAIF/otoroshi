# Features

**Traffic Management**

* Can proxy any HTTP(s) service (APIs, webapps, websocket, etc)
* Can proxy any TCP service (app, database, etc)
* Can proxy any gRPC service (using a netty listener), also gRPC-Web support
* Can proxy any GraphQL service (proxy, query composition, and schema-first backend)
* Full WebSocket support with message validation, transformation, and mirroring
* End-to-end HTTP/1.1 support
* End-to-end HTTP/2 support (including H2C cleartext)
* End-to-end HTTP/3 support (QUIC)
* Multiple load balancing options:
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
* Backend Failover targets support
* Distributed in-flight request limiting
* Distributed rate limiting
* Per-IP, per-API key, per-route, and custom throttling and quotas
* Request and response bandwidth throttling
* Request and response body size limiting
* Traffic mirroring to secondary backends
* Traffic capture (GoReplay format)
* Canary deployments (percentage-based and time-controlled)
* Relay routing across network zones
* Tunnels for easier network exposition (TCP, UDP, WebSocket-based)
* Custom error templates

**Routing**

* Router can support tens of thousands of concurrent routes
* Router supports path parameter extraction (with regex validation)
* Routing based on:
    * HTTP method
    * hostname (exact, wildcard)
    * path (exact, prefix, wildcard)
    * header values (exact, regex, wildcard)
    * query param values (exact, regex, wildcard)
    * cookie values
* Full URL rewriting
* Path stripping
* Target predicates (route to specific backends based on geolocation, cloud region, datacenter, rack, etc)

**Routes customization**

* Over 200 built-in middlewares (plugins) covering:
    * circuit breakers (with configurable thresholds)
    * automatic retries (with exponential backoff)
    * response caching
    * gzip and brotli compression
    * request and response headers manipulation
    * request and response cookies manipulation
    * CORS handling
    * body transformation (jq, regex, XML/JSON conversion, SOAP)
    * query string transformation
    * GraphQL composition
    * HTML patching
    * redirection
    * maintenance and build modes
    * static responses and mocks
    * etc
* Support middlewares compiled to WASM (using Extism)
* Support Open Policy Agent policies for traffic control (via WASM)
* Visual workflow engine for building complex processing pipelines
* Write your own custom middlewares:
    * in Scala deployed as jar files
    * in whatever language you want that can be compiled to WASM

**Security**

* Coraza Web Application Firewall (WAF) with OWASP Core Rule Set support
* IP address allow and block lists (with CIDR support)
* Domain name allow and block lists
* Fail2Ban-style automatic IP banning
* Geolocation-based access control (MaxMind, IPStack)
* Time-restricted access control
* Log4Shell and React2Shell vulnerability detection
* Security headers injection (HSTS, CSP, X-Frame-Options, X-XSS-Protection, X-Content-Type-Options)
* security.txt endpoint (RFC 9116)
* robots.txt handling

**API security**

* Access management with API keys and quotas
* Multiple API key extraction methods (header, query param, cookie, bearer token, basic auth, JWT)
* Automatic API key secrets rotation
* Mandatory tags and metadata validation on API keys
* HTTPS and TLS
* End-to-end mTLS calls
* Routing constraints and restrictions
* Public/private path separation
* JWT token validation and manipulation
    * Multiple validators on the same route
    * JWE (encrypted JWT) support
    * JWT signing
* HMAC request signing and validation
* Biscuit token extraction and validation (datalog-based authorization)
* OpenFGA fine-grained authorization
* Role-based access control (RBAC)
* Context validation (JSON path-based claims validation)

**Monitoring and observability**

* Active health checks (with customizable healthy/unhealthy status codes and regex body checks)
* Route state for the last 90 days
* Calls tracing using W3C Trace Context
* Real-time traffic metrics
* Prometheus metrics export
* OpenTelemetry metrics and logs export (OTLP)
* Datadog, StatsD metrics export
* Export alerts and events to external systems:
    * Elasticsearch
    * Apache Kafka
    * Apache Pulsar
    * Webhook (HTTP)
    * File
    * S3
    * Mailer (Mailgun, Mailjet, Sendgrid, generic SMTP)
    * Console / Logger
    * Splunk
    * Datadog
    * New Relic
    * GoReplay (file and S3)
    * TCP / UDP / Syslog
    * JMS
    * WASM-based custom exporter
    * Workflow-based custom exporter
* GreenScore: ecological scoring of API routes based on efficiency rules

**Services discovery**

* Through DNS
* Through Eureka (internal and external)
* Through Kubernetes API (namespace scanning)
* Through custom Otoroshi protocol (self-registration)
* Through Tailscale network

**Authentication**

* OAuth 2.0/2.1 authentication (with PKCE support)
* OpenID Connect (OIDC) authentication
* LDAP authentication (with nested groups)
* JWT authentication
* OAuth 1.0a authentication
* SAML V2 authentication
* Basic authentication (username/password)
* WebAuthn / FIDO2 passwordless authentication
* Auth0 passwordless flow
* WASM-based custom authentication
* Internal users management
* Multi-authentication module chaining
* Client credentials OAuth2 flow with token endpoint

**Secret vaults support**

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

**Certificates management**

* Dynamic TLS certificates store
* Dynamic TLS termination
* Internal PKI
    * generate self-signed certificates/CAs
    * generate/sign certificates/CAs/sub-CAs
    * AIA (Authority Information Access)
    * OCSP responder
    * import P12/certificate bundles
* ACME / Let's Encrypt support
* On-the-fly certificate generation based on a CA certificate without request loss
* JWKS exposition for public key pairs
* Default certificate
* Customize mTLS trusted CAs in the TLS handshake
* Tailscale certificates integration

**Clustering**

* Based on a control plane / data plane pattern
* Encrypted communication between nodes
* Backup capabilities allowing data planes to start without control plane (improved resilience)
* Relay routing to forward traffic across network zones
* Distributed web authentication across nodes

**Static content and backends**

* Serve static files from local filesystem
* Serve static files from Amazon S3
* Serve static files from ZIP archives
* Echo backend for debugging
* Static response / mock backends

**Administration UI**

* Manage and organize all resources
* Secured user access with authentication module
* Audited user actions
* Dynamic changes at runtime without full reload
* Test your routes without any external tools
* Visual workflow designer with step-by-step debugger
* Extensible via admin extensions (custom entities, routes, frontend modules)

**Kubernetes integration**

* Standard Ingress controller
* Custom Ingress controller with CRD support
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via admission webhook
* Sidecar injection for service mesh
* Bidirectional TLS certificate synchronization (Kubernetes secrets <-> Otoroshi certificates)

**Dynamic HTTP listeners**

* Create and manage additional HTTP listeners on custom ports
* Multi-protocol support (HTTP/1.1, HTTP/2, HTTP/3, H2C)
* Dynamic configuration without restart
* TLS and mTLS support per listener

**Infrastructure as Code / GitOps**

* Remote Catalogs: declarative entity management from trusted external sources
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
    * Push-based IaC, very similar to what `kubectl apply` can do.

**Storage backends**

* Redis (via Lettuce)
* PostgreSQL (via Reactive PG)
* Cassandra
* In-memory (with file persistence)
* S3
* HTTP

**Organize**

* Multi-organizations
* Multi-teams
* Routes groups

**Developers portal**

* Using @link:[Daikoku](https://maif.github.io/daikoku/manual/index.html) { open=new }
