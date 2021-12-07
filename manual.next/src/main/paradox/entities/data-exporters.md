# Data exporters

The data exporters are the way to export alerts and events from Otoroshi to an external storage.

To try them, you can folllow @ref[this tutorial](../how-to-s/export-alerts-using-mailgun.md).

## Common fields

* `Type`: the type of event exporter
* `Enabled`: enabled or not the exporter
* `Name`: given name to the exporter
* `Description`: the data exporter description
* `Tags`: list of tags associated to the module
* `Metadata`: list of metadata associated to the module

All exporters are split in three parts. The first and second parts are common and the last are specific by exporter.

* `Filtering and projection` : section to filter the list of sent events and alerts. The projection field allows you to export only certain event fields and reduce the size of exported data. It's composed of `Filtering` and `Projection` fields.


**Filtering** is used to **include** or **exclude** some kind of events and alerts. For each include and exclude field, you can add a list of key-value. 

Let's say we only want to keep Otoroshi alerts
```json
{ "include": [{ "@type": "AlertEvent" }] }
```

**Projection** is a list of fields to export. In the case of an empty list, all the fields of an event will be exported. In other case, **only** the listed fields will be exported.

Let's say we only want to keep Otoroshi alerts and only type, timestamp and id of each exported events
```json
{
 "@type": true,
 "@timestamp": true,
 "@id": true
}
```

An other possibility is to **rename** the exported field. This value will be the same but the exported field will have a different name.

Let's say we want to rename all `@id` field with `unique-id` as key

```json
{ "@id": "unique-id" }
```

The last possiblity is to retrieve a sub-object of an event. Let's say we want to get the name of each exported user of events.

```json
{ "user": { "name": true } }
```


* `Queue details`: set of fields to adjust the workers of the exporter. 
  * `Buffer size`: if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with a strategy specified by the user. Keep in memory the number of events.
  * `JSON conversion workers`: number of workers used to transform events to JSON format in paralell
  * `Send workers`: number of workers used to send transformed events
  * `Group size`: chunk up this stream into groups of elements received within a time window (the time window is the next field)
  * `Group duration`: waiting time before sending the group of events. If the group size is reached before the group duration, the events will be instantly sent
  
For the last part, the `Exporter configuration` will be detail individually.

## Matching and projections

@@@ warning
TODO: this section needs to be written
@@@

## Elastic

With this kind of exporter, every matching event will be sent to an elastic cluster (in batch). It is quite useful and can be used in combination with [elastic read in global config](./global-config.html#analytics-elastic-dashboard-datasource-read-)

* `Cluster URI`: Elastic cluster URI
* `Index`: Elastic index 
* `Type`: Event type (not needed for elasticsearch above 6.x)
* `User`: Elastic User (optional)
* `Password`: Elastic password (optional)
* `Version`: Elastic version (optional, if none provided it will be fetched from cluster)
* `Apply template`: Automatically apply index template
* `Check Connection`: Button to test the configuration. It will displayed a modal with checked point, and if the case of it's successfull, it will displayed the found version of the Elasticsearch and the index used
* `Manually apply index template`: try to put the elasticsearch template by calling the api of elasticsearch
* `Show index template`: try to retrieve the current index template presents in elasticsearch
* `Client side temporal indexes handling`: When enabled, Otoroshi will manage the creation of indexes. When it's disabled, Otoroshi will push in the same index
* `One index per`: When the previous field is enabled, you can choose the interval of time between the creation of a new index in elasticsearch 
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch

## Webhook 

* `Alerts hook URL`: url used to post events
* `Hook Headers`: headers add to the post request
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch


## Pulsar 

* `Pulsar URI`: URI of the pulsar server
* `Custom TLS Settings`: Enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch
* `Pulsar tenant`: tenant on the pulsar server
* `Pulsar namespace`:  namespace on the pulsar server
* `Pulsar topic`: topic on the pulsar server

## Kafka 

* `Kafka Servers`: the list of servers to contact to connect the Kafka client with the Kafka cluster
* `Kafka keypass`: the keystore password if you use a keystore/truststore to connect to Kafka cluster
* `Kafka keystore path`: the keystore path on the server if you use a keystore/truststore to connect to Kafka cluster
* `Kafka truststore path`: the truststore path on the server if you use a keystore/truststore to connect to Kafka cluster
* `Custom TLS Settings`: enable the TLS configuration for the communication with Elasticsearch
  * `TLS loose`: if enabled, will block all untrustful ssl configs
  * `TrustAll`: allows any server certificates even the self-signed ones
  * `Client certificates`: list of client certificates used to communicate with elasticsearch
  * `Trusted certificates`: list of trusted certificates received from elasticsearch
* `Kafka topic`: the topic on which Otoroshi alerts will be sent

## Mailer 

Otoroshi supports 5 exporters of email type.

### Console

Nothing to add. The events will be write on the standard output.

### Generic

* `Mailer url`: URL used to push events
* `Headers`: headers add to the push requests
* `Email addresses`: recipients of the emails

### Mailgun

* `EU`: is EU server ? if enabled, *https://api.eu.mailgun.net/* will be used, otherwise, the US URL will be used : *https://api.mailgun.net/*
* `Mailgun api key`: API key of the mailgun account
* `Mailgun domain`: domain name of the mailgun account
* `Email addresses`: recipients of the emails

### Mailjet

* `Public api key`: public key of the mailjet account
* `Private api key`: private key of the mailjet account
* `Email addresses`: recipients of the emails

### Sendgrid

* `Sendgrid api key`: api key of the sendgrid account
* `Email addresses`: recipients of the emails

## File 

* `File path`: path where the logs will be write 
* `Max file size`: when size is reached, Otoroshi will create a new file postfixed by the current timestamp

## Console 

Nothing to add. The events will be write on the standard output.

## Custom 

This type of exporter let you the possibility to write your own exporter with your own rules. To create an exporter, we need to navigate to the plugins page, and to create a new item of type exporter.

When it's done, the exporter will be visible in this list.

* `Exporter config.`: the configuration of the custom exporter.

## Metrics 

This plugin is useful to rewrite the metric labels exposed on the `/metrics` endpoint.

* `Labels`: list of metric labels. Each pair contains an existing field name and the new name.