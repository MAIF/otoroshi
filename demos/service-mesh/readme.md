# Service mesh with otoroshi

Just a demo showing how it is possible to create a service mesh using Otoroshi

```sh
docker-compose build
docker-compose up
curl -H 'Host: service-frontend.foo.bar' http://127.0.0.1:8080/front
docker-compose down
```

Right now a service is used to create custom ad hoc configuration for the sidecar otoroshi instance. In the future, Otoroshi will support SIDECAR configuration (https://github.com/MAIF/otoroshi/issues/138)

## Logical calls graph

<img src ="https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/service-mesh/calls.png">

## Actual calls graph

<img src ="https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/service-mesh/mesh.png">