# Export events to Elasticsearch

### Cover by this tutorial
- [Before you start](#before-you-start)
- [Deploy a Elasticsearch and kibana stack on Docker](#deploy-a-elasticsearch-and-kibana-stack-on-docker)
- [Create an Elasticsearch exporter](#create-an-elasticsearch-exporter)
- [Testing your configuration](#testing-your-configuration)
- [Advanced usage](#advanced-usage)
- [Debug your configuration](#debug-your-configuration)
- 

@@@ warning
TODO - schema
@@@

### Before you start

<!-- @@snip[init-otoroshi.md](../snippets/init-otoroshi.md)  -->

### Deploy a Elasticsearch and kibana stack on Docker

Let's start by create an Elasticsearch and Kibana stack on our machine (if it's already done for you, you can skip this section).

To start an Elasticsearch container for development or testing, run:

```sh
docker network create elastic
docker pull docker.elastic.co/elasticsearch/elasticsearch:7.15.1
docker run --name es01-test --net elastic -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:7.15.1
```

```sh
docker pull docker.elastic.co/kibana/kibana:7.15.1
docker run --name kib01-test --net elastic -p 5601:5601 -e "ELASTICSEARCH_HOSTS=http://es01-test:9200" docker.elastic.co/kibana/kibana:7.15.1
```

To access Kibana, go to *http://localhost:5601*.

### Create an Elasticsearch exporter

Let's create an exporter. The exporter will export by default all events generate by Otoroshi.

1. Go ahead, and navigate to http://otoroshi.oto.tools:8080
2. Click on the cog icon on the top right
3. Then `Exporters` button
4. And add a new configuration when clicking on the `Add item` button
5. Select the `elastic` in the `type` selector field
6. Jump to `Exporter config`
7. Set the following values: `Cluster URI` -> `http://localhost:9200`

Then test your configuration with `Check connection` button. This should output a modal with Elasticsearch version and the number of loaded docs.

Save at the bottom page and enable the exporter (on the top of the page or in list).

### Testing your configuration

One simple way to test is to setup the reading of the elasticsearch by Otoroshi.

Navigate to the danger zone (click on the cog on the right top and scroll to `danger zone`).

Jump to the `Analytics: Elastic dashboard datasource (read)` section.

Set the following values : `Cluster URI` -> `http://localhost:9200`

Then click on the `Check connection`. This should ouput the same result as the previous part. Save the global configuration and navigate to *http://otoroshi.oto.tools:8080/bo/dashboard/stats*.

This should output a list of graphs.

### Advanced usage

By default, an exporter handle all events from Otoroshi. In some case, you need to filter the events to send to elasticsearch.

To filter the events, jump to the `Filtering and projection` field in exporter view. Otoroshi supports to include a kind of events or to exclude a list of events. 

An example which include only events with a field `@type` of value `AlertEvent` :
```json
{
    "include": [
        { "@type": "AlertEvent" }
    ],
    "exclude": []
}
```
An example which exclude only events with a field `@type` of value `GatewayEvent` :
```json
{
    "exclude": [
        { "@type": "GatewayEvent" }
    ],
    "include": []
}
```

The next field is the `Projection`. This field is a json when you can list the fields to keep for each events.

```json
{
 "@type": true,
 "@timestamp": true,
 "@id": true
}
```
With this example, only `@type`, `@timestamp` and `@id` will be send to ES.

### Debug your configuration

#### Missing user rights on Elasticsearch

When creating an exporter, Otoroshi try to join the index route of the elasticsearch instance. If you have a specific management access rights on Elasticsearch, you have two possiblities :

- set a full access to the user used in Otoroshi for write in Elasticsearch
- set the version of Elasticsearch inside the `Version` field of your exporter.

#### None events appear in your Elasticsearch

When creating an exporter, Otoroshi try to push the index template on Elasticsearch. If the post failed, Otoroshi will fail for each push of events and your database will keep empty. 

To fix this problem, you can try to send the index template with the `Manually apply index template` button in your exporter.