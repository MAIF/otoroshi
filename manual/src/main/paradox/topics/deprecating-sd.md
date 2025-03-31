# Deprecating Service Descriptors

for more than 2 years, after the @ref[rewrite of the proxy engine](./engine.md) in `v1.5.3`, Service Descriptors has been deprecated.

Now (v17.0.0) it's time to go ahead and provide some migration tools to help users getting rid of Service Descriptor in favor of routes before completely removing support of Service Descriptors in v18.0.0 (probably in 2026). Under the hood the new proxy engine already convert your Service Descriptors to Route in order to route traffic to your backends. The idea here is to remove the Service Descriptor entity in favor of the Route entity and let the user adapt their workflows and automations.

This toolkit consists of 3 tools that you can use to migrate existing Service Descriptors.

## Migration Job

TODO

## Migration API

TODO

## Migration button

TODO