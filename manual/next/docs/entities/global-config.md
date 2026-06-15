---
title: Global config
sidebar_position: 9
---
# Global config

The global configuration is the single, system-wide configuration object that governs the behavior of the entire Otoroshi gateway. There is exactly one global config per Otoroshi instance (or cluster), and every change made here affects **all** traffic flowing through the gateway, not just a single route or API.

## Why is it called "Danger Zone"?

In the Otoroshi admin UI, this page is labeled **Danger Zone** for a reason: the settings here are powerful and far-reaching. Modifying global throttling limits, enabling maintenance mode, or changing IP filtering rules will immediately impact every service proxied by Otoroshi. Unlike route-level configuration, which is scoped to a single route or service, the global config acts as a blanket policy layer across the entire platform.

## What does the global config control?

The global config covers the following areas:

- **Global throttling and rate limiting** -- system-wide request-per-second limits, both globally and per IP address
- **IP address filtering** -- gateway-wide allow lists, block lists, and endless-response addresses that apply before any route matching
- **Maintenance mode and read-only mode** -- instantly put every service into maintenance or freeze the datastore
- **Global plugins** -- plugins that run on every single request processed by Otoroshi, on top of any route-level plugins
- **TLS settings** -- default certificate behavior, trusted CAs, SNI defaults, and automatic certificate generation
- **Analytics and metrics** -- Elasticsearch datasource configuration, StatsD/Datadog integration, and live metrics toggle
- **Let's Encrypt / ACME** -- automated certificate provisioning settings
- **Geolocation and user-agent extraction** -- enrichment of analytics events with geographic and device information
- **Backoffice authentication** -- the authentication module protecting the Otoroshi admin UI itself
- **Proxies** -- HTTP proxy configuration for outgoing calls (webhooks, OAuth, Elasticsearch, etc.)
- **Quotas alerting** -- thresholds for API key quota warnings
- **Snowflake ID generator** -- unique instance identifier used for distributed ID generation
- **Chaos engineering (Snow Monkey)** -- fault injection settings that apply across all services
- **Global metadata and tags** -- labels attached to the global config for organizational purposes

## Global config vs. route-level config

Most of your configuration should happen at the **route level**. Routes let you define specific behavior (authentication, rate limiting, transformations, etc.) for individual APIs or groups of APIs. The global config is reserved for policies that genuinely need to apply everywhere:

- Use **route-level config** when you want to protect a specific API with an auth module, apply a custom rate limit to one consumer, or transform headers for a particular backend.
- Use **global config** when you need a gateway-wide IP block list, a system-wide request ceiling, TLS defaults for all domains, or plugins that must execute on every request regardless of the route.

## Hot-reloadable

All global config changes are **hot-reloadable**: they take effect immediately without restarting Otoroshi. This makes the Danger Zone both powerful and sensitive -- there is no deployment step between saving a change and it being live in production.

---


### Misc. Settings


