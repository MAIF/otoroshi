# redis component

Single-leader / single-follower Redis StatefulSets for Otoroshi's data plane.
**For production, prefer a managed Redis** (AWS ElastiCache, GCP Memorystore,
Upstash, …) — this is fine for dev and small self-managed clusters.

## What it ships

- `redis:7-alpine` (current LTS line, ~30 MB)
- `redis-leader-deployment` / `redis-follower-deployment` StatefulSets, each with a 100 Mi PVC (uses the cluster's default StorageClass)
- `redis-leader-service` / `redis-follower-service` (ClusterIP, 6379)
- `--requirepass` AUTH on both, with the password fed via a `REDIS_PASSWORD` env var (sourced from the `redis-password` Secret) so it never appears in `kubectl describe pod`
- `--masterauth` + `--replicaof` on the follower so it authenticates to the leader (modern replacement for the deprecated `--slaveof`)
- Probes use `redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping`

## Important: password lives in two places

Because the Lettuce client Otoroshi uses only reads the Redis password from the URI itself (not from a separate env var), the password literal appears **twice** in `kustomization.yaml`:

1. In the `redis-password` Secret (used by the Redis containers' `--requirepass`)
2. Embedded in the `otoroshi-redis-secret.redisUrl` override (used by Otoroshi to connect)

**Keep the two values in sync.** Edit both literals when rotating.

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/redis
```

Then change `changeMePleaseTheRedisDefaultPassword` in **both** `kustomization.yaml` literals before deploying.

## Storage class

The `volumeClaimTemplates` don't pin a StorageClass, so the cluster's default is used. To pin one, add a patch in your overlay:

```yaml
# your-overlay/patches/redis-pvc.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-leader-deployment
spec:
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        storageClassName: my-fast-ssd
```

and reference it via `patches:` in your overlay's `kustomization.yaml`.
