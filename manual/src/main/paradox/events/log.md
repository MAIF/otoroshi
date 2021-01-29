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