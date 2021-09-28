# Scaling Otoroshi

## Using multiple instances with a front load balancer

Otoroshi has been designed to work with multiple instances. If you already have an infrastructure using frontal load balancing, you just have to declare Otoroshi instances as the target of all domain names handled by Otoroshi

## Using master / workers mode of Otoroshi

You can read everything about it in @ref:[the clustering section](../deploy/clustering.md) of the documentation.

## Using IPVS

You can use [IPVS](https://en.wikipedia.org/wiki/IP_Virtual_Server) to load balance layer 4 traffic directly from the Linux Kernel to multiple instances of Otoroshi. You can find example of configuration [here](http://www.linuxvirtualserver.org/VS-DRouting.html) 

## Using DNS Round Robin

You can use [DNS round robin technique](https://en.wikipedia.org/wiki/Round-robin_DNS) to declare multiple A records under the domain names handled by Otoroshi.

## Using software L4/L7 load balancers

You can use software L4 load balancers like NGINX or HAProxy to load balance layer 4 traffic directly from the Linux Kernel to multiple instances of Otoroshi.

NGINX L7
:   @@snip [nginx-http.conf](../snippets/nginx-http.conf) 

NGINX L4
:   @@snip [nginx-tcp.conf](../snippets/nginx-tcp.conf) 

HA Proxy L7
:   @@snip [haproxy-http.conf](../snippets/haproxy-http.conf) 

HA Proxy L4
:   @@snip [haproxy-tcp.conf](../snippets/haproxy-tcp.conf) 

## Using a custom TCP load balancer

You can also use any other TCP load balancer, from a hardware box to a small js file like

tcp-proxy.js
:   @@snip [tcp-proxy.js](../snippets/tcp-proxy.js) 

tcp-proxy.rs
:   @@snip [tcp-proxy.rs](../snippets/proxy.rs) 

