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
