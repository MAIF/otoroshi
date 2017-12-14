# Otoroshi

The <a href="https://en.wikipedia.org/wiki/Gazu_Hyakki_Yagy%C5%8D#/media/File:SekienOtoroshi.jpg" target="blank">Otoroshi</a> is a large hairy monster that tends to lurk on the top of the torii gate in front of Shinto shrines. It's a hostile creature, but also said to be the guardian of the shrine and is said to leap down from the top of the gate to devour those who approach the shrine for only self-serving purposes.

**Otoroshi** is also a modern http reverse proxy with a thin layer of api management written in <a href="https://www.scala-lang.org/" target="_blank">Scala</a> and developped by the <a href="https://maif.github.io" target="_blank">MAIF OSS</a> team that can handle all the calls to and between your microservices without service locator and let you change configuration dynamicaly at runtime.

@@@ div { .centered-img }
<img src="https://github.com/MAIF/otoroshi/raw/master/resources/otoroshi-logo.png" width="300"></img>
@@@

## Installation

*TODO : change jar url when release is done*

```sh
# wget --quiet 'https://github.com/MAIF/otoroshi/releases/download/v1.0.0/otoroshi.jar'
wget --quiet 'https://github.com/MAIFX/otoroshi-tryout/raw/master/otoroshi.jar'
sudo echo "127.0.0.1    otoroshi-api.foo.bar otoroshi.foo.bar otoroshi-admin-internal-api.foo.bar" >> /etc/hosts
java -jar otoroshi.jar
```

now open your browser to http://otoroshi.foo.bar:8080/ or if you want better instructions, go to the @ref:[Quick Start](./quickstart.md)

## Documentation

* @ref:[About Otoroshi](./about.md)
* @ref:[Architecture](./archi.md)
* @ref:[Features](./features.md)
* @ref:[Try Otoroshi in 5 minutes](./quickstart.md)
* @ref:[Get Otoroshi](./getotoroshi/index.md)
* @ref:[First run](./firstrun/index.md)
* @ref:[Setup Otoroshi](./setup/index.md)
* @ref:[Using Otoroshi](./usage/index.md)
* @ref:[Thrid party Integrations](./integrations/index.md)
* @ref:[Admin REST API](./api.md)
* @ref:[Rust CLI](./cli.md)
* @ref:[Deploy to production](./deploy/index.md)
* @ref:[Connectors](./connectors/index.md)

## Discussion

Join the [Otoroshi](https://gitter.im/MAIF/otoroshi) channel on the [MAIF Gitter](https://gitter.im/MAIF)

## Sources

The sources of Otoroshi are available on [Github](https://github.com/MAIF/otoroshi).

## Logo

You can find the official Otoroshi logo [on GitHub](https://github.com/MAIF/otoroshi/blob/master/resources/otoroshi-logo.png).

## Changelog

Every release, along with the migration instructions, is documented on the [Github Releases](https://github.com/MAIF/otoroshi/releases) page.

## Patrons

The work on Otoroshi was funded by <a href="https://www.maif.fr/" target="_blank">MAIF</a> with the help of the community.

## Licence 

Otoroshi is Open Source and available under the [Apache 2 License](https://opensource.org/licenses/Apache-2.0)

@@@ index

* [About Otoroshi](about.md)
* [Architecture](archi.md)
* [Features](features.md)
* [Quickstart](quickstart.md)
* [Get otoroshi](getotoroshi/index.md)
    * [from sources](getotoroshi/fromsources.md)
    * [from binaries](getotoroshi/frombinaries.md)
    * [from docker](getotoroshi/fromdocker.md)
* [First run](firstrun/index.md)
    * [choose a datastore](firstrun/datastore.md)
    * [use custom config file](firstrun/configfile.md)
    * [use ENV](firstrun/env.md)
    * [initial state](firstrun/initialstate.md)
    * [Hosts](firstrun/host.md)
    * [Run](firstrun/run.md)
* [Setup](setup/index.md)
    * [create admins](setup/admin.md)
    * [configure danger zone](setup/dangerzone.md)
* [Using Otoroshi](usage/index.md)
    * [create group](usage/1-groups.md)
    * [create service](usage/2-services.md)
    * [create ApiKeys](usage/3-apikeys.md)
    * [monitor service](usage/4-monitor.md) 
    * [sessions management](usage/5-sessions.md)
    * [Audit trail and alerts](usage/6-audit.md)
    * [Global metrics](usage/7-metrics.md)
    * [exports and imports](usage/8-importsexports.md)
* [Integrations](integrations/index.md)
    * [Analytics](integrations/analytics.md)
    * [Auth0](integrations/auth0.md)
    * [Mailgun](integrations/mailgun.md)
    * [StatsD / Datadog](integrations/statsd.md)
    * [clevercloud](integrations/clevercloud.md)
* [Admin REST API](api.md)
* [Rust CLI](cli.md)
* [Deploy in production](deploy/index.md)
    * [clevercloud](deploy/clevercloud.md)
    * [others](deploy/other.md)  
* [Connectors](connectors/index.md)
    * [clevercloud](connectors/clevercloud.md)
    * [kubernetes](connectors/kubernetes.md)
    * [rancher](connectors/rancher.md)
    * [Elastic](connectors/elastic.md)

@@@

*TODO*

* add badges