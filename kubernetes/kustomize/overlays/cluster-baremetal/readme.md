# Cluster deployment on baremetal kubernetes cluster

here we only deploy 2 replicas of the otoroshi leader instance using redis and 2 replicas of otoroshi worker instances

The otoroshi instances are exposed as `nodePort` so you'll have to add a loadbalancer in front of your kubernetes nodes to route external traffic (TCP) to your otoroshi instances. You'll also have to configure your DNS to route otoroshi domain names to the loadbalancer itself

## NGINX config. example

```
stream {

  upstream worker_http_nodes {
    zone worker_http_nodes 64k;
    server 10.2.2.40:32080 max_fails=1;
    server 10.2.2.41:32080 max_fails=1;
    server 10.2.2.42:32080 max_fails=1;
  }

  upstream worker_https_nodes {
    zone worker_https_nodes 64k;
    server 10.2.2.40:32443 max_fails=1;
    server 10.2.2.41:32443 max_fails=1;
    server 10.2.2.42:32443 max_fails=1;
  }

  upstream leader_http_nodes {
    zone leader_http_nodes 64k;
    server 10.2.2.40:31080 max_fails=1;
    server 10.2.2.41:31080 max_fails=1;
    server 10.2.2.42:31080 max_fails=1;
  }

  upstream leader_https_nodes {
    zone leader_https_nodes 64k;
    server 10.2.2.40:31443 max_fails=1;
    server 10.2.2.41:31443 max_fails=1;
    server 10.2.2.42:31443 max_fails=1;
  }

  server {
    listen     80;
    proxy_pass worker_http_nodes;
    health_check;
  }

  server {
    listen     443;
    proxy_pass worker_https_nodes;
    health_check;
  }

  server {
    listen     81;
    proxy_pass leader_http_nodes;
    health_check;
  }

  server {
    listen     444;
    proxy_pass leader_https_nodes;
    health_check;
  }
  
}
```

## HAProxy config. example

here we use different ports to access either leader or workers. You can also configure HAProxy to use SNI to route to the right instance using the same input port (https://www.haproxy.com/fr/blog/enhanced-ssl-load-balancing-with-server-name-indication-sni-tls-extension/)

```
frontend front_worker_nodes_http
    bind *:80
    mode tcp
    default_backend worker_http_nodes
    timeout client          1m

frontend front_worker_nodes_https
    bind *:443
    mode tcp
    default_backend worker_https_nodes
    timeout client          1m

frontend front_leader_nodes_http
    bind *:81
    mode tcp
    default_backend leader_http_nodes
    timeout client          1m

frontend front_leader_nodes_https
    bind *:444
    mode tcp
    default_backend leader_https_nodes
    timeout client          1m

backend worker_http_nodes
    mode tcp
    balance roundrobin
    server kubernetes-node1 10.2.2.40:32080
    server kubernetes-node2 10.2.2.41:32080
    server kubernetes-node3 10.2.2.42:32080
    timeout connect        10s
    timeout server          1m

backend worker_https_nodes
    mode tcp
    balance roundrobin
    server kubernetes-node1 10.2.2.40:32443
    server kubernetes-node2 10.2.2.41:32443
    server kubernetes-node3 10.2.2.42:32443
    timeout connect        10s
    timeout server          1m

backend leader_http_nodes
    mode tcp
    balance roundrobin
    server kubernetes-node1 10.2.2.40:31080
    server kubernetes-node2 10.2.2.41:31080
    server kubernetes-node3 10.2.2.42:31080
    timeout connect        10s
    timeout server          1m

backend leader_https_nodes
    mode tcp
    balance roundrobin
    server kubernetes-node1 10.2.2.40:31443
    server kubernetes-node2 10.2.2.41:31443
    server kubernetes-node3 10.2.2.42:31443
    timeout connect        10s
    timeout server          1m
```

## DNS config. example

if your loadbalancer is at ip address 10.2.2.50

```
otoroshi.your.otoroshi.domain      IN A 10.2.2.50
otoroshi-api.your.otoroshi.domain  IN A 10.2.2.50
privateapps.your.otoroshi.domain   IN A 10.2.2.50
api1.another.domain                IN A 10.2.2.50
api2.another.domain                IN A 10.2.2.50
*.api.the.api.domain               IN A 10.2.2.50
```