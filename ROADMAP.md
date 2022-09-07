this issue will try to sum things up about where otoroshi is going, what otoroshi can be and how everything will work.

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
- [ ] Q3 2022  
  - remote tunnels for easier exposition
  - relay routing
- [ ] Q4 2022  
  - rollout new proxy engine
  - kubernetes integration upgrades
    - implement k8s SMI spec support (if it fits the use case)
    - implement k8s Gateway API support (https://github.com/kubernetes-sigs/gateway-api)
  - introduce wizards to help resources creation
  - support complex call in networking features (maybe in next if no time ?)
    - support websocket calls in relay routing
    - support websocket calls in remote tunnels
    - support long/streaming calls in remote tunnels
- [ ] next (2023 - 202x)
  - introduce new versioning scheme (see below)
  - new plugins
    - provide a plugin to act as an Eureka 2 backend
    - provide a plugin to handle backend discovery backed by Eureka 2
    - provide a Brotli compression plugin
    - provide a graphql federation plugin
  - cleanup kubernetes integration 
    - fix versioning in crds
    - check against latest kubernetes version
    - support external-dns (https://github.com/kubernetes-sigs/external-dns)
  - pluggable authentication modules
  - scala version upgrade 
  - better testing infrastructure for multi node environments
  - new documentation generator
  - upgrade all frontend libs
  - stored tunnels
  - expand vault mechanism to config. file
  - disable TLS 1.0 and 1.1 by default

## versioning

after releasing 1.5.0 we plan to make a new release immediately with version number 16.0.0 as previous minor version where actually major ones. 

Then, as we release a new version every month (actually every 4 to 8 weeks), we will increment the major version for each release.

## authentication and security

provide the authentication modules needed for most cases and associated tools 

- [x] Local (in memory)
- [x] LDAP
- [x] OAuth1
- [x] OAuth2
- [x] OIDC
- [x] SAML v2
- [x] support ocsp, aia, public keys access through jwks.json
- [x] support oauth2 `client_credentials` flow
- [ ] pluggable authentication modules using the existing discovery mecanism
- [x] plugin to handle basic auth calls
- [x] plugin to handle OAuth1 calls
- [x] plugin to handle OAuth2 calls
- [ ] more integration of biscuit tokens
  - [ ] add biscuit playground to the UI
- [ ] access control helpers
- [ ] spikes and DoS detection and arrest
- [ ] beyondcorp like setup helpers
- [x] secret management from pluggable vaults
  - [x] apikey secrets
  - [x] jwt verifier secrets
  - [x] certificates keypairs
  - [ ] datastore credentials
  - [x] auth. modules secrets
  - [x] data exporter secrets
  - [ ] config. file secrets
  - [x] global config secrets

## plugins

- [ ] versioning helpers
- [ ] orchestrator plugin (based on flow plugin work)
- [ ] access control helpers
- [ ] eureka compatibility
- [x] representation plugins
  - [x] protocol transformations
    - [x] rest to soap
  - [x] payload transformations
    - [x] json-to-xml
    - [x] xml-to-json
- [ ] graphql plugins
  - [x] graphql query as REST
  - [x] graphql proxy with validation
  - [ ] graphql federation
  - [x] graphql composer

## backoffice

- [ ] multi-instances
  - [ ] where to store access_keys ?
  - [ ] multi-cluster monitoring
- [ ] customizable embbeded dashboarding
- [ ] UX enhancements
  - [ ] introduce simplified wizard to enhance user experience
  - [x] introduce graphical service creation/design mode  to enhance user experience
  - [x] "try it" feature with debug mode

## container orchestrators

- [x] support for kubernetes ingress controller api
- [x] support for custom kubernetes CRDs to configure otoroshi
- [x] optimize kubernetes CRD job
- [ ] support for [SMI spec](https://smi-spec.io/)
- [ ] support [Gateway API](https://gateway-api.sigs.k8s.io/)

## clustering

- [x] support postgresql as leader datastore
- [x] support S3 as leader datastore
- [ ] support cosmos db as leader datastore (should be already true with cassandra support but needs to be checked)
- [ ] master - master replication (leader / follower at least)
- [ ] experiment around lightweight workers
  - [ ] written in rust (based on sozu or hyper ?)
  - [ ] written in c++ and lua (based on envoy ?)

## observability and data exports

- [x] support a generic way to export data (events) from otoroshi
- [ ] add more data export modules
  - [ ] cosmos db bulks (which API ?)
- [x] support W3C Trace Context: [trace-context spec](https://www.w3.org/TR/trace-context), [manual impl.](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/trace/propagation/W3CTraceContextPropagator.java)
  - [x] support Jaeger exporter (use [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/tree/main/exporters/jaeger))
  - [x] support Zipkin exporter (use [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/tree/main/exporters/zipkin))
- [x] support W3C Baggage propagation: [baggage spec](https://www.w3.org/TR/baggage/), [manual impl.](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/baggage/propagation/W3CBaggagePropagator.java)

## deprecations and renaming

- [x] rename everything `master/slave` in the api
- [ ] rename everything `blacklist/whitelist` in the api

## language

- [ ] upgrade to scala `2.13.x`
- [ ] upgrade to scala `3.x.x`

## multi-tenancy

- [x] support multi-tenancy through organizations and teams in the UI and admin API

## platform

- [ ] investiguate graalvm build and perf boost
- [ ] build a graalvm native image
- [ ] investiguate using polyglot api and embedded languages of graalvm for better scripts
- [ ] investiguate using WASM as script language (run with wasmer-java)

## otoroshi.next

at some point we will have the opportunity to rewrite otoroshi with major breaking changes

- [ ] remove play framework
  - [ ] rewritte http engine using akka http
- [ ] split admin api http server and http routing server with default routing for admin api
- [ ] modular architecture rework  
  - [x] default template (customizable) for services with standard plugins
  - [ ] make it the default
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
- [ ] rewrite entities
  - [ ] each entity has a creation timestamp
  - [ ] each entity has an update timestamp
  - [x] each entity has an id that is human readable `${entity_singular_name}_${uuid}`
  - [x] each entity has a name
  - [x] each entity has a description
  - [x] each entity has metadata
  - [x] each entity has tags
  - [x] each entity has a version
  - [x] each entity has a json write function

## storage

- [ ] switch default redis driver to lettuce and remove rediscala
- [ ] remove support for mongodb
- [ ] remove support for leveldb
