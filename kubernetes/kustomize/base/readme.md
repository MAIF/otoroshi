# base

Mandatory floor that every overlay builds on top of:

- `crds.yaml` — Otoroshi CRDs (`proxy.otoroshi.io/v1` — Route, Backend, ApiKey, Certificate, …)
- `rbac.yaml` — ServiceAccount + ClusterRole + ClusterRoleBinding for the Otoroshi controller

Optional add-ons live in `../components/` (webhooks, redis, hpa, coredns, gateway-api).
Demo manifests live in `../examples/`.