* `Maintenance mode` : It passes every single service in maintenance mode. If a user calls a service, the maintenance page will be displayed
* `No OAuth login for BackOffice` : Forces admins to login only with user/password or user/password/u2F device
* `API Read Only`: Freeze Otoroshi datastore in read only mode. Only people with access to the actual underlying datastore will be able to disable this.
* `Auto link default` : When no group is specified on a service, it will be assigned to default one
* `Use circuit breakers` : Use circuit breaker on all services
* `Use new http client as the default Http client` : All http calls will use the new http client by default
* `Enable live metrics` : Enable live metrics in the Otoroshi cluster. Performs a lot of writes in the datastore
* `Digitus medius` : Use middle finger emoji as a response character for endless HTTP responses (see [IP address filtering settings](#ip-address-filtering-settings)).
* `Limit conc. req.` : Limit the number of concurrent request processed by Otoroshi to a certain amount. Highly recommended for resilience
* `Use X-Forwarded-* headers for routing` : When evaluating routing of a request, X-Forwarded-* headers will be used if presents
* `Max conc. req.` : Maximum number of concurrent requests processed by otoroshi.
* `Max HTTP/1.0 resp. size` : Maximum size of an HTTP/1.0 response in bytes. After this limit, response will be cut and sent as is. The best value here should satisfy (maxConcurrentRequests * maxHttp10ResponseSize) < process.memory for worst case scenario.
* `Max local events` : Maximum number of events stored.
* `Lines` : *deprecated* 

### IP address filtering settings

* `IP allowed list`: Only IP addresses that will be able to access Otoroshi exposed services
* `IP blocklist`: IP addresses that will be refused to access Otoroshi exposed services
* `Endless HTTP Responses`: IP addresses for which each request will return around 128 Gb of 0s


### Quotas settings

* `Global throttling`: The max. number of requests allowed per second globally on Otoroshi
* `Throttling per IP`: The max. number of requests allowed per second per IP address globally on Otoroshi

### Analytics: Elastic dashboard datasource (read)

* `Cluster URI`: Elastic cluster URI
* `Index`: Elastic index 
* `Type`: Event type (not needed for elasticsearch above 6.x)
* `User`: Elastic User (optional)
* `Password`: Elastic password (optional)
* `Version`: Elastic version (optional, if none provided it will be fetched from cluster)
* `Apply template`: Automatically apply index template
* `Check Connection`: Button to test the configuration. It will displayed a modal with a connection checklist, if connection is successfull, it will display the found version of the Elasticsearch and the index used
* `Manually apply index template`: try to put the elasticsearch template by calling the api of elasticsearch
* `Show index template`: try to retrieve the current index template present in elasticsearch
* `Client side temporal indexes handling`: When enabled, Otoroshi will manage the creation of indexes over time. When it's disabled, Otoroshi will push in the same index
* `One index per`: When the previous field is enabled, you can choose the interval of time between the creation of a new index in elasticsearch 
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
* `TLS loose`: if enabled, will block all untrustful ssl configs
* `TrustAll`: allows any server certificates even the self-signed ones
* `Client certificates`: list of client certificates used to communicate with elasticsearch
* `Trusted certificates`: list of trusted certificates received from elasticsearch


### Statsd settings

* `Datadog agent`: The StatsD agent is a Datadog agent
* `StatsD agent host`: The host on which StatsD agent is listening
* `StatsD agent port`: The port on which StatsD agent is listening (default is 8125)


### Backoffice auth. settings

* `Backoffice auth. config`: the authentication module used in front of Otoroshi. It will be used to connect to Otoroshi on the login page

### Let's encrypt settings

* `Enabled`: when enabled, Otoroshi will have the possiblity to sign certificate from let's encrypt notably in the SSL/TSL Certificates page 
* `Server URL`: ACME endpoint of let's encrypt 
* `Email addresses`: (optional) list of addresses used to order the certificates 
* `Contact URLs`: (optional) list of addresses used to order the certificates 
* `Public Key`: used to ask a certificate to let's encrypt, generated by Otoroshi 
* `Private Key`: used to ask a certificate to let's encrypt, generated by Otoroshi 


### CleverCloud settings

Once configured, you can register one clever cloud app of your organization directly as an Otoroshi service.

* `CleverCloud consumer key`: consumer key of your clever cloud OAuth 1.0 app
* `CleverCloud consumer secret`: consumer secret of your clever cloud OAuth 1.0 app
* `OAuth Token`: oauth token of your clever cloud OAuth 1.0 app
* `OAuth Secret`: oauth token secret of your clever cloud OAuth 1.0 app 
* `CleverCloud orga. Id`: id of your clever cloud organization

###  Global scripts

Global scripts is deprecated, please use global plugins instead (see the next section)!

###  Global plugins

* `Enabled`: enable the use of global plugins
* `Plugins on new Otoroshi engine`: list of plugins used by the new Otoroshi engine
* `Plugins on old Otoroshi engine`: list of plugins used by the old Otoroshi engine
* `Plugin configuration`: the overloaded configuration of plugins

###  Proxies

In this section, you can add a list of proxies for :

* Proxy for alert emails (mailgun)
* Proxy for alert webhooks
* Proxy for Clever-Cloud API access
* Proxy for services access
* Proxy for auth. access (OAuth, OIDC)
* Proxy for client validators
* Proxy for JWKS access
* Proxy for elastic access

Each proxy has the following fields 

* `Proxy host`: host of proxy
* `Proxy port`: port of proxy
* `Proxy principal`: user of proxy
* `Proxy password`: password of proxy
* `Non proxy host`: IP address that can access the service

###  Quotas alerting settings

* `Enable quotas exceeding alerts`: When apikey quotas is almost exceeded, an alert will be sent 
* `Daily quotas threshold`: The percentage of daily calls before sending alerts
* `Monthly quotas threshold`: The percentage of monthly calls before sending alerts

###  User-Agent extraction settings

* `User-Agent extraction`: Allow user-agent details extraction. Can have impact on consumed memory. 

###  Geolocation extraction settings

Extract a geolocation for each call to Otoroshi.

###  Tls Settings

* `Use random cert.`: Use the first available cert when none matches the current domain
* `Default domain`: When the SNI domain cannot be found, this one will be used to find the matching certificate 
* `Trust JDK CAs (server)`: Trust JDK CAs. The CAs from the JDK CA bundle will be proposed in the certificate request when performing TLS handshake 
* `Trust JDK CAs (trust)`: Trust JDK CAs. The CAs from the JDK CA bundle will be used as trusted CAs when calling HTTPS resources 
* `Trusted CAs (server)`: Select the trusted CAs you want for TLS terminaison. Those CAs only will be proposed in the certificate request when performing TLS handshake 


###  Auto Generate Certificates

* `Enabled`: Generate certificates on the fly when they don't exist
* `Reply Nicely`: When receiving request from a not allowed domain name, accept connection and display a nice error message 
* `CA`: certificate CA used to generate missing certificate
* `Allowed domains`: Allowed domains
* `Not allowed domains`: Not allowed domains
  

###  Global metadata

* `Tags`: tags attached to the global config
* `Metadata`: metadata attached to the global config

### Actions at the bottom of the page

* `Recover from a full export file`: Load global configuration from a previous export
* `Full export`: Export with all created entities
* `Full export (ndjson)`: Export your full state of database to ndjson format
* `JSON`: Get the global config at JSON format 
* `YAML`: Get the global config at YAML format 
* `Enable Panic Mode`: Log out all users from UI and prevent any changes to the database by setting the admin Otoroshi api to read-only. The only way to exit of this mode is to disable this mode directly in the database. 