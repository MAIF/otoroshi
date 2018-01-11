# Analytics

Each actions and request on Otoroshi create events that can be sent ouside of Otoroshi for further usage. Those events can be sent using a webhook or through a Kafka topic. 

## WebHooks

Go to `settings (cog icon) / Danger Zone` and expand the `Analytics settings` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-4-analytics.png" />
@@@

Here you can configure two URL. The first one is the URL where events will be posted (in batch). The second one is a URL used to query the produced events to display analytics pages in Otoroshi. Those URLs must point to a instance of Omoikane or an instance of the @ref:[Elastic connector](../connectors/elastic.md). The Elastic connector is just a thin layer of APIs on top of Elastic.

for instance, valid values can be

```
https://my.elastic.connector.host/api/v1/events
```

you can also provide some security headers if needed

## Kafka 

Events can also be sent through a Kafka topic. Go to `settings (cog icon) / Danger Zone` and expand the `Kafka  settings` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-5-kafka.png" />
@@@

and fill the form. Default values for topic names are 

* `otoroshi-alerts`
* `otoroshi-analytics`
* `otoroshi-audits`

@@@ warning
If you use trustore/keystore to access your kafka instances, the paths should be absolute and refers to host paths.
@@@