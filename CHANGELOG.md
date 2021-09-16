# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0-beta.7] - 2021-09-16

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.7+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.6...v1.5.0-beta.7
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.7

- Service Group list view - No filter for service associated with Service Group (#920)
- Fix bad apikey parsing (#921)

## [1.5.0-beta.6] - 2021-09-13

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.6+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.5...v1.5.0-beta.6
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.6

- Error when displaying elasticsearch config (#918)

## [1.5.0-beta.5] - 2021-09-13

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.5+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.4...v1.5.0-beta.5
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.5

- Do not display type in elastic config if version greater than 7 (#909)
- Enable index per day pattern in elastic exporter or not (#910)
- Add a "test connection" button in elastic config (#911)

## [1.5.0-beta.4] - 2021-08-30

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.4+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.3...v1.5.0-beta.4
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.4

- try to put `trustXForwarded` in danger zone  (#906)
- error while parsing certificate with bag attributes (#907)

## [1.5.0-beta.3] - 2021-08-06

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.3+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.2...v1.5.0-beta.3
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.3

- Seeking configuration for Read timeout to XXX after 120000 ms (#880)
- use akka-http cached host level client behind a feature flag (#901)
- slow down worker retries to avoid flooding leader api #900

## [1.5.0-beta.2] - 2021-08-05

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.2+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.1...v1.5.0-beta.2
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.2

- client certificate lookup strategy is broken (#899)

## [1.5.0-beta.1] - 2021-07-30

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.1+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.20...v1.5.0-beta.1
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.1

- Make SameSite cookie flags works with both http clients

## [1.5.0-alpha.20] - 2021-07-29

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.20+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.19...v1.5.0-alpha.20
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.20

- Seeking configuration for Read timeout to XXX after 120000 ms (#880)
- Select trusted CAs for server mtls from global config (#886)
- Add env. variables to enable server MTLS in documentation (#887)
- when using circuit breaker with akka client, need to discardBytes (#888)
- Unable to change api key status from all api keys view (#889)
- Return http error when ES statistics cannot be returned (#890)
- When I clicked on the stats button of an api key (from all Api keys view), I got an undefined analytics view  (#891)
- Add options in pki api to avoid certificate save (#893)
- investigate about kube secret changing even if apikey didn't change  (#894)

## [1.5.0-alpha.19] - 2021-06-30

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.19+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.18...v1.5.0-alpha.19
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.19

- dynamic root domains (#879)
- configure input MTLS in the danger zone (#881)
- static response plugin (#882)
- circuit breaker cache too aggressive  (#883)
- add tags and meta on targets (#884)

## [1.5.0-alpha.18] - 2021-06-10

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.18+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.17...v1.5.0-alpha.18
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.18

- Add migration button for old plugins in services (#872)
- Add migration button for old plugins in danger zone (#873)
- add prefix in plugins excluded path to apply only on one specific plugin (#874)
- Plugin documentation extractor (#875)

## [1.5.0-alpha.17] - 2021-06-09

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.17+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.16...v1.5.0-alpha.17
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.17

- expose new plugins settings (#871)

## [1.5.0-alpha.16] - 2021-06-09

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.16+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.15...v1.5.0-alpha.16
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.16

- Add form description for the kubernetes jobs config (#870)

## [1.5.0-alpha.15] - 2021-06-09

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.15+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.14...v1.5.0-alpha.15
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.15

* Default rights to admins (#846)
* Fix admin creation from super admin (#848)
* fix the target component when pasting URL with trailing slash (#849)
* check if custom error templates works in worker mode (#850)
* add metadata tags check in ApikeyAuthModule  (#851)
* remove validation from crds definitions (#852)
* missing namespace in subject from clusterrolebinding (#853)
* version in crds becomes versions (#854)
* button to hide/toggle plugin configuration (#856)
* additionnal host added indefinitely (#857)
* kubernetes watch fails on otoroshi crds (#858)
* Hashed Password visible when infos token is enabled on service  (#859)
* Rename SAML to SAML v2 in auth module list (#861)
* Admin api seems to be in default group at first startup (#862)
* check if multi purpose plugins works as they should be (#863)
* display instance name in window title (#864)
* do not hardcode otoroshi.mesh, use config (#865)
* add a flag to nuke openshift coredns operator customization that uses otoroshi.mesh (#866)

## [1.5.0-alpha.14] - 2021-05-25

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.14+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.13...v1.5.0-alpha.14
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.14

- Fix bad js function reference

## [1.5.0-alpha.13] - 2021-05-21

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.13+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.12...v1.5.0-alpha.13
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.13

- Missing kid in default token (#841)

## [1.5.0-alpha.12] - 2021-05-19

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.12+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.11...v1.5.0-alpha.12
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.12

- Publish otoroshi_lib on maven central (#789)

## [1.5.0-alpha.11] - 2021-05-19

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.11+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.10...v1.5.0-alpha.11
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.11

- Publish otoroshi_lib on maven central (#789)
- Local authentication doesn't works (#836)
- Add google floc specific headers in ui (#837)
- Fix docker build (#838)
- Add auto cleanup in the cache plugin (#839)

## [1.5.0-alpha.10] - 2021-05-12

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.10+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.9...v1.5.0-alpha.10
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.10

- Provide plugins to perform service discovery (#797)
- Enhance de LDAP auth. module (#799)
- remove log on server (service/global) options (#806)
- Support mTLS for PG connection (#810)
- Missing kid when signing with RSAKPAlgoSettings and ESKPAlgoSettings (#832)
- Add support for S3 persistence for the in-memory datastore (#834)
- Add support for SAML V2 auth. module (#815)
- Add support for OAuth1 auth. module (#865)

## [1.5.0-alpha.9] - 2021-03-12

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.9+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.8...v1.5.0-alpha.9
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.9

- Scoping otoroshi sources (#130)
- Biscuit basic support (#783)
- Add hash and line count in response header when exporting cluster state (#785)
- Limit classpath scanning to the bare minimum (#786)
- Introduce generic plugins (#787)
- Use exposed ports instead of regular ports (#790)
- Try to parse datetime as string in json readers (#791)
- Add alerts when kubernetes jobs fails crd parsing (#792)
- fix documentation about kubernetes webhooks (#793)
- openapi descriptor generation (#795)

## [1.5.0-alpha.8] - 2021-02-24

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.8+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.7...v1.5.0-alpha.8
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.8

- Add an OCSP responder API to the pki (#754)
- On status page, when ES instance isn't setup, an undefined sort error occured (#765)
- add something to the target UI components to avoid passing a \"path\" (#766)
- When adding a target, we can't do show more without saving first (#767)
- strip path seems to be broken (#768)
- handle keypair renew in jwt verifier (#769)
- Plugin to support canary stuff from izanami AB testing campaign (#770)
- Add informations about OCSP and Authority informations access in cert extensions (#782)

## [1.5.0-alpha.7] - 2021-02-10

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.7+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.6...v1.5.0-alpha.7
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.7

- Add JWT EC token sign/valid from keypair (#759)

## [1.5.0-alpha.6] - 2021-02-10

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.6+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.5...v1.5.0-alpha.6
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.6

- improve PostgreSQL support (#758)

## [1.5.0-alpha.5] - 2021-02-05

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.5+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.4...v1.5.0-alpha.5
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.5

- kubernetes job improvments (#677)
- Add an api to get the availability of a service over time (#713)
- Support private key passwords on certificates (#717)
- watch does not handle new namespaces until restart (#719)
- watch does not use namespace label filtering (#720)
- Add support for kubedns and openshift dns operator (#721)
- Add documentation about coredns stubdomain for openshift and kubedns (#722)
- Exporter to fill internal metrics  (#725)
- Support EC certificates in the pki (#727)
- Title not displayed after cancel a TCP service (#728)
- change apiversion in crds (#730)
- fix the findCertMatching in DynamicKeyManager to chose the most client specific cert (#733)
- enhance pki (#735)
- default jwks.json route (#736)
- create default team and orga at first startup (#742)
- Team selector is broken in UI (#743)
- Unleash the snow monkey seems broken (#744)
- Add specific JVM flags in kubernetes manifests (#745)
- Session management does not work in cluster mode (#752)
- Login tokens does not work in cluster mode (#753)
- Chaining jwt verifier in non strict mode generate more events than needed (#755)
- Experiment postgresql support (#757)


## [1.5.0-alpha.4] - 2020-12-18

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.4+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.3...v1.5.0-alpha.4
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.4

- [UI] : add button to export form data to YAML descriptor (#679)
- Experiment with a MutatingAdmissionWebhook to add a helper as a sidecar (#681)
- Experiment with a ValidatingAdmissionWebhook to return useful errors when deploying otoroshi entities (#682)
- Rename 'mtlsSettings' to 'tlsSettings' in the UI (#684)
- Use standard kubernetes service names in target transformation (#688)
- deprecate HasAllowedApiKeyValidator plugin (#696)
- Remove whitelist/blacklist from UI (#697)
- Custom template button does not work anymore (#698)
- Cleanup possible hostnames for the kubernetes internal cluster calls (#700)
- try to reduce memory impact of initial classpath scanning (#701)
- only organization admins can create others admins  (#704)
- when an organization admin creates other admins, enforce new admin organizations and teams (#705)
- flag in kubernetes config to accepts apikeys only with daikoku tokens (#706)
- jwt-verifiers not imported with kubernetes job (#707)
- workflow job (#708)
- weird npe on job list since 1.5.0-alpha.3 (#709)
- fix bad jsonpath functions (#710)
- include jsonpath operator in transformation utils (#711)
- include simple el in transformation utils (#712)
- json editor adds '{}' at the end when pasting a json document (#714)
- strip path removes too much stuff (#715)
- io.otoroshi/id is not in annotations in documentation (#716)
- Add a flag in service to avoid adding default hosts (#718)
- Make global client_credential flow available by default  (#723)
- issue when generating subcas (#726)
- Fix issuer DN in certificate to avoid certificate check in go (#729)
- Some "add" doesn't work for HTTP headers in Service descriptor (#734)

## [1.5.0-alpha.3] - 2020-11-18

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.3+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha.2...v1.5.0-alpha.3
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.3

- Add a clever cloud generator in doc page (#673)
- service registration fails when no endpoints (#674)
- Increase default chunk size for akka http (#676)
- disabling global script should stop current jobs (#678)
- fix kubernetes job watch (#680)
- Job for apikeys rotation (#683)
- Add entries in the ApiKey secret to have Base64(client_id:client_secret) ready (#686)
- Provide job context to various duration function in Job api (#687)
- Add tenants and teams to crds (#689)
- Get kubernetes job interval from config. (#691)
- fix watch for ingress and certs (#692)
- add env in coredns customization (#693)
- handle coredns customization removal (#694)
- add various watch timeout in KubernetesConfig (#695)

## [1.5.0-alpha.2] - 2020-11-06

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-alpha.2+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-alpha01...v1.5.0-alpha.2
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha.2

### Fixed

- Fix the version checker to understand alpha and beta (#669)
- Better coredns config patch (#671)

### Added

- Include jwt token fields in the elContext (#672)

## [1.5.0-alpha01] - 2020-10-29

https://github.com/MAIF/otoroshi/milestone/6?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.22...v1.5.0-alpha01
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-alpha01

- Add pluggable authentication modules for services instead of Auth0 only (#3)
- Include Kubernetes ingress controller as a Job (#91)
- Add support for Redis cluster (#252)
- More JWT support from Otoroshi to Backends (#4)
- [plugin] - Mirror traffic (#118)
- HealthCheck disable service strategy (#221)
- add bulk apis for main entities (#285)
- Cleanup documentation (#295)
- Support full OIDC / OAuth2 lifecycle with forwarded access token (#298)
- Streaming input issue (#331)
- Identity aware TCP forwarding over HTTPS  (#332)
- Add a geoloc target matcher  (#338)
- Use ndjson raw export for the import/export feature instead of partial json (#343)
- Compatibility issues with Elastic 7.x (#344)
- Document tcp tunneling (#356)
- Update U2F documentation (#357)
- add a button to test LDAP connection (#426)
- cleanup datastore code (#464)
- Remove U2F support (#468)
- Customize UI title from config. (#469)
- Update all JS deps (#470)
- Webauthn support multiple devices for the same user (#471)
- Support for client_credentials flow backed by apikeys (#472)
- Rewrite SSL/TLS part with new Dynamic SSLContext (#473)
- accesslog plugins - the referred is not in the response (#477)
- If script description is empty, then take the programmatic one (#478)
- multi-tenant / multi-teams (#479)
- plugins to populate user from jwt token and use it in otoroshi token (#481)
- Move third-party apikeys OIDC to a plugin and deprecate the original feature (#482)
- Add email address in let's encrypt settings (#484)
- add kubernetes deployment descriptors examples (#485)
- add Helm chart to deploy otoroshi (#486)
- Add routing constraint based on meta keys (#536)
- Have a unique name for groups (#539)
- Handle initial data import with a merge option (#544)
- Read initial secrets from files path (#545)
- Do not support enabled in kubernetes entities (#546)
- override httpOnly and secure in private apps session cookie from module config (#547)
- Typo in security header key X-Frame-Options (#548)
- Delete non existant (in otoroshi) secrets  (#551)
- Try to find entities by id then merge it (#552)
- Avoid phishing during private-apps authentication (#553)
- Revamping secrets and testing them at startup (#555)
- Duplicate auth. module configs. (#557)
- back <- after consulting a service don't refresh the menu (#558)
- Error after suppressing a Service target (#559)
- Multiple authorized entities on apikeys (#560)
- Multiple groups on service descriptors (#561)
- Generic data exporters (#588)
- Assign worker to handle only selected tenant data (#590)
- Support Kubernetes 1.18 IngressClass (#591)
- Generic notifier support in exporters (#592)
- Add liveness probe check in service descriptors health check (crd, ingress) (#593)
- Add usage of custom configuration file in configfile (#633)
- Handle samesite in cookies (#660)
- Support sendgrid for alert emails (#665)
- enhance coredns patching to handle config changes (#667)

## [1.4.22] - 2020-03-17

https://github.com/MAIF/otoroshi/milestone/29?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.21...v1.4.22
https://github.com/MAIF/otoroshi/releases/tag/v1.4.22

### Changed

- Request handler refactoring (#462)

### Fixed

- Fix pre-routing (#461)

## [1.4.21] - 2020-03-10

https://github.com/MAIF/otoroshi/milestone/28?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.20...v1.4.21
https://github.com/MAIF/otoroshi/releases/tag/v1.4.21

### Added

- Add provider JS url to hook into UI (#440)
- Now preRoutes can cancel a request if necessary (#444)
- Plugins can enrich analytics events (#446)
- Experiments with Lettuce driver for redis (#448)
- Add plugins that can be background jobs (#449)
- Add some kind of scheduler to have distributed unique jobs that run only on one cluster member at a time (#450)
- Use JSON path to validate user profile and meta (#451)
- Old LDAP version : bypass authentification without password (#452)

### Changed

- cosmetic changes concerning plugins in UI (#441)
- cosmetic changes to the service page (#442)

### Fixed

- Add request sinks in the plugin form (#443)
- Healthcheck to removed services (#447)

## [1.4.20] - 2020-02-11

https://github.com/MAIF/otoroshi/milestone/27?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.19...v1.4.20
https://github.com/MAIF/otoroshi/releases/tag/v1.4.20

### Added

- Allow changing password your own admin account (#172)
- Add a "new service" button at the top of services list (#424)
- Store cluster datastore content on file for faster restarts (#428)
- Add env. var. for array values (redis connector)  (#429)
- Add apikey rotation plugin (#430)
- Add option to block exposed domain name for the instance (#431)
- Add option to inject provider UI (#432)
- Add option to set global plugin per instance (#433)
- Add plugin to limit global handled request per day and month per instance (#434)
- Public quotas per service plugin (#438)
- Provide a plugin to use client certs as apikey (#439)

### Fixed

- possible matching root fails from UI (#437)

## [1.4.19] - 2020-01-20

https://github.com/MAIF/otoroshi/compare/v1.4.18...v1.4.19
https://github.com/MAIF/otoroshi/releases/tag/v1.4.19

### Fixed 

- fix bad env. var name (thanks to my broken keyboard !!!)

## [1.4.18] - 2020-01-17

https://github.com/MAIF/otoroshi/compare/v1.4.17...v1.4.18
https://github.com/MAIF/otoroshi/releases/tag/v1.4.18

### Added 

- trust all in mtls config
- template API for each API entity
- new mode for cluster state export for leaders

## [1.4.17] - 2020-01-16

https://github.com/MAIF/otoroshi/compare/v1.4.16...v1.4.17
https://github.com/MAIF/otoroshi/releases/tag/v1.4.17

### Added 

- Really fine tuning of the trust part of the SSL context per target for better mTLS handlings

## [1.4.16] - 2020-01-15

https://github.com/MAIF/otoroshi/milestone/26?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.15...v1.4.16
https://github.com/MAIF/otoroshi/releases/tag/v1.4.16

### Added 

- Support Let's Encrypt certificate creation and renew (#178)
- Customize created certificates (#182)
- Integration with enterprise PKIs (#274)
- Add internal PKI to Otoroshi
- Automatically download maxmind db (#409)
- Listen to internal events in plugins (#410)
- Disable kafka event sending on demand (#411)
- Add configuration access in EL (#412)
- Trust X-Forwarded-* headers or not (#413)
- Add request lifecycle handlers in request transformers (#416)
- [plugin] Add configuration description in plugin to generate UI automatically (#420)
- HMAC secret can be base64 encoded (#423)
- Really fine tuning of the SSL context per target for better mTLS handlings

### Changed

- Add reponse format in query string for metrics (#417)

### Fixed 

- Fix configuration access for plugins (#414)
- Fix nano apps when request body involved (#415)
- Certificate with SANs doesn't seems to work (#419)
- constrainedServicesOnly does not work as expected (#422)
- Quotas + 1 (#421)

## [1.4.15] - 2019-12-02

https://github.com/MAIF/otoroshi/compare/v1.4.14...v1.4.15
https://github.com/MAIF/otoroshi/releases/tag/v1.4.15

### Fixed 

- routing on /.well-known/otoroshi/*

## [1.4.14] - 2019-11-29

https://github.com/MAIF/otoroshi/milestone/25?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.13...v1.4.14
https://github.com/MAIF/otoroshi/releases/tag/v1.4.14

### Added 

- Added scripts status in /health (#403)
- Add detailed client informations based on User-Agent (#335)
- Add more user related user info (identity) in events (#336)
- Add client geo info (from ip) in events (#337)
- [plugin] introduce pre-routing plugins (#398)
- [plugin] introduce request sink plugins (#407)
- [plugin] add a plugin to expose prometheus metrics per service (#312)
- [plugin] add body logger with graphical debugger (#400)
- [plugin] add information extraction based on user-agent (#401)
- [plugin] add geolocation extraction based on ip address (#402)
- [plugin] add a plugin to pass OIDC tokens and profile in headers (#404)
- [plugin] add a plugin to pass client cert. chain in headers (#405)
- [plugin] add CLF logger (#406)
- [plugin] resources caching (#76)
- [plugin] support some kind of real time debugging (#94)
- create new events (#334)
- allow path strip or not (#393)
- allow multi host (#394)
- allow multi matching root (#395)

### Changed

- use another default domain name (#294)
- use Parcel.js to  build Otoroshi UI (#105)
- update front build (#392)

### Fixed 

- filter with dates range don't return expected events (#385)
- sometimes services can't be found (#391)
- // in services events (#396)
- fix bad behavior for /metrics and /health (#397)
- enhance UDP tunneling support (#399)

## [1.4.13] - 2019-10-30

https://github.com/MAIF/otoroshi/milestone/24?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.12...v1.4.13
https://github.com/MAIF/otoroshi/releases/tag/v1.4.13

### Added 

- Add an "all events" view (#374)
- Support Webauthn authentication for backoffice login (#352)
- Integration of mTLS tests (#359)
- Chaining transformers enhancement (#313)
- Support DefaultToken strategy in JWT Verifiers (#373)
- Early apikey extraction even if service is public (#351)
- Expression language in targets (#353)
- Introducing global validators and transformers (#372)
- Support UDP tunneling (#361)
- Use webauthn for U2F login of the backoffice (#340)
- Addionnal headers only if missing (#364)
- Use webauthn for U2F login of private apps (#358)
- Global EL (#369)
- Add extra metadata in auth modules (#370)

### Changed

- Improve tunneling (#354)
- Improve request transformer request object (#299)
- Can we use external access validation without client cert ? (#333)
- Handle multiple JWT verifiers refs, with matchOne strategy (#363)
- Rewrite validation authorities (#360)
- Refactor transformer to only take a context as param like AccessValidator (#366)
- Replace all in EL for multiple values (#371)

### Fixed 

- Issues with target component (#355)
- Created Global Jwt Verifiers not appears in global list (#362)
- Fix overflow of icons in public column of services page (#365)
- JWTVerifier cannot be saved (#368)
- Case sensitivity issue in headers manipulation bug (#367)

## [1.4.12] - 2019-09-27

https://github.com/MAIF/otoroshi/compare/v1.4.11...v1.4.12
https://github.com/MAIF/otoroshi/releases/tag/v1.4.12

### Fixed 

- Fixed version display in UI

## [1.4.11] - 2019-09-27

https://github.com/MAIF/otoroshi/milestone/23?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.10...v1.4.11
https://github.com/MAIF/otoroshi/releases/tag/v1.4.11

### Added 

- Experimental: Identity aware TCP tunneling over HTTPS (#349)
- Otoroshi version available on the dashboard (#346)
- Support something like `urn:ietf:wg:oauth:2.0:oob` in private apps. (#297)

### Fixed 

- Weird behavior with chunked transfer encoding (#350)
- Logout does not work properly when using a In Memory Basic Auth config (#348)
- Use 'storageRoot' when deleting all keys from Redis Storage (#347)
- Cannot set Api Keys Restrictions (#345)
- Allow multiple master nodes (ip address) in cluster config with client loadbalancing to avoid DNS setup (#342)
- Delete Elastic config (#341)

## [1.4.10] - 2019-08-02

https://github.com/MAIF/otoroshi/milestone/22?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.9...v1.4.10
https://github.com/MAIF/otoroshi/releases/tag/v1.4.10

### Added 

- Removing incoming and outgoing headers (#326)

### Changed 

- Improve cassandra support (#325)

### Fixed 

- Api Keys could have a ttl (#328)
- Fix empty String and Option[String] JSON parsing (#330) 
- Navigating on a group cause a 'Page not found' page (#329)
- Some apiKeys disappears after apiKey creation (#322)
- Problem to empty the Url field of Service Targets (#327)
- Remove bintray links (#324)

## [1.4.9] - 2019-07-15

https://github.com/MAIF/otoroshi/milestone/21?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.8...v1.4.9
https://github.com/MAIF/otoroshi/releases/tag/v1.4.9

### Added 

- Http method and path validation per apikey (#315)
- Add new new concepts on targets like matcher, manual DNS resolution, etc  (#309, #310)
- Support new loadbalancing policies (#80, #79, #77) 
- Add expression language to headers in/out values (#308)
- Routing based on apikey roles and metadata (#307)
- Live switching of the default http client (#300)

### Changed 

- Improve otoroshi exchange protocol settings exchange protocol (#320)
- Support CIDR notation in ip address whitelists / blacklists (#318)
- Add "items in arrays" validation in JWT verifiers (#290)
- Transfer more tags and metadata in third party api key from OIDC tokens (#317)
- Remove support for `ahc:` http client (#302)
- Better timeout management with the akka http client (#301)


### Fixed 

- Enforce TTL on secured exchange protocol v2 bug (#316)
- Remove default ssl context dump (#303)
- APP_STORAGE is missing for AWS configuration (#304)
- Cannot read property 'data.dataIn' of null in Analytics (#305)

## [1.4.8] - 2019-05-06

https://github.com/MAIF/otoroshi/milestone/20?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.7...v1.4.8
https://github.com/MAIF/otoroshi/releases/tag/v1.4.8

### Added
- Support datastores other than redis for clusterings cluster
- Support enterprise proxy to access outside world enterprise
- Introduce secure exchange v2
- Provide a "serverless" trait for request transformer
- Disable HTTP/1.0 per service
- Provide a root CA to trust at startup through config.
- Add api key constraints
- Third party apikeys (OIDC) verification module 
- Support Mailjet as alert mailer
- Implements /me endpoint for private apps 

### Changed
- Drop support for leveldb store datastore
- Consider using a JsValue instead of Option[String, String] in private apps sesssions 
- Remove dev centric features 
- Add last sync date in /health of cluster workers 
- OAuth: do not send client_secret if not specified
- Display event content in service events
- Add query param to filter response body on analytics apis
- Add documentation about /metrics and /health  


### Fixed
- Use password input in 'in memory auth. module'
- Fix missing doc in request transformer scripts section
- Fix self signed certificate 
- Fix certificate generation at startup 
- Disabled service should not be used in routing

## [1.4.7] - 2019-03-08

https://github.com/MAIF/otoroshi/milestone/19?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.6...v1.4.7
https://github.com/MAIF/otoroshi/releases/tag/v1.4.7

### Added
- documentation to deploy otoroshi en AWS Elastic Beanstalk
- Support for Mailgun EU
- Support for Prometheus metrics
- Support for json metrics
- New analytics apis that are more flexible
- Header value verification post routing
- additional headers out
- shortcut to add security headers
- gzip support

### Changed
- Show sessions profile and metadata in modal window
- Do not use native modals in browser anymore
- OIDC auto config is more reliable
- Chunked response handling has been rewritten

### Fixed
- reload admins table after adding a new admin
- Content-Encoding is not missing anymore in responses

## [1.4.6] - 2019-02-18

https://github.com/MAIF/otoroshi/milestone/18?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.5...v1.4.6
https://github.com/MAIF/otoroshi/releases/tag/v1.4.6

### Changed
- Updated swagger according to last API changes
- Fixed OAuth / OIDC scope settings reading from datastore

## [1.4.5] - 2019-02-18

https://github.com/MAIF/otoroshi/milestone/17?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.4...v1.4.5
https://github.com/MAIF/otoroshi/releases/tag/v1.4.5

### Added
- flag to enabled Host header override
- flag for global maintenance mode 
- env. var for global maintenance mode 
- support for X-Forwarded-* headers between otoroshi and targets
- support for additional headers between otoroshi and clients
- auto configuration of OIDC module from its well known public configuration URL

### Changed
- OAuth2 / OIDC option to read profile from JWT token or user info endpoint
- OAuth2 / OIDC option to use URL Form Encoded or JSON for payloads

## [1.4.4] - 2019-01-28

https://github.com/MAIF/otoroshi/milestone/16?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.3...v1.4.4
https://github.com/MAIF/otoroshi/releases/tag/v1.4.4

### Changed
- improve analytics dashboards (hits by apikeys and users)
- improve cluster reporting (display all members of the cluster)
- fix corner cases where some routing data does not have the correct TTL

## [1.4.3] - 2019-01-23

https://github.com/MAIF/otoroshi/milestone/15?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.2...v1.4.3
https://github.com/MAIF/otoroshi/releases/tag/v1.4.3

### Added
- Request transformers

### Changed
- Xmas logo feature flipped

## [1.4.2] - 2018-12-21

https://github.com/MAIF/otoroshi/compare/v1.4.1...v1.4.2
https://github.com/MAIF/otoroshi/releases/tag/v1.4.2

### Changed
- Xmas logo feature flipped

## [1.4.1] - 2018-12-21

https://github.com/MAIF/otoroshi/milestone/13?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.0...v1.4.1
https://github.com/MAIF/otoroshi/releases/tag/v1.4.1
https://medium.com/oss-by-maif/otoroshi-v1-4-1-is-out-9e11eaa78354

### Added
- Support redirection per service
- Support HTTP/2 targets (using experimental http client behind a flag)
- Update to Bootstrap 3.4.0
- Support dynamic SSL/TLS termination
- Support dynamic mTLS connections between the client and Otoroshi
- Support dynamic mTLS connection between the Otoroshi and the target
- Support client certificate validation

## [1.4.0] - 2018-11-22

https://github.com/MAIF/otoroshi/milestone/9?closed=1
https://github.com/MAIF/otoroshi/compare/v1.3.1...v1.4.0
https://github.com/MAIF/otoroshi/releases/tag/v1.4.0

### Added
- Otoroshi clustering

## [1.3.1] - 2018-11-02

https://github.com/MAIF/otoroshi/milestone/11?closed=1
https://github.com/MAIF/otoroshi/compare/v1.3.0...v1.3.1
https://github.com/MAIF/otoroshi/releases/tag/v1.3.1

### Added
- Dynamic TLS support

## [1.3.0] - 2018-10-18

https://github.com/MAIF/otoroshi/milestone/10?closed=1
https://github.com/MAIF/otoroshi/compare/v1.2.0...v1.3.0
https://github.com/MAIF/otoroshi/releases/tag/v1.3.0

### Added
- Auth modules
- OAuth 2 auth module
- In memory auth module
- LDAP auth module
- CORS support
- Elastic support

## [1.2.0] - 2018-07-27

https://github.com/MAIF/otoroshi/milestone/3?closed=1
https://github.com/MAIF/otoroshi/compare/v1.1.2...v1.2.0
https://github.com/MAIF/otoroshi/releases/tag/v1.2.0

### Added
- Otoroshi first open-source release
- Mongo support
- JWT token verification
- Otoroshi exchange protocol customization
- Snow Monkey (chaos engineering)
- API Key as JWT token inside cookie

## [1.1.2] - 2018-06-01

https://github.com/MAIF/otoroshi/milestone/7?closed=1
https://github.com/MAIF/otoroshi/compare/v1.1.1...v1.1.2
https://github.com/MAIF/otoroshi/releases/tag/1.1.2

## [1.1.1] - 2018-03-22

https://github.com/MAIF/otoroshi/compare/v1.1.0...v1.1.1
https://github.com/MAIF/otoroshi/releases/tag/1.1.1

## [1.1.0] - 2018-03-19

https://github.com/MAIF/otoroshi/milestone/1?closed=1
https://github.com/MAIF/otoroshi/compare/v1.0.2...v1.1.0
https://github.com/MAIF/otoroshi/releases/tag/1.1.0

## [1.0.2] - 2018-02-13

https://github.com/MAIF/otoroshi/milestone/5?closed=1
https://github.com/MAIF/otoroshi/compare/1.0.1...v1.0.2
https://github.com/MAIF/otoroshi/releases/tag/1.0.2

### Changed
- #54 - cache invalidation is missing when a group is modified
- #56 - Docker image should provide a volume for import files
- #55 - Expiration does not work like it should in other datastores than Redis
- #53 - Set the cookie domain using the app.domain property
- #49 - Add flag to avoid exposition of admin dashboard and admin API
- #52 - Provide a demo instance of Otoroshi oss
- #50 - Product name is not propagated in analytic events

## [1.0.1] - 2018-02-07

https://github.com/MAIF/otoroshi/milestone/4?closed=1
https://github.com/MAIF/otoroshi/compare/1.0.0...1.0.1
https://github.com/MAIF/otoroshi/releases/tag/1.0.1

### Changed
- #44 - fix the 'No ApiKey provided' response when calling with an ApiKey
- #39 - Update Auth0 lock signin
- #37 - Fix the "SEND_TO_ANALYTICS_ERROR" error
- #38 - Fix the "Server Error Clock is running backward" error
- #42 - Fix a bug preventing the edition of a service url
- #41 - Fix the "Open group link" button in "All service groups" page

## [1.0.0] - 2018-01-18

https://github.com/MAIF/otoroshi/releases/tag/1.0.0
Otoroshi first open-source release

