# simple-baremetal overlay

Single-pod Otoroshi exposed via `NodePort` for clusters without a cloud-managed
LoadBalancer (on-prem k3s/kubeadm, …). You put your own L4 LB (nginx,
haproxy, F5, …) in front of the cluster nodes and route to the well-known
nodePorts.

## What it deploys

- 1 × Otoroshi `Deployment` (`otoroshi-deployment`, 2 replicas)
- 1 × `Service: ClusterIP` (`otoroshi-service`, in-cluster)
- 1 × `Service: NodePort` (`otoroshi-external-service`, ports **31080** / **31443**)
- 1 × `Certificate`
- Base + initial-customization-single component

## Prerequisites

- Kubernetes ≥ 1.25 baremetal cluster
- An external L4 load balancer reachable on its own IP, routing TCP/80/443 to
  the cluster nodes on TCP/31080 / TCP/31443
- DNS pointing the Otoroshi hostnames at the LB's IP
- See [`nginx.example`](nginx.example) and [`haproxy.example`](haproxy.example)
  for ready-to-use LB configs
- An external Redis (or enable `components/redis`)

## Override checklist

Same as the [simple overlay](../simple/readme.md), plus the NodePort numbers
if they collide with another workload on your cluster:

```yaml
# overlays/simple-baremetal/deployment.yaml
spec:
  ports:
    - port: 80
      nodePort: 31080   # ← change here
    - port: 443
      nodePort: 31443   # ← and here
```

## DNS

```
otoroshi.your.domain        IN A <lb-ip>
otoroshi-api.your.domain    IN A <lb-ip>
privateapps.your.domain     IN A <lb-ip>
*.api.your.domain           IN A <lb-ip>
```

See [`dns.example`](dns.example) for a full sample.
