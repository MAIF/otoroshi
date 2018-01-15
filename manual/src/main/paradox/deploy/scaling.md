# Scaling Otoroshi *

@@@ warning
this section is still under construction...
@@@

## Using multiple instances with a front load balancer

Otoroshi has been designed to work with multiple instances. If you already have an infrastructure using frontal load balancing, you just have to declare Otoroshi instances as the target of all domain names handled by Otoroshi

## Using master / workers mode of Otoroshi

Work in progress :) you can follow the issue here https://github.com/MAIF/otoroshi/issues/8

## Using IPVS

You can use [IPVS](https://en.wikipedia.org/wiki/IP_Virtual_Server) to load balance layer 4 traffic directly from the Linux Kernel to multiple instances of Otoroshi.

## Using DNS Round Robin

You can use [DNS round robin technique](https://en.wikipedia.org/wiki/Round-robin_DNS) to declare multiple A records under the domain names handled by Otoroshi.

## Using Netty SO_REUSEPORT on Linux

If you plan to use the DNS Round Robin technique, you can also run 2 instance of Otoroshi per host using the `SO_REUSEPORT` of Netty if Otoroshi is running on Linux. Just add `play.server.netty.option.child.SO_REUSEPORT = true` in your prod config file, and the 2 instances will share the same port.

## Using software L4 load balancers

You can use software L4 load balancers like NGINX or HAProxy to load balance layer 4 traffic directly from the Linux Kernel to multiple instances of Otoroshi.
