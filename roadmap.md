this issue will try to sum things up about where otoroshi is going, what otoroshi can be and how everything will work.

# Roadmap

## authentication

provide the authentication modules needed for most cases and associated tools 

- [x] Local (in memory)
- [x] LDAP
- [x] OAuth1
- [x] OAuth2
- [x] OIDC
- [x] SAML v2
- [ ] pluggable authentication modules using the existing discovery mecanism
- [ ] plugin to handle basic auth calls
- [ ] plugin to handle OAuth1 calls
- [ ] plugin to handle OAuth2 calls

## container orchestrators

- [x] support for kubernetes ingress controller api
- [x] support for custom kubernetes CRDs to configure otoroshi
- [ ] support for [SMI spec](https://smi-spec.io/)

## clustering

- [x] support postgresql as leader datastore
- [ ] experiment around lightweight workers
  - [ ] written in rust (based on sozu ?)
  - [ ] written in c++ and lua (based on envoy ?)

## data exports

- [x] support a generic way to export data (events) from otoroshi
- [ ] add more data export modules

## deprecations and renaming

- [x] rename everything master/slave in the api
- [ ] rename everything blacklist/whitelist in the api

## language

- [ ] upgrade to scala 2.13.x
- [ ] upgrade to scala 3.x.x

## otoroshi.next

at some point we will have the opportunity to rewrite otoroshi with major breaking changes

- [ ] remove play framework
- [ ] rewritte http engine using akka http
- [ ] split admin api http server and http routing server with default routing for admin api
- [ ] rewrite entities
  - [ ] each entity has an id that is human readable s"${entity_singular_name}_${uuid}"
  - [ ] each entity has a name
  - [ ] each entity has a description
  – [ ] each entity has metadata
  – [ ] each entity has tags
  – [ ] each entity has an version
  – [ ] each entity has an creation timestamp
  – [ ] each entity has an update timestamp
  – [ ] each entity has a json write function
– [ ] rewrite datastore layer to be less redis specific and offer better performance improvement possibilities
- [ ] rewrite http handler to be mostly plugin based
