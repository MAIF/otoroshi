# Otoroshi — Kustomize overlays

Each overlay under `overlays/` is a runnable scenario:

| Overlay | Mode | Exposure | Workload |
|---|---|---|---|
| `simple` | single | LoadBalancer | Deployment |
| `simple-baremetal` | single | NodePort | Deployment |
| `simple-baremetal-daemonset` | single | hostPort | DaemonSet |
| `cluster` | Leader + Worker | LoadBalancer | Deployment |
| `cluster-baremetal` | Leader + Worker | NodePort | Deployment |
| `cluster-baremetal-daemonset` | Leader + Worker | hostPort | DaemonSet |

Build a render or apply directly:

```sh
kubectl kustomize overlays/simple        # preview
kubectl apply -k overlays/simple         # deploy
```

## Configuration

All overlays are **self-contained** — no `${var}` substitution step, no `envsubst`. Values come from:

- a **`secretGenerator`** for credentials (`password`, `clientId`, `clientSecret`, `otoroshiSecret`)
- a **`configMapGenerator`** for non-secret config (`domain`, `redisUrl`)
- a **`configMapGenerator`** built from `initial-customization.json` (mounted in the pod and loaded by Otoroshi via `OTOROSHI_INITIAL_CUSTOMIZATION=file:///etc/otoroshi/initial-customization.json`)
- the **Downward API** + Kubernetes `$(VAR)` substitution for namespace-templated hostnames (the pod reads its own namespace from `metadata.namespace`)

Both generators ship in each overlay's `kustomization.yaml` with **placeholder defaults you must override before deploying to anything other than a throwaway cluster**.

### Editing the literals in place

For a quick test:

```yaml
# overlays/simple/kustomization.yaml
secretGenerator:
  - name: otoroshi-admin-secret
    literals:
      - password=changeMePlease
      - clientId=admin-api-apikey-id
      - clientSecret=$(openssl rand -hex 32)
      - otoroshiSecret=$(openssl rand -hex 32)

configMapGenerator:
  - name: otoroshi-config
    literals:
      - domain=otoroshi.example.com
      - redisUrl=redis://:redisPassword@redis.svc.cluster.local:6379/0
```

Then `kubectl apply -k overlays/simple`.

Because the generators have **hash-suffixed names** by default (kustomize standard), pods automatically roll when you change any value — no `kubectl rollout restart` needed.

### Production: bring your own Secret / ConfigMap

For real workloads, don't put plaintext in `kustomization.yaml`. Replace the generator with one that points at an external source, or layer a personal overlay on top:

```yaml
# my-overlay/kustomization.yaml
resources:
  - ../overlays/simple

secretGenerator:
  - name: otoroshi-admin-secret
    behavior: replace
    env: my-secrets.env       # gitignored

configMapGenerator:
  - name: otoroshi-config
    behavior: replace
    env: my-config.env
```

Or use [External Secrets](https://external-secrets.io/), [SOPS](https://github.com/getsops/sops), [Vault Secrets Operator](https://developer.hashicorp.com/vault/docs/platform/k8s/vso), etc. — the in-cluster `otoroshi-admin-secret` is a regular `Secret` and can be produced by any external mechanism, as long as the keys match (`password`, `clientId`, `clientSecret`, `otoroshiSecret`).

### Tweaking the bootstrap JSON

`initial-customization.json` sits next to each overlay's `kustomization.yaml`. It seeds:

- the Kubernetes CRDs controller job (`KubernetesOtoroshiCRDsControllerJob`)
- the validation + sidecar-injection admission webhook sinks
- the `KubernetesConfig` block (namespaces, ingress class, webhook names, …)

Override per-overlay by editing the file in place. The kustomize hash suffix on the generated ConfigMap means a rolling restart is triggered automatically on each change.

The JSON intentionally does **not** include `tlsSettings.defaultDomain` anymore — that's persisted in Otoroshi's GlobalConfig and can be set later via the admin UI or a `GlobalConfig` CR.

If you deploy in a non-`otoroshi` namespace, edit the JSON's `otoroshiNamespace` field to match.

## `base/` content

Everything in `base/` is opt-in unless explicitly referenced from an overlay's `resources:` list:

| File | Wired into base? | Purpose |
|---|---|---|
| `rbac.yaml` | ✓ | ServiceAccount + ClusterRole + ClusterRoleBinding |
| `crds.yaml` | ✓ | Otoroshi CRDs (`proxy.otoroshi.io/v1`) |
| `crds-gateway.yaml` | — | Kubernetes Gateway API integration CRD |
| `webhooks.yaml` | — | Validating + Mutating admission webhooks (canonical) |
| `validation-webhook.yaml` | — | Validating webhook only (legacy split) |
| `sidecar-webhook.yaml` | — | Mutating sidecar-injector webhook only (legacy split) |
| `hpa.yml` | — | Horizontal Pod Autoscaler (uses removed API — needs update) |
| `redis.yaml` | — | Bundled Redis (leader + follower StatefulSets, no auth) |
| `coredns.yaml` | — | Optional in-cluster CoreDNS for `*.otoroshi.mesh` resolution |
| `sidecar.yaml` / `example.yaml` | — | Example backend app with otoroshi-sidecar |

To enable any of them in an overlay, add the path to `resources:`:

```yaml
resources:
  - ../../base
  - ../../base/webhooks.yaml
  - ../../base/redis.yaml
  - deployment.yaml
```
