# Otoroshi — Kustomize manifests

```
base/         # mandatory floor: CRDs + RBAC
overlays/     # runnable scenarios (single vs cluster, LB vs NodePort vs DaemonSet)
components/   # opt-in add-ons (webhooks, redis, hpa, coredns, gateway-api)
examples/     # reference apps (sidecar-app, …)
```

## Pick an overlay

Each overlay is self-contained and runnable. Pick the one closest to your environment:

| Overlay | Otoroshi mode | External exposure | Workload kind |
|---|---|---|---|
| `simple` | single | LoadBalancer | Deployment |
| `simple-baremetal` | single | NodePort | Deployment |
| `simple-baremetal-daemonset` | single | hostPort | DaemonSet |
| `cluster` | Leader + Worker | LoadBalancer | Deployment |
| `cluster-baremetal` | Leader + Worker | NodePort | Deployment |
| `cluster-baremetal-daemonset` | Leader + Worker | hostPort | DaemonSet |

```sh
kubectl kustomize overlays/simple        # preview
kubectl apply -k overlays/simple         # deploy
```

### Baremetal port allocation

Overlays that bypass cloud LoadBalancers expose Otoroshi via well-known
`nodePort` / `hostPort` values. Allocations within a single overlay are
collision-free; pick the table row that matches what you're deploying.

| Overlay | Pod | HTTP | HTTPS |
|---|---|---|---|
| `simple-baremetal` | Otoroshi | `31080` (nodePort) | `31443` (nodePort) |
| `simple-baremetal-daemonset` | Otoroshi | `41080` (hostPort) | `41443` (hostPort) |
| `cluster-baremetal` | Leader | `31080` (nodePort) | `31443` (nodePort) |
| `cluster-baremetal` | Worker | `32080` (nodePort) | `32443` (nodePort) |
| `cluster-baremetal-daemonset` | Leader | `41080` (hostPort) | `41443` (hostPort) |
| `cluster-baremetal-daemonset` | Worker | `42080` (hostPort) | `42443` (hostPort) |

The `*.example` files (`nginx.example`, `haproxy.example`, `dns.example`)
shipped next to each baremetal overlay are pre-wired with these ports.

## Provide configuration values

No `${var}` substitution, no `envsubst` step. Values come from:

- a **`secretGenerator`** for credentials (`password`, `clientId`, `clientSecret`, `otoroshiSecret`, `redisUrl`)
- a **`configMapGenerator`** for non-secret config (`domain`)
- a **`configMapGenerator`** built from `initial-customization.json` (mounted in the pod, loaded by Otoroshi via `OTOROSHI_INITIAL_CUSTOMIZATION=file:///etc/otoroshi/initial-customization.json`)
- the **Downward API** + Kubernetes `$(VAR)` substitution for hostnames that need the pod's own namespace

Both generators ship in each overlay's `kustomization.yaml` with **placeholder defaults you must override before deploying to anything other than a throwaway cluster**.

### Edit literals in place

```yaml
# overlays/simple/kustomization.yaml
secretGenerator:
  - name: otoroshi-admin-secret
    literals:
      - password=changeMePlease
      - clientId=admin-api-apikey-id
      - clientSecret=$(openssl rand -hex 32)
      - otoroshiSecret=$(openssl rand -hex 32)
  - name: otoroshi-redis-secret
    literals:
      - redisUrl=redis://:redisPassword@redis.svc.cluster.local:6379/0

configMapGenerator:
  - name: otoroshi-config
    literals:
      - domain=otoroshi.example.com
```

Because the generators have **hash-suffixed names** by default, pods automatically roll when you change any value — no `kubectl rollout restart` needed.

### Production: layered overlay + external secret manager

For real workloads, don't put plaintext in `kustomization.yaml`. Layer a personal overlay:

```yaml
# my-overlay/kustomization.yaml
resources:
  - ../overlays/simple

secretGenerator:
  - name: otoroshi-admin-secret
    behavior: replace
    envs:
      - my-admin.env            # gitignored

  - name: otoroshi-redis-secret
    behavior: replace
    envs:
      - my-redis.env

configMapGenerator:
  - name: otoroshi-config
    behavior: replace
    literals:
      - domain=otoroshi.acme.io
```

