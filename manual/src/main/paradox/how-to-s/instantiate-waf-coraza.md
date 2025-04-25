# Instantiate a WAF with Coraza

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.wasm.proxywasm.NgCorazaWAF">Coraza WAF</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/built-in-plugins.
html#otoroshi.next.plugins.OverrideHost">Override Host Header</a>
</div>

Sometimes you may want to secure an app with a [Web Appplication Firewall (WAF)](https://en.wikipedia.org/wiki/Web_application_firewall) and apply the security rules from the [OWASP Core Rule Set](https://owasp.org/www-project-modsecurity-core-rule-set/). To allow that, we integrated [the Coraza WAF](https://coraza.io/) in Otoroshi through a plugin that uses the WASM version of Coraza.

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create a WAF configuration

first, go on [the features page of otoroshi](http://otoroshi.oto.tools:8080/bo/dashboard/features) and then click on the [Coraza WAF configs. item](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/coraza-waf/coraza-configs). 

Now, create a new configuration by giving it a name and description. Make sure to enable the `Inspect request/response body` flag, then save your configuration.

The corresponding Admin API call is as follows:

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/apis/coraza-waf.extensions.otoroshi.io/v1/coraza-configs' \
  -u admin-api-apikey-id:admin-api-apikey-secret -H 'Content-Type: application/json' -d '
{
  "id": "coraza-wafdemo",
  "name": "My blocking WAF",
  "description": "An awesome WAF",
  "inspect_body": true,
  "is_blocking_mode": true,
  "include_owasp_crs": true,
  "directives": []
}'
```

### Configure Coraza and the OWASP Core Rule Set

Now you can easily configure the coraza WAF in the `directives` field.

You can find anything about OWASP core rules in [the documentation of Coraza](https://coraza.io/docs/tutorials/introduction/).

Here, we have the basic setup to apply the OWASP Core Rule Set in detection mode only.
This means that whenever Coraza detects something suspicious in a request, it will simply log it and allow the request to proceed.
To enable blocking, set is_blocking_mode to true.

We can also deny access to the `/admin` uri by adding the following directive

```json
"SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny,msg:'ADMIN PATH forbidden'\""
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

### Generated events

each time Coraza will generate log about vunerability detection, an event will be generated in otoroshi and exporter through the usual data exporter way. The event will look like :

```json
{
    "@id": "c9a7d86d0-c382-4831-89b8-4bb9c4eb96a6",
    "@timestamp": 1744964454673,
    "@type": "CorazaTrailEvent",
    "@product": "otoroshi",
    "@serviceId": "--",
    "@service": "--",
    "@env": "prod",
    "route" {},
    "msg": [
        {
            "message": "XSS Attack Detected via libinjection",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 941100,
                "file": "@owasp_crs/REQUEST-941-APPLICATION-ATTACK-XSS.conf",
                "severity": 2
            }
        },
        {
            "message": "XSS Filter - Category 1: Script Tag Vector",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 941110,
                "file": "@owasp_crs/REQUEST-941-APPLICATION-ATTACK-XSS.conf",
                "severity": 2
            }
        },
        {
            "message": "NoScript XSS InjectionChecker: HTML Injection",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 941160,
                "file": "@owasp_crs/REQUEST-941-APPLICATION-ATTACK-XSS.conf",
                "severity": 2
            }
        },
        {
            "message": "Javascript method detected",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 941390,
                "file": "@owasp_crs/REQUEST-941-APPLICATION-ATTACK-XSS.conf",
                "severity": 2
            }
        },
        {
            "message": "SQL Injection Attack Detected via libinjection",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 942100,
                "file": "@owasp_crs/REQUEST-942-APPLICATION-ATTACK-SQLI.conf",
                "severity": 2
            }
        },
        {
            "message": "Inbound Anomaly Score Exceeded (Total Score: 25)",
            "uri": "/admiasdasdn",
            "rule": {
                "id": 949110,
                "file": "@owasp_crs/REQUEST-949-BLOCKING-EVALUATION.conf",
                "severity": 0
            }
        }
    ]
}
```