
# Main entities

In this section, we will pass through all the main Otoroshi entities. Otoroshi entities are the main items stored in otoroshi datastore that will be used to configure routing, authentication, etc.

Any entity has the following properties

* `location` or `_loc`: the location of the entity (organization and team)
* `id`: the id of the entity (except for apikeys)
* `name`: the name of the entity
* `description`: the description of the entity (optional)
* `tags`: free tags that you can put on any entity to help you manage it, automate it, etc.
* `metadata`: free key/value tuples that you can put on any entity to help you manage it, automate it, etc.

@@@div { .plugin .entities }
<img src="../imgs/entities-groups.png">
<div>
<span>Organizations</span>
<span>This the most high level for grouping resources.</span>
</div>
@ref:[View](./organizations.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-groups.png">
<div>
<span>Teams</span>
<span>Organize your resources by teams</span>
</div>
@ref:[View](./teams.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-groups.png">
<div>
<span>Service groups</span>
<span>Group your services</span>
</div>
@ref:[View](./service-groups.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-keys.png">
<div>
<span>JWT verifiers</span>
<span>Verify and forge token by services.</span>
</div>
@ref:[View](./jwt-verifiers.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-keys.png">
<div>
<span>Apikeys</span>
<span>Add security to your services using apikeys</span>
</div>
@ref:[View](./apikeys.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-danger-zone.png">
<div>
<span>Global Config</span>
<span>The danger zone of Otoroshi</span>
</div>
@ref:[View](./global-config.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-services.png">
<div>
<span>Service descriptors</span>
<span>Proxy your applications with service descriptors</span>
</div>
@ref:[View](./service-descriptors.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-services.png">
<div>
<span>TCP services</span>
<span></span>
</div>
@ref:[View](./tcp-services.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-security.png">
<div>
<span>Auth. modules</span>
<span>Secure the Otoroshi UI and your web apps</span>
</div>
@ref:[View](./auth-modules.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-certificates.png">
<div>
<span>Certificates</span>
<span>Add secure communication between Otoroshi, clients and services</span>
</div>
@ref:[View](./certificates.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-plugins.png">
<div>
<span>Data exporters</span>
<span>Export alerts, events ands logs</span>
</div>
@ref:[View](./data-exporters.md)
@@@

@@@div { .plugin .entities }
<img src="../imgs/entities-groups.png">
<div>
<span>Scripts</span>
<span></span>
</div>
@ref:[View](./scripts.md)
@@@

@@@ index

* [Organizations](./organizations.md)
* [Teams](./teams.md)
* [Global Config](./global-config.md)
* [Apikeys](./apikeys.md)
* [Service groups](./service-groups.md)
* [Service descriptors](./service-descriptors.md)
* [Auth. modules](./auth-modules.md)
* [Certificates](./certificates.md)
* [JWT verifiers](./jwt-verifiers.md)
* [Data exporters](./data-exporters.md)
* [Scripts](./scripts.md)
* [TCP services](./tcp-services.md)

@@@
