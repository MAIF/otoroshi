
# Access log (CLF)

## Infos

* plugin type: `transformer`
* configuration root: `AccessLog`

## Description

With this plugin, any access to a service will be logged in CLF format.

Log format is the following:

`"$service" $clientAddress - "$userId" [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referer" "$userAgent" $http $duration $errorMsg`

The plugin accepts the following configuration

```json
{
  "AccessLog": {
    "enabled": true,
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
  "AccessLog" : {
    "enabled" : true,
    "statuses" : [ ],
    "paths" : [ ],
    "methods" : [ ],
    "identities" : [ ]
  }
}
```




