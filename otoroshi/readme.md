# Otoroshi server

this is the home of the Otoroshi server app

## What you need

* git
* docker
* jdk 8 at least (jdk 11 recommended)
* sbt
* node
* yarn
* rustup toolchain with the latest stable version of rust

## Build Otoroshi for prod

at the root of the repository, run the following command

```sh
sh ./scripts/build.sh all
```

it will build 

* the Otoroshi server
* the Otoroshi admin UI
* the Otoroshi manual
* the Otoroshi CLI

## Build Otoroshi in dev mode

then open two bash session, in the first one run the following commands

```sh
cd ./otorosohi
sbt

[otoroshi] $ ~run -Dapp.storage=file -Dapp.liveJs=true -Dapp.adminPassword=password -Dapp.domain=oto.tools
```

it will run the play app in dev mode with hot reload

in the second bash session, run the following commands

```sh
cd ./otorosohi/javascript
yarn install
yarn start
```

it will run a build server for the JS app of the admin dashboard

then open your browser at <a href="" target="_blank">http://otoroshi.dev.oto.tools:9999</a>

## Format the code

at the root of the repository, run the following command

```sh
sh ./scripts/fmt.sh
```

## Generate the doc

at the root of the repository, run the following command

```sh
sh ./scripts/docs.sh
```

