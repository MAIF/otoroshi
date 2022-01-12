# next-gen proxy engine

## TODO

- [x] Loader Job to keep all route in memory
- [ ] Loader Job to keep all apikeys in memory
- [ ] Loader Job to keep all certificates in memory
- [ ] Loader Job to keep all auth. modules in memory
- [ ] Loader Job to keep all jwt verifiers in memory
- [x] Some kind of reporting mecanism to keep track of everything (useful for debug)

## new entities

- [x] Route
- [ ] Backend

## needed plugins

- [ ] apikey extractor asap (pre route)
- [ ] apikey plugin
  - [ ] extraction (from allowed locations)
  - [ ] validate enabled
  - [ ] validate expiration date
  - [ ] validate readonly
  - [ ] validate route restriction
  - [ ] validate apikeys constraints (should be autonomous actually)
  - [ ] validate quotas
- [ ] jwt verifier (access validator)
- [ ] auth. module validation (access validator)
- [ ] route restrictions (access validator)
- [x] public/private path plugin (access validator)
- [ ] otoroshi state plugin (transformer)
- [ ] otoroshi claim plugin (transformer)
- [ ] CORS (transformer)
- [ ] tricky plugins
  - [ ] gzip (transformer)
  - [ ] tcp/udp tunneling (?? - if possible)
  - [ ] snow monkey (transformer)
  - [ ] canary (??)
- [x] headers related plugins
  - [x] add headers in (transformer)
  - [x] add headers out (transformer)
  - [x] add missing headers in (transformer)
  - [x] add missing headers out (transformer)
  - [x] remove headers in (transformer)
  - [x] remove headers out (transformer)
  - [x] send otoroshi headers back (transformer)
  - [x] send xforwarded headers (transformer)
  - [x] headers validation (access validator)
- [x] endless response clients (transformer)
- [x] maintenance mode (transformer)
- [x] construction mode (transformer)
- [x] override host header (transformer)
- [x] ip blocklist (access validator)
- [x] ip allowed list (access validator)
- [x] force https traffic (pre route)
- [x] allow http/1.0 traffic (pre route or access validator)
- [x] redirection plugin
- [x] readonly route (access validator)

## killed features

- [x] sidecar (handled with kube stuff now)
- [x] local redirection
