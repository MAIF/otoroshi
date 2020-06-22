# Analytics

Each action and request on Otoroshi creates events that can be sent outside of Otoroshi for further usage. Those events can be sent using a webhook and/or through a Kafka topic.

## Push events to Elasticsearch

@@@ warning
Otoroshi supports only Elasticsearch versions under 7.0
@@@

You can use elastic search to store otoroshi events. To do this you have to configure the access to elasticsearch from `settings (cog icon) / Danger Zone` and expand the `Analytics: Elastic cluster (write)` section.

@@@ div { .centered-img }
<img src="../img/push-to-elastic.png" />
@@@

## Read events from Elasticsearch

You can use elastic search to store otoroshi events. To do this you have to configure the access to elasticsearch from `settings (cog icon) / Danger Zone` and expand the `Analytics: Elastic dashboard datasource (read)` section.

@@@ div { .centered-img }
<img src="../img/push-to-elastic.png" />
@@@

## Push events to WebHooks

Go to `settings (cog icon) / Danger Zone` and expand the `Analytics: Webhooks` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-4-analytics.png" />
@@@

Here you can configure the URL of the webhook and its headers if needed.

## Push events to Kafka

Events can also be sent through a Kafka topic. Go to `settings (cog icon) / Danger Zone` and expand the `Analytics: Kafka` section.

@@@ div { .centered-img }
<img src="../img/danger-zone-5-kafka.png" />
@@@

Fill the form, default values for topic names are :

* `otoroshi-alerts`
* `otoroshi-analytics`
* `otoroshi-audits`

@@@ warning
If you use trustore/keystore to access your kafka instances, the paths should be absolute and refers to host paths. You can also choose a client certificate from otoroshi for client authentication.
@@@
