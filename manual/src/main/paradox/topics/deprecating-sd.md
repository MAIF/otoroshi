# Sunsetting Service Descriptors

For more than 2 years, after the @ref[rewrite of the proxy engine](./engine.md) in `v1.5.3`, Service Descriptors has been deprecated.

Now (v17.0.0) it's time to go ahead and provide some migration tools to help users getting rid of Service Descriptor in favor of routes before completely removing support of Service Descriptors in v18.0.0 (probably in 2026). Under the hood the new proxy engine already convert your Service Descriptors to Route in order to route traffic to your backends. The idea here is to remove the Service Descriptor entity in favor of the Route entity and let the user adapt their workflows and automations.

If you still have Service Descriptors in you database, otoroshi will warn you in the logs

@@@ div { .centered-img }
<img src="../imgs/sd-migration-logs.png" />
@@@

you will also have a popup displayed in the backoffice from time to time

@@@ div { .centered-img }
<img src="../imgs/sd-migration-popup.png" />
@@@

This toolkit consists of 3 tools that you can use to migrate existing Service Descriptors

## Migration Job

you can enable a job launched after the start of the otoroshi cluster by setting `otoroshi.service-descriptors-migration-job.enabled=true` or `OTOROSHI_SERVICE_DESCRIPTORS_MIGRATION_JOB_ENABLED=true`

@@@ div { .centered-img }
<img src="../imgs/sd-migration-job-auto.png" />
@@@

## Migration API

You can migrate service descriptor to routes by calling the following endpoint

```sh
curl -X POST -H 'Content-Type: application/json' http://otoroshi-api.oto.tools:8080/api/services/service-xxxxxxxx/route -d '{}'
```

## Migration button

on any Service Descriptor, you can click on the `convert to route` button

@@@ div { .centered-img }
<img src="../imgs/sd-migration-ui.png" />
@@@

and just confirm it

@@@ div { .centered-img }
<img src="../imgs/sd-migration-ui-confirm.png" />
@@@
