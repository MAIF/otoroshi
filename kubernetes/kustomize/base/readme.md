# base

Mandatory floor that every overlay builds on top of:

- `crds.yaml` — Otoroshi CRDs (`proxy.otoroshi.io/v1` — Route, Backend, ApiKey, Certificate, …)
- `rbac.yaml` — ServiceAccount + ClusterRole + ClusterRoleBinding for the Otoroshi controller

Optional add-ons live in `../components/` (webhooks, redis, hpa, coredns, gateway-api, initial-customization-*).
Demo manifests live in `../examples/`.

## A note on `namespace:`

`base/kustomization.yaml` itself does **not** declare a `namespace:` field —
namespaced resources render without `metadata.namespace` if you `kubectl
kustomize base/` directly. That's intentional: the overlay decides where
things live (every overlay sets `namespace: otoroshi`). The CRDs in
`crds.yaml` are cluster-scoped, and the RBAC ServiceAccount inherits the
overlay's namespace via the kustomize NamespaceTransformer.

If you want to render `base/` standalone (e.g., to install only the CRDs
cluster-wide), layer a tiny one-off kustomization:

```yaml
# my-crds-only.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../base/crds.yaml
```

…and `kubectl apply -k .` it.
