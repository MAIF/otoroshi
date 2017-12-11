# Otoroshi

The <a href="https://en.wikipedia.org/wiki/Gazu_Hyakki_Yagy%C5%8D#/media/File:SekienOtoroshi.jpg" target="blank">Otoroshi</a> is a large hairy monster that tends to lurk on the top of the torii gate in front of Shinto shrines. It is a hostile creature, but also said to be the guardian of the shrine and is said to leap down from the top of the gate to devour those who approach the shrine for only self-serving purposes.

**Otoroshi** is also a modern http reverse proxy with a thin layer of api management written in <a href="https://www.scala-lang.org/" target="_blank">Scala</a> and developped by the <a href="https://maif.github.io" target="_blank">MAIF OSS</a> team that can handle all the calls to and between your microservices without service locator and let you change configuration dynamicaly at runtime.

<img src="https://github.com/MAIF/otoroshi/raw/master/resources/otoroshi-logo.png" width="300"></img>

@@@ index

* [About Otoroshi](about.md)
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
* [Setup](setup/index.md)
    * [create admins](setup/admin.md)
    * [configure danger zone](setup/dangerzone.md)
* [Basic tasks](basictasks/index.md)
    * [create group](basictasks/groups.md)
    * [create service](basictasks/services.md)
    * [create ApiKeys](basictasks/apikeys.md)
    * [monitor service](basictasks/monitor.md) 
* [Other tasks](othertasks/index.md)
    * [sessions management](othertasks/sessions.md)
    * [Audit trail and alerts](othertasks/audit.md)
    * [Global metrics](othertasks/metrics.md)
    * [exports and imports](othertasks/importsexports.md)
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