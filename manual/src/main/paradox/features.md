# Features 

All the features supported by **Otoroshi** are listed below

* Dynamic changes at runtime without full reload 
* Can proxy any HTTP/HTTP2 server (websockets and streamed responses included)
* Full featured admin Rest Api to control Otoroshi the way you want. Included, Swagger descriptor
* Gorgeous React Web UI
* Full end-to-end streaming of HTTP requests and responses
* Completely non blocking and async internals
* @ref:[Official Docker image](./getotoroshi/fromdocker.md)
* @ref:[Multi backend datastore support](./firstrun/datastore.md)
    * Redis
    * In memory
    * Cassandra (experimental support)
    * FileDb (not suitable for production usage)	
* Pluggable modules system (plugins) 
    * you can create your own modules to change de behavior of Otoroshi per service or globally
    * impacts on access validation, routing, body transformation, apikey extraction
    * listen to internal otoroshi events
    * modules can be written and deployed from the UI
* Full featured TLS integration
    * @ref:[Dynamic SSL termination](./topics/ssl.md)
    * mTLS support for input and output connections (end-to-end mTLS)
    * extended client certificate validation
    * TLS certificate automation based on a CA certificate
    * ACME/Let's Encrypt support
* Classic features for reverse proxying
    * expose the same service on multiple domain names (including wildcards)
    * support multiple loadbalancing algorithms
    * configurable circuit breaker per service
    * @ref:[maintenance page per service](./usage/2-services.md)
    * @ref:[build page per service](./usage/2-services.md)
    * @ref:[force HTTPS usage per service](./usage/2-services.md)
    * @ref:[Add current Api key quotas usage in response headers](./usage/3-apikeys.md)
    * @ref:[Add current latencies in response headers](./usage/3-apikeys.md)
* Api management features
    * http verb/path filtering per apikey
* Authentication modules
    * LDAP
    * In memory (managed by otoroshi)
    * OAuth2/OIDC
    * modules can be used for admin. backoffice login
    * webauthentication support
* JWT token utilities
    * validate incoming JWT tokens
    * transform incoming JWT tokens
* Analytics / Metrics
    * @ref:[Global live metrics](./setup/index.md) TODO: 
    * @ref:[Live metrics per service (Rest)](./usage/4-monitor.md#service-live-stats) TODO: 
    * @ref:[Global metrics and analytics (if used with the Elastic connector)](./usage/7-metrics.md)
    * multiple technical metrics exporters (statsd, datadog, prometheus)
* Audit trail
    * @ref:[Global audit log on admins actions](./usage/6-audit.md#audit-trail)
    * @ref:[Global alert log on admins actions](./usage/6-audit.md#alerts)
    * @ref:[Internal events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
    * @ref:[Audit events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
    * @ref:[Alerts events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
    * @ref:[Alerts can be send to people by email using email provider (Mailgun, mailjet)](./integrations/mailgun.md)
* @ref:[Chaos engineering tools with the Snow Monkey](./topics/snow-monkey.md)
* @ref:[Advanced CleverCloud integration (create services from CleverCloud apps)](./integrations/clevercloud.md)






* @ref:[Service is private (Api key access) by default with exclusions](./usage/2-services.md)
* @ref:[Support routing headers for a service (ie. for service versioning)](./usage/2-services.md)
* @ref:[Support adding headers for a service request (ie. add Authorization header)](./usage/2-services.md)
* @ref:[Support custom html errors templates per service](./usage/2-services.md#custom-error-templates)
* @ref:[Configurable circuit breaker and retries (with backoff) on network errors per service](./usage/2-services.md#service-circuit-breaker)
* @ref:[Support for canary mode per service](./usage/2-services.md#canary-mode)
* @ref:[Configurable health check per service](./usage/2-services.md#service-health-check)
* @ref:[Support IP addresses blacklist per service (with wildcard support)](./usage/2-services.md#service-settings)
* @ref:[Support IP addresses whitelist per service (with wildcard support)](./usage/2-services.md#service-settings)
* @ref:[Support mutiple Api keys per service](./usage/3-apikeys.md)
* @ref:[Support configurable throttling quota per Api key](./usage/3-apikeys.md)
* @ref:[Support configurable daily quota per Api key](./usage/3-apikeys.md)
* @ref:[Support configurable monthly quota per Api key](./usage/3-apikeys.md)
* @ref:[Api keys are authorized for a group of services](./usage/3-apikeys.md)
* @ref:[Api keys can be passed with custom headers, `Authorization: Basic ` headers, `Authorization: Bearer` or Cookies](./usage/3-apikeys.md)
* @ref:[Force secured exchanges with exclusions per service](./usage/2-services.md)
* @ref:[OpenAPI documentation displayed through web UI per service](./usage/2-services.md#service-settings)
* @ref:[Metrics and analytics per service (if used with the Elastic connector)](./usage/4-monitor.md#service-analytics)

* @ref:[Support global IP addresses blacklist (with wildcard support)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global IP addresses whitelist (with wildcard support)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global endless responses for IP addresses (128 Gb of 0 for each response)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global throttling quota](./setup/dangerzone.md#global-throttling-settings)
* @ref:[Support global throttling quota per IP address (with wildcard support)](./setup/dangerzone.md#global-throttling-settings)
* @ref:[Support global max number of concurrent connections](./setup/dangerzone.md#commons-settings)
* @ref:[Global live metrics](./setup/index.md)
