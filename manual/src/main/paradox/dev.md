# Developing Otoroshi 

If you want to play with Otoroshis code, here are some tips

## The tools

You will need

* git
* JDK 11
* SBT 1.3.x
* Node 13 + yarn 1.x

## Clone the repository

```sh
git clone https://github.com/MAIF/otoroshi.git
```

or fork otoroshi and clone your own repository.

## Run otoroshi in dev mode

to run otoroshi in dev mode, you'll need to run two separate process to serve the javascript UI and the server part.

### Javascript side

just go to `<repo>/otoroshi/javascript` and install the dependencies with

```sh
yarn install
# or
npm install
```

then run the dev server with

```sh
yarn start
# or
npm run start
```

### Server side

just go to `<repo>/otoroshi` and run the sbt console with 

```sh
sbt
```

then in the sbt console run the following command

```sh
~run -Dapp.storage=file -Dapp.liveJs=true -Dhttps.port=9998 -D-Dapp.privateapps.port=9999 -Dapp.adminPassword=password -Dapp.domain=oto.tools -Dplay.server.https.engineProvider=ssl.DynamicSSLEngineProvider -Dapp.events.maxSize=0
```

you can now access your otoroshi instance at `http://otoroshi.oto.tools:9999`

## Test otoroshi

to run otoroshi test just go to `<repo>/otoroshi` and run the main test suite with

```sh
sbt `testOnly OtoroshiTests`
```

## Create a release

just go to `<repo>/otoroshi/javascript` and then build the UI

```sh
yarn install
yarn build
```

then go to `<repo>/otoroshi` and build the otoroshi distribution

```sh
sbt ';clean;compile;dist;assembly'
```

the otoroshi build is waiting for you in `<repo>/otoroshi/target/scala-2.12/otoroshi.jar` or `<repo>/otoroshi/target/universal/otoroshi-1.x.x.zip`

## Build the documentation

from the root of your repository run

```sh
sh ./scripts/doc.sh all
```

## Format the sources

from the root of your repository run

```sh
sh ./scripts/fmt.sh
```