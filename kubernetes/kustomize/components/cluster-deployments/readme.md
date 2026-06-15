# cluster-deployments component

Canonical Leader + Worker Otoroshi workloads: two `Deployment`s, three
internal `Service`s (`otoroshi-leader-api-service`, `otoroshi-leader-service`,
`otoroshi-worker-service` — all ClusterIP), and the shared
`otoroshi-service-certificate` CR.

Shared by `overlays/cluster` and `overlays/cluster-baremetal`. Each overlay
adds:

- `cluster/` — LoadBalancer Services (`otoroshi-leader-external-service`,
  `otoroshi-worker-external-service`)
- `cluster-baremetal/` — patches the three internal Services to `type:
  NodePort` with the documented nodePorts (`31080`, `31443`, `32080`, `32443`)

Does not apply to `cluster-baremetal-daemonset` (different workload kind).
