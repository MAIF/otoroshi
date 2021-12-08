# Log levels customization

If you want to customize the log level of your otoroshi instances, it's pretty easy to do it using environment variables or configuration file.

## Customize log level for one logger with configuration file

Let say you want to see `DEBUG` messages from the logger `otoroshi-http-handler`.

Then you just have to declare in your otoroshi configuration file

```
otoroshi.loggers {
  ...
  otoroshi-http-handler = "DEBUG"
  ...
}
```

possible levels are `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. Default one is `WARN`.

## Customize log level for one logger with environment variable

Let say you want to see `DEBUG` messages from the logger `otoroshi-http-handler`.

Then you just have to declare an environment variable named `OTOROSHI_LOGGERS_OTOROSHI_HTTP_HANDLER` with value `DEBUG`. The rule is 

```scala
"OTOROSHI_LOGGERS_" + loggerName.toUpperCase().replace("-", "_")
```

possible levels are `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. Default one is `WARN`.

## List of loggers

* `otoroshi-error-handler`
* `otoroshi-http-handler`
* `otoroshi-http-handler-debug`
* `otoroshi-websocket-handler`
* `otoroshi-websocket`
* `otoroshi-websocket-handler-actor`
* `otoroshi-snowmonkey`
* `otoroshi-circuit-breaker`
* `otoroshi-circuit-breaker`
* `otoroshi-worker`
* `otoroshi-http-handler`
* `otoroshi-auth-controller`
* `otoroshi-swagger-controller`
* `otoroshi-u2f-controller`
* `otoroshi-backoffice-api`
* `otoroshi-health-api`
* `otoroshi-stats-api`
* `otoroshi-admin-api`
* `otoroshi-auth-modules-api`
* `otoroshi-certificates-api`
* `otoroshi-pki`
* `otoroshi-scripts-api`
* `otoroshi-analytics-api`
* `otoroshi-import-export-api`
* `otoroshi-templates-api`
* `otoroshi-teams-api`
* `otoroshi-events-api`
* `otoroshi-canary-api`
* `otoroshi-data-exporter-api`
* `otoroshi-services-api`
* `otoroshi-tcp-service-api`
* `otoroshi-tenants-api`
* `otoroshi-global-config-api`
* `otoroshi-apikeys-fs-api`
* `otoroshi-apikeys-fg-api`
* `otoroshi-apikeys-api`
* `otoroshi-statsd-actor`
* `otoroshi-snow-monkey-api`
* `otoroshi-jobs-eventstore-checker`
* `otoroshi-initials-certs-job`
* `otoroshi-alert-actor`
* `otoroshi-alert-actor-supervizer`
* `otoroshi-alerts`
* `otoroshi-apikeys-secrets-rotation-job`
* `otoroshi-loader`
* `otoroshi-api-action`
* `otoroshi-api-action`
* `otoroshi-analytics-writes-elastic`
* `otoroshi-analytics-reads-elastic`
* `otoroshi-events-actor-supervizer`
* `otoroshi-data-exporter`
* `otoroshi-data-exporter-update-job`
* `otoroshi-kafka-wrapper`
* `otoroshi-kafka-connector`
* `otoroshi-analytics-webhook`
* `otoroshi-jobs-software-updates`
* `otoroshi-analytics-actor`
* `otoroshi-analytics-actor-supervizer`
* `otoroshi-analytics-event`
* `otoroshi-env`
* `otoroshi-script-compiler`
* `otoroshi-script-manager`
* `otoroshi-script`
* `otoroshi-tcp-proxy`
* `otoroshi-tcp-proxy`
* `otoroshi-tcp-proxy`
* `otoroshi-custom-timeouts`
* `otoroshi-client-config`
* `otoroshi-canary`
* `otoroshi-redirection-settings`
* `otoroshi-service-descriptor`
* `otoroshi-service-descriptor-datastore`
* `otoroshi-console-mailer`
* `otoroshi-mailgun-mailer`
* `otoroshi-mailjet-mailer`
* `otoroshi-sendgrid-mailer`
* `otoroshi-generic-mailer`
* `otoroshi-clevercloud-client`
* `otoroshi-metrics`
* `otoroshi-gzip-config`
* `otoroshi-regex-pool`
* `otoroshi-ws-client-chooser`
* `otoroshi-akka-ws-client`
* `otoroshi-http-implicits`
* `otoroshi-service-group`
* `otoroshi-data-exporter-config`
* `otoroshi-data-exporter-config-migration-job`
* `otoroshi-lets-encrypt-helper`
* `otoroshi-apkikey`
* `otoroshi-error-template`
* `otoroshi-job-manager`
* `otoroshi-plugins-internal-eventlistener-actor`
* `otoroshi-global-config`
* `otoroshi-jwks`
* `otoroshi-jwt-verifier`
* `otoroshi-global-jwt-verifier`
* `otoroshi-snowmonkey-config`
* `otoroshi-webauthn-admin-datastore`
* `otoroshi-webauthn-admin-datastore`
* `otoroshi-leveldb-datastores`
* `otoroshi-service-datatstore`
* `otoroshi-cassandra-datastores`
* `otoroshi-redis-like-store.error(s"Try to deserialize ${Json.prettyPrint(value)}`
* `otoroshi-globalconfig-datastore`
* `otoroshi-mongo-redis`
* `otoroshi-mongo-datastores`
* `otoroshi-reactive-pg-datastores`
* `otoroshi-reactive-pg-kv`
* `otoroshi-cassandra-datastores`
* `otoroshi-apikey-datastore`
* `otoroshi-datastore`
* `otoroshi-certificate-datastore`
* `otoroshi-simple-admin-datastore`
* `otoroshi-atomic-in-memory-datastore`
* `otoroshi-lettuce-redis`
* `otoroshi-lettuce-redis-cluster`
* `otoroshi-redis-lettuce-datastores`
* `otoroshi-datastores`
* `otoroshi-file-db-datastores`
* `otoroshi-http-db-datastores`
* `otoroshi-s3-datastores`
* `PluginDocumentationGenerator`
* `otoroshi-health-checker`
* `otoroshi-healthcheck-job`
* `otoroshi-healthcheck-local-cache-job`
* `otoroshi-plugins-response-cache`
* `otoroshi-oidc-apikey-config`
* `otoroshi-plugins-maxmind-geolocation-info`
* `otoroshi-plugins-ipstack-geolocation-info`
* `otoroshi-plugins-maxmind-geolocation-helper`
* `otoroshi-plugins-user-agent-helper`
* `otoroshi-plugins-user-agent-extractor`
* `otoroshi-global-el`
* `otoroshi-plugins-oauth1-caller-plugin`
* `otoroshi-dynamic-sslcontext`
* `otoroshi-plugins-access-log-clf`
* `otoroshi-plugins-access-log-json`
* `otoroshi-plugins-kafka-access-log`
* `otoroshi-plugins-kubernetes-client`
* `otoroshi-plugins-kubernetes-ingress-controller-job`
* `otoroshi-plugins-kubernetes-ingress-sync`
* `otoroshi-plugins-kubernetes-crds-controller-job`
* `otoroshi-plugins-kubernetes-crds-sync`
* `otoroshi-cluster`
* `otoroshi-crd-validator`
* `otoroshi-sidecar-injector`
* `otoroshi-plugins-kubernetes-cert-sync`
* `otoroshi-plugins-kubernetes-to-otoroshi-certs-job`
* `otoroshi-plugins-otoroshi-certs-to-kubernetes-secrets-job`
* `otoroshi-apikeys-workflow-job`
* `otoroshi-cert-helper`
* `otoroshi-certificates-ocsp`
* `otoroshi-claim`
* `otoroshi-cert`
* `otoroshi-ssl-provider`
* `otoroshi-cert-data`
* `otoroshi-client-cert-validator`
* `otoroshi-ssl-implicits`
* `otoroshi-saml-validator-utils`
* `otoroshi-global-saml-config`
* `otoroshi-plugins-hmac-caller-plugin`
* `otoroshi-plugins-hmac-access-validator-plugin`
* `otoroshi-plugins-hasallowedusersvalidator`
* `otoroshi-auth-module-config`
* `otoroshi-basic-auth-config`
* `otoroshi-ldap-auth-config`
* `otoroshi-plugins-jsonpath-helper`
* `otoroshi-global-oauth2-config`
* `otoroshi-global-oauth2-module`
* `otoroshi-ldap-auth-config`
