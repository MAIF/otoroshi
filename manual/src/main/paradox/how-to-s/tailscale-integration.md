# Tailscale integration

[Tailscale](https://tailscale.com/) is a VPN service that let you create your own private network based on [Wireguard](https://www.wireguard.com/). Tailscale goes beyond the simple meshed wireguard based VPN and offers out of the box NAT traversal, third party identity provider integration, access control, magic DNS and let's encrypt integration for the machines on your VPN.

Otoroshi provides somes plugins out of the box to work in a [Tailscale](https://tailscale.com/) environment.

by default Otoroshi, works out of the box when integrated in a `tailnet` as you can contact other machines usign their ip address. But we can go a little bit further.

## tailnet configuration

first thing, go to your tailnet setting on [tailscale.com](https://login.tailscale.com/admin/machines) and go to the [DNS tab](https://login.tailscale.com/admin/dns). Here you can find 

* your tailnet name: the domain name of all your machines on your tailnet
* MagicDNS: a way to address your machines by directly using their names
* HTTPS Certificates: HTTPS certificates provision for all your machines

to use otoroshi Tailscale plugin you must enable `MagicDNS` and `HTTPS Certificates`

## Tailscale certificates integration

you can use tailscale generated let's encrypt certificates in otoroshi by using the `Tailscale certificate fetcher job` in the plugins section of the danger zone. Once enabled, this job will fetch certificates for domains in `xxxx.ts.net` that belong to your tailnet. 

as usual, the fetched certificates will be available in the [certificates page](http://otoroshi.oto.tools:8080/bo/dashboard/certificates) of otoroshi.

## Tailscale targets integration

the following pair of plugins let your contact tailscale machine by using their names even if their are multiple instance.

when you register a machine on a tailnet, you have to provide a name for it, let say `my-server`. This machine will be addressable in your tailnet with `my-server.tailxxx.ts.net`. But if you have multiple instance of the same server on several machines with the same `my-server` name, their DNS name on the tailnet will be `my-server.tailxxx.ts.net`, `my-server-1.tailxxx.ts.net`, `my-server-2.tailxxx.ts.net`, etc. If you want to use those names in an otoroshi backend it could be tricky if the application has something like autoscaling enabled.

in that case, you can add the `Tailscale targets job` in the plugins section of the danger zone. Once enabled, this job will fetch periodically available machine on the tailnet with their names and DNS names. Then, in a route, you can use the `Tailscale select target by name` plugin to tell otoroshi to loadbalance traffic between all machine that have the name specified in the plugin config. instead of their DNS name.