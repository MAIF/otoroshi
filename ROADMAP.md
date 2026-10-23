this document tries to sum things up about where otoroshi is going, what otoroshi can be and how everything will work.

the day to day roadmap lives in the [official github project](https://github.com/orgs/MAIF/projects/20/views/1).

# Roadmap

- [x] Q1 2022 
  - implement the new proxy engine
  - implement basic views for the new proxy engine
  - implement some enterprise plugins (payload transformation, soap, etc)
  - implement secret vaults support
  - implement tracing plugins
- [x] Q2 2022
  - implement views for the new proxy engine
  - implement a "try it" view for services
    - REST
    - GraphQL
    - reporting
  - implement secret vaults
  - implement grapql plugins (except federation)
- [x] Q3 2022  
  - remote tunnels for easier exposition
  - relay routing
- [x] Q4 2022  
  - [x] experimental netty stuff
  - [x] http3 preview
  - [x] rollout new proxy engine (version 16)
  - [x] introduce wizards to help resources creation
- [x] 2023 - early 2025 (versions 16.x)
  - [x] WASM plugins support and WASM manager (16.1.0)
  - [x] Tailscale integration (16.1.0)
  - [x] anonymous reporting (16.2.0)
  - [x] admin extensions API (16.3.0)
  - [x] Coraza WAF (16.4.0)
  - [x] Brotli compression plugin (16.4.0)
  - [x] GreenScore (16.10.0)
  - [x] several http listeners per otoroshi instance (16.18.0)
- [x] 2025 - mid 2026 (versions 17.x)
  - [x] API entity with full lifecycle management (17.0.0)
  - [x] ServiceDescriptor migration toolkit (17.0.0)
  - [x] workflow engine with visual designer and debugger
  - [x] TCP, UDP and Syslog data exporters (17.2.0)
  - [x] fail2ban plugin (17.5.0)
  - [x] OpenFGA fine grained authorizations (17.11.0)
  - [x] Kubernetes Gateway API support (17.13.0)
  - [x] remote catalogs (17.14.0)
  - [x] new UI themes (17.15.0)
  - [x] user defined analytics dashboards and alerts (17.16.0)
- [ ] 18.0.0 (release candidates since october 2026)
  - [x] move to Scala 3, Play 3 and Apache Pekko
  - [x] move the admin UI build from webpack to Vite and remove jQuery
  - [x] remove ServiceDescriptors (automatic migration to routes)
  - [x] every API consumer is an API key, plugin chains per consumer
  - [x] conditional plugins
  - [x] security pass and production checklist
  - [x] hand written openapi document of the admin API
- [ ] next
  - [x] create an official github project
  - [x] add an anonymous telemetry agent to gather usage statistics (anonymous reporting)
  - [x] research around new UX (route designer, API views, wizards, themes)
  - [x] research around WASM integration (Extism based plugins, OPA policies, Coraza)
  - [x] introduce new versioning scheme (see below)
  - [x] introduce event system bridges (bundle)
    - [x] support event systems like kafka, pulsar, mqtt, amqp, gcp pub/sub, aws kinesis, azure storage queue, etc
    - [x] support event producing through http post, websockets
    - [x] support event subscriptions through webhooks, sse, websockets, grpc streaming
  - [ ] minimal gRCP support
    - [x] proxy gRPC services (HTTP/2 end to end)
    - [x] plugin to call gRPC services
    - [x] expose routes as gRPC-Web
    - [ ] grpc transformation
  - [ ] new plugins
    - [x] provide a plugin to act as an Eureka 2 backend
    - [x] provide a plugin to handle backend discovery backed by Eureka 2
    - [x] provide a Brotli compression plugin
    - [x] provide an orchestration plugin (workflows)
    - [x] websocket validation: frame format, frame size, etc
  - [ ] cleanup kubernetes integration 
    - [x] fix versioning in crds (`v1` served, `v1alpha1` deprecated)
    - [x] check against latest kubernetes version
    - [ ] support external-dns (https://github.com/kubernetes-sigs/external-dns)
    - [x] implement k8s Gateway API support (https://github.com/kubernetes-sigs/gateway-api), still experimental
  - [x] pluggable authentication modules (WASM and workflow based auth. modules)
  - [x] upgrade scala version (straight to scala 3) 
  - [x] better testing infrastructure for multi node environments (testcontainers, in process leader/workers clusters)
  - [x] new documentation website generator (docusaurus)
  - [ ] upgrade all frontend libs
    - [x] move from webpack to Vite
    - [x] remove jQuery
    - [ ] upgrade React (still 17) and Ant Design (still 4)
  - [ ] stored tunnels
  - [x] expand vault mechanism to config. file
  - [x] disable TLS 1.0 and 1.1 by default
  - [ ] support websocket calls in relay routing
  - [ ] improve tunnels 
    - [ ] improve stability
    - [ ] improve performances (use binary format ?)
    - [ ] improve resilience
    - [ ] support websocket calls
    - [ ] support long/streaming calls
  - [x] new data exporters
    - [x] TCP
    - [x] UDP
    - [x] Syslog
  - [x] support plugins thats ships their own custom UI (admin extensions frontend modules)

## versioning

after releasing 1.5.0 we made a new release immediately with version number 16.0.0 as previous minor versions where actually major ones.

we ended up with the second strategy: we release a new minor version every month (actually every 4 to 8 weeks) and keep the major version for breaking changes (16.x from december 2022, 17.x from march 2025, 18.x from fall 2026).

## authentication and security

provide the authentication modules needed for most cases and associated tools 

- [x] Local (in memory)
- [x] LDAP
- [x] OAuth1
- [x] OAuth2
- [x] OIDC
- [x] SAML v2
- [x] WebAuthn
- [x] WASM
- [x] Workflows
- [x] support ocsp, aia, public keys access through jwks.json
- [x] support oauth2 `client_credentials` flow
- [x] pluggable authentication modules (WASM and workflow based auth. modules)
- [x] plugin to handle basic auth calls
- [x] plugin to handle OAuth1 calls
- [x] plugin to handle OAuth2 calls
- [x] more integration of biscuit tokens
  - [x] biscuit extraction and validation plugins
  - [x] add biscuit playground to the UI
- [x] access control helpers (RBAC, OpenFGA, context validation, allowed/blocked lists)
- [x] spikes and DoS detection and arrest (fail2ban plugin is a first step)
- [ ] beyondcorp like setup helpers
- [x] secret management from pluggable vaults
  - [x] apikey secrets
  - [x] jwt verifier secrets
  - [x] certificates keypairs
  - [x] datastore credentials (through the config. file)
  - [x] auth. modules secrets
  - [x] data exporter secrets
  - [x] config. file secrets
  - [x] global config secrets

## plugins

- [x] versioning helpers (API entity)
- [x] orchestrator plugin (workflows)
- [x] access control helpers
- [x] eureka compatibility
- [x] representation plugins
  - [x] protocol transformations
    - [x] rest to soap
  - [x] payload transformations
    - [x] json-to-xml
    - [x] xml-to-json
- [x] graphql plugins
  - [x] graphql query as REST
  - [x] graphql proxy with validation
  - [x] graphql composer

## backoffice

- [ ] multi-instances
  - [ ] where to store access_keys ?
  - [ ] multi-cluster monitoring
- [x] customizable embbeded dashboarding (user analytics dashboards)
- [x] UX enhancements
  - [x] introduce simplified wizard to enhance user experience
  - [x] introduce graphical service creation/design mode  to enhance user experience
  - [x] "try it" feature with debug mode

## container orchestrators

- [x] support for kubernetes ingress controller api
- [x] support for custom kubernetes CRDs to configure otoroshi
- [x] optimize kubernetes CRD job
- [x] support [Gateway API](https://gateway-api.sigs.k8s.io/) (HTTPRoute and GRPCRoute, experimental)

## clustering

- [x] support postgresql as leader datastore
- [x] support S3 as leader datastore
- [ ] master - master replication (leader / follower at least)

## observability and data exports

- [x] support a generic way to export data (events) from otoroshi
- [x] add more data export modules
  - [x] OpenTelemetry logs and metrics (OTLP)
  - [x] Splunk, Datadog, New Relic
  - [x] TCP, UDP, Syslog
  - [x] JMS
  - [x] PostgreSQL
  - [x] WASM and workflow based exporters
- [x] support W3C Trace Context: [trace-context spec](https://www.w3.org/TR/trace-context), [manual impl.](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/trace/propagation/W3CTraceContextPropagator.java)
  - [x] support Jaeger exporter (use [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/tree/main/exporters/jaeger))
  - [x] support Zipkin exporter (use [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/tree/main/exporters/zipkin))
- [x] support W3C Baggage propagation: [baggage spec](https://www.w3.org/TR/baggage/), [manual impl.](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/baggage/propagation/W3CBaggagePropagator.java)

## deprecations and renaming

- [x] rename everything `master/slave` in the api
- [ ] rename everything `blacklist/whitelist` in the api (done in the new engine plugins, some legacy leftovers remain)
- [x] remove ServiceDescriptors (18.0.0)

## language

- [x] upgrade to scala `2.13.x` (skipped, moved straight from `2.12.x` to `3.x.x`)
- [x] upgrade to scala `3.x.x` (18.0.0)

## multi-tenancy

- [x] support multi-tenancy through organizations and teams in the UI and admin API

## platform

- [x] investiguate using WASM as script language (run with Extism instead of wasmer-java)

## otoroshi.next

at some point we will have the opportunity to rewrite otoroshi with major breaking changes

- [ ] remove play framework (otoroshi runs on play 3 and apache pekko since 18.0.0)
  - [ ] rewritte http engine using akka http (well maybe remove akka too ...), an alternative netty server exists (experimental)
- [ ] split admin api http server and http routing server with default routing for admin api
- [x] modular architecture rework  
  - [x] default template (customizable) for services with standard plugins
  - [x] make it the default (the new engine is the only engine since 18.0.0)
  - [x] powerful reporting mecanism that can support debugging
  - [x] rewrite http handler to be mostly plugin based
  - [x] targets should be a separate entity to allow reuse
  - [x] store targets as entities ??? 
  - [x] extract standard plugins from legacy http handler
- [ ] configuration enhancements
  - [ ] move all `app.*` config. keys to `otoroshi.*` in config lookups
  - [ ] move all `app.*` config. keys to `otoroshi.*` in config file 
  - [ ] move all `app.*` config. keys to `otoroshi.*` in documentation
  - [ ] all env. variables about initial data should start with `OTOROSHI_INITIAL_`
  - [x] merge `app.*` and `otoroshi.*` to avoid confusion
  - [x] all env. variables should start with `OTOROSHI_`
- [ ] rewrite datastore layer to be less redis specific and offer better performance improvement possibilities
- [x] rewrite entities
  - [x] each entity has a creation timestamp (`created_at` in metadata, set by the admin API)
  - [x] each entity has an update timestamp (`updated_at` in metadata, set by the admin API)
  - [x] each entity has an id that is human readable `${entity_singular_name}_${uuid}`
  - [x] each entity has a name
  - [x] each entity has a description
  - [x] each entity has metadata
  - [x] each entity has tags
  - [x] each entity has a version
  - [x] each entity has a json write function

## storage

- [x] switch default redis driver to lettuce and remove rediscala
- [x] remove support for mongodb
- [x] remove support for leveldb
