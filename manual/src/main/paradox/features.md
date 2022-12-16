# Features

**Traffic Management**

* Can proxy any HTTP(s) service (apis, webapps, websocket, etc)
* Can proxy any TCP service (app, database, etc)
* Multiple load-balancing options: 
    * RoundRobin
    * Random, Sticky
    * Ip address hash
    * Best Response Time
* Distributed in-flight request limiting	
* Distributed rate limiting 
* End-to-end HTTP3 support
* Traffic mirroring
* Canary deployments
* Relay routing 
* Tunnels for easier network exposition

**Services customization**

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
* Write your own custom middlewares

**Services Monitoring**

* Active healthchecks
* Calls tracing using W3C trace context
* Export alerts and events to external database
* Real-time traffic metrics
* Alert mailers
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

**Administration UI**

* Manage and organize all resources
* Secured users access with Authentication module
* Traced users actions
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

**Certificates management**

* Dynamic TLS certificates store 
* Dynamic TLS termination
* Internal PKI
* ACME / Let's Encrypt support
* On-the-fly certificate generation based on a CA certificate without request loss

**Performances and testing**

* Chaos engineering
* Clustering with encrypted communication
* Scalability

**Kubernetes integration**

* Standard Ingress controller
* Custom Ingress controller
    * Manage Otoroshi resources from Kubernetes
* Validation of resources via webhook
* Service Mesh for easy service-to-service communication

**Developpers portal**

* Using @link:[Daikoku](https://maif.github.io/daikoku/manual/index.html) { open=new }
