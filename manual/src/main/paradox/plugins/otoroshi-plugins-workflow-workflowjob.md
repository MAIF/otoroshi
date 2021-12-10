
@@@ div { .plugin .plugin-hidden .plugin-kind-job }

# Workflow job

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `job`
* configuration root: `WorkflowJob`

## Description

Periodically run a custom workflow



## Default configuration

```json
{
  "WorkflowJob" : {
    "input" : {
      "namespace" : "otoroshi",
      "service" : "otoroshi-dns"
    },
    "intervalMillis" : "60000",
    "workflow" : {
      "name" : "some-workflow",
      "description" : "a nice workflow",
      "tasks" : [ {
        "name" : "call-dns",
        "type" : "http",
        "request" : {
          "method" : "PATCH",
          "url" : "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/v1/namespaces/${input.namespace}/services/${input.service}",
          "headers" : {
            "accept" : "application/json",
            "content-type" : "application/json",
            "authorization" : "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
          },
          "tls" : {
            "mtls" : true,
            "trustAll" : true
          },
          "body" : [ {
            "op" : "replace",
            "path" : "/spec/selector",
            "value" : {
              "app" : "otoroshi",
              "component" : "dns"
            }
          } ]
        },
        "success" : {
          "statuses" : [ 200 ]
        }
      } ]
    }
  }
}
```





@@@

