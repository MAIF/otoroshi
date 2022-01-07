# next-gen proxy engine

## TODO

- Loader Job to keep all route in memory
- Some kind of reporting mecanism to keep track of everything (useful for debug)

## new entities

- Route
- Backend

## needed plugins

- redirection plugin
- tcp/udp tunneling (?? - if possible)
- headers verification (access validator)
- readonly route (access validator)
- readonly apikey (access validator)
- jwt verifier (access validator)
- apikey validation with constraints (access validator)
- auth. module validation (access validator)
- route restrictions (access validator)
- public/private path plugin (access validator)
- force https traffic (pre route)
- allow http/1.0 traffic (pre route or access validator)
- snowmonkey (??)
- canary (??)
- otoroshi state plugin (transformer)
- otoroshi claim plugin (transformer)
- headers manipulation (transformer)
- headers validation (access validator)
- endless response clients (transformer)
- maintenance mode (transformer)
- construction mode (transformer)
- apikey extractor (pre route)
- send otoroshi headers back (transformer)
- override host header (transformer)
- send xforwarded headers (transformer)
- CORS (transformer)
- gzip (transformer)
- ip blocklist (access validator)
- ip allowed list (access validator)
- custom error templates (transformer)
- snow monkey (transformer)

## killed features

- sidecar (handled with kube stuff now)
- local redirection
