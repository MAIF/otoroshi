
# Response Cache

## Infos

* plugin type: `transformer`
* configuration root: `ResponseCache`

## Description

This plugin can cache responses from target services in the otoroshi datasstore
It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.

This plugin can accept the following configuration

```json
{
  "ResponseCache": {
    "enabled": true, // enabled cache
    "ttl": 300000,  // store it for some times (5 minutes by default)
    "maxSize": 5242880, // max body size (body will be cut after that)
    "autoClean": true, // cleanup older keys when all bigger than maxSize
    "filter": { // cache only for some status, method and paths
      "statuses": [],
      "methods": [],
      "paths": [],
      "not": {
        "statuses": [],
        "methods": [],
        "paths": []
      }
    }
  }
}
```



## Default configuration

```json
{
  "ResponseCache" : {
    "enabled" : true,
    "ttl" : 3600000,
    "maxSize" : 52428800,
    "autoClean" : true,
    "filter" : {
      "statuses" : [ ],
      "methods" : [ ],
      "paths" : [ ],
      "not" : {
        "statuses" : [ ],
        "methods" : [ ],
        "paths" : [ ]
      }
    }
  }
}
```





