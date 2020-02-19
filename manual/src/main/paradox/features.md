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
    * lot of module provided out of the box (see TODO: )
* Full featured TLS integration
    * @ref:[Dynamic SSL termination](./topics/ssl.md)
    * mTLS support for input and output connections (end-to-end mTLS)
    * extended client certificate validation
    * TLS certificate automation (create, renew, etc) based on a CA certificate
    * ACME/Let's Encrypt support (create, renew)
    * on-the-fly certificate generation based on a CA certificate without request loss
* Classic features for reverse proxying
    * expose the same service on multiple domain names (including wildcards)
    * support multiple loadbalancing algorithms
    * configurable circuit breaker per service, with timeouts per path and verb
    * @ref:[maintenance page per service](./usage/2-services.md)
    * @ref:[build page per service](./usage/2-services.md)
    * @ref:[force HTTPS usage per service](./usage/2-services.md)
    * @ref:[Add current Api key quotas usage in response headers](./usage/3-apikeys.md)
    * @ref:[Add current latencies in response headers](./usage/3-apikeys.md)
    * headers manipulation
    * routing headers
    * custom html error templates
    * healthcheck per service
    * sink services
    * CORS support
    * GZIP support
    * filtering on http verb and path
* Api management features
    * throttling / daily quotas / monthly quotas per apikey
    * apikey authorization based on http verb and path
    * global throttling
    * global throttling per ip address
    * global or per service ip address blacklist / whitelist
    * automatic apikey secret rotation
* Authentication modules
    * LDAP
    * In memory (managed by otoroshi)
    * OAuth2/OIDC
    * modules can be used for admin. backoffice login
    * webauthentication support
    * sessions management from UI
* JWT token utilities
    * validate incoming JWT tokens
    * transform incoming JWT tokens
    * chain multiple validators
* Analytics / Metrics
    * rich traffic events for each proxied http request
    * @ref:[Live metrics per service and globaly](./usage/4-monitor.md)  
    * @ref:[Global metrics and analytics (requires elastic server)](./usage/7-metrics.md)
    * @ref:[Traffic events can be sent using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
    * multiple technical metrics exporters (statsd, datadog, prometheus)
* Audit trail
    * @ref:[Global audit log alert log on admins actions](./usage/6-audit.md)
    * @ref:[Audit and alerts events can be sent using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
    * @ref:[Alerts events can be send to people by email using email provider (Mailgun, mailjet)](./integrations/mailgun.md)
* Extract informations from `User-Agent` headers to enrich traffic events
* Extract geolocation informations (need external service)  to enrich traffic events
* Support enterprise http proxies globaly and per service
* TCP proxy with SNI and TLS passthrought support
* TCP / UDP tunnelings
    * add web authentication on top of anything
    * local tunnel client with CLI or UI
* @ref:[Canary mode per service](./topics/snow-monkey.md)
* @ref:[Chaos engineering tools with the Snow Monkey](./topics/snow-monkey.md)
* @ref:[Advanced CleverCloud integration (create services from CleverCloud apps)](./integrations/clevercloud.md)
