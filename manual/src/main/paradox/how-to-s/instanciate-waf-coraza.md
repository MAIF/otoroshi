# Instanciate a WAF with Coraza

Sometimes you may want to secure an app with a [Web Appplication Firewall (WAF)](https://en.wikipedia.org/wiki/Web_application_firewall) and apply the security rules from the [OWASP Core Rule Set](https://owasp.org/www-project-modsecurity-core-rule-set/). To allow that, we integrated [the Coraza WAF](https://coraza.io/) in Otoroshi through a plugin that uses the WASM version of Coraza.

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create a WAF configuration

first, go on [the features page of otoroshi](http://otoroshi.oto.tools:8080/bo/dashboard/features) and then click on the [Coraza WAF configs. item](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/coraza-waf/coraza-configs). 

Now create a new configuration, give it a name and a description, ensure that you enabled the `Inspect req/res body` flag and save your configuration.

The corresponding admin api call is the following :

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/apis/coraza-waf.extensions.otoroshi.io/v1/coraza-configs' \
  -u admin-api-apikey-id:admin-api-apikey-secret -H 'Content-Type: application/json' -d '
{
  "id": "coraza-waf-demo",
  "name": "My blocking WAF",
  "description": "An awesome WAF",
  "inspect_body": true,
  "config": {
    "directives_map": {
      "default": [
        "Include @recommended-conf",
        "Include @crs-setup-conf",
        "Include @owasp_crs/*.conf",
        "SecRuleEngine DetectionOnly"
      ]
    },
    "default_directives": "default",
    "per_authority_directives": {}
  }
}'
```

### Configure Coraza and the OWASP Core Rule Set

Now you can easily configure the coraza WAF in the `json` config. section. By default it should look something like :

```json
{
  "directives_map": {
    "default": [
      "Include @recommended-conf",
      "Include @crs-setup-conf",
      "Include @owasp_crs/*.conf",
      "SecRuleEngine DetectionOnly"
    ]
  },
  "default_directives": "default",
  "per_authority_directives": {}
}
```

You can find anything about it in [the documentation of Coraza](https://coraza.io/docs/tutorials/introduction/).

here we have the basic setup to apply the OWASP core rule set in detection mode only. 
So each time Coraza will find something weird in a request, it will only log it but let the request pass.
 We can enable blocking by setting `"SecRuleEngine On"`

we can also deny access to the `/admin` uri by adding the following directive

```json
"SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny\""
```

You can also provide multiple profile of rules in the `directives_map` with different names and use the `per_authority_directives` object to map hostnames to a specific profile.

the corresponding admin api call is the following :

```sh
curl -X PUT 'http://otoroshi-api.oto.tools:8080/apis/coraza-waf.extensions.otoroshi.io/v1/coraza-configs/coraza-waf-demo' \
  -u admin-api-apikey-id:admin-api-apikey-secret -H 'Content-Type: application/json' -d '
{
  "id": "coraza-waf-demo",
  "name": "My blocking WAF",
  "description": "An awesome WAF",
  "inspect_body": true,
  "config": {
    "directives_map": {
      "default": [
        "Include @recommended-conf",
        "Include @crs-setup-conf",
        "Include @owasp_crs/*.conf",
        "SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny\"",
        "SecRuleEngine On"
      ]
    },
    "default_directives": "default",
    "per_authority_directives": {}
  }
}'
```

### Add the WAF plugin on your route

Now you can create a new route that will use your WAF configuration. Let say we want a route on `http://wouf.oto.tools:8080` to goes to `https://www.otoroshi.io`. Now add the `Coraza WAF` plugin to your route and in the configuration select the configuration you created previously.

the corresponding admin api call is the following :

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' -d '
{
  "id": "route_demo",
  "name": "WAF route",
  "description": "A new route with a WAF enabled",
  "frontend": {
    "domains": [
      "wouf.oto.tools"
    ]
  },
  "backend": {
    "targets": [
      {
        "hostname": "www.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.wasm.proxywasm.NgCorazaWAF",
      "config": {
        "ref": "coraza-waf-demo"
      },
      "plugin_index": {
        "validate_access": 0,
        "transform_request": 0,
        "transform_response": 0
      }
    },
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "plugin_index": {
        "transform_request": 1
      }
    }
  ]
}'
```

### Try to use an exploit ;)

let try to trigger Coraza with a Log4Shell crafted request:

```sh
curl 'http://wouf.oto.tools:9999' -H 'foo: ${jndi:rmi://foo/bar}' --include

HTTP/1.1 403 Forbidden
Date: Thu, 25 May 2023 09:47:04 GMT
Content-Type: text/plain
Content-Length: 0

```

or access to `/admin`

```sh
curl 'http://wouf.oto.tools:9999/admin' --include

HTTP/1.1 403 Forbidden
Date: Thu, 25 May 2023 09:47:04 GMT
Content-Type: text/plain
Content-Length: 0

```

if you look at otoroshi logs you will find something like :

```log
[error] otoroshi-proxy-wasm - [client "127.0.0.1"] Coraza: Warning. Potential Remote Command Execution: Log4j / Log4shell 
  [file "@owasp_crs/REQUEST-944-APPLICATION-ATTACK-JAVA.conf"] [line "10608"] [id "944150"] [rev ""] 
  [msg "Potential Remote Command Execution: Log4j / Log4shell"] [data ""] [severity "critical"] 
  [ver "OWASP_CRS/4.0.0-rc1"] [maturity "0"] [accuracy "0"] [tag "application-multi"] 
  [tag "language-java"] [tag "platform-multi"] [tag "attack-rce"] [tag "OWASP_CRS"] 
  [tag "capec/1000/152/137/6"] [tag "PCI/6.5.2"] [tag "paranoia-level/1"] [hostname "wwwwouf.oto.tools"] 
  [uri "/"] [unique_id "uTYakrlgMBydVGLodbz"]
[error] otoroshi-proxy-wasm - [client "127.0.0.1"] Coraza: Warning. Inbound Anomaly Score Exceeded (Total Score: 5) 
  [file "@owasp_crs/REQUEST-949-BLOCKING-EVALUATION.conf"] [line "11029"] [id "949110"] [rev ""] 
  [msg "Inbound Anomaly Score Exceeded (Total Score: 5)"] 
  [data ""] [severity "emergency"] [ver "OWASP_CRS/4.0.0-rc1"] [maturity "0"] [accuracy "0"] 
  [tag "anomaly-evaluation"] [hostname "wwwwouf.oto.tools"] [uri "/"] [unique_id "uTYakrlgMBydVGLodbz"]
[info] otoroshi-proxy-wasm - Transaction interrupted tx_id="uTYakrlgMBydVGLodbz" context_id=3 action="deny" phase="http_response_headers"
...
[error] otoroshi-proxy-wasm - [client "127.0.0.1"] Coraza: Warning.  [file ""] [line "12914"] 
  [id "101"] [rev ""] [msg ""] [data ""] [severity "emergency"] [ver ""] [maturity "0"] [accuracy "0"] 
  [hostname "wwwwouf.oto.tools"] [uri "/admin"] [unique_id "mqXZeMdzRaVAqIiqvHf"]
[info] otoroshi-proxy-wasm - Transaction interrupted tx_id="mqXZeMdzRaVAqIiqvHf" context_id=2 action="deny" phase="http_request_headers"
```

### Generated events

each time Coraza will generate log about vunerability detection, an event will be generated in otoroshi and exporter through the usual data exporter way. The event will look like :

```json
{
  "@id" : "86b647450-3cc7-42a9-aaec-828d261a8c74",
  "@timestamp" : 1684938211157,
  "@type" : "CorazaTrailEvent",
  "@product" : "otoroshi",
  "@serviceId" : "--",
  "@service" : "--",
  "@env" : "prod",
  "level" : "ERROR",
  "msg" : "Coraza: Warning. Potential Remote Command Execution: Log4j / Log4shell",
  "fields" : {
    "hostname" : "wouf.oto.tools",
    "maturity" : "0",
    "line" : "10608",
    "unique_id" : "oNbisKlXWaCdXntaUpq",
    "tag" : "paranoia-level/1",
    "data" : "",
    "accuracy" : "0",
    "uri" : "/",
    "rev" : "",
    "id" : "944150",
    "client" : "127.0.0.1",
    "ver" : "OWASP_CRS/4.0.0-rc1",
    "file" : "@owasp_crs/REQUEST-944-APPLICATION-ATTACK-JAVA.conf",
    "msg" : "Potential Remote Command Execution: Log4j / Log4shell",
    "severity" : "critical"
  },
  "raw" : "[client \"127.0.0.1\"] Coraza: Warning. Potential Remote Command Execution: Log4j / Log4shell [file \"@owasp_crs/REQUEST-944-APPLICATION-ATTACK-JAVA.conf\"] [line \"10608\"] [id \"944150\"] [rev \"\"] [msg \"Potential Remote Command Execution: Log4j / Log4shell\"] [data \"\"] [severity \"critical\"] [ver \"OWASP_CRS/4.0.0-rc1\"] [maturity \"0\"] [accuracy \"0\"] [tag \"application-multi\"] [tag \"language-java\"] [tag \"platform-multi\"] [tag \"attack-rce\"] [tag \"OWASP_CRS\"] [tag \"capec/1000/152/137/6\"] [tag \"PCI/6.5.2\"] [tag \"paranoia-level/1\"] [hostname \"wouf.oto.tools\"] [uri \"/\"] [unique_id \"oNbisKlXWaCdXntaUpq\"]\n",
}
```