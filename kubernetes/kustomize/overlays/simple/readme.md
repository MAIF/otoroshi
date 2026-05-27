# simple overlay

Single-pod Otoroshi exposed through a cloud-managed `LoadBalancer`. The
default choice for managed Kubernetes (EKS, GKE, AKS, OVH, Scaleway, DO, …).

## What it deploys

- 1 × Otoroshi `Deployment` (`otoroshi-deployment`, 2 replicas, soft pod
  anti-affinity)
- 1 × `Service: ClusterIP` (`otoroshi-service`, in-cluster)
- 1 × `Service: LoadBalancer` (`otoroshi-external-service`, public)
- 1 × `Certificate` (otoroshi service cert, SANs for `oto.tools`)
- The mandatory floor from `base/` (CRDs + RBAC)
- The bootstrap JSON from `components/initial-customization-single`

## Prerequisites

- Kubernetes ≥ 1.25
- A cloud-controller that provisions LoadBalancers (managed K8s, or MetalLB on baremetal)
- DNS pointing the Otoroshi hostnames at the LB's IP / CNAME
- An external Redis (or enable `components/redis` for a bundled one)

## Override checklist

Before deploying to anything other than a throwaway cluster, edit
`kustomization.yaml`:

| Field | Default | Override for |
|---|---|---|
| `secretGenerator.otoroshi-admin-secret.password` | `password` | A real password (`$(openssl rand -hex 24)`) |
| `secretGenerator.otoroshi-admin-secret.clientSecret` | `admin-api-apikey-secret` | A real secret (`$(openssl rand -hex 32)`) |
| `secretGenerator.otoroshi-admin-secret.otoroshiSecret` | `verysecretvaluethatyoumustoverwrite` | A real secret (`$(openssl rand -hex 32)`) |
| `secretGenerator.otoroshi-redis-secret.redisUrl` | `redis://redis-leader-service:6379/0` | Your Redis URL with password embedded |
| `configMapGenerator.otoroshi-config.domain` | `oto.tools` | Your real domain |
| `images.maif/otoroshi.newTag` | `17.16.0-dev-jdk11` | A release tag — see top-level readme |
| `app.kubernetes.io/instance` label | `simple` | Whatever identifies your deployment |

## Common operations

```sh
# Preview
kubectl kustomize overlays/simple

# Deploy
kubectl apply -k overlays/simple

# Bump the image tag (requires standalone kustomize CLI)
kustomize edit set image maif/otoroshi=maif/otoroshi:17.13.0

# Scale
kustomize edit set replicas otoroshi-deployment=5

# Watch the LB get its external IP
kubectl get svc otoroshi-external-service -n otoroshi -w

# Get the auto-generated admin password
kubectl get secret -n otoroshi -l app.kubernetes.io/name=otoroshi -o name | head -1 \
  | xargs kubectl get -n otoroshi -o jsonpath='{.data.password}' | base64 -d
```

## Add-ons

Common compositions:

```yaml
# overlays/simple/kustomization.yaml — uncomment as needed
components:
  - ../../components/initial-customization-single  # always on
  - ../../components/webhooks                      # CR validation + sidecar injection
  - ../../components/redis                         # bundled Redis (dev only)
  - ../../components/hpa                           # autoscale on CPU/memory
  - ../../components/pdb-single                    # PodDisruptionBudget
  - ../../components/network-policy                # NetworkPolicy
  - ../../components/observability                 # Prometheus Operator integration
```

## DNS

```
otoroshi.your.domain        IN CNAME <lb-hostname>
otoroshi-api.your.domain    IN CNAME <lb-hostname>
privateapps.your.domain     IN CNAME <lb-hostname>
*.api.your.domain           IN CNAME <lb-hostname>
```
