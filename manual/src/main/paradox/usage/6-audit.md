# Auditing Otoroshi

With Otoroshi, any admin action and any sucpicious/alert action is recorded. These records are stored in Otoroshi's datastore (only the last n records, defined by the `app.events.maxSize` @ref:[config key](../firstrun/configfile.md)). All the records can be send through the analytics mechanism (WebHook, Kafka, Elastic) for external and/or further usage. We recommand sending away those records for security reasons.

@@@ warning
You have to use [Elastic](https://www.elastic.co) to enable analytics features in Otoroshi. See @ref:[Elastic setup section](../integrations/analytics.md)
@@@

## Audit trail

To see last `app.events.maxSize` admin actions on Otoroshi from the UI, go to `settings (cog icon) / Audit log`.

@@@ div { .centered-img }
<img src="../img/audit-log.png" />
@@@

## Alerts
<!-- TODO: if dataexporters "ALERTS" not set, it can't works ?? -->
To see last `app.events.maxSize` alerts on Otoroshi from the UI, go to `settings (cog icon) / Alerts log`.

@@@ div { .centered-img }
<img src="../img/alerts-log.png" />
@@@

You can also have a look at the payload sent to the Otoroshi server by clicking the `content` button

@@@ div { .centered-img }
<img src="../img/alerts-log-content.png" />
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
