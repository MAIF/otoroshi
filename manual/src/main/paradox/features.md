# Features

**Traffic Management**

* Can proxy any HTTP(s) service (apis, webapps, websocket, etc)
* Can proxy any TCP service (app, database, etc)
* Can proxy any GRPC service
* Multiple load-balancing options: 
    * RoundRobin
    * Random, Sticky
    * Ip address hash
    * Best Response Time
* Distributed in-flight request limiting	
* Distributed rate limiting 
* End-to-end HTTP/1.1 support
* End-to-end H2 support
* End-to-end H3 support
* Traffic mirroring
* Traffic capture
* Canary deployments
* Relay routing 
* Tunnels for easier network exposition
* Error templates

**Routing**

* Router can support ten of thousands of concurrent routes
* Router support path params extraction (can be regex validated)
* Routing based on 
    * method
    * hostname (exact, wildcard)
    * path (exact, wildcard)
    * header values (exact, regex, wildcard)
    * query param values (exact, regex, wildcard)
* Support full url rewriting

**Routes customization**

* Dozens of built-in middlewares (policies/plugins) 
    * circuit breakers
    * automatic retries
    * buffering
    * gzip
    * headers manipulation
    * cors
    * body transformation
    * graphql gateway
    * etc 
* Support middlewares compiled to WASM (using extism)
* Support Open Policy Agent policies for traffic control
* Write your own custom middlewares
    * in scala deployed as jar files
    * in whatever language you want that can be compiled to WASM

**Routes Monitoring**

* Active healthchecks
* Route state for the last 90 days
* Calls tracing using W3C trace context
* Export alerts and events to external database
    * file
    * S3
    * elastic
    * pulsar
    * kafka
    * webhook
    * mailer
    * logger
* Real-time traffic metrics
* Real-time traffic metrics (Datadog, Prometheus, StatsD)

**Services discovery**

* through DNS
* through Eureka 2
* through Kubernetes API
* through custom otoroshi protocol

**API security**

* Access management with apikeys and quotas
* Automatic apikeys secrets rotation
* HTTPS and TLS
* End-to-end mTLS calls 
* Routing constraints
* Routing restrictions
* JWT tokens validation and manipulation
    * can support multiple validator on the same routes

**Administration UI**

* Manage and organize all resources
* Secured users access with Authentication module
* Audited users actions
* Dynamic changes at runtime without full reload
* Test your routes without any external tools

**Webapp authentication and security**

* OAuth2.0/2.1 authentication
* OpenID Connect (OIDC) authentication
* LDAP authentication
* JWT authentication
* OAuth 1.0a authentication
* SAML V2 authentication
* Internal users management
* Secret vaults support
    * Environment variables
    * Hashicorp Vault
    * Azure key vault
    * AWS secret manager
    * Google secret manager
    * Kubernetes secrets
    * Izanami
    * Spring Cloud Config
    * Http
    * Local

**Certificates management**

* Dynamic TLS certificates store 
* Dynamic TLS termination
* Internal PKI
    * generate self signed certificates/CAs
    * generate/sign certificates/CAs/subCAs
    * AIA
    * OCSP responder
    * import P12/certificate bundles
* ACME / Let's Encrypt support
* On-the-fly certificate generation based on a CA certificate without request loss
* JWKS exposition for public keypair
* Default certificate
* Customize mTLS trusted CAs in the TLS handshake

**Clustering**

* based on a control plane/data plane pattern
* encrypted communication
* backup capabilities to allow data plane to start without control plane reachable to improve resilience
* relay routing to forward traffic from one network zone to others
* distributed web authentication accross nodes

**Performances and testing**

* Chaos engineering
* Horizontal Scalability or clustering
* Canary testing
* Http client in UI
* Request debugging
* Traffic capture

**Kubernetes integration**

* Standard Ingress controller
* Custom Ingress controller
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via webhook
* Service Mesh for easy service-to-service communication (based on Kubernetes sidecars)

**Organize**

* multi-organizations
* multi-teams
* routes groups

**Developpers portal**

* Using @link:[Daikoku](https://maif.github.io/daikoku/manual/index.html) { open=new }
