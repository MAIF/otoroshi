
# Kafka access log

## Infos

* plugin type: `transformer`
* configuration root: `KafkaAccessLog`

## Description

With this plugin, any access to a service will be logged as an event in a kafka topic.

The plugin accepts the following configuration

```json
{
  "KafkaAccessLog": {
    "enabled": true,
    "topic": "otoroshi-access-log",
    "statuses": [], // list of status to enable logs, if none, log everything
    "paths": [], // list of paths to enable logs, if none, log everything
    "methods": [], // list of http methods to enable logs, if none, log everything
    "identities": [] // list of identities to enable logs, if none, log everything
  }
}
```



## Default configuration

```json
{
  "KafkaAccessLog" : {
    "enabled" : true,
    "topic" : "otoroshi-access-log",
    "statuses" : [ ],
    "paths" : [ ],
    "methods" : [ ],
    "identities" : [ ]
  }
}
```




