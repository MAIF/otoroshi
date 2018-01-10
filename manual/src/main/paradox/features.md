# Features 

All the features supported by **Otoroshi** are listed below

* Dynamic changes at runtime without full reload 
* Can proxy any HTTP server (HTTP2 is not supported yet, but will be soon enough)
* Can proxy websockets
* Full featured admin Rest Api to control Otoroshi the way you want. Included, Swagger descriptor
* Gorgeous React Web UI
* Full end-to-end streaming of HTTP requests and responses
* Completely non blocking and async. internals
* Offical Docker image
* Multi backend datastore support
    * Redis
    * LevelDB
    * In memory
    * Cassandra
* Service is private (Api key access) by default with exclusions
* Support any domain name per service
* Support wildcard domain names per service
* Support routing headers for a service (ie. for service versioning)
* Support adding headers for a service request (ie. add Authorization header)
* Support custom html errors templates per service
* Configurable circuit breaker and retries (with backoff) on network errors per service
* Round Robin load balancing per service
* Services can be declared private (private apps) using an Auth0 domain
* Support for canary mode per service
* Configurable health check per service
* Support IP addresses blacklist per service (with wildcard support)
* Support IP addresses whitelist per service (with wildcard support)
* Support mutiple Api keys per service
* Support configurable throttling quota per Api key
* Support configurable daily quota per Api key
* Support configurable monthly quota per Api key
* Api keys are authorized for a group of services 
* Api keys can be passed with custom headers, `Authorization: Basic ` headers or `Authorization: Bearer ` JWT token signed with Api key secret
* Add current Api key quotas usage in response headers
* Add current latencies in response headers
* Support service duplication through web UI
* Maintenance page per service
* Build page per service
* Force HTTPS usage per service
* Force secured exchanges with exclusions per service
* OpenAPI documentation displayed through web UI per service
* Live metrics per service (Rest)
* Metrics and analytics per service (if used with the Elastic connector)
* Global metrics and analytics (if used with the Elastic connector)
* Global audit log on admins actions
* Global alert log on admins actions
* Internal events can be sent outside using webhooks or Kafka topic
* Audit events can be sent outside using webhooks or Kafka topic
* Alerts events can be sent outside using webhooks or Kafka topic
* Alerts can be send to actual people by email using Mailgun
* Support global IP addresses blacklist (with wildcard support)
* Support global IP addresses whitelist (with wildcard support)
* Support global endless responses for IP addresses (128 Gb of 0 for each response)
* Support global throttling quota
* Support global throttling quota per IP address (with wildcard support)
* Support global max number of concurrent connections
* Support global request tracing
* Support full JSON export of the reverse proxy state
* Support full JSON import of the reverse proxy state
* Support initial internal state import from JSON local file
* Support initial internal state import from JSON file over HTTP
* Enforce request URL max size
* Enforce request headers max size
* Enforce request cookies max size
* Global live metrics
* Send metrics to a StatsD/Datadog agent
* Advanced CleverCloud integration (create services from CleverCloud apps)
* Embedded documentation
* Global services map and top 10
* Support admin login with Auth0
* Support admin login with FIDO U2F device
* Support runtime changes of internal log levels
* Rust CLI running on MacOS, Linux and Windows
* Connectors to various HTTP services providers
    * generic connector API
    * Clever Cloud
    * Rancher
    * Kubernetes
