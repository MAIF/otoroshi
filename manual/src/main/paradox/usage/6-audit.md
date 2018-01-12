# Auditing Otoroshi

With Otoroshi, any admin action and any sucpicious/alert action is recorded. Those records are stored in the Otoroshi datastore (only the last n records, defined by the `app.events.maxSize` @ref:[config key](../firstrun/configfile.md)). All the records can be send through the analytics mechanism (WebHook and/or Kafka) for further usage. We recommand sending away those records for security reasons.

@@@ warning
You have to use @ref:[the Elastic connector](../connectors/elastic.md) to enable analytics features in Otoroshi
@@@

## Audit trail

To see last `app.events.maxSize` admin actions on Otoroshi from the UI, go to `settings (cog icon) / Audit log`.

@@@ div { .centered-img }
<img src="../img/audit-log.png" />
@@@

## Alerts

To see last `app.events.maxSize` alerts on Otoroshi from the UI, go to `settings (cog icon) / Alerts log`.

@@@ div { .centered-img }
<img src="../img/alerts-log.png" />
@@@

## List of possible alerts

```
MaxConcurrentRequestReachedAlert
CircuitBreakerOpenedAlert
CircuitBreakerClosedAlert
SessionDiscardedAlert
SessionsDiscardedAlert
PanicModeAlert
OtoroshiExportAlert
U2FAdminDeletedAlert
BlackListedBackOfficeUserAlert
AdminLoggedInAlert
AdminFirstLogin
AdminLoggedOutAlert
DbResetAlert
DangerZoneAccessAlert
GlobalConfigModification
RevokedApiKeyUsageAlert
ServiceGroupCreatedAlert
ServiceGroupUpdatedAlert
ServiceGroupDeletedAlert
ServiceCreatedAlert
ServiceUpdatedAlert
ServiceDeletedAlert
ApiKeyCreatedAlert
ApiKeyUpdatedAlert
ApiKeyDeletedAlert
```