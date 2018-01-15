# Monitoring services

Once you have declared services, you can monitor them with Otoroshi

@@@ warning
You have to use @ref:[the Elastic connector](../connectors/elastic.md) to enable analytics features in Otoroshi
@@@

## Service healthcheck

If you have defined an healthcheck URL in the service descriptor, you can access the healthcheck page from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-healthcheck.png" />
@@@

## Service live stats

You can also monitor live stats like total of served request, average response time, average overhead, etc. The live stats page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-live-stats.png" />
@@@

## Service analytics

You can also get some aggregated metrics. The analytics page can be accessed from the sidebar of the service page.

@@@ div { .centered-img }
<img src="../img/service-analytics.png" />
@@@