# Service mesh with Otoroshi

[Service mesh](http://philcalcado.com/2017/08/03/pattern_service_mesh.html) is a pattern that gained a lot of traction lately with tools like [Kubernetes](https://kubernetes.io/) and [Istio]() using patterns like [sidecar](). It has the advantages to move the complexity of calling other services outside the application. For the application, it's just a local call and the proxy handles apikeys, throttling, quotas, resilience, tracing, etc ...

## Architecture

Let's say that we have 4 service that want to talk to each other. But we don't want to handle the complexity of calling those services with their own apikey, etc ...

@@@ div { .centered-img }
<img src="https://github.com/MAIF/otoroshi/raw/master/demos/service-mesh/calls.png" />
@@@

So we are going to build an infrastructure where each service has a sidecar Otoroshi that will handle the calls to other services

@@@ div { .centered-img }
<img src="https://github.com/MAIF/otoroshi/raw/master/demos/service-mesh/mesh.png" />
@@@

## Configuration

In this infrastructure, each service has its own service descriptor in Otoroshi that points to another Otoroshi instance. The challenge here is to dynamically change the target of any service when the current call is supposed to access th actual app.

To do that, each Otoroshi instance (that is part of the same cluster and use the same database) will have a configuration slightly different configuration than the other ones. 

```hocon
sidecar {
  serviceId = "my-local-service-id" # the id of the local sidecar app
  target = "http://127.0.0.1:56876" # the local service target, it should be on 127.0.0.1 but can be on another ip address
  from = "127.0.0.1" # the ip address authorized to actualy access the local app, it should be on 127.0.0.1 but can be on another ip address
  strict = true # use actual remote address or remote address/proxied remote address
  apikey {
    clientId = "my-apikey-id" # the apikey client id to access other services
  }
}
```

## Configuration using env. variables

You can also only env. variables to configure sidecar Otoroshi instances 

```sh
SIDECAR_SERVICE_ID=my-local-service-id
SIDECAR_TARGET=http://127.0.0.1:56876
SIDECAR_FROM=127.0.0.1
SIDECAR_STRICT=true
SIDECAR_APIKEY_CLIENT_ID=my-apikey-id
```

## Example

You can find a full example of a simple service mesh using Otoroshi [here](https://github.com/MAIF/otoroshi/tree/master/demos/service-mesh).