# cluster overlay

Otoroshi in Leader + Worker mode, exposed via cloud-managed `LoadBalancer`s.
Use when you want a separated control-plane (Leader, where you change
config) and data-plane (Worker, where production traffic flows).

## What it deploys

- 1 × Leader `Deployment` (`otoroshi-leader-deployment`, 2 replicas)
- 1 × Worker `Deployment` (`otoroshi-worker-deployment`, 2 replicas)
- 3 × `Service: ClusterIP` — `otoroshi-leader-api-service` (admin API only),
  `otoroshi-leader-service` (leader UI), `otoroshi-worker-service` (in-cluster
  traffic to the data-plane)
- 2 × `Service: LoadBalancer` — `otoroshi-leader-external-service` (admin
  UI / API from outside), `otoroshi-worker-external-service` (public traffic
  ingress)
- 1 × `Certificate`
- Base + initial-customization-**cluster** component (points the Kubernetes
  controller at the worker Service)

## Prerequisites

- Kubernetes ≥ 1.25
- A cloud-controller that provisions LoadBalancers (or MetalLB on baremetal)
- DNS:
  - admin/API hostnames → leader LB
  - production traffic hostnames → worker LB
- An external Redis (or enable `components/redis`)

## Override checklist

Same as the [simple overlay](../simple/readme.md). One extra knob:

| Field | Default | Override for |
|---|---|---|
| `replicas.otoroshi-leader-deployment` | 2 | Independent leader sizing (1–3 typical) |
| `replicas.otoroshi-worker-deployment` | 2 | Data-plane sizing — scale based on traffic |

## DNS

```
# Admin / control plane → leader LB
otoroshi.your.domain        IN CNAME <leader-lb-hostname>
otoroshi-api.your.domain    IN CNAME <leader-lb-hostname>
privateapps.your.domain     IN CNAME <leader-lb-hostname>

# Production traffic → worker LB
api1.your.domain            IN CNAME <worker-lb-hostname>
api2.your.domain            IN CNAME <worker-lb-hostname>
*.api.your.domain           IN CNAME <worker-lb-hostname>
```

## Add-ons

```yaml
components:
  - ../../components/initial-customization-cluster   # always on
  - ../../components/webhooks
  - ../../components/redis
  - ../../components/pdb-cluster                     # separate PDB for leader + worker
  - ../../components/network-policy
  - ../../components/observability
```

HPA in cluster mode: the default `components/hpa` targets
`otoroshi-deployment` (single mode). For cluster mode, you'll want a per-role
HPA — see [`components/hpa/readme.md`](../../components/hpa/readme.md) for the
patch recipe targeting `otoroshi-worker-deployment`.
