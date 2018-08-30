# Analytics

Each action and request on Otoroshi creates events that can be sent outside of Otoroshi for further usage. Those events can be sent using a webhook and/or through a Kafka topic.

## Elasticsearch

You can use elastic search to store and retrieve events. To do this you have to configure the access to elasticsearch using java system properties or env variable : 

`-Dapp.elastic.url=http://localhost:9200 -Dapp.elastic.user=xxxx -Dapp.elastic.password=xxxx -Dapp.elastic.index=otoroshi-events`

or 

`ELASTIC_URL=http://localhost:9200 ELASTIC_USER=xxxx ELASTIC_PASSWORD=xxxx ELASTIC_INDEX=otoroshi-events`



## WebHooks

Go to `settings (cog icon) / Danger Zone` and expand the `Analytics settings` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-4-analytics.png" />
@@@

Here you can configure two URLs. The first one is the URL where events will be posted (in batch). The second one is a URL used to query the produced events to display analytics pages in Otoroshi. Those URLs must point to a instance of Omoikane or an instance of the @ref:[Elastic connector](../connectors/elastic.md). The Elastic connector is just a thin layer of APIs on top of Elastic.

For instance, valid values can be :

```
https://my.elastic.connector.host/api/v1/events
```

You can also provide some security headers if needed.

## Kafka

Events can also be sent through a Kafka topic. Go to `settings (cog icon) / Danger Zone` and expand the `Kafka  settings` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-5-kafka.png" />
@@@

Fill the form, default values for topic names are :

* `otoroshi-alerts`
* `otoroshi-analytics`
* `otoroshi-audits`

@@@ warning
If you use trustore/keystore to access your kafka instances, the paths should be absolute and refers to host paths.
@@@
