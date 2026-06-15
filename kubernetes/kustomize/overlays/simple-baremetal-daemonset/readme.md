# simple-baremetal-daemonset overlay

Single Otoroshi instance per node, listening directly on the node's network
ports via `hostPort` (no Service in the path → one fewer hop, source IP
preserved). Use for performance-sensitive baremetal deployments.

## What it deploys

- 1 × `DaemonSet` (`otoroshi-deployment`) — one pod per node matching
  `nodeAffinity { otoroshi-kind: instance }`
- Pods bind **41080** / **41443** on the host network
- 1 × `Service: ClusterIP` (`otoroshi-service`, in-cluster only — no external
  Service, hostPorts do the external exposure)
- 1 × `Certificate`
- Base + initial-customization-single component

## Prerequisites

- Kubernetes ≥ 1.25 baremetal cluster
- Label the nodes you want to run Otoroshi on:

  ```sh
  kubectl label node node-A otoroshi-kind=instance
  kubectl label node node-B otoroshi-kind=instance
  ```

- An external L4 load balancer routing TCP/80/443 → `<node-ip>:41080` /
  `<node-ip>:41443` for every labeled node — see [`nginx.example`](nginx.example)
  and [`haproxy.example`](haproxy.example)
- DNS pointing at the LB — see [`dns.example`](dns.example)
- An external Redis (or enable `components/redis`)

## Trade-offs vs `simple-baremetal`

- ✅ One network hop fewer (no kube-proxy in the data path)
- ✅ Client source IP preserved by default
- ❌ DaemonSet locks the pod to specific nodes — you lose scheduler
  flexibility (e.g. autoscaling)
- ❌ `replicas:` doesn't apply — one pod per matching node

## Override checklist

| Field | Default | Where |
|---|---|---|
| Admin / Redis credentials, domain, image | see [simple readme](../simple/readme.md) | `kustomization.yaml` |
| Node label key/value | `otoroshi-kind: instance` | `deployment.yaml` (`nodeAffinity`) |
| `hostPort` numbers | `41080` / `41443` | `deployment.yaml` (`containers[].ports[].hostPort`) |
