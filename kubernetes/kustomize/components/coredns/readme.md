# coredns component

In-cluster CoreDNS sidecar that resolves `*.otoroshi.mesh` to the Otoroshi
service. Use this when you want pods to be able to call Otoroshi via a
`*.otoroshi.mesh` hostname (useful with the otoroshi-sidecar pattern and the
`KubernetesMeshDNS` plugin) without having to patch your cluster's main CoreDNS.

- 2 replicas with soft anti-affinity (one-per-node when possible).
- `coredns/coredns:1.11.3` (current line — bump as needed).
- Listens on UDP/TCP 5353 (clients must be configured to use this resolver — typical setup is to point a sub-zone in the cluster's main CoreDNS at `otoroshi-dns.<ns>.svc.cluster.local:5353`).

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/coredns
```

## Alternative

Otoroshi can also patch the **cluster's own CoreDNS** ConfigMap directly through the `coreDnsIntegration: true` flag in `initial-customization.json`. That's lighter (no extra pods) but requires write access to `kube-system/coredns`. Use this component when you want isolation, or when you don't have cluster-wide CoreDNS write permissions.
