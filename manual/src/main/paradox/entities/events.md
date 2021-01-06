## Monitoring services

Once you have declared services, you can monitor them with Otoroshi.

@@@ warning
You have to use [Elastic](https://www.elastic.co) to enable analytics features in Otoroshi
@@@

<!-- TODO: data exporter kill elastic write config-->
Once you have setup @ref:[Otoroshi events push to an elastic cluster](../integrations/analytics.md) (through webhooks, kafka, or elastic integration) you can setup Otoroshi events read from an elastic cluster. Go to `settings (cog icon) / Danger Zone` and expand the `Analytics: Elastic cluster (write)` section.

@@@ div { .centered-img }
<img src="../img/push-to-elastic.png" />
@@@

### Service healthcheck

If you have defined an health check URL in the service descriptor, you can access the health check page from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-healthcheck.png" />
@@@

### Service live stats

You can also monitor live stats like total of served request, average response time, average overhead, etc. The live stats page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-live-stats.png" />
@@@

### Service analytics

You can also get some aggregated metrics. The analytics page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-analytics.png" />
@@@

## Auditing Otoroshi

With Otoroshi, any admin action and any sucpicious/alert action is recorded. These records are stored in Otoroshi's datastore (only the last n records, defined by the `app.events.maxSize` @ref:[config key](../firstrun/configfile.md)). All the records can be send through the analytics mechanism (WebHook, Kafka, Elastic) for external and/or further usage. We recommand sending away those records for security reasons.

@@@ warning
You have to use [Elastic](https://www.elastic.co) to enable analytics features in Otoroshi. See @ref:[Elastic setup section](../integrations/analytics.md)
@@@

### Audit trail

To see last `app.events.maxSize` admin actions on Otoroshi from the UI, go to `settings (cog icon) / Audit log`.

@@@ div { .centered-img }
<img src="../img/audit-log.png" />
@@@

### Alerts
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

## Otoroshi global metrics

Otoroshi provide some global metrics about services usage.  Go to `settings (cog icon) / Global Ananlytics`

@@@ warning
You have to use [Elastic](https://www.elastic.co) to enable analytics features in Otoroshi. See @ref:[Elastic setup section](../integrations/analytics.md)
@@@

@@@ div { .centered-img }
<img src="../img/global-analytics.png" />
@@@