Or use [External Secrets](https://external-secrets.io/), [SOPS](https://github.com/getsops/sops), [Vault Secrets Operator](https://developer.hashicorp.com/vault/docs/platform/k8s/vso) — the in-cluster Secrets are regular `Secret` objects with documented keys (`password`, `clientId`, `clientSecret`, `otoroshiSecret`, `redisUrl`) and can be produced by any external mechanism.

### Bootstrap JSON (`initial-customization.json`)

The JSON file sitting next to each overlay's `kustomization.yaml` seeds:

- the Kubernetes CRDs controller job (`KubernetesOtoroshiCRDsControllerJob`)
- the validation + sidecar-injection admission webhook sinks (consumed by `components/webhooks/`)
- the `KubernetesConfig` block (namespaces, ingress class, webhook names, …)

Edit it in place to tune Otoroshi's Kubernetes integration. The hash suffix on the generated ConfigMap means a rolling restart is triggered automatically when the file changes.

The JSON intentionally does **not** seed `tlsSettings.defaultDomain` — that's persisted in Otoroshi's GlobalConfig and can be set later via the admin UI or a `GlobalConfig` CR.

If you deploy in a namespace other than `otoroshi`, edit the JSON's `otoroshiNamespace` field to match.

## Components — opt-in add-ons

All add-ons are off by default. To enable, uncomment the `components:` block in your overlay's `kustomization.yaml`:

```yaml
components:
  - ../../components/webhooks
  - ../../components/redis
  - ../../components/hpa
  - ../../components/coredns
  - ../../components/gateway-api
```

| Component | What it adds | When to enable |
|---|---|---|
| [`webhooks`](components/webhooks/readme.md) | Validating + Mutating admission webhooks | Validate CRs server-side + inject otoroshi-sidecar via pod label |
| [`redis`](components/redis/readme.md) | Bundled Redis StatefulSets (leader+follower) with AUTH | Dev / small self-managed clusters; prefer managed Redis in prod |
| [`hpa`](components/hpa/readme.md) | HorizontalPodAutoscaler (autoscaling/v2) | Autoscale Otoroshi on CPU/memory (needs metrics-server) |
| [`coredns`](components/coredns/readme.md) | In-cluster CoreDNS resolving `*.otoroshi.mesh` | Service mesh mode / otoroshi-sidecar; isolation from the cluster's main CoreDNS |
| [`gateway-api`](components/gateway-api/readme.md) | RBAC patch for `gateway.networking.k8s.io` | Use Otoroshi as a Gateway API controller (CRDs must be installed separately from upstream) |
| [`pdb-single`](components/pdb-single/readme.md) / [`pdb-cluster`](components/pdb-cluster/readme.md) | PodDisruptionBudget | Survive node drains / cluster upgrades without losing all replicas |
| [`network-policy`](components/network-policy/readme.md) | Default NetworkPolicy (permissive starting point) | Required by clusters with deny-by-default policies; tighten via overlay patches |

## Patch recipes

Kustomize doesn't have "values" — anything not exposed as a generator
literal is a **patch**. The recipes below cover the common asks the Helm
chart exposes as values.

### Cloud LoadBalancer annotations + source ranges (P9)

```yaml
# overlays/your-overlay/kustomization.yaml
patches:
  - target:
      kind: Service
      name: otoroshi-external-service       # or otoroshi-{leader,worker}-external-service in cluster mode
    patch: |-
      - op: add
        path: /metadata/annotations
        value:
          service.beta.kubernetes.io/aws-load-balancer-type: nlb
          service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
      - op: add
        path: /spec/loadBalancerSourceRanges
        value:
          - 10.0.0.0/8
          - 192.168.0.0/16
      - op: add
        path: /spec/loadBalancerClass
        value: service.k8s.aws/nlb
```

### IRSA / Workload Identity on the ServiceAccount (P3)

```yaml
patches:
  - target:
      kind: ServiceAccount
      name: otoroshi-admin-user
    patch: |-
      - op: add
        path: /metadata/annotations
        value:
          eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/otoroshi-irsa
          # or GCP Workload Identity:
          # iam.gke.io/gcp-service-account: otoroshi@my-project.iam.gserviceaccount.com
```

### `extraEnv` / `extraVolumes` / scheduling fields (P11)

For arbitrary additions to the Otoroshi pod spec — `nodeSelector`,
`tolerations`, `priorityClassName`, extra env vars, extra volumes,
`imagePullSecrets`, etc. — patch the canonical Deployment:

```yaml
patches:
  - target:
      kind: Deployment
      name: otoroshi-deployment              # or otoroshi-{leader,worker}-deployment
    patch: |-
      - op: add
        path: /spec/template/spec/nodeSelector
        value:
          workload: otoroshi
      - op: add
        path: /spec/template/spec/tolerations
        value:
          - key: dedicated
            operator: Equal
            value: otoroshi
            effect: NoSchedule
      - op: add
        path: /spec/template/spec/priorityClassName
        value: system-cluster-critical
      - op: add
        path: /spec/template/spec/imagePullSecrets
        value:
          - name: my-private-registry
      - op: add
        path: /spec/template/spec/containers/0/env/-
        value:
          name: MY_EXTRA_VAR
          value: hello
      - op: add
        path: /spec/template/spec/volumes/-
        value:
          name: extra-config
          configMap:
            name: my-extra-config
      - op: add
        path: /spec/template/spec/containers/0/volumeMounts/-
        value:
          name: extra-config
          mountPath: /etc/otoroshi-extra
          readOnly: true
```

For `topologySpreadConstraints`, `dnsConfig`, `hostAliases`, etc., same
pattern — the Deployment is the canvas.

## Examples

- [`examples/sidecar-app/`](examples/sidecar-app/readme.md) — reference whoami backend wired with the otoroshi-sidecar pattern (uses `components/webhooks/`)

Examples are **not** building blocks — copy them into your own setup rather than depending on them from production overlays.
