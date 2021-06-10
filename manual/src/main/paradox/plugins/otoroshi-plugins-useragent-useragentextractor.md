
# User-Agent details extractor

## Infos

* plugin type: `preroute`
* configuration root: `UserAgentInfo`

## Description

This plugin extract informations from User-Agent header such as browsser version, OS version, etc.
The informations are store in plugins attrs for other plugins to use

This plugin can accept the following configuration

```json
{
  "UserAgentInfo": {
    "log": false // will log user-agent details
  }
}
```



## Default configuration

```json
{
  "UserAgentInfo" : {
    "log" : false
  }
}
```





