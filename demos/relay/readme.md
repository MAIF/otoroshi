# relay routing demo

this demo shows how an otoroshi cluster can be deployed on mutliple network zones where apps can communicate between zones with otoroshi instances acting as relays automatically. In this scenario, each service and otoroshi instance declares where it is located (using metadata) and otoroshi will handle relay routing between instances. In order to make everything work, otoroshi leaders should be able to communicate freely with each others.

![network-map](./relay.png)

in this demo, we do not use a replicated datastore. otoroshi leader instance are located in their own zone network but also have access to another network where a redis instance is deployed. Also, as it's not easy to access otoroshi instances through a DNS round robin setup, each otoroshi instance is bound to a port on the host machine.

- `leader-zone-1`: exposed on `127.0.0.1:8081`
- `leader-zone-2`: exposed on `127.0.0.1:8082`
- `leader-zone-3`: exposed on `127.0.0.1:8083`
- `worker-zone-1`: exposed on `127.0.0.1:8084`
- `worker-zone-2`: exposed on `127.0.0.1:8085`
- `worker-zone-3`: exposed on `127.0.0.1:8086`
- `api-a-zone-1`: exposed on `127.0.0.1:8091`
- `api-b-zone-2`: exposed on `127.0.0.1:8092`
- `api-c-zone-3`: exposed on `127.0.0.1:8093`
- `api-d-zone-1`: exposed on `127.0.0.1:8094`
- `api-d-zone-2`: exposed on `127.0.0.1:8095`

each of the 3 zones  has an otoroshi leader instance, an otoroshi worker instance, and an api instance deployed. Otoroshi instance from one zone cannot directly access apis from other zones as they are not in the same `docker-compose` network. But you can access any api from any otoroshi instance thanks to relay routing.

## Build otoroshi

as there is no release with the relay feature yet, to be able to run the demo, you need to build an otoroshi jar file first. Once created, copy the jar file in the `otoroshi` folder

```sh
cd ../../otoroshi/javascript
yarn install
yarn build
cd ..
sbt ';clean;compile;assembly'
cp ./target/scala-2.12/otoroshi.jar ../demos/relay/otoroshi/otoroshi.jar
```

## Start everything

start everything with 

```sh
docker-compose up
```

then import the `otoroshi.json` file through the `Danger Zone` if it's the first time

## Test

run test calls with

```sh
sh ./call.sh
```

everything should work as expected ;)

## Stop everything

shutdown everything with 

```sh
docker-compose down
```