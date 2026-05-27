# cluster-baremetal-daemonset overlay

Otoroshi Leader + Worker as `DaemonSet`s on baremetal — one pod per node,
listening on `hostPort` directly. Performance-sensitive variant of
[cluster-baremetal](../cluster-baremetal/readme.md).

## What it deploys

- 1 × Leader `DaemonSet` (`nodeAffinity: otoroshi-kind=leader`,
  hostPort **41080** / **41443**)
- 1 × Worker `DaemonSet` (`nodeAffinity: otoroshi-kind=worker`,
  hostPort **42080** / **42443**)
- 3 × `Service: ClusterIP` (`otoroshi-leader-api-service`,
  `otoroshi-leader-service`, `otoroshi-worker-service` — used for in-cluster
  traffic only; external traffic hits the hostPorts directly)
- 1 × `Certificate`

## Prerequisites

- Baremetal Kubernetes cluster
- Label your nodes:

  ```sh
  kubectl label node leader-node-A otoroshi-kind=leader
  kubectl label node leader-node-B otoroshi-kind=leader
  kubectl label node worker-node-1 otoroshi-kind=worker
  kubectl label node worker-node-2 otoroshi-kind=worker
  kubectl label node worker-node-3 otoroshi-kind=worker
  ```

- External L4 LB(s) routing:
  - control plane → `<leader-node-ip>:41080` / `<leader-node-ip>:41443`
  - production traffic → `<worker-node-ip>:42080` /
    `<worker-node-ip>:42443`
  - see [`nginx.example`](nginx.example) and [`haproxy.example`](haproxy.example)
- DNS pointing at the LB(s) — see [`dns.example`](dns.example)
- An external Redis (or enable `components/redis`)

## Port allocation

| Pod | HTTP | HTTPS |
|---|---|---|
| Leader | `41080` | `41443` |
| Worker | `42080` | `42443` |

## Trade-offs vs `cluster-baremetal`

- ✅ Direct node networking — one hop less, source IP preserved
- ✅ Clear physical separation: leader pods only on leader-labeled nodes,
  worker pods only on worker-labeled nodes
- ❌ `replicas:` doesn't apply — one pod per matching node
- ❌ Adding capacity requires labelling more nodes (not just bumping a number)
