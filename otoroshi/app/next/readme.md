# next-gen proxy engine

## TODO

- Loader Job to keep all route in memory
- Some kind of reporting mecanism to keep track of everything (useful for debug)

## new entities

- Route
- Backend

## needed plugins

- [ ] redirection plugin
- [ ] tcp/udp tunneling (?? - if possible)
- [ ] readonly route (access validator)
- [ ] readonly apikey (access validator)
- [ ] jwt verifier (access validator)
- [ ] apikey validation with constraints (access validator)
- [ ] auth. module validation (access validator)
- [ ] route restrictions (access validator)
- [ ] apikey restrictions (access validator)
- [ ] public/private path plugin (access validator)
- [x] force https traffic (pre route)
- [x] allow http/1.0 traffic (pre route or access validator)
- [ ] snowmonkey (??)
- [ ] canary (??)
- [ ] otoroshi state plugin (transformer)
- [ ] otoroshi claim plugin (transformer)
- [ ] add headers in (transformer)
- [ ] add headers out (transformer)
- [ ] add missing headers in (transformer)
- [ ] add missing headers out (transformer)
- [ ] remove headers in (transformer)
- [ ] remove headers out (transformer)
- [x] headers validation (access validator)
- [ ] endless response clients (transformer)
- [x] maintenance mode (transformer)
- [x] construction mode (transformer)
- [ ] apikey extractor (pre route)
- [ ] send otoroshi headers back (transformer)
- [x] override host header (transformer)
- [ ] send xforwarded headers (transformer)
- [ ] CORS (transformer)
- [ ] gzip (transformer)
- [ ] ip blocklist (access validator)
- [ ] ip allowed list (access validator)
- [ ] custom error templates (transformer)
- [ ] snow monkey (transformer)

## killed features

- sidecar (handled with kube stuff now)
- local redirection
