---
title: Main entities
sidebar_label: "Overview"
sidebar_position: 1
slug: /entities
---

# Main entities

In this section, we will pass through all the main Otoroshi entities. Otoroshi entities are the main items stored in otoroshi datastore that will be used to configure routing, authentication, etc.

Any entity has the following properties

* **location** or **\_loc**: the location of the entity (organization and team)
* **id**: the id of the entity (except for apikeys)
* **name**: the name of the entity
* **description**: the description of the entity (optional)
* **tags**: free tags that you can put on any entity to help you manage it, automate it, etc.
* **metadata**: free key/value tuples that you can put on any entity to help you manage it, automate it, etc.

- **[Routes](./routes.md)**: Proxy your applications with routes

- **[APIs](./apis.md)**: Proxy your applications with apis

- **[Backends](./backends.md)**: Reuse route targets

- **[Apikeys](./apikeys.md)**: Add security to your services using apikeys


- **[Organizations](./organizations.md)**: This the most high level for grouping resources.

- **[Teams](./teams.md)**: Organize your resources by teams

- **[Service groups](./service-groups.md)**: Group your services

- **[JWT verifiers](./jwt-verifiers.md)**: Verify and forge token by services.

- **[Global Config](./global-config.md)**: The danger zone of Otoroshi

- **[TCP services](./tcp-services.md)**: 

- **[Auth. modules](./auth-modules.md)**: Secure the Otoroshi UI and your web apps

- **[Certificates](./certificates.md)**: Add secure communication between Otoroshi, clients and services

- **[Data exporters](./data-exporters.mdx)**: Export alerts, events ands logs

- **[Service descriptors](./service-descriptors.md)**: Proxy your applications with service descriptors

- **[Remote Catalogs](./remote-catalogs.md)**: Sync entities from remote sources

- **[HTTP Listeners](./http-listeners.md)**: Serve traffic on custom ports

- **[Workflows](./workflows.md)**: Build automation pipelines

- **[WASM Plugins](./wasm-plugins.md)**: Reusable WebAssembly plugin configurations

- **[Route Templates](./route-templates.md)**: Reusable route blueprints

- **[Error Templates](./error-templates.md)**: Customize error pages for your services

- **[Otoroshi Admins](./otoroshi-admins.md)**: Manage backoffice admin users


