
# Otoroshi certs. to Kubernetes secrets synchronizer

## Infos

* plugin type: `job`
* configuration root: `KubernetesConfig`

## Description

This plugin syncs. Otoroshi certs to Kubernetes TLS secrets

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```



## Default configuration

```json
{
  "KubernetesConfig" : {
    "endpoint" : "https://kube.cluster.dev",
    "token" : "xxx",
    "userPassword" : "user:password",
    "caCert" : "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    "trust" : false,
    "namespaces" : [ "*" ],
    "labels" : { },
    "namespacesLabels" : { },
    "ingressClasses" : [ "otoroshi" ],
    "defaultGroup" : "default",
    "ingresses" : true,
    "crds" : true,
    "coreDnsIntegration" : false,
    "coreDnsIntegrationDryRun" : false,
    "kubeLeader" : false,
    "restartDependantDeployments" : true,
    "watch" : true,
    "syncDaikokuApikeysOnly" : false,
    "kubeSystemNamespace" : "kube-system",
    "coreDnsConfigMapName" : "coredns",
    "coreDnsDeploymentName" : "coredns",
    "corednsPort" : 53,
    "otoroshiServiceName" : "otoroshi-service",
    "otoroshiNamespace" : "otoroshi",
    "clusterDomain" : "cluster.local",
    "syncIntervalSeconds" : 60,
    "coreDnsEnv" : null,
    "watchTimeoutSeconds" : 60,
    "watchGracePeriodSeconds" : 5,
    "mutatingWebhookName" : "otoroshi-admission-webhook-injector",
    "validatingWebhookName" : "otoroshi-admission-webhook-validation",
    "meshDomain" : "otoroshi.mesh",
    "openshiftDnsOperatorIntegration" : false,
    "openshiftDnsOperatorCoreDnsNamespace" : "otoroshi",
    "openshiftDnsOperatorCoreDnsName" : "otoroshi-dns",
    "openshiftDnsOperatorCoreDnsPort" : 5353,
    "kubeDnsOperatorIntegration" : false,
    "kubeDnsOperatorCoreDnsNamespace" : "otoroshi",
    "kubeDnsOperatorCoreDnsName" : "otoroshi-dns",
    "kubeDnsOperatorCoreDnsPort" : 5353,
    "templates" : {
      "service-group" : { },
      "service-descriptor" : { },
      "apikeys" : { },
      "global-config" : { },
      "jwt-verifier" : { },
      "tcp-service" : { },
      "certificate" : { },
      "auth-module" : { },
      "script" : { },
      "data-exporters" : { },
      "organizations" : { },
      "teams" : { },
      "admins" : { },
      "webhooks" : { }
    }
  }
}
```




