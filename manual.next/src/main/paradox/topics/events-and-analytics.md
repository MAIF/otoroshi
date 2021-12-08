# Events and analytics

Otoroshi is a solution fully traced : calls to services or any access to UI generate logs.

## Events

* Analytics event
* Gateway event
* TCP event
* Healthcheck event

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

