# cluster-baremetal overlay

Otoroshi Leader + Worker exposed via `NodePort` on baremetal clusters.

## What it deploys

- 1 × Leader `Deployment` + 1 × Worker `Deployment` (each 2 replicas)
- 1 × `Service: ClusterIP` (`otoroshi-leader-api-service`)
- 2 × `Service: NodePort` — `otoroshi-leader-service` (**31080** / **31443**),
  `otoroshi-worker-service` (**32080** / **32443**) — these are the
  in-cluster Services from `components/cluster-deployments`, patched to
  NodePort by `deployment.yaml`
- 1 × `Certificate`

No external LoadBalancer Service is created; the patched internal Services
ARE the external entry points (NodePort opens the ports on every node).

## Prerequisites

- Baremetal Kubernetes cluster (k3s, kubeadm, …)
- External L4 LB(s) routing:
  - admin / API hostnames → `<node-ip>:31080` / `<node-ip>:31443` (leader)
  - production traffic → `<node-ip>:32080` / `<node-ip>:32443` (worker)
  - see [`nginx.example`](nginx.example) and [`haproxy.example`](haproxy.example)
    for ready-to-use configs split per role
- DNS pointing at the LB(s) — see [`dns.example`](dns.example)
- An external Redis (or enable `components/redis`)

## Port allocation

| Pod | HTTP | HTTPS |
|---|---|---|
| Leader | `31080` | `31443` |
| Worker | `32080` | `32443` |

Documented in the [top-level readme](../../readme.md#baremetal-port-allocation).

## Override checklist

Same as [cluster](../cluster/readme.md). NodePort numbers can be changed in
`deployment.yaml` (the patch file) if they collide with another workload.
