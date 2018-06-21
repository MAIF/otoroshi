# Service mesh with otoroshi

Just a demo showing how it is possible to create a service mesh using Otoroshi

```sh
docker-compose build
docker-compose up
curl -H 'Host: service-frontend.foo.bar' http://127.0.0.1:8080/front
docker-compose down
```