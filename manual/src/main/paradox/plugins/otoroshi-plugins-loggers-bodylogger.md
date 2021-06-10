
# Body logger

## Infos

* plugin type: `transformer`
* configuration root: `BodyLogger`

## Description

This plugin can log body present in request and response. It can just logs it, store in in the redis store with a ttl and send it to analytics.
It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.

This plugin can accept the following configuration

```json
{
  "BodyLogger": {
    "enabled": true, // enabled logging
    "log": true, // just log it
    "store": false, // store bodies in datastore
    "ttl": 300000,  // store it for some times (5 minutes by default)
    "sendToAnalytics": false, // send bodies to analytics
    "maxSize": 5242880, // max body size (body will be cut after that)
    "password": "password", // password for the ui, if none, it's public
    "filter": { // log only for some status, method and paths
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
  "BodyLogger" : {
    "enabled" : true,
    "log" : true,
    "store" : false,
    "ttl" : 300000,
    "sendToAnalytics" : false,
    "maxSize" : 5242880,
    "password" : "password",
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




