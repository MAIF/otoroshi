# Events and analytics

Otoroshi is a solution fully traced : calls to services, access to UI, creation of resources, etc.

@@@ warning
You have to use [Elastic](https://www.elastic.co) to enable analytics features in Otoroshi
@@@

## Events

* Analytics event
* Gateway event
* TCP event
* Healthcheck event

## Event log

Otoroshi can read his own exported events from an Elasticsearch instance, set up in the danger zone. Theses events are available from the UI, at the following route: `https://xxxxx/bo/dashboard/events`.

The `Global events` page display all events of **GatewayEvent** type. This page is a way to quickly read an interval of events and can be used in addition of a Kibana instance.

For each event, a list of information will be displayed and an additional button `content` to watch the full content of the event, at the JSON format. 

## Alerts 

* `MaxConcurrentRequestReachedAlert`: happening when the handled requests number are greater than the limit of concurrent requests indicated in the global configuration of Otoroshi
* `CircuitBreakerOpenedAlert`: happening when the circuit breaker pass from closed to opened
* `CircuitBreakerClosedAlert`: happening when the circuit breaker pass from opened to closed
* `SessionDiscardedAlert`: send when an admin discarded an admin sessions
* `SessionsDiscardedAlert`: send when an admin discarded all admin sessions
* `PanicModeAlert`: send when panic mode is enabled
* `OtoroshiExportAlert`: send when otoroshi global configuration is exported
* `U2FAdminDeletedAlert`: send when an admin has deleted an other admin user
* `BlackListedBackOfficeUserAlert`: send when a blacklisted user has tried to acccess to the UI
* `AdminLoggedInAlert`: send when an user admin has logged to the UI
* `AdminFirstLogin`: send when an user admin has successfully logged to the UI for the first time
* `AdminLoggedOutAlert`: send when an user admin has logged out from Otoroshi
* `GlobalConfigModification`: send when an user amdin has changed the global configuration of Otoroshi
* `RevokedApiKeyUsageAlert`: send when an user admin has revoked an apikey
* `ServiceGroupCreatedAlert`: send when an user admin has created a service group
* `ServiceGroupUpdatedAlert`: send when an user admin has updated a service group
* `ServiceGroupDeletedAlert`: send when an user admin has deleted a service group
* `ServiceCreatedAlert`: send when an user admin has created a tcp service
* `ServiceUpdatedAlert`: send when an user admin has updated a tcp service
* `ServiceDeletedAlert`: send when an user admin has deleted a tcp service
* `ApiKeyCreatedAlert`: send when an user admin has crated a new apikey
* `ApiKeyUpdatedAlert`: send when an user admin has updated a new apikey
* `ApiKeyDeletedAlert`: send when an user admin has deleted a new apikey

## Audit

With Otoroshi, any admin action and any sucpicious/alert action is recorded. These records are stored in Otoroshi’s datastore (only the last n records, defined by the app.events.maxSize config key). All the records can be send through the analytics mechanism (WebHook, Kafka, Elastic) for external and/or further usage. We recommand sending away those records for security reasons.

Otoroshi keep the following list of information for each executed action:

* `Date`: moment of the action
* `User`: name of the owner
* `From`: IP of the concerned user
* `Action`: action performed by the person. The possible actions are:

    * `ACCESS_APIKEY`: User accessed a apikey
    * `ACCESS_ALL_APIKEYS`: User accessed all apikeys
    * `CREATE_APIKEY`: User created a apikey
    * `UPDATE_APIKEY`: User updated a apikey
    * `DELETE_APIKEY`: User deleted a apikey
    * `ACCESS_AUTH_MODULE`: User accessed an Auth. module
    * `ACCESS_ALL_AUTH_MODULES`: User accessed all Auth. modules
    * `CREATE_AUTH_MODULE`: User created an Auth. module
    * `UPDATE_AUTH_MODULE`: User updated an Auth. module
    * `DELETE_AUTH_MODULE`: User deleted an Auth. module
    * `ACCESS_CERTIFICATE`: User accessed a certificate
    * `ACCESS_ALL_CERTIFICATES`: User accessed all certificates
    * `CREATE_CERTIFICATE`: User created a certificate
    * `UPDATE_CERTIFICATE`: User updated a certificate
    * `DELETE_CERTIFICATE`: User deleted a certificate
    * `ACCESS_CLIENT_CERT_VALIDATOR`: User accessed a client cert. validator
    * `ACCESS_ALL_CLIENT_CERT_VALIDATORS`: User accessed all client cert. validators
    * `CREATE_CLIENT_CERT_VALIDATOR`: User created a client cert. validator
    * `UPDATE_CLIENT_CERT_VALIDATOR`: User updated a client cert. validator
    * `DELETE_CLIENT_CERT_VALIDATOR`: User deleted a client cert. validator
    * `ACCESS_DATA_EXPORTER_CONFIG`: User accessed a data exporter config
    * `ACCESS_ALL_DATA_EXPORTER_CONFIG`: User accessed all data exporter config
    * `CREATE_DATA_EXPORTER_CONFIG`: User created a data exporter config
    * `UPDATE_DATA_EXPORTER_CONFIG`: User updated a data exporter config
    * `DELETE_DATA_EXPORTER_CONFIG`: User deleted a data exporter config
    * `ACCESS_GLOBAL_JWT_VERIFIER`: User accessed a global jwt verifier
    * `ACCESS_ALL_GLOBAL_JWT_VERIFIERS`: User accessed all global jwt verifiers
    * `CREATE_GLOBAL_JWT_VERIFIER`: User created a global jwt verifier
    * `UPDATE_GLOBAL_JWT_VERIFIER`: User updated a global jwt verifier
    * `DELETE_GLOBAL_JWT_VERIFIER`: User deleted a global jwt verifier
    * `ACCESS_SCRIPT`: User accessed a script
    * `ACCESS_ALL_SCRIPTS`: User accessed all scripts
    * `CREATE_SCRIPT`: User created a script
    * `UPDATE_SCRIPT`: User updated a script
    * `DELETE_SCRIPT`: User deleted a Script
    * `ACCESS_SERVICES_GROUP`: User accessed a service group
    * `ACCESS_ALL_SERVICES_GROUPS`: User accessed all services groups
    * `CREATE_SERVICE_GROUP`: User created a service group
    * `UPDATE_SERVICE_GROUP`: User updated a service group
    * `DELETE_SERVICE_GROUP`: User deleted a service group
    * `ACCESS_SERVICES_FROM_SERVICES_GROUP`: User accessed all services from a services group
    * `ACCESS_TCP_SERVICE`: User accessed a tcp service
    * `ACCESS_ALL_TCP_SERVICES`: User accessed all tcp services
    * `CREATE_TCP_SERVICE`: User created a tcp service
    * `UPDATE_TCP_SERVICE`: User updated a tcp service
    * `DELETE_TCP_SERVICE`: User deleted a tcp service
    * `ACCESS_TEAM`: User accessed a Team
    * `ACCESS_ALL_TEAMS`: User accessed all teams
    * `CREATE_TEAM`: User created a team
    * `UPDATE_TEAM`: User updated a team
    * `DELETE_TEAM`: User deleted a team
    * `ACCESS_TENANT`: User accessed a Tenant
    * `ACCESS_ALL_TENANTS`: User accessed all tenants
    * `CREATE_TENANT`: User created a tenant
    * `UPDATE_TENANT`: User updated a tenant
    * `DELETE_TENANT`: User deleted a tenant
    * `SERVICESEARCH`: User searched for a service
    * `ACTIVATE_PANIC_MODE`: Admin activated panic mode


* `Message`: explicit message about the action (example: the `SERVICESEARCH` action happened when an `user searched for a service`)
* `Content`: all information at JSON format

## Global metrics

The global metrics are displayed on the index page of the Otoroshi UI. Otoroshi provides information about :

* the number of requests served
* the amount of data received and sended
* the number of concurrent requests
* the number of requests per second
* the current overhead

More metrics can be found on the **Global analytics** page (available at https://xxxxxx/bo/dashboard/stats).

## Monitoring services

Once you have declared services, you can monitor them with Otoroshi. 

Let's starting by setup Otoroshi to push events to an elastic cluster via a data exporter. Then you will can setup Otoroshi events read from an elastic cluster. Go to `settings (cog icon) / Danger Zone` and expand the `Analytics: Elastic cluster (read)` section.

@@@ div { .centered-img }
<img src="../imgs/push-to-elastic.png" />
@@@

### Service healthcheck

If you have defined an health check URL in the service descriptor, you can access the health check page from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../imgs/service-healthcheck.png" />
@@@

### Service live stats

You can also monitor live stats like total of served request, average response time, average overhead, etc. The live stats page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../imgs/service-live-stats.png" />
@@@

### Service analytics

You can also get some aggregated metrics. The analytics page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../imgs/service-analytics.png" />
@@@
