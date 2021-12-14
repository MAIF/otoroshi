# Configure the Danger zone

@@@ warning
This section is under rewrite. The following content is deprecated and UI may have changed
@@@

Now that you have an actual admin account, go to `setting (cog icon) / Danger Zone` in order to configure your Otoroshi instance.

@@@ div { .centered-img }
<img src="../img/go-to-danger-zone.png" />
@@@

## Commons settings

This part allows you to configure various things :

* `No Auth0 login` => allow you to disabled Auth0 login to the Otoroshi admin dashboard
* `API read only` => disable `writes` on the Otoroshi admin api
* `Use HTTP streaming` => use http streaming for each response. It should always be true
* `Auto link default` => when no group is specified on a service, it will be assigned to default one
* `Use circuit breakers` => allow usage of circuit breakers for each service
* `Log analytics on servers` => all analytics will be logged on the servers
* `Use new http client as the default Http client` => all http call will use the new http client client by default
* `Enable live metrics` => enable live metrics in the Otoroshi cluster. Performs a lot of writes in the datastore
* `Digitus medius` => change the character of endless HTTP responses from `0` to `🖕`
* `Limit concurrent requests` => allow you to specify a max number of concurrent requests on an Otoroshi instance to avoid overloading
* `Max concurrent requests` => max allowed number of concurrent requests on an Otoroshi instance to avoid overloading
* `Max HTTP/1.0 response size` => max size of an HTTP/1.0 responses, because they are memory mapped
* `Max local events` => number of events stored localy (alerts and audits)
* `lines` => at least one (`prod`). for other, it will allow you to declare urls like `service.line.domain.tld`. For prod it will be `service.domain.tld`

@@@ div { .centered-img }
<img src="../img/danger-zone-1-commons.png" />
@@@

## Whitelist / blacklist settings

Otoroshi is capable of filtering request by ip address, allowing or blocking requests.

Otoroshi also provides a fun feature called `Endless HTTP responses`. If you put an ip address in that field, then, for any http request on Otoroshi, every response will be 128 GB of `0`.

@@@ div { .centered-img }
<img src="../img/danger-zone-2-whitelist-blacklist.png" />
@@@

@@@ note
Note that you may provide ip address with wildcard like the following `42.42.*.42` or `42.42.42.*` or `42.42.*.*`
@@@

## Global throttling settings

Otoroshi is capable of managing throttling at a global level. Here you can configure number of authorized requests per second on a single Otoroshi instance and the number of authorized request per second for a unique ip address.

@@@ div { .centered-img }
<img src="../img/danger-zone-3-throttling.png" />
@@@

## Analytics settings

One on the major features of Otoroshi is being able of generating internal events. Those events are not stored in Otoroshi's datastore but can be sent using `WebHooks`. You can configure those `WebHooks` from the `Danger Zone`.

Otoroshi is also capable of reading some analytics and displays it from another MAIF product called `Omoïkane`. As Omoikane is not publicly available yet, is capable of storing events in an [Elastic](https://www.elastic.co/) cluster. For more information about analytics and what it does, just go to the @ref:[detailed chapter](../integrations/analytics.md)

## Kafka settings

One on the major features of Otoroshi is being able of generating internal events. These events are not stored in Otoroshi's datastore but can be sent using a [Kafka message broker](https://kafka.apache.org/). You can configure Kafka access from the `Danger Zone`.

By default, Otoroshi's alert events will be sent on `otoroshi-alerts` topic, Otoroshi's audit events will be sent on `otoroshi-audits` topic and  Otoroshi's traffic events will be sent on `otoroshi-analytics` topic.

@@@ warning
Keystore and truststore paths are optional local path on the server hosting Otoroshi
@@@

@@@ div { .centered-img }
<img src="../img/danger-zone-5-kafka.png" />
@@@

For more information about Kafka integration and what it does, just go to the @ref:[detailed chapter](../integrations/analytics.md)

## Alerts settings

Each time a dangerous action or something unusual is performed on Otoroshi, it will create an alert and store it. You can be notified for each of these alerts using `WebHooks` or emails. To do so, just add the `WebHook` URL and optional headers in the `Danger Zone` or any email address you want (you can add more than one email address).

You can enable mutual authentication via the `Use mTLS` button and add your certificates. The `TLS loose` option will block all untrustful ssl configs, the `TrustAll` option allows any server certificates even the self-signed ones.

@@@ div { .centered-img }
<img src="../img/danger-zone-6-alerts.png" />
@@@

## StatsD settings

Otoroshi is capable of sending internal metrics to a StatsD agent. Just put the host and port of you StatsD agent in the `Danger Zone` to collect these metrics. If you using [Datadog](https://www.datadoghq.com), don't forget to check the dedicated button :)

@@@ div { .centered-img }
<img src="../img/danger-zone-7-statsd.png" />
@@@

For more information about StatsD integration and what it does, just go to the @ref:[detailed chapter](../integrations/statsd.md)

## Mailer settings

If you want to send emails for every alert generated by Otoroshi, you need to configure your Mailgun credentials in the `Danger Zone`. These parameters are provided in you Mailgun domain dashboard (i.e. https://app.mailgun.com/app/domains/my.domain.oto.tools) in the information section.

@@@ div { .centered-img }
<img src="../img/danger-zone-9-mailgun.png" />
@@@

For more information about Mailgun integration and what it does, just go to the @ref:[detailed chapter](../integrations/mailgun.md)

## CleverCloud settings

As we built our products to run on Clever-Cloud, Otoroshi has a close integration with Clever-Cloud. In this section of `Danger Zone` you can configure how to access Clever-Cloud API.

To generate the needed value, please refers to [Clever-Cloud documentation](https://www.clever-cloud.com/doc/clever-cloud-apis/cc-api/)

@@@ div { .centered-img }
<img src="../img/danger-zone-10-clevercloud.png" />
@@@

For more information about Clever-Cloud integration and what it does, just go to the @ref:[detailed chapter](../integrations/clevercloud.md)

## Import / exports and panic mode

For more details about imports and exports, please go to the @ref:[dedicated chapter](../usage/8-importsexports.md)

About panic mode, it's an unusual feature that allows you to discard all current admin. sessions, allows only admin users with U2F devices to log back, and pass the API in read-only mode. Only a person who has access to Otoroshi's datastore will be able to turn it back on.

@@@ div { .centered-img }
<img src="../img/danger-zone-11-bottom.png" />
@@@
