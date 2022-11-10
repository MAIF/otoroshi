# Otoroshi

**Otoroshi** is a layer of lightweight api management on top of a modern http reverse proxy written in <a href="https://www.scala-lang.org/" target="_blank">Scala</a> and developped by the <a href="https://maif.github.io" target="_blank">MAIF OSS</a> team that can handle all the calls to and between your microservices without service locator and let you change configuration dynamicaly at runtime.


> *The <a href="https://en.wikipedia.org/wiki/Gazu_Hyakki_Yagy%C5%8D#/media/File:SekienOtoroshi.jpg" target="blank">Otoroshi</a> is a large hairy monster that tends to lurk on the top of the torii gate in front of Shinto shrines. It's a hostile creature, but also said to be the guardian of the shrine and is said to leap down from the top of the gate to devour those who approach the shrine for only self-serving purposes.*

@@@ div { .centered-img }
[![build](https://github.com/MAIF/otoroshi/actions/workflows/server_build_and_test.yaml/badge.svg)](https://github.com/MAIF/otoroshi/actions/workflows/server_build_and_test.yaml) [![Join the chat at https://gitter.im/MAIF/otoroshi](https://badges.gitter.im/MAIF/otoroshi.svg)](https://gitter.im/MAIF/otoroshi?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [ ![Download](https://img.shields.io/github/release/MAIF/otoroshi.svg) ](hhttps://github.com/MAIF/otoroshi/releases/download/v1.5.16/otoroshi.jar)
@@@

@@@ div { .centered-img }
<img src="https://github.com/MAIF/otoroshi/raw/master/resources/otoroshi-logo.png" width="300"></img>
@@@

## Installation

You can download the latest build of Otoroshi as a @ref:[fat jar](./install/get-otoroshi.md#from-jar-file), as a @ref:[zip package](./install/get-otoroshi.md#from-zip) or as a @ref:[docker image](./install/get-otoroshi.md#from-docker).

You can install and run Otoroshi with this little bash snippet

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.16/otoroshi.jar'
java -jar otoroshi.jar
```

or using docker

```sh
docker run -p "8080:8080" maif/otoroshi:1.5.16
```

now open your browser to <a href="http://otoroshi.oto.tools:8080/" target="_blank">http://otoroshi.oto.tools:8080/</a>, **log in with the credential generated in the logs** and explore by yourself, if you want better instructions, just go to the @ref:[Quick Start](./getting-started.md) or directly to the @ref:[installation instructions](./install/get-otoroshi.md)

## Documentation

* @ref:[About Otoroshi](./about.md)
* @ref:[Architecture](./architecture.md)
* @ref:[Features](./features.md)
* @ref:[Getting started](./getting-started.md)
* @ref:[Install Otoroshi](./install/index.md)
* @ref:[Main entities](./entities/index.md)
* @ref:[Detailed topics](./topics/index.md)
* @ref:[How to's](./how-to-s/index.md)
* @ref:[Plugins](./plugins/index.md)
* @ref:[Admin REST API](./api.md)
* @ref:[Deploy to production](./deploy/index.md)
* @ref:[Developing Otoroshi](./dev.md)
* @ref:[Otoroshi next](./next/index.md)

## Discussion

Join the [Otoroshi](https://gitter.im/MAIF/otoroshi) channel on the [MAIF Gitter](https://gitter.im/MAIF) { open=new }

## Sources

The sources of Otoroshi are available on @link:[Github](https://github.com/MAIF/otoroshi) { open=new }.

## Logo

You can find the official Otoroshi logo @link:[on GitHub](https://github.com/MAIF/otoroshi/blob/master/resources/otoroshi-logo.png) { open=new }. The Otoroshi logo has been created by François Galioto ([@fgalioto](https://twitter.com/fgalioto))

## Changelog

Every release, along with the migration instructions, is documented on the @link:[Github Releases](https://github.com/MAIF/otoroshi/releases) { open=new } page. A condensed version of the changelog is available on @link:[github](https://github.com/MAIF/otoroshi/blob/master/CHANGELOG.md) { open=new }

## Patrons

The work on Otoroshi was funded by <a href="https://www.maif.fr/" target="_blank">MAIF</a> with the help of the community.

## Licence

Otoroshi is Open Source and available under the @link:[Apache 2 License](https://opensource.org/licenses/Apache-2.0)  { open=new }

@@@ index

* [About Otoroshi](./about.md)
* [Architecture](./architecture.md)
* [Features](./features.md)
* [Getting started](./getting-started.md)
* [Install Otoroshi](./install/index.md)
* [Main entities](./entities/index.md)
* [Detailed topics](./topics/index.md)
* [How to's](./how-to-s/index.md)
* [Plugins](./plugins/index.md)
* [Admin REST API](./api.md)
* [Deploy to production](./deploy/index.md)
* [Developing Otoroshi](./dev.md)
* [Otoroshi next](./next/index.md)

@@@

