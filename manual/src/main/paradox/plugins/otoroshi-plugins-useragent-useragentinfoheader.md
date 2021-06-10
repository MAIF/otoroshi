
# User-Agent header

## Infos

* plugin type: `transformer`
* configuration root: `UserAgentInfoHeader`

## Description

This plugin will sent informations extracted by the User-Agent details extractor to the target service in a header.

This plugin can accept the following configuration

```json
{
  "UserAgentInfoHeader": {
    "headerName": "X-User-Agent-Info" // header in which info will be sent
  }
}
```



## Default configuration

```json
{
  "UserAgentInfoHeader" : {
    "headerName" : "X-User-Agent-Info"
  }
}
```




