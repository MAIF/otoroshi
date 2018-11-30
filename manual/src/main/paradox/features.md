# Features 

All the features supported by **Otoroshi** are listed below

* Dynamic changes at runtime without full reload 
* Can proxy any HTTP server (HTTP2 targets are not supported yet, but will be soon enough)
* Can proxy websockets
* Full featured admin Rest Api to control Otoroshi the way you want. Included, Swagger descriptor
* Gorgeous React Web UI
* Full end-to-end streaming of HTTP requests and responses
* Completely non blocking and async internals
* @ref:[Official Docker image](./getotoroshi/fromdocker.md)
* @ref:[Multi backend datastore support](./firstrun/datastore.md)
    * Redis
    * LevelDB
    * In memory
    * Cassandra
    * Mongo
* @ref:[Service is private (Api key access) by default with exclusions](./usage/2-services.md)
* @ref:[Support wildcard domain names per service](./usage/2-services.md)
* @ref:[Support routing headers for a service (ie. for service versioning)](./usage/2-services.md#service-meta)
* @ref:[Support adding headers for a service request (ie. add Authorization header)](./usage/2-services.md#service-meta)
* @ref:[Support custom html errors templates per service](./usage/2-services.md#custom-error-templates)
* @ref:[Configurable circuit breaker and retries (with backoff) on network errors per service](./usage/2-services.md#service-circuit-breaker)
* @ref:[Round Robin load balancing per service](./usage/2-services.md#targets)
* @ref:[Services can be declared private (private apps) using an Auth0 domain](./usage/2-services.md#service-flags)
* @ref:[Support for canary mode per service](./usage/2-services.md#canary-mode)
* @ref:[Configurable health check per service](./usage/2-services.md#service-health-check)
* @ref:[Support IP addresses blacklist per service (with wildcard support)](./usage/2-services.md#service-settings)
* @ref:[Support IP addresses whitelist per service (with wildcard support)](./usage/2-services.md#service-settings)
* @ref:[Support mutiple Api keys per service](./usage/3-apikeys.md)
* @ref:[Support configurable throttling quota per Api key](./usage/3-apikeys.md#quotas)
* @ref:[Support configurable daily quota per Api key](./usage/3-apikeys.md#quotas)
* @ref:[Support configurable monthly quota per Api key](./usage/3-apikeys.md#quotas)
* @ref:[Api keys are authorized for a group of services](./usage/3-apikeys.md)
* @ref:[Api keys can be passed with custom headers, `Authorization: Basic ` headers, `Authorization: Bearer` or Cookies](./usage/3-apikeys.md)
* @ref:[Add current Api key quotas usage in response headers](./usage/3-apikeys.md#quotas)
* @ref:[Add current latencies in response headers](./usage/3-apikeys.md#quotas)
* @ref:[Support service duplication through web UI](./usage/2-services.md#service-flags)
* @ref:[Maintenance page per service](./usage/2-services.md#service-flags)
* @ref:[Build page per service](./usage/2-services.md#service-flags)
* @ref:[Force HTTPS usage per service](./usage/2-services.md#service-flags)
* @ref:[Force secured exchanges with exclusions per service](./usage/2-services.md#service-flags)
* @ref:[OpenAPI documentation displayed through web UI per service](./usage/2-services.md#service-settings)
* @ref:[Live metrics per service (Rest)](./usage/4-monitor.md#service-live-stats)
* @ref:[Metrics and analytics per service (if used with the Elastic connector)](./usage/4-monitor.md#service-analytics)
* @ref:[Global metrics and analytics (if used with the Elastic connector)](./usage/7-metrics.md)
* @ref:[Global audit log on admins actions](./usage/6-audit.md#audit-trail)
* @ref:[Global alert log on admins actions](./usage/6-audit.md#alerts)
* @ref:[Internal events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
* @ref:[Audit events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
* @ref:[Alerts events can be sent outside using webhooks or Kafka topic](./setup/dangerzone.md#analytics-settings)
* @ref:[Alerts can be send to people by email using Mailgun](./integrations/mailgun.md)
* @ref:[Support global IP addresses blacklist (with wildcard support)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global IP addresses whitelist (with wildcard support)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global endless responses for IP addresses (128 Gb of 0 for each response)](./setup/dangerzone.md#whitelist-blacklist-settings)
* @ref:[Support global throttling quota](./setup/dangerzone.md#global-throttling-settings)
* @ref:[Support global throttling quota per IP address (with wildcard support)](./setup/dangerzone.md#global-throttling-settings)
* @ref:[Support global max number of concurrent connections](./setup/dangerzone.md#commons-settings)
* Support global request tracing
* @ref:[Support full JSON export of the reverse proxy state](./usage/8-importsexports.md#full-export)
* @ref:[Support full JSON import of the reverse proxy state](./usage/8-importsexports.md#full-import)
* @ref:[Support initial internal state import from JSON local file](./firstrun/configfile.md#db-configuration)
* @ref:[Support initial internal state import from JSON file over HTTP](./firstrun/configfile.md#db-configuration)
* @ref:[Enforce incoming JWT token verification and transformation](./topics/jwt-verifications.md)
* @ref:[Introduce HTTP level chaos engineering pratices with the Snow Monkey](./topics/snow-monkey.md)
* @ref:[Native support for service mesh architectures](./topics/service-mesh.md)
* @ref:[Global live metrics](./setup/index.md#first-login)
* @ref:[Send metrics to a StatsD/Datadog agent](./integrations/statsd.md)
* @ref:[Advanced CleverCloud integration (create services from CleverCloud apps)](./integrations/clevercloud.md)
* @ref:[Support admin login with any auth. module](./usage/0-auth.md)
* @ref:[Support admin login with FIDO U2F device](./setup/admin.md#create-admin-user-with-u2f-device-login)
* @ref:[Dynamic SSL termination](./topics/ssl.md)
* @ref:[Rust CLI running on MacOS, Linux and Windows](./cli.md)
* @ref:[Connectors to various HTTP services providers](./connectors/index.md)
    * [generic connector API](https://github.com/MAIF/otoroshi/tree/master/connectors/common)
    * @ref:[Clever Cloud](./connectors/clevercloud.md)
    * @ref:[Rancher](./connectors/rancher.md)
    * @ref:[Kubernetes](./connectors/kubernetes.md)
