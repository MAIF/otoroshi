# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [16.17.0] - 2024-04-29


https://github.com/MAIF/otoroshi/milestone/94?closed=1
https://github.com/MAIF/otoroshi/compare/v16.16.1...v16.17.0
https://github.com/MAIF/otoroshi/releases/tag/v16.17.0


### Added 

- Add plugins to implement Auth0 passwordless login flow (#1892)
- support form-urlencoded payloads for write operations on generic admin apis (#1895)
- support kubernetes armored payloads for read operations on generic admin apis (#1897)
- JDK 22 support for docker images (#1898)       
      
### Changed 

- bump wasm4s version (#1893)
- support kubernetes armored payloads for write operations on generic admin apis (#1896)
- bump biscuit version (#1899)
- bump netty version (#1902)
- bump reactor netty version (#1903)       
      
### Fixed 

- zip backend plugin: stackoverflow when ttl expired (#1894)
- http3 client send duplicated headers (#1900)
- http3 client close connection pipe before end of body stream (#1901)
- http3 server access log induce wrong http3 frame in the http3 decoder (#1904)
- new netty version removes QuicConnectionEvent hence we're not able to get remote ip address (#1905)       
      

### Contributors

* @mathieuancelin

## [16.16.1] - 2024-04-03


https://github.com/MAIF/otoroshi/milestone/93?closed=1
https://github.com/MAIF/otoroshi/compare/v16.16.0...v16.16.1
https://github.com/MAIF/otoroshi/releases/tag/v16.16.1



### Changed 

- bump acme4j version (#1885)
- bump bouncycastle version (#1886)       
      
### Fixed 

- parsing error when reading letsEncrypt settings (#1884)
- ACME challenge can be triggered before challenge authorization has been stored (#1887)       
      

### Contributors

* @mathieuancelin

## [16.16.0] - 2024-03-31


https://github.com/MAIF/otoroshi/milestone/89?closed=1
https://github.com/MAIF/otoroshi/compare/v16.15.4...v16.16.0
https://github.com/MAIF/otoroshi/releases/tag/v16.16.0



### Changed 

- Default Jwt token : added values do not support expression language (#1867)
- NgWebsocketBackendPlugin needs to be able to return an error instead on the WS flow (#1869)
- upgrade to latest wasm4s version (#1871)       
      
### Fixed 

- login form is not responsive on mobile phone (#1827)
- Default Jwt Token : The claim 'nbf' contained a non-numeric date value. (#1866)
- Default Jwt token : the format of claim dates is in milliseconds (#1868)
- JWT selector in Jwt plugins is too limited on types (#1870)       
      

### Contributors

* @mathieuancelin
* @Zwiterrion

## [16.15.4] - 2024-03-21


https://github.com/MAIF/otoroshi/milestone/91?closed=1
https://github.com/MAIF/otoroshi/compare/v16.15.3...v16.15.4
https://github.com/MAIF/otoroshi/releases/tag/v16.15.4


### Added 

- Add new kind of plugin for websocket backend calls (#1863)       
      
### Changed 

- Enhance jsonpath api for better performances and better DX (#1862)       
      
### Fixed 

- move scala-schema jar in lib as jitpack is not reliable enough (#1864)       
      

### Contributors

* @mathieuancelin

## [16.15.3] - 2024-03-15


https://github.com/MAIF/otoroshi/milestone/90?closed=1
https://github.com/MAIF/otoroshi/compare/v16.15.2...v16.15.3
https://github.com/MAIF/otoroshi/releases/tag/v16.15.3



### Changed 

- Add state callback in the Table component (#1858)
- better handling of Websockets errors when using the Websocket Wasm transform plugin (#1860)       
      
### Fixed 

- retrieving and updating wasmo settings from danger zone didn't work (#1857)       
      

### Contributors

* @Zwiterrion
* @mathieuancelin

## [16.15.2] - 2024-03-15


https://github.com/MAIF/otoroshi/milestone/87?closed=1
https://github.com/MAIF/otoroshi/compare/v16.15.1...v16.15.2
https://github.com/MAIF/otoroshi/releases/tag/v16.15.2



### Changed 

- Custom data exporters should support custom config. forms (#1854)
- Supports NotContainedIn and ContainedIn expressions in EL (#1855)       
      
### Fixed 

- Missing template for Custom data exporter (#1853)
- build does not publish the maif/otoroshi:xxxx image (#1856)       
      

### Contributors

* @mathieuancelin
* @Zwiterrion

## [16.15.1] - 2024-02-29


https://github.com/MAIF/otoroshi/milestone/88?closed=1
https://github.com/MAIF/otoroshi/compare/v16.15.0...v16.15.1
https://github.com/MAIF/otoroshi/releases/tag/v16.15.1



### Changed 

- Plugin can have a custom category (#1849)       
      


### Contributors

* @mathieuancelin

## [16.15.0] - 2024-02-29


https://github.com/MAIF/otoroshi/milestone/86?closed=1
https://github.com/MAIF/otoroshi/compare/v16.14.0...v16.15.0
https://github.com/MAIF/otoroshi/releases/tag/v16.15.0


### Added 

- Bump to the latest wasm4s version to be able to use the Classpath source kind (#1842)
- Coraza plugin could benefits from latest wasm4s enhancements (#1848)       
      
### Changed 

- prevent creating wasmo wasm plugin without source (#1838)       
      
### Fixed 

- routes created with admin api doesn't show plugins if they don't have nodeId/plugin_index (#1832)
- Default Token Strategy doesn't include json content (#1835)
- DataStoresBuilder does not extends NamedPlugin (#1839)
- Custom data exporter list is not well displayed in the data exporter pages (#1840)
- Some plugins API does not extends the right parent API (#1841)
- the WasmPlugin entity does not provide template API (#1843)
- There is no easy way to consume next gen apis from the backoffice (#1844)
- wasm backendcall plugins must be able to delegate call if needed (#1845)
- plugin without js form descriptor embedded in the backoffice cannot be displayed in the route designer (#1846)
- wasmo configuration values are broken in documentation (#1847)       
      
### Documentation 

- wasmo configuration values are broken in documentation (#1847)       
      
### Contributors

* @mathieuancelin
* @Zwiterrion
* @ptitFicus

## [16.14.0] - 2024-02-07


https://github.com/MAIF/otoroshi/milestone/85?closed=1
https://github.com/MAIF/otoroshi/compare/v16.13.0...v16.14.0
https://github.com/MAIF/otoroshi/releases/tag/v16.14.0


### Added 

- upgrade coraza (#1822)
- pass identity tags and metadata to analytics events (#1829)       
      

### Fixed 

- wasm vm is released after each function call (#1821)
- "Response chunk size read is different from the computed one logs" with coraza (#1824)
- documentation style is broken on iOS (#1826)       
      

### Contributors

* @mathieuancelin

## [16.13.0] - 2024-01-22


https://github.com/MAIF/otoroshi/milestone/84?closed=1
https://github.com/MAIF/otoroshi/compare/v16.12.0...v16.13.0
https://github.com/MAIF/otoroshi/releases/tag/v16.13.0


### Added 

- allow deep search in the findAll operation of the admin api (#1803)
- read local state from the admin api when necessary (#1804)
- plugin to block request based on an el expression (#1806)
- plugin to allow request based on an el expression (#1807)
- plugin to block request if an apikey does no have the specified metadata (#1808)
- plugin to block request if an apikey does no have the specified tags (#1809)
- plugin to validate the current request with an external service using http request (#1810)       
      

### Fixed 

- elastic search config. forms needs to be sanitized (#1801)
- slf4j warning at startup (#1802)
- backend call plugins do not sent content-length when not provided (#1805)
- export buttons on entities are present two times in the UI (#1811)
- in some cases, clientId is wrongly inferred as entity id (#1812)
- extracting json or yaml on a route downloads an entity with kind JwtVerifier (#1813)
- 2.23.x of scala-schema is no longer available (#1814)
- data exporter form does not work with exporters other than elasticsearch (#1815)
- [data-exporter] elastic - cluster uris seems broken (#1817)       
      

### Contributors

* @mathieuancelin
* @Zwiterrion
* @quentinovega

## [16.12.0] - 2023-12-22


https://github.com/MAIF/otoroshi/milestone/76?closed=1
https://github.com/MAIF/otoroshi/compare/v16.11.2...v16.12.0
https://github.com/MAIF/otoroshi/releases/tag/v16.12.0


### Added 

- Move build and release process on github actions (#788)
- add a plugin to serve a zip file content as a backend (#1785)
- add security checks to use env and config from the expression language (#1792)
- plugin that let you choose how to configure throttling based on el expressions (#1793)
- plugin that let you choose how to configure quotas based on el expressions (#1794)
- introduce a new kind of safe single value apikey (#1795)
- plugins blocklist to avoid security issues in some use cases (#1798)       
      
### Changed 

- redesign the route pages organisation (#1790)
- redesign the topbar (#1791)       
      
### Fixed 

- leaving some route pages didn't clean the route sidebar (#1788)
- upgrade old static jquery dist (#1789)
- error in zip backend plugin (#1799)
- JSON and YAML export buttons not working on route designer (#1800)       
      

### Contributors

* @mathieuancelin
* @Zwiterrion
* @baudelotphilippe

## [16.11.2] - 2023-12-05


https://github.com/MAIF/otoroshi/milestone/83?closed=1
https://github.com/MAIF/otoroshi/compare/v16.11.1...v16.11.2
https://github.com/MAIF/otoroshi/releases/tag/v16.11.2


### Added 

- close milestone at the end of the release (#1783)
- generate milestone in release process (#1784)       
      



### Contributors

* @mathieuancelin

## [16.11.1] - 2023-12-05

https://github.com/MAIF/otoroshi/milestone/82?closed=1
https://github.com/MAIF/otoroshi/compare/v16.11.0...v16.11.1
https://github.com/MAIF/otoroshi/releases/tag/v16.11.1


### Fixed 

- Got an error NoClassDefFoundError io.otoroshi.wasm4s.impl.WasmVmPoolImpl when calling route with wasm plugin (#1781)
- wasm not working with windows (#1782)

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @baudelotphilippe 


## [16.11.0] - 2023-11-29

https://github.com/MAIF/otoroshi/milestone/75?closed=1
https://github.com/MAIF/otoroshi/compare/v16.10.3...v16.11.0
https://github.com/MAIF/otoroshi/releases/tag/v16.11.0


### Added 

- support pluggable datastores (#1722)
- support pluggable data exporters (#1723)
- add the ability to override existing admin api paths in extensions (#1738)
- any otoroshi link could be a shortcut (#1761)

### Changed 

- moving common-wasm project in its own repo (#1732)
- rewrite wasm manager documentation (#1754)
- bump wasm4s to 2.0.0 (#1779)

### Fixed 

- Title not refresh (#1756)
- Cancel not working (#1757)
- Pb with link to User manual (abs/rel) (#1758)
- The `Routes only` flag on danger zone has reverse operation (#1777)

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @baudelotphilippe 


## [16.10.3] - 2023-11-16

https://github.com/MAIF/otoroshi/milestone/81?closed=1
https://github.com/MAIF/otoroshi/compare/v16.10.2...v16.10.3
https://github.com/MAIF/otoroshi/releases/tag/v16.10.3


### Fixed

- common-wasm: caching process does not work (#1764)

### Contributors

* @mathieuancelin 
* @baudelotphilippe 


## [16.10.2] - 2023-11-14

https://github.com/MAIF/otoroshi/milestone/80?closed=1
https://github.com/MAIF/otoroshi/compare/v16.10.1...v16.10.2
https://github.com/MAIF/otoroshi/releases/tag/v16.10.2


### Added

- add new documentation search engine (#1762)

### Fixed

- select are sometimes blank even with a value (#1763)

### Contributors

* @mathieuancelin 
* @Zwiterrion 


## [16.10.1] - 2023-11-10

https://github.com/MAIF/otoroshi/milestone/79?closed=1
https://github.com/MAIF/otoroshi/compare/v16.10.0...v16.10.1
https://github.com/MAIF/otoroshi/releases/tag/v16.10.1

### Fixed

- selects are empty  (#1759)
- fix integration of wasmo (#1760)

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @baudelotphilippe 


## [16.10.0] - 2023-10-31

https://github.com/MAIF/otoroshi/milestone/73?closed=1
https://github.com/MAIF/otoroshi/compare/v16.9.2...v16.10.0
https://github.com/MAIF/otoroshi/releases/tag/v16.10.0

### Added

- support route designer tabs in admin extensions (#1693)
- provide an extension to compute green score of your apis (#1704)
- WasmManager : create a public API (#1725)
- Github version of the Wasm Manager (#1728)
- display version in wasm-manager (#1737)
- add jdk21 temuring build back in release process (#1741)
- add the ability to customize otoroshi sidebar per user (#1746)

### Changed

- Migration to aws-sdk v3 (#1743)
- Migrate from wapm to wasmer (#1745)
- moving wasm-manager in its own repository (#1740)

### Fixed 

- analytics dashboard seems to be broken with elastic 8 (#1709)
- WasmManager : endpoint /wasm/name/version does not work in 16.9.0 (#1724)
- delete groups on greenscore generate errors (#1734)
- investigate around local backoffice proxy that behaves weirdly (#1739)
- missing algorithm on encrypted private key (#1747)
- netty http client sends empty cookie header in some cases (#1751)
- query param transformer is broken in some cases (#1752)"

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @baudelotphilippe 


## [16.9.2] - 2023-10-11

https://github.com/MAIF/otoroshi/milestone/78?closed=1
https://github.com/MAIF/otoroshi/compare/v16.9.1...v16.9.2
https://github.com/MAIF/otoroshi/releases/tag/v16.9.2

### Added

- support one time jobs (#670)

### Fixed

- missing entity kind in postgresql datastore implementation (#1735)

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @baudelotphilippe 


## [16.9.1] - 2023-10-09

https://github.com/MAIF/otoroshi/milestone/77?closed=1
https://github.com/MAIF/otoroshi/compare/v16.9.0...v16.9.1
https://github.com/MAIF/otoroshi/releases/tag/v16.9.1

### Added

- make optional Extism runtime for launching the wasm manager (#1727)
- add button in plugin view to download WASM binaries (#1729)

### Fixed

- bad overhead out computing in case of circuit breaker opening (#1731)

### Contributors

* @mathieuancelin 
* @Zwiterrion 

## [16.9.0] - 2023-09-29

https://github.com/MAIF/otoroshi/milestone/74?closed=1
https://github.com/MAIF/otoroshi/compare/v16.8.1...v16.9.0
https://github.com/MAIF/otoroshi/releases/tag/v16.9.0

### Added

- support entity validators per admin api consumer (user or apikey) using opa rules (#1629)
- support Infisical as secret vault (#1688)
- add JDK21 docker image (#1705)
- add support for custom vaults in admin extensions (#1708)
- restore request size computation if not too sluggish (#1710)
- ALPN customization per domain name (#1720)
- remove protocol specific headers when backend target use lower protocol version (#1721)

### Changed 

- split wasm runtime into a separate library (#1702)
- rework on the cluster protocol (#1713)
- react-select 1.2.1 -> 5.x (#1718)

### Fixed 

- WasmManager : it should be launched with pm2 or similar tool (#1698)
- Wasm manager docker image build warnings (#1699)
- wasm manager project query param sometimes has bad value (#1703)
- wasm manager is not built with a version (#1707)
- Backend : Cannot reach HTTP/1.1 (no SSL) Backend  ? (#1711)
- admin rights sometimes fails when super admin (#1712)

### Contributors

* @mathieuancelin 
* @Zwiterrion 
* @ptitFicus 
* @baudelotphilippe 

## [16.8.1] - 2023-09-07

https://github.com/MAIF/otoroshi/milestone/72?closed=1
https://github.com/MAIF/otoroshi/compare/v16.8.0...v16.8.1
https://github.com/MAIF/otoroshi/releases/tag/v16.8.1

### Changed 

- update coraza to 0.1.2 (#1697)

### Fixed 

- force elastic version read from server even if applyTemplate not checked (#1700)
- update default elastic version in code with 8.0.0 (#1701)


## [16.8.0] - 2023-08-31

https://github.com/MAIF/otoroshi/milestone/71?closed=1
https://github.com/MAIF/otoroshi/compare/v16.7.0...v16.8.0
https://github.com/MAIF/otoroshi/releases/tag/v16.8.0

### Added 

- allow different mutual auth. settings on play server and netty server (#1691)
- add endpoints to get otoroshi version and cluster infos (#1696)

### Fixed

- tls session issue with first request using TLS 1.3 (#1695)
- issue when consuming backoffice with the netty backend and tls (#1694)
- issue in el add on 16.7.0 with this kind of EL '${now.plus_ms(300000).epoch_ms}' (#1689)


## [16.7.0] - 2023-08-01

https://github.com/MAIF/otoroshi/milestone/70?closed=1
https://github.com/MAIF/otoroshi/compare/v16.6.0...v16.7.0
https://github.com/MAIF/otoroshi/releases/tag/v16.7.0

### Added 

- X-Forwarded-Prefix not send with other X-Forwarded-* (#1626)
- new kind of data exporter for opentelemetry metrics (#1637)
- new kind of data exporter for opentelemetry logs (#1636)
- integration with opentelemetry logs (#1635)
- add new endpoint to get one simple admin at a time (#1681)
- support opentelemetry for internal metrics (#1686)
- support date manipulation in expression language (#1682)
- Allow to access script by url (#1670)
- SAMLv2 improvements (#1685)

### Fixed

- updating admin with api leads to an error because its expected to provide a backoffice user (#1684)
- Auth module - SAML : Authentication successfull, but loop to login page (#1671)
- bad SAML2 url composition in some cases (#1683)
- circuit breaker help labels broken (#1687)


## [16.6.0] - 2023-07-19

https://github.com/MAIF/otoroshi/milestone/67?closed=1
https://github.com/MAIF/otoroshi/compare/v16.5.2...v16.6.0
https://github.com/MAIF/otoroshi/releases/tag/v16.6.0

### Added

- add generic pooling support for mono-threaded WASM VMs (#1642)
- add routine to automatically kill unused vm, vm with too much memory consumed, etc  (#1649)
- tweak extism to provide efficient vm instanciation with scoped host functions (#1621)
- include `$jq` operator in Projection utils (#1656)
- documentation fo jsonpath based projections (#1662)
- documentation for matcher operators (#1631)
- add the ability to test filtering and projection from data-exporter view (#1675)
- use wasm-opt in the wasm manager to cleanup produced wasm (#1630)
- doc: add circuit breaker topic (#1641)


### Changed

- remove lifetime from wasm vm config. (#1654)
- support wasm vm pool on non wasm plugin configs. (#1652)
- support coraza calls in wasm vm pools (#1643)
- support OPA calls in wasm vm pools (#1646)
- rewrite wasm plugins to use vm pools (#1645)
- rewrite coraza plugin to avoid blocking (#1644)
- rewrite wasm data exporters to use vm pools (#1647)
- rewrite wasm auth plugins to use vm pools (#1653)

### Fixed

- make template update work in multi-thread env. (#1648)
- handle requests burst on wasm plugin (#1650)
- auth module - SAML : Using wizard, Single Logout URL is mandatory? (#1663)
- removing all lines from a file make it impossible to edit (#1673)
- saml request should be deflated before base64 encoding (#1678)


## [16.5.2] - 2023-07-04

https://github.com/MAIF/otoroshi/milestone/69?closed=1
https://github.com/MAIF/otoroshi/compare/v16.5.1...v16.5.2
https://github.com/MAIF/otoroshi/releases/tag/v16.5.2

### Fixed

- ServiceDescriptor to route conversion does not copy IP whitelist (#1639)
- fix bad value resolution in cluster config (#1640)


## [16.5.1] - 2023-07-03

https://github.com/MAIF/otoroshi/milestone/68?closed=1
https://github.com/MAIF/otoroshi/compare/v16.5.0...v16.5.1
https://github.com/MAIF/otoroshi/releases/tag/v16.5.1

### Fixed

- Plugins that synchronously transform response are not merged correctly (#1632)
- Chaos monkey body length related faults does not work propertly (#1634)


## [16.5.0] - 2023-06-30

https://github.com/MAIF/otoroshi/milestone/66?closed=1
https://github.com/MAIF/otoroshi/compare/v16.4.0...v16.5.0
https://github.com/MAIF/otoroshi/releases/tag/v16.5.0

- enhance gRPC support (#1299)
- support entity validators per admin api consumer (user or apikey) using opa rules (#1629)
- use wasm-gc in the wasm manager to cleanup produced wasm (#1630)
- support plugins thats ships their own custom UI (#1300)
- Externals vault - Support getting certificates (#1364)
- support entity validators per admin api consumer (user or apikey) (#1617)
- user rights check is broken on the admin api (#1618)
- add more validation to avoid data override in the admin api (#1619)
- upgrade netty dependencies (#1620)
- update coraza version (#1622)
- fix opa execution crash on second invocation (#1623)
- play framework add default content-type on response with no content-type (#1624)
- otoroshi does not support `application/json` mediatype with open charset  (#1625)


## [16.4.0] - 2023-05-25

https://github.com/MAIF/otoroshi/milestone/63?closed=1
https://github.com/MAIF/otoroshi/compare/v16.3.2...v16.4.0
https://github.com/MAIF/otoroshi/releases/tag/v16.4.0

### Added

- provide a Brotli compression plugin (#1289)
- support OWASP Coraza as WASM go plugin (#1497)
- support wasm data exporters (#1565)
- support wasm auth. modules (#1598)
- handle ctrl+s shortcut to save route (#1594)
- enable anonymous reporting (#1595)
- make authentication modules pluggable (#1285)

### Fixed

- add the ability to extract informations from apikeys as jwt tokens (#1584)
- avoid creating linear memory instance when non OPA plugin (#1586)
- discard invitation link expired (#1587)
- config. validation happens before configuration merge (#1588)
- better concurrency handling when fetching wasm sources (#1589)
- better handling of wasm resources to enhance performances at scale (#1590)
- route events view does not allow filtering (#1591)
- prohibit creation of new User with empty informations (#1596)
- Content-Length missing due to akka-http-client model (#1597)
- bad conversion of service descriptor to route for info. token (#1600)
- host functions around attributes does not work when lifetime is forever (#1604)
- data exporter duplication (#1610)
- regex routing is broken (#1611)
- handle target from service name and port in kubernetes crds for a route (#1612)
- add otoroshi.mesh domains on route in kubernetes crds (#1613)

## [16.3.2] - 2023-04-26

https://github.com/MAIF/otoroshi/milestone/65?closed=1
https://github.com/MAIF/otoroshi/compare/v16.3.1...v16.3.2
https://github.com/MAIF/otoroshi/releases/tag/v16.3.2

### Fixed

- wasm plugin can be an OPA policy (#1579)
- add query params in plugin http request json representation (#1580)
- wasm request transformer is never called (#1581)
- handle left case or request/response transformers when using wasm (#1582)

## [16.3.1] - 2023-04-25

https://github.com/MAIF/otoroshi/milestone/64?closed=1
https://github.com/MAIF/otoroshi/compare/v16.3.0...v16.3.1
https://github.com/MAIF/otoroshi/releases/tag/v16.3.1

### Fixed

- rust plugin does not produce version in wasm manager (#1574)
- opa wasm plugin should produce a nice error page when forbidden (#1575)
- wasm-plugin template has a static id (#1576)
- rust wasm plugin template has bad path to lib.rs file (#1577)
- go wasm plugin template is not right by default (#1578)

## [16.3.0] - 2023-04-25

https://github.com/MAIF/otoroshi/milestone/61?closed=1
https://github.com/MAIF/otoroshi/compare/v16.2.1...v16.3.0
https://github.com/MAIF/otoroshi/releases/tag/v16.3.0

Major features announcements in this release are 

- all legacy plugins have been migrated to the new plugin model
- a new kind of plugin has been introduced to extend otoroshi api easily
- new spring cloud config. vault backend
- new http vault backend
- new local vault backend where secrets are stored in the otoroshi global config.


### Added

- add the ability to run jobs/data-exporters only on specific network zones with specific configuration per zone (#1472)
- add a new kind of vault that stores values in otoroshi (#1522)
- support vaults in EL (#1523)
- support spring cloud config as a vault backend (#1527)
- support http as a vault backend (#1528)
- support memberUid based ldap groups (#1560)
- provide an admin. extension api to make core contributions easier#1510

### Changed

- enhance the health check possibilities (#1372)
- remember sidebar state (#1524)

### Fixed

- cannot instantly add and modify an old plugin on a route  (#1511)
- fix synchronous plugins step merge (#1260)
- some service-descriptor -> route conversions does not work (#1521)
- wasmManager : give plugin name as package.json name (#1529)
- JWT claim extraction may not work  (#1564)
- can't create a route with multi instance plugins more than one (#1566)
- prevent chunking response when content length is equal to zero (#1571)
- can't find my route's apikey in route/apikeys tab (#1570)
- fix CRDs v1alpha1 served (#1512)
- rewrite legacy plugins as ng plugins (#1148)
- rewrite legacy HMAC validator as ng plugins (#1513)
- rewrite legacy HMAC Caller plugin (#1514)
- rewrite legacy Basic Auth Caller plugin (#1515)
- rewrite legacy OAuth 1 Caller plugin (#1516)
- rewrite legacy OAuth 2 Caller plugin (#1517)
- rewrite legacy response cache plugin (#1518)
- rewrite legacy OIDC plugins (#1520)
- rewrite ClientCertChainHeader (#1531)
- rewrite HasClientCertMatchingValidator (#1532)
- rewrite HasClientCertMatchingApikeyValidator (#1533)
- rewrite HasClientCertValidator (#1534)
- rewrite CertificateAsApikey (#1535)
- rewrite BiscuitExtractor (#1536)
- rewrite BiscuitValidator (#1537)
- rewrite HasClientCertMatchingHttpValidator (#1538)
- rewrite HasAllowedUsersValidator (#1539)
- rewrite UserAgentExtractor (#1540)
- rewrite UserAgentInfoEndpoint (#1541)
- rewrite UserAgentInfoHeader (#1542)
- rewrite SecurityTxt (#1543)
- rewrite ServiceQuotas (#1544)
- rewrite MirroringPlugin (#1545)
- rewrite Log4ShellFilter (#1546)
- rewrite JwtUserExtractor (#1547)
- rewrite IzanamiProxy (#1548)
- rewrite IzanamiCanary (#1549)
- rewrite HMACValidator (#1550)
- rewrite MaxMindGeolocationInfoExtractor (#1551)
- rewrite IpStackGeolocationInfoExtractor (#1552)
- rewrite GeolocationInfoHeader (#1553)
- rewrite GeolocationInfoEndpoint (#1554)
- rewrite DiscoverySelfRegistrationSink (#1555)
- rewrite DiscoverySelfRegistrationTransformer (#1556)
- rewrite DiscoveryTargetsSelector (#1557)
- rewrite DeferPlugin (#1558)
- rewrite ClientCredentialService (#1559)

## [16.2.1] - 2023-04-03

https://github.com/MAIF/otoroshi/milestone/62?closed=1
https://github.com/MAIF/otoroshi/compare/v16.2.0...v16.2.1
https://github.com/MAIF/otoroshi/releases/tag/v16.2.1

- fix release java version
- creation of wizard route failed (#1505)
- clean array of string in a plugin form doesn't work (#1506)
- bad header names in otoroshi challenge and info. plugins (#1507)

## [16.2.0] - 2023-03-31

https://github.com/MAIF/otoroshi/milestone/60?closed=1
https://github.com/MAIF/otoroshi/compare/v16.1.0...v16.2.0
https://github.com/MAIF/otoroshi/releases/tag/v16.2.0

Major features announcements in this release are 

- new plugin kind to handle routing decisions
- [WASM host functions](https://maif.github.io/otoroshi/devmanual/topics/wasm-usage.html)
- new WASM plugin entities
- New wasm plugin kind supported (request handlers, jobs, ...)
- Lots of improvements on the WASM manager
- New backup feature to improve resilience of otoroshi workers
- JDK20 docker images

### Added

- add an anonymous telemetry agent to gather usage statistics (#1305)
- research around open policy agent integration (#1315)
- support file versioning through git integration (#1453)
- support versioned binary releases (#1454)
- support selecting a binary release from the wasm manager (#1455)
- add otoroshi integration through host functions (#1456)
- add graphql directive to handle wasm targets (#1457)
- support github repo as sources of the wasm manager (#1460)
- implement host functions and flags to enable special access for wasm plugins (#1464)
- ensure wasm execution happens in a dedicated execution context outside of main one (#1478)
- support S3 import during first leader sync if leader down (#1479)
- support wasm jobs (#1480)
- support wasm RequestHandlers (#1481)
- support fetching wapm artifacts (#1482)
- new kind of plugin that can take routing decisions (#1483)
- refactor wasm related plugin stuff to have a common infrastructure (#1484)
- let wasm vm run during the whole request lifecycle to speed up reuse (#1485)
- add a new kind of entity to handle wasm scripts at one place (#1486)
- support WASI allowed paths for wasm  (#1488)
- provide host function wrappers (#1491)
- add documentations for wasm plugins (#1492)
- support call to \"native\" WASM functions (#1494)
- upload/donwload plugin sources (zip) (#1498)
- Support JDK20 (#1500)
- rewrite wasm plugins creation process in wasm manager (#1503)
- support request attributes access from wasm (#1504)

### Fixed

- fix openapi generator to handle generic apis (#1487)
- potential memory leak caused by big timeouts in kubernetes client (#1489)
- fix otoroshi exchange protocol form (#1495)
- bad cors translation to ng plugins (#1496)
- Prevent multiples plugins with same name (#1499)
- function name override does not seems to work (#1502)


## [16.1.0] - 2023-02-28

https://github.com/MAIF/otoroshi/milestone/59?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.5...v16.1.0
https://github.com/MAIF/otoroshi/releases/tag/v16.1.0

Major features announcements in this release are 

- [WASM plugin support in otoroshi](https://maif.github.io/otoroshi/manual/how-to-s/wasm-usage.html)
- [WASM manager to help you build WASM plugins](https://maif.github.io/otoroshi/manual/how-to-s/wasm-manager-installation.html)
- [Tailscale integration](https://maif.github.io/otoroshi/manual/how-to-s/tailscale-integration.html)

### Added

- tailscale cert job wants to get cert not handled by tailscale (#1451)
- add documentation for tailscale documentation (#1462)
- tailscale TLS integration (#1448)
- tailscale machines integrations (#1449)
- http cache plugin (#1423)
- error response rewrite plugin (#1424)
- missing request body plugin (#1425)
- full state exporter for disaster recovery (#1426)
- support .pfx files import (#1427)

### Changed

- elastic export improvments (#1466)
- ability to use tls settings in the SOAPAction plugin (#1469)
- upgrade to Play framework version 2.8.19 (#1447)
- enhance vault mechanism (#1296)

### Fixed

- http3 request failure (#1468)
- do not show save button on jwt verifiers page when path contains 'edit' (#1465)
- proxy settings in route backend parsing fails (#1450)
- plain text h2c calls failing (#1446)
- netty native transport fails on linux (#1428)
- fix missing resources in documentation (#1429)
- fix errors in kubernetes documentation (#1430)
- jobs cannot be added in danger zone plugins, only in scripts (#1431)
- fix coredns integration on azure (#1432)
- fix coredns integration on kubernetes (#1433)

## [16.0.5] - 2023-01-26

https://github.com/MAIF/otoroshi/milestone/58?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.4...v16.0.5
https://github.com/MAIF/otoroshi/releases/tag/v16.0.5

- resources loader - Certificate (#1420)
- handle coredns integration on AKS (#1421)
- bad query string forward (#1422)

## [16.0.4] - 2023-01-23

https://github.com/MAIF/otoroshi/milestone/57?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.3...v16.0.4
https://github.com/MAIF/otoroshi/releases/tag/v16.0.4

- bad url path when using tls (#1419)

## [16.0.3] - 2023-01-23

https://github.com/MAIF/otoroshi/milestone/56?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.2...v16.0.3
https://github.com/MAIF/otoroshi/releases/tag/v16.0.3

- certificate with bad from/to does not update until next expiration job (#1407)
- create a prometheus exporter with custom metrics (#1409)
- resources loader - Backend (#1411)
- enabling exporters from list does not work anymore (#1412)
- can create missing labels in metrics exporters (#1413)
- sometimes cert resolver choose the wrong wildcard certificate (#1418)

## [16.0.2] - 2023-01-13

https://github.com/MAIF/otoroshi/milestone/55?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.1...v16.0.2
https://github.com/MAIF/otoroshi/releases/tag/v16.0.2

- weird behavior of the \"authorized on\" select in apikeys (#1399)
- weird behavior of the \"groups\" select in routes / services (#1400)
- handle errors correctly in the new engine (#1403)
- handle oauth server communication errors (#1404)
- missing ui descriptor for `cp:otoroshi.next.plugins.OtoroshiHeadersIn` (#1405)
- avoid one more http call when backoffice call admin api (#1406)

## [16.0.1] - 2023-01-11

https://github.com/MAIF/otoroshi/milestone/54?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.0...v16.0.1
https://github.com/MAIF/otoroshi/releases/tag/v16.0.1

- Change the way requests from the admin backoffice are forwarded to the admin api (#1390)
- Rollout the features dashboard with rights management (#1391)
- fix bad request/response body forward through request transformer wrapper (#1392)
- fix possible rights escalation on the teams/organizations apis (#1393)
- weird behavior of the ObjectInput component that leads to reload loop (#1394)
- Add a new plugin to mimic behavior of old service on otoroshi specific headers in (#1395)
- /.well-known/otoroshi/me does not work with routes (#1401)
- /.well-known/otoroshi/logout does not work on routes (#1402)

## [16.0.0] - 2022-12-16

https://github.com/MAIF/otoroshi/milestone/53?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.0-rc3...v16.0.0
https://github.com/MAIF/otoroshi/releases/tag/v16.0.0

- remove leveldb support (#466)
- remove mongodb support (#467)
- ensure everything is wired for the rollout of the new proxy engine (#1161)
- introduce new versioning scheme (#1275)
- improve externals vault secrets-ttl (#1339)
- admin login does not work anymore after upgrading webauthn-server-core to 2.1.0 (#1340)
- switch lettuce as default redis driver (#1363)
- remove StoredNgTargets (#1365)
- creating an apikey from a route fails miserably (#1366)
- creating an auth. module from a route fails miserably (#1367)
- otoroshi challenge plugin ui is not completely setup with version selectors (#1368)
- add deprecation message on service descriptor pages (#1369)
- add conversion button on service descriptor (#1370)
- upgrade sbt-paradox (#1371)
- healthcheck not working with routes (#1373)
- sometimes job locks does not have ttl and are blocked (#1374)
- remove the react-paginate dependency (#1375)
- force advanced form view on jwt verifier creation (#1377)
- documentation updates (#1379)
- add missing entities in the sidebar (#1380)
- add features page (#1381)

## [16.0.0-rc.3] - 2022-12-15

https://github.com/MAIF/otoroshi/milestone/53?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.0-rc.2...v16.0.0-rc.3
https://github.com/MAIF/otoroshi/releases/tag/v16.0.0-rc.3

- healthcheck not working with routes (#1373)
- sometimes job locks does not have ttl and are blocked (#1374)
- Remove the react-paginate dependency (#1375)

## [16.0.0-rc.2] - 2022-12-14

https://github.com/MAIF/otoroshi/milestone/53?closed=1
https://github.com/MAIF/otoroshi/compare/v16.0.0-rc.1...v16.0.0-rc.2
https://github.com/MAIF/otoroshi/releases/tag/v16.0.0-rc.2

- creating an apikey from a route fails miserably (#1366)
- creating an auth. module from a route fails miserably (#1367)
- otoroshi challenge plugin ui is not completely setup with version selectors (#1368)
- add deprecation message on service descriptor pages (#1369)
- add conversion button on service descriptor (#1370)
- upgrade sbt-paradox (#1371)

## [16.0.0-rc.1] - 2022-12-13

https://github.com/MAIF/otoroshi/milestone/53?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.20...v16.0.0-rc.1
https://github.com/MAIF/otoroshi/releases/tag/v16.0.0-rc.1

- ensure everything is wired for the rollout of the new proxy engine (#1161)
- Remove leveldb support (#466)
- Remove mongodb support (#467)
- introduce new versioning scheme (#1275)
- Improve externals vault secrets-ttl (#1339)
- admin login does not work anymore after upgrading webauthn-server-core to 2.1.0 (#1340)
- switch lettuce as default redis driver (#1363)
- remove StoredNgTargets (#1365)

## [1.5.20] - 2022-11-30

https://github.com/MAIF/otoroshi/milestone/51?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.19...v1.5.20
https://github.com/MAIF/otoroshi/releases/tag/v1.5.20

- missing `clientSideSessionEnabled` flag in auth. modules ui (#1331)
- doc rewrite with routes (#1332)

## [1.5.19] - 2022-11-24

https://github.com/MAIF/otoroshi/milestone/52?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.18...v1.5.19
https://github.com/MAIF/otoroshi/releases/tag/v1.5.19

- finalize server side tables (#1329)

## [1.5.18] - 2022-11-23

https://github.com/MAIF/otoroshi/milestone/50?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.17...v1.5.18
https://github.com/MAIF/otoroshi/releases/tag/v1.5.18

- server side pagination for entities (#1256)
- provide QUIC remote address when using http3 (#1317)
- tls session extraction fails with HTTP/1.1 on netty backend (#1319)
- uriCache causes memory leaks in some cases (#1321)
- cleanup cache usage in codebase to avoid unbounded behaviors (#1322)
- upgrade swagger-ui (#1324)
- bad error message when using jwt verifiers (#1328)

## [1.5.17] - 2022-11-14

https://github.com/MAIF/otoroshi/milestone/49?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.16...v1.5.17
https://github.com/MAIF/otoroshi/releases/tag/v1.5.17

- make the auth module plugin on route behaves like service descriptor does (#1314)

## [1.5.16] - 2022-11-10

https://github.com/MAIF/otoroshi/milestone/48?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.15...v1.5.16
https://github.com/MAIF/otoroshi/releases/tag/v1.5.16

- button stay displayed inside Breadcrumb (#1264)
- fix public/private patterns issue again when converting service descriptors (#1274)
- fix bad default value for target protocol (#1306)
- fix long running issues on apikey plugin conversion on routes from service descriptors (#1307)
- otoroshi docker image not running on Apple Silicon (#1308)

## [1.5.15] - 2022-10-28

https://github.com/MAIF/otoroshi/milestone/47?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.14...v1.5.15
https://github.com/MAIF/otoroshi/releases/tag/v1.5.15

- support gRPC calls (#45)
- add experimental HTTP/3 support for netty client backend (#1255)
- bad public/private path translation for apikeycalls plugin (#1259)
- fix qpack dynamic table compression in http3 server when called from a browser (#1265)
- add an alt-svc plugin for h3 (#1266)
- fix CHACHA20 incompatibility with JDK11 (#1267)
- migrate HtmlPatcher plugin to new plugin API (#1268)
- do not fail jwks gen. on cert error (#1269)
- jvm dependencies upgrade (#1270)
- support trailer headers passing with http3 (#1271)
- reporting on subsystems initialization failures (#1272)

## [1.5.14] - 2022-09-30

https://github.com/MAIF/otoroshi/milestone/46?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.13...v1.5.14
https://github.com/MAIF/otoroshi/releases/tag/v1.5.14

- add experimental HTTP/3 support for netty server backend (#1254)
- kafka exporter - SASL Authentication (#1211)
- fix node communication behing load balancers for tunnels (#1234)
- fix relay routing images in documentation (#1235)
- generic docker image (#1236)
- env variables for akka http settings (#1237)
- new plugin to extract logged in user if any (#1241)
- new plugin to ensure a logged in user exists (#1242)
- fix ServiceDescriptor conversion when using `detectApiKeySooner` (#1243)
- better TLS defaults (#1244)
- add experimental server backend using netty and reactor-netty (#1245)
- matching routes issue (#1247)
- expose all keypairs in jwks endpoint (#1250)
- add experimental client backend using netty and reactor-netty (#1253)

## [1.5.13] - 2022-08-05

https://github.com/MAIF/otoroshi/milestone/45?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.12...v1.5.13
https://github.com/MAIF/otoroshi/releases/tag/v1.5.13

- allow exposing remote services using tunnels (#1206)
- Use eclipse-temurin as base image for docker builds (#1228)
- bad config. read for OtoroshiChallenge (#1229)
- first attempt at providing wizard for entity creation (#915)

## [1.5.12] - 2022-07-08

https://github.com/MAIF/otoroshi/milestone/44?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.11...v1.5.12
https://github.com/MAIF/otoroshi/releases/tag/v1.5.12

- introduce relay routing  (#1182)
- metrics viewer (#1193)
- add JDK20 build (#1194)
- upgrade webpack 4 to webpack 5 (#1194)

## [1.5.11] - 2022-06-29

https://github.com/MAIF/otoroshi/milestone/42?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.11...v1.5.11
https://github.com/MAIF/otoroshi/releases/tag/v1.5.11

- Static files/folder based backend (#1154)
- S3 bucket based backend (#1155)
- Enhance resilience for auth. module in cluster mode (#1156)
- Teams DELETE through UI and API return 404 (#1158)
- event exclusion on multiple predicate fails in dataexporters (#1160)
- used quotas are not displayed on apikeys anymore (#1163)
- add option for encrypted client side sessions on auth. modules (#1164)
- cached items should not be be synced across nodes (#1165)
- ApikeyCalls plugins decrements 2 calls per call in quotas (#1166)
- Upgrade playframework to 2.8.16 (#1167)
- exporter should be able to check 'otoroshi.instance' (#1177)
- Resource Loader - kind Organization or Tenant (#1173)
- Resource Loader - Global Config - YAML (#1176)
- Resource Loader - JwtVerifier (#1178)
- Resource Loader - Admin (#1179)


## [1.5.10] - 2022-05-16

https://github.com/MAIF/otoroshi/milestone/41?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.9...v1.5.10
https://github.com/MAIF/otoroshi/releases/tag/v1.5.10

- redis key conflict between healthcheck and StoredNgTarget (#1145)
- GraphQL Caller plugin (#1146)
- Avoid breaking legacy plugins (#1147)
- Introduce a new kind of plugin to handle backend call (#1149)
- do not fail ip address validation on `X-Forwarded-For` containing proxy address(es) (#1150)
- Fix bad regex value for default data exporter (#1151)
- implements some kind of traffic capture feature (#1152)
- Fix bad export for prometheus metrics (#1153)


## [1.5.9] - 2022-04-22

https://github.com/MAIF/otoroshi/milestone/40?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.8...v1.5.9
https://github.com/MAIF/otoroshi/releases/tag/v1.5.9

- fix bad config. reference that prevents otoroshi to start (#1144)

## [1.5.8] - 2022-04-21

https://github.com/MAIF/otoroshi/milestone/39?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.7...v1.5.8
https://github.com/MAIF/otoroshi/releases/tag/v1.5.8

**THIS RELEASE IS BROKEN, PLEASE UPGRADE DIRECTLY TO 1.5.9**

- fix bad config. reference that prevents otoroshi to start (#1143)

## [1.5.7] - 2022-04-21

https://github.com/MAIF/otoroshi/milestone/38?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.6...v1.5.7
https://github.com/MAIF/otoroshi/releases/tag/v1.5.7

**THIS RELEASE IS BROKEN, PLEASE UPGRADE DIRECTLY TO 1.5.8**

- Admin login not working while running the app in the default mode (#1137)
- fix ${otoroshi.domain} reference in config. file for cookie domain (#1138)
- Apikey plugin does not catch user cookie when possible (#1139)
- Implement token fetch for the Azure Key Vault (#1140)
- Enhance JSONPath validators (#1141)
- Think about the possibility to fetch vault secrets only on the leader nodes (#1142)

## [1.5.6] - 2022-04-19

https://github.com/MAIF/otoroshi/milestone/36?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.5...v1.5.6
https://github.com/MAIF/otoroshi/releases/tag/v1.5.6

- Upgrade biscuit-java lib to be able to use biscuit V2 (#1103)
- resources loader broken for apikeys (#1132)
- fix missing JDK17 build (#1133)
- fix context validation plugin (#1134)
- plugins missing in route designer (#1135)
- change log level for JWKS verifier (#1136)

## [1.5.5] - 2022-04-01

https://github.com/MAIF/otoroshi/milestone/35?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.4...v1.5.5
https://github.com/MAIF/otoroshi/releases/tag/v1.5.5


- Add hostname validation flag for kafka exporter (#1102)

## [1.5.4] - 2022-03-31

https://github.com/MAIF/otoroshi/milestone/34?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.3...v1.5.4
https://github.com/MAIF/otoroshi/releases/tag/v1.5.4

- Add storage section in documentation (#993)
- robots plugin (#1038)
- ordering issue with spread projection (#1047)
- fix gzip compression application (#1048)
- introduce plugins to handle payload transformation json <-> xml (#1049)
- introduce plugin to call soap actions (#1050)
- user validation post successful login (#1052)
- Support pluggable secret vaults throught EL and proxy state job (#1074)
- Kafka data exporter does not send events by default (#1077)
- Default templates for entities (#1078)
- Support new entities in kubernetes (#1079)
- New engine triggers weird behavior on resources when proxying simple website (#1084)
- Add docker image with JDK19 (#1089)

## [1.5.3] - 2022-02-14

https://github.com/MAIF/otoroshi/milestone/33?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.2...v1.5.3
https://github.com/MAIF/otoroshi/releases/tag/v1.5.3

- bad certificate parsing with jdk18 (#975)
- plugin Html Patcher - appendHead not working (#1015)
- otoroshi 1.5.2 - API Key usage not show (#1024)
- data exporter error log (#1026)
- experiment around rust tls termination proxy (#1027)
- bug in SSL cert selector (#1028)
- allow negative projection in DataExporter (#1029)
- bad ttl handling for the pg datastore (#1030)
- introduce the next proxy engine as a plugin (#1036)

## [1.5.2] - 2022-01-03

https://github.com/MAIF/otoroshi/milestone/32?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.1...v1.5.2
https://github.com/MAIF/otoroshi/releases/tag/v1.5.2

- Fix issue with CRD definition (#994)
- Upgrade log4j-api to avoid CVE-2021-44832 (#995)

## [1.5.1] - 2021-12-21

https://github.com/MAIF/otoroshi/milestone/31?closed=1
https://github.com/MAIF/otoroshi/compare/v1.5.0...v1.5.1
https://github.com/MAIF/otoroshi/releases/tag/v1.5.1

- Upgrade Log4J to 2.17.0 to mitigate CVE-2021-45105 (#992)

## [1.5.0] - 2021-12-17

https://github.com/MAIF/otoroshi/milestone/6?closed=1
https://github.com/MAIF/otoroshi/compare/v1.4.22...v1.5.0
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0

- Add pluggable authentication modules for services instead of Auth0 only (#3)
- More JWT support from Otoroshi to Backends (#4)
- Include Kubernetes ingress controller as a Job (#91)
- Mirror traffic (#118)
- Scoping otoroshi sources (#130)
- HealthCheck disable service strategy (#221)
- Add support for Redis cluster (#252)
- add bulk apis for main entities (#285)
- Cleanup documentation (#295)
- Support full OIDC / OAuth2 lifecycle with forwarded access token (#298)
- Make the UI multi-tenant (#311)
- Streaming input issue (#331)
- Identity aware TCP forwarding over HTTPS  (#332)
- Add a geoloc target matcher  (#338)
- Use ndjson raw export for the import/export feature instead of partial json (#343)
- Compatibility issues with Elastic 7.x (#344)
- Document tcp tunneling (#356)
- Update U2F documentation (#357)
- add a button to test LDAP connection (#426)
- Work on documentation (#445)
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
- Fix the version checker to understand alpha and beta (#669)
- Better coredns config patch (#671)
- Include jwt token fields in the elContext (#672)
- Add a clever cloud generator in doc page (#673)
- service registration fails when no endpoints (#674)
- Increase default chunk size for akka http (#676)
- kubernetes job improvments (#677)
- disabling global script should stop current jobs (#678)
- add button to export form data to YAML descriptor (#679)
- fix kubernetes job watch (#680)
- Experiment with a MutatingAdmissionWebhook to add a helper as a sidecar (#681)
- Experiment with a ValidatingAdmissionWebhook to return useful errors when deploying otoroshi entities (#682)
- Job for apikeys rotation (#683)
- Rename 'mtlsSettings' to 'tlsSettings' in the UI (#684)
- Add entries in the ApiKey secret to have Base64(client_id:client_secret) ready (#686)
- Provide job context to various duration function in Job api (#687)
- Use standard kubernetes service names in target transformation (#688)
- Add tenants and teams to crds (#689)
- Get kubernetes job interval from config. (#691)
- fix watch for ingress and certs (#692)
- add env in coredns customization (#693)
- handle coredns customization removal (#694)
- add various watch timeout in KubernetesConfig (#695)
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
- Add an api to get the availability of a service over time (#713)
- json editor adds '{}' at the end when pasting a json document (#714)
- strip path removes too much stuff (#715)
- io.otoroshi/id is not in annotations in documentation (#716)
- Support private key passwords on certificates (#717)
- Add a flag in service to avoid adding default hosts (#718)
- watch does not handle new namespaces until restart (#719)
- watch does not use namespace label filtering (#720)
- Add support for kubedns and openshift dns operator (#721)
- Add documentation about coredns stubdomain for openshift and kubedns (#722)
- Make global client_credential flow available by default  (#723)
- Exporter to fill internal metrics  (#725)
- issue when generating subcas (#726)
- Support EC certificates in the pki (#727)
- Title not displayed after cancel a TCP service (#728)
- Fix issuer DN in certificate to avoid certificate check in go (#729)
- change apiversion in crds (#730)
- fix the findCertMatching in DynamicKeyManager to chose the most client specific cert (#733)
- Some \"add\" doesn't work for HTTP headers in Service descriptor (#734)
- enhance pki (#735)
- default jwks.json route (#736)
- authenticated apis calls plugins (#737)
- verify that everything that checks certificates DN uses comparison  (#741)
- create default team and orga at first startup (#742)
- Team selector is broken in UI (#743)
- Unleash the snow monkey seems broken (#744)
- Add specific JVM flags in kubernetes manifests (#745)
- Session management does not work in cluster mode (#752)
- Login tokens does not work in cluster mode (#753)
- Add an OCSP responder API to the pki (#754)
- Chaining jwt verifier in non strict mode generate more events than needed (#755)
- Experiment postgresql support (#757)
- Improve PostgreSQL support (#758)
- Add JWT EC token sign/valid from keypair (#759)
- On status page, when ES instance isn't setup, an undefined sort error occured (#765)
- add something to the target UI components to avoid passing a \"path\" (#766)
- When adding a target, we can't do show more without saving first (#767)
- strip path seems to be broken (#768)
- handle keypair renew in jwt verifier (#769)
- Plugin to support canary stuff from izanami AB testing campaign (#770)
- Add informations about OCSP and Authority informations access in cert extensions (#782)
- Biscuit basic support (#783)
- Add hash and line count in response header when exporting cluster state (#785)
- Limit classpath scanning to the bare minimum (#786)
- Introduce generic plugins (#787)
- Publish otoroshi_lib on maven central (#789)
- Use exposed ports instead of regular ports (#790)
- Try to parse datetime as string in json readers (#791)
- Add alerts when kubernetes jobs fails crd parsing (#792)
- fix documentation about kubernetes webhooks (#793)
- openapi descriptor generation (#795)
- Provide plugins to perform service discovery (#797)
- Enhance de LDAP auth module (#799)
- remove log on server (service/global) options (#806)
- Support mTLS for PG connection (#810)
- Missing kid when signing with RSAKPAlgoSettings and ESKPAlgoSettings (#832)
- Add support for S3 persistence for the in-memory datastore (#834)
- Local authentication doesn't works (#836)
- add google floc specific headers in ui (#837)
- fix docker build (#838)
- add auto cleanup in the cache plugin (#839)
- Missing kid in default token (#841)
- Default rights to admins (#846)
- Fix admin creation from super admin (#848)
- fix the target component when pasting URL with trailing slash (#849)
- check if custom error templates works in worker mode (#850)
- add metadata tags check in ApikeyAuthModule  (#851)
- remove validation from crds definitions (#852)
- missing namespace in subject from clusterrolebinding (#853)
- version in crds becomes versions (#854)
- CRDs manifest evolution for kubernetes 1.22 (#855)
- button to hide/toggle plugin configuration (#856)
- additionnal host added indefinitely (#857)
- kubernetes watch fails on otoroshi crds (#858)
- Hashed Password visible when infos token is enabled on service  (#859)
- Rename SAML to SAML v2 in auth module list (#861)
- Admin api seems to be in default group at first startup (#862)
- check if multi purpose plugins works as they should be (#863)
- display instance name in window title (#864)
- do not hardcode otoroshi.mesh, use config (#865)
- add a flag to nuke openshift coredns operator customization that uses otoroshi.mesh (#866)
- Add form description for the kubernetes jobs config (#870)
- expose new plugins settings (#871)
- Add migration button for old plugins in services (#872)
- Add migration button for old plugins in danger zone (#873)
- add prefix in plugins excluded path to apply only on one specific plugin (#874)
- Plugin documentation extractor (#875)
- Dynamic root domains (#879)
- Seeking configuration for Read timeout to XXX after 120000 ms (#880)
- configure input MTLS in the danger zone (#881)
- static response plugin (#882)
- circuit breaker cache too aggressive  (#883)
- add tags and meta on targets (#884)
- Select trusted CAs for server mtls from global config (#886)
- Add env. variables to enable server MTLS in documentation (#887)
- when using circuit breaker with akka client, need to discardBytes (#888)
- Unable to change api key status from all api keys view (#889)
- Return http error when ES statistics cannot be returned (#890)
- When I clicked on the stats button of an api key (from all Api keys view), I got an undefined analytics view  (#891)
- Add options in pki api to avoid certificate save (#893)
- investigate about kube secret changing even if apikey didn't change  (#894)
- JDK17 breaks TLS ... (#896)
- client certificate lookup strategy is broken (#899)
- use akka-http cached host level client behind a feature flag (#901)
- try to put `trustXForwarded` in danger zone  (#906)
- error while parsing certificate with bag attributes (#907)
- Do not display type in elastic config if version greater than 7 (#909)
- Enable index per day pattern in elastic exporter or not (#910)
- Add a \"test connection\" buttin in elastic config (#911)
- dark/light css theme for admin ui (#913)
- Error when displaying elasticsearch config (#918)
- Service Group list view - No filter for service associated with Service Group (#920)
- Fix bad apikey parsing (#921)
- bad playframework behavior with JDK17/JDK18 (#922)
- Add CRDs loader in UI to create resources from yaml/json file (#929)
- OAuth1 caller plugin (#930)
- HMAC caller plugin (#931)
- HMAC access validator plugin (#932)
- ES data exporter fail to export events with to field (#940)
- Add quotas exceeded alerts (#941)
- fix target ui (#943)
- request sink plugins should not be visible in service plugins select box (#945)
- Support Authorization code + PKCE flow in OAuth2/OIDC auth. module (#947)
- Handle OAuth2 service redirection using cookies only (#951)
- Errors while installing with helm to Minikube (#956)
- Avoid importing empty chain certificates in kubernetes plugin (#969)
- Fix TLS engine weird behavior (#974)
- fix log4j dependency (CVE-2021-44228) (#976)
- fix http2 TLS issue (#977)
- JQ plugin (#984)
- fix log4j dependency - CVE-2021-45046 (#985)
- Move manual (#986)
- New dev mode (#987)
- PEM bundle import endpoint (#988)
- Add a log4shell mitigation plugin (#989)

## [1.5.0-rc.4] - 2021-12-17

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-rc.4+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-rc.3...v1.5.0-rc.4
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-rc.4

- Add a log4shell mitigation plugin (#989)
- fix typos in documentation
- add helm package publication in the release process

## [1.5.0-rc.3] - 2021-12-15

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-rc.3+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-rc.2...v1.5.0-rc.3
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-rc.3

- JQ plugin (#984)
- fix log4j dependency - CVE-2021-45046 (#985)
- Move manual (#986)
- New dev mode (#987)
- PEM bundle import endpoint (#988)

## [1.5.0-rc.2] - 2021-12-13

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-rc.2+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-rc.1...v1.5.0-rc.2
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-rc.2

- fix log4j dependency (#976)
- fix http2 TLS issue (#977)

## [1.5.0-rc.1] - 2021-12-10

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-rc.1+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.8...v1.5.0-rc.1
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-rc.1

- Work on documentation (#445)
- bad playframework behavior with JDK17/JDK18 (#922)
- Avoid importing empty chain certificates in kubernetes plugin (#969)
- Fix TLS engine weird behavior (#974)

## [1.5.0-beta.8] - 2021-11-08

https://github.com/MAIF/otoroshi/issues?q=is%3Aissue+label%3A1.5.0-beta.8+is%3Aclosed
https://github.com/MAIF/otoroshi/compare/v1.5.0-beta.7...v1.5.0-beta.8
https://github.com/MAIF/otoroshi/releases/tag/v1.5.0-beta.8

- Add CRDs loader in UI to create resources from yaml/json file (#929)
- OAuth1 caller plugin (#930)
- HMAC caller plugin (#931)
- HMAC access validator plugin (#932)
- ES data exporter fail to export events with to field (#940)
- Add quotas exceeded alerts (#941)
- fix target ui (#943)
- request sink plugins should not be visible in service plugins select box (#945)
- Support Authorization code + PKCE flow in OAuth2/OIDC auth. module (#947)
- Handle OAuth2 service redirection using cookies only (#951)
- Errors while installing with helm to Minikube (#956)

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

