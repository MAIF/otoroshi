
@@@ div { .plugin .plugin-hidden .plugin-kind-transformer }

# Basic Auth. caller

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `transformer`
* configuration root: `BasicAuthCaller`

## Description

This plugin can be used to call api that are authenticated using basic auth.

This plugin accepts the following configuration

{
  "username" : "the_username",
  "password" : "the_password",
  "headerName" : "Authorization",
  "headerValueFormat" : "Basic %s"
}



## Default configuration

```json
{
  "username" : "the_username",
  "password" : "the_password",
  "headerName" : "Authorization",
  "headerValueFormat" : "Basic %s"
}
```





@@@

