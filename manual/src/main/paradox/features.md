# Features

**Traffic Management**

* Can proxy any HTTP(s) service (apis, webapps, websocket, etc)
* Can proxy any TCP service (app, database, etc)
* Traffic mirroring
* Canary deployments
* Multiple load-balancing options: RoundRobin, Random, Sticky, Ip Hash, Best Response Time
* Distributed in-flight request limiting	
* Distributed rate limiting 

**Services customization**

* Dozens of built-in middlewares (plugins) 
    * circuit breakers
    * automatic retries
    * buffering
    * gzip
    * headers manipulation
    * cors
    * etc 
* Custom middleware
* Higly customizable visibility

**Services Monitoring**

* Active health checks
* Calls tracing
* Export alerts and events to external database
* Real-time traffic metrics
* Alert mailers
* Real-time traffic metrics (Datadog, Prometheus, StatsD)

**API security**

* Access management with apikeys and quotas
* Automatic apikeys secrets rotation
* HTTPS and TLS
* mTLS in/out calls 
* Routing constraints
* Routing restrictions
* JWT tokens validation and manipulation

**Administration UI**

* Manage and organize all resources
* Secured users access with Authentication module
* Traced users actions
* Dynamic changes at runtime without full reload

**Webapp authentication and security**

* OAuth2.0/2.1 authentication
* OpenID Connect (OIDC) authentication
* Internal users management
* LDAP authentication
* JWT authentication
* OAuth 1.0a authentication
* SAML V2 authentication

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
