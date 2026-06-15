# single-deployment component

Canonical single-mode Otoroshi workload: one `Deployment` + the internal
`otoroshi-service` (ClusterIP) + the `otoroshi-service-certificate` CR.

Shared by `overlays/simple` and `overlays/simple-baremetal` — they each add
their own external Service (LoadBalancer or NodePort) on top.

Does not apply to `simple-baremetal-daemonset` (different workload kind).
