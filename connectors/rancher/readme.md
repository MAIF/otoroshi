# Rancher Synchronization daemon

The connector to synchronize Otoroshi and Rancher. This is an highly experimental tool, use with caution.

## Requirements

* linux OS
* git
* curl
* docker
* node
* Rancher
* yarn (https://yarnpkg.com/lang/en/)

## Start Rancher

first, start a Rancher server instance and one or more Rancher host like described [here](http://rancher.com/docs/rancher/v1.6/en/quick-start-guide/)
and create an api key to access the Rancher API like described [here](http://rancher.com/docs/rancher/latest/en/api/v2-beta/)

## Start Otoroshi

```sh
git clone https://github.com/MAIFX/otoroshi-tryout.git --depth=1
cd ./otoroshi-tryout/
docker build -t otoroshi-tryout .
docker run -p "8080:8080" -d otoroshi-tryout
docker logs -f <CONTAINER_ID>
```

## Run the daemon

first you have to provide some config to allow the daemon to connect to Otoroshi and Rancher APIs. The config is located in the `config.json` file.

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
docker build -t otoroshi-rancher-connector .
docker run -p "8081:8080" -d otoroshi-rancher-connector 
```
