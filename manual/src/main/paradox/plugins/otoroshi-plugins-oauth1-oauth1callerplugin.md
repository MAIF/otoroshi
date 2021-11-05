
# OAuth1 caller

## Infos

* plugin type: `transformer`
* configuration root: `OAuth1Caller`

## Description

This plugin can be used to call api that are authenticated using OAuth1.
 Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
 or via the configuration of this plugin.



## Default configuration

```json
{
  "OAuth1Caller" : {
    "algo" : "HmacSHA512"
  }
}
```





