## Alerts
<!-- FIXME: if dataexporters "ALERTS" not set, it can't works: explain that -->
To see last `app.events.maxSize` alerts on Otoroshi from the UI, go to `settings (cog icon) / Alerts log`.

@@@ div { .centered-img }
<img src="../img/alerts-log.png" />
@@@

You can also have a look at the payload sent to the Otoroshi server by clicking the `content` button

@@@ div { .centered-img }
<img src="../img/alerts-log-content.png" />
@@@

### List of possible alerts

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