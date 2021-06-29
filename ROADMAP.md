this issue will try to sum things up about where otoroshi is going, what otoroshi can be and how everything will work.

# Roadmap

## versioning

after releasing 1.5.0 we plan to make a new release immediately with version number 16.0.0 as previous minor version where actually major ones. We will not make a 15.0.0 as there are already alpha releases of the 1.5.0.

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
- [ ] plugin to handle basic auth calls
- [ ] plugin to handle OAuth1 calls
- [ ] plugin to handle OAuth2 calls
- [ ] more integration of biscuit tokens
  - [ ] add biscuit playground to the UI
- [ ] access control helpers
- [ ] spikes and DoS detection and arrest
- [ ] beyondcorp like setup helpers

## plugins

- [ ] versioning helpers
- [ ] orchestrator plugin (based on flow plugin work)
- [ ] representation plugins
  - [ ] protocol transformations
  - [ ] payload transformations

## backoffice

- [ ] multi-instances
- [ ] customizable embbeded dashboarding

## container orchestrators

- [x] support for kubernetes ingress controller api
- [x] support for custom kubernetes CRDs to configure otoroshi
- [ ] support for [SMI spec](https://smi-spec.io/)

## clustering

- [x] support postgresql as leader datastore
- [x] support S3 as leader datastore
- [ ] master - master replication (leader / follower at least)
- [ ] experiment around lightweight workers
  - [ ] written in rust (based on sozu or hyper ?)
  - [ ] written in c++ and lua (based on envoy ?)

## data exports

- [x] support a generic way to export data (events) from otoroshi
- [ ] add more data export modules

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
- [ ] rewrite http handler to be mostly plugin based
- [ ] targets should be a separate entity to allow reuse
- [ ] extract standard plugins from legacy http handler
- [ ] rewrite datastore layer to be less redis specific and offer better performance improvement possibilities
  - [ ] default template (customizable) for services with standard plugins
- [ ] rewrite entities
  - [ ] each entity has an id that is human readable `${entity_singular_name}_${uuid}`
  - [ ] each entity has a name
  - [ ] each entity has a description
  - [ ] each entity has metadata
  - [ ] each entity has tags
  - [ ] each entity has a version
  - [ ] each entity has a creation timestamp
  - [ ] each entity has an update timestamp
  - [ ] each entity has a json write function

## storage

- [ ] switch default redis driver to lettuce and remove rediscala
- [ ] remove support for mongodb
- [ ] remove support for leveldb
