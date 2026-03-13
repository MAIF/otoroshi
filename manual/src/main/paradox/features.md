# Features

**Traffic Management**

* Can proxy any HTTP(s) service (APIs, webapps, websocket, etc) (@ref:[routes](./entities/routes.md), @ref:[engine](./topics/engine.md))
* Can proxy any TCP service (app, database, etc) (@ref:[TCP services](./entities/tcp-services.md))
* Can proxy any gRPC service (using a netty listener), also gRPC-Web support (@ref:[HTTP listeners](./topics/http-listeners.md))
* Can proxy any GraphQL service (proxy, query composition, and schema-first backend) (@ref:[GraphQL composer](./topics/graphql-composer.md))
* Full WebSocket support with message validation, transformation, and mirroring
* End-to-end HTTP/1.1 support
* End-to-end HTTP/2 support (including H2C cleartext) (@ref:[Netty server](./topics/netty-server.md))
* End-to-end HTTP/3 support (QUIC) (@ref:[HTTP/3](./topics/http3.md))
* Multiple load balancing options: (@ref:[backends](./entities/backends.md))
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
* Backend Failover targets support (@ref:[backends](./entities/backends.md))
* Distributed in-flight request limiting (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Distributed rate limiting (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Per-IP, per-API key, per-route, and custom throttling and quotas (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Request and response bandwidth throttling
* Request and response body size limiting
* Traffic mirroring to secondary backends (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Traffic capture (GoReplay format) (@ref:[engine](./topics/engine.md))
* Canary deployments (percentage-based and time-controlled)
* Relay routing across network zones (@ref:[relay routing](./topics/relay-routing.md))
* Tunnels for easier network exposition (TCP, UDP, WebSocket-based) (@ref:[tunnels](./topics/tunnels.md))
* Custom error templates (@ref:[error templates](./entities/error-templates.md))

**Routing**

* Router can support tens of thousands of concurrent routes (@ref:[engine](./topics/engine.md))
* Router supports path parameter extraction (with regex validation) (@ref:[engine](./topics/engine.md))
* Routing based on: (@ref:[routes](./entities/routes.md))
    * HTTP method
    * hostname (exact, wildcard)
    * path (exact, prefix, wildcard)
    * header values (exact, regex, wildcard)
    * query param values (exact, regex, wildcard)
    * cookie values
* Full URL rewriting (@ref:[backends](./entities/backends.md))
* Path stripping (@ref:[routes](./entities/routes.md))
* Target predicates (route to specific backends based on geolocation, cloud region, datacenter, rack, etc) (@ref:[backends](./entities/backends.md))

**Routes customization**

* Over 200 built-in middlewares (plugins) covering: (@ref:[built-in plugins](./plugins/built-in-plugins.md))
    * circuit breakers (with configurable thresholds) (@ref:[circuit breaker](./topics/circuit-breaker.md))
    * automatic retries (with exponential backoff)
    * response caching
    * gzip and brotli compression
    * request and response headers manipulation
    * request and response cookies manipulation
    * CORS handling
    * body transformation (jq, regex, XML/JSON conversion, SOAP)
    * query string transformation
    * GraphQL composition (@ref:[GraphQL composer](./topics/graphql-composer.md))
    * HTML patching
    * redirection
    * maintenance and build modes
    * static responses and mocks
    * etc
* Support middlewares compiled to WASM (using Extism) (@ref:[WASM plugins](./entities/wasm-plugins.md), @ref:[WASM usage](./topics/wasm-usage.md))
* Support Open Policy Agent policies for traffic control (via WASM) (@ref:[WASM usage](./topics/wasm-usage.md))
* Visual workflow engine for building complex processing pipelines (@ref:[workflows](./entities/workflows.md), @ref:[workflow editor](./topics/workflows-editor.md))
* Write your own custom middlewares: (@ref:[create plugins](./plugins/create-plugins.md))
    * in Scala deployed as jar files
    * in whatever language you want that can be compiled to WASM (@ref:[WASM how-to](./how-to-s/wasm-usage.md))

**Security**

* Coraza Web Application Firewall (WAF) with OWASP Core Rule Set support (@ref:[WAF how-to](./how-to-s/instantiate-waf-coraza.md))
* IP address allow and block lists (with CIDR support) (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Domain name allow and block lists (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Fail2Ban-style automatic IP banning (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Geolocation-based access control (MaxMind, IPStack) (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Time-restricted access control
* Log4Shell and React2Shell vulnerability detection (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Security headers injection (HSTS, CSP, X-Frame-Options, X-XSS-Protection, X-Content-Type-Options)
* security.txt endpoint (RFC 9116)
* robots.txt handling

**API security**

* Access management with API keys and quotas (@ref:[API keys](./entities/apikeys.md), @ref:[how-to](./how-to-s/secure-with-apikey.md))
* Multiple API key extraction methods (header, query param, cookie, bearer token, basic auth, JWT) (@ref:[API keys](./entities/apikeys.md))
* Automatic API key secrets rotation (@ref:[API keys](./entities/apikeys.md))
* Mandatory tags and metadata validation on API keys
* HTTPS and TLS (@ref:[TLS](./topics/tls.md))
* End-to-end mTLS calls (@ref:[mTLS how-to](./how-to-s/end-to-end-mtls.md))
* Routing constraints and restrictions
* Public/private path separation
* JWT token validation and manipulation (@ref:[JWT verifiers](./entities/jwt-verifiers.md), @ref:[how-to](./how-to-s/secure-an-app-with-jwt-verifiers.md))
    * Multiple validators on the same route
    * JWE (encrypted JWT) support
    * JWT signing
* HMAC request signing and validation (@ref:[Otoroshi protocol](./topics/otoroshi-protocol.md))
* Biscuit token extraction and validation (datalog-based authorization) (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* OpenFGA fine-grained authorization (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Role-based access control (RBAC) (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Context validation (JSON path-based claims validation) (@ref:[built-in plugins](./plugins/built-in-plugins.md))

**Monitoring and observability**

* Active health checks (with customizable healthy/unhealthy status codes and regex body checks) (@ref:[backends](./entities/backends.md))
* Route state for the last 90 days
* Calls tracing using W3C Trace Context (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Real-time traffic metrics (@ref:[monitoring](./topics/monitoring.md))
* Prometheus metrics export (@ref:[monitoring](./topics/monitoring.md))
* OpenTelemetry metrics and logs export (OTLP) (@ref:[OpenTelemetry](./topics/opentelemetry.md))
* Datadog, StatsD metrics export (@ref:[monitoring](./topics/monitoring.md))
* Export alerts and events to external systems: (@ref:[data exporters](./entities/data-exporters.md), @ref:[events and analytics](./topics/events-and-analytics.md))
    * Elasticsearch (@ref:[how-to](./how-to-s/export-events-to-elastic.md))
    * Apache Kafka (@ref:[how-to](./how-to-s/communicate-with-kafka.md))
    * Apache Pulsar
    * Webhook (HTTP)
    * File
    * S3
    * Mailer (Mailgun, Mailjet, Sendgrid, generic SMTP) (@ref:[how-to](./how-to-s/export-alerts-using-mailgun.md))
    * Console / Logger
    * Splunk
    * Datadog
    * New Relic
    * GoReplay (file and S3)
    * TCP / UDP / Syslog
    * JMS
    * WASM-based custom exporter (@ref:[WASM usage](./topics/wasm-usage.md))
    * Workflow-based custom exporter (@ref:[workflows](./topics/workflows.md))
* GreenScore: ecological scoring of API routes based on efficiency rules (@ref:[GreenScore](./topics/green-score.md))

**Services discovery**

* Through DNS
* Through Eureka (internal and external) (@ref:[how-to](./how-to-s/working-with-eureka.md))
* Through Kubernetes API (namespace scanning) (@ref:[Kubernetes](./deploy/kubernetes.md))
* Through custom Otoroshi protocol (self-registration) (@ref:[Otoroshi protocol](./topics/otoroshi-protocol.md))
* Through Tailscale network (@ref:[how-to](./how-to-s/tailscale-integration.md))

**Authentication**

* OAuth 2.0/2.1 authentication (with PKCE support) (@ref:[auth modules](./entities/auth-modules.md))
* OpenID Connect (OIDC) authentication (@ref:[auth modules](./entities/auth-modules.md), @ref:[Keycloak how-to](./how-to-s/secure-app-with-keycloak.md))
* LDAP authentication (with nested groups) (@ref:[auth modules](./entities/auth-modules.md), @ref:[LDAP how-to](./how-to-s/secure-app-with-ldap.md))
* JWT authentication (@ref:[auth modules](./entities/auth-modules.md))
* OAuth 1.0a authentication (@ref:[how-to](./how-to-s/secure-with-oauth1-client.md))
* SAML V2 authentication (@ref:[auth modules](./entities/auth-modules.md))
* Basic authentication (username/password) (@ref:[auth modules](./entities/auth-modules.md))
* WebAuthn / FIDO2 passwordless authentication
* Auth0 passwordless flow (@ref:[Auth0 how-to](./how-to-s/secure-app-with-auth0.md))
* WASM-based custom authentication (@ref:[auth modules](./entities/auth-modules.md), @ref:[WASM usage](./topics/wasm-usage.md))
* Internal users management (@ref:[Otoroshi admins](./entities/otoroshi-admins.md))
* Multi-authentication module chaining (@ref:[auth modules](./entities/auth-modules.md))
* Client credentials OAuth2 flow with token endpoint (@ref:[how-to](./how-to-s/secure-with-oauth2-client-credentials.md))

**Secret vaults support** (@ref:[secrets management](./topics/secrets.md))

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

**Certificates management** (@ref:[PKI](./topics/pki.md), @ref:[certificates](./entities/certificates.md))

* Dynamic TLS certificates store (@ref:[TLS](./topics/tls.md))
* Dynamic TLS termination (@ref:[TLS how-to](./how-to-s/tls-termination-using-own-certificates.md))
* Internal PKI (@ref:[PKI](./topics/pki.md))
    * generate self-signed certificates/CAs
    * generate/sign certificates/CAs/sub-CAs
    * AIA (Authority Information Access)
    * OCSP responder
    * import P12/certificate bundles
* ACME / Let's Encrypt support (@ref:[Let's Encrypt how-to](./how-to-s/tls-using-lets-encrypt.md))
* On-the-fly certificate generation based on a CA certificate without request loss
* JWKS exposition for public key pairs (@ref:[PKI](./topics/pki.md))
* Default certificate
* Customize mTLS trusted CAs in the TLS handshake (@ref:[mTLS how-to](./how-to-s/end-to-end-mtls.md))
* Tailscale certificates integration (@ref:[how-to](./how-to-s/tailscale-integration.md))

**Clustering** (@ref:[clustering](./deploy/clustering.md))

* Based on a control plane / data plane pattern
* Encrypted communication between nodes
* Backup capabilities allowing data planes to start without control plane (improved resilience)
* Relay routing to forward traffic across network zones (@ref:[relay routing](./topics/relay-routing.md))
* Distributed web authentication across nodes (@ref:[sessions management](./topics/sessions-mgmt.md))

**Static content and backends**

* Serve static files from local filesystem (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Serve static files from Amazon S3 (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Serve static files from ZIP archives (@ref:[ZIP backend how-to](./how-to-s/zip-backend-plugin.md))
* Echo backend for debugging (@ref:[built-in plugins](./plugins/built-in-plugins.md))
* Static response / mock backends (@ref:[built-in plugins](./plugins/built-in-plugins.md))

**Administration UI**

* Manage and organize all resources
* Secured user access with authentication module (@ref:[auth modules](./entities/auth-modules.md))
* Audited user actions (@ref:[events and analytics](./topics/events-and-analytics.md))
* Dynamic changes at runtime without full reload
* Test your routes without any external tools
* Visual workflow designer with step-by-step debugger (@ref:[workflow editor](./topics/workflows-editor.md))
* Extensible via admin extensions (custom entities, routes, frontend modules)

**Kubernetes integration** (@ref:[Kubernetes](./deploy/kubernetes.md))

* Standard Ingress controller
* Custom Ingress controller with CRD support
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via admission webhook
* Kubernetes Gateway API support (@ref:[Gateway API](./topics/kubernetes-gateway-api.md))
* Sidecar injection for service mesh
* Bidirectional TLS certificate synchronization (Kubernetes secrets <-> Otoroshi certificates)

**Dynamic HTTP listeners** (@ref:[topic](./topics/http-listeners.md), @ref:[entity](./entities/http-listeners.md))

* Create and manage additional HTTP listeners on custom ports
* Multi-protocol support (HTTP/1.1, HTTP/2, HTTP/3, H2C)
* Dynamic configuration without restart
* TLS and mTLS support per listener

**Infrastructure as Code / GitOps**

* Remote Catalogs: declarative entity management from trusted external sources (@ref:[topic](./topics/remote-catalogs.md), @ref:[entity](./entities/remote-catalogs.md))
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

**Storage backends** (@ref:[setup](./install/setup-otoroshi.md))

* Redis (via Lettuce)
* PostgreSQL (via Reactive PG)
* Cassandra
* In-memory (with file persistence)
* S3
* HTTP

**Organize**

* Multi-organizations (@ref:[organizations](./entities/organizations.md))
* Multi-teams (@ref:[teams](./entities/teams.md))
* Routes groups (@ref:[service groups](./entities/service-groups.md))

**Developers portal**

* Using @link:[Daikoku](https://maif.github.io/daikoku/manual/index.html) { open=new } (@ref:[dev portal](./topics/dev-portal.md))
