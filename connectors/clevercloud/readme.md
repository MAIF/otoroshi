# CleverCloud Synchronization daemon

The connector to synchronize Otoroshi and CleverCloud. This is an highly experimental tool, use with caution.

## Requirements

* git
* curl
* docker
* node
* yarn (https://yarnpkg.com/lang/en/)

## Create a CleverCloud account

https://www.clever-cloud.com/

## Start Otoroshi

```sh
wget --quiet 'https://github.com/MAIF/otoroshi/releases/download/v1.0.0/otoroshi.jar'
java -jar otoroshi.jar &
```

## Run the daemon

first you have to provide some config to allow the daemon to connect to Otoroshi and CleverCloud APIs. The config is located in the `config.json` file.

then install the dependencies

```sh
yarn install
```

then run the daemon

```sh
yarn start
# or
node src/index.js
```

or just build the local Docker image and run it

```sh
docker build -t otoroshi-clevercloud-connector .
docker run -p "8081:8080" -d otoroshi-clevercloud-connector 
```
