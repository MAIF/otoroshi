# Helm chart review — `kubernetes/helm/otoroshi/`

Comparison against `kustomize/` manifests, `manual/next/docs/deploy/kubernetes.mdx`, `KubernetesConfig` (Scala) and the bundled CRDs.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` skipped / out of scope

---

## Critical (install-blocking or broken behavior)

- [x] **1a. Duplicate `ClusterRole otoroshi-admin-user`** — `templates/rbac.yaml` and `templates/rbac-gateway.yaml` both define a ClusterRole with the same name → `helm install` fails. Merge into a single `rbac.yaml` (including Gateway API rules). → **merged**; `rbac-gateway.yaml` deleted.
- [x] **1b. HPA broken** — `templates/hpa.yml` uses removed API `autoscaling/v2beta1` (gone in K8s 1.25), uses `targetAverageUtilization` (renamed in v2), and `scaleTargetRef.name` points to `{{ template "otoroshi.name" . }}` (= `otoroshi`) instead of `.Values.deployment.name` (= `otoroshi-deployment`). HPA never finds its target. Also rename file to `.yaml`. → **fixed**: migrated to `autoscaling/v2`, correct `scaleTargetRef.name`, renamed to `hpa.yaml`. New values: `autoscaling.targetCPUUtilizationPercentage`, `autoscaling.targetMemoryUtilizationPercentage`, `autoscaling.extraMetrics`, `autoscaling.behavior`.
- [x] **1c. `env.redisURL` ignored** — `deployment.yaml:67-68` comments out the value and force-overrides with `redis://{{ .Release.Name }}-redis-master:6379`. External / managed / sentinel Redis impossible via that knob. → **fixed**: `env.redisURL` honored when set, falls back to bundled subchart service.
- [x] **2. Gateway API job not registered** — `crds-gateway.yaml` + Gateway RBAC are shipped, but `OTOROSHI_INITIAL_CUSTOMIZATION` (deployment.yaml) doesn't include `cp:otoroshi.plugins.jobs.kubernetes.KubernetesGatewayApiControllerJob`. Gateway API does nothing out-of-the-box. Add it (conditional on `gatewayApi.enabled`). → **added**, gated on `gatewayApi.enabled` (with `controllerName`, listener ports, sync interval values).
- [x] **3. Secrets in plain env vars** — `password`, `clientId`, `clientSecret`, `secret` injected as `value: …` in the Deployment → visible in `kubectl get pod -o yaml`, `helm get values`. No `Secret` template, no `existingSecret` support. Create a `Secret` template + add `env.existingSecret` to reference an external one. → **fixed**: new `templates/secret.yaml`, env vars now use `secretKeyRef`, `env.existingSecret` short-circuits Secret creation and reuses an external one (expected keys: `password`, `clientId`, `clientSecret`, `otoroshiSecret`).
- [x] **4. `global.prod` couples 3 unrelated concerns** — replica count (1 vs N), backend (`inmemory` vs `lettuce`), Redis subchart deployment (`condition: global.prod`). Cannot do `inmemory + 2 replicas` (dev/test) nor `lettuce + 1 replica` (external Redis). Split into `storage.backend`, `redis.deploy` (or rely on chart dep condition), `replicaCount`. → **fixed**: `storage.backend`, `redis.deploy` (Chart.yaml dependency condition flipped to `redis.deploy`), `replicaCount`. `global.prod` left in `values.yaml` as a soft no-op for compatibility.
- [x] **5a. `crds-with-schema.yaml` (244 KB) orphan** — sits at chart root, outside `crds/`, never applied. Remove or move into `crds/` (but it overlaps with the existing `crds.yaml`). → **deleted**.
- [x] **5b. Duplicate `update` verb** in `rbac.yaml:73-74`. → **fixed via rbac merge**.
- [x] **5c. Invalid `caBundle: "."`** in `webhooks.yaml:78` (mutating webhook). Should be `""` (validating webhook uses `""`, mutating uses `"."` — invalid). → **fixed**.
- [x] **5d. Hardcoded webhook port `8443`** in `webhooks.yaml:33,77`. Should be `{{ .Values.service.https }}`. → **templated**.
- [x] **6. `JAVA_OPTS` hardcoded `-Xms2g -Xmx4g`** — ignores `.Values.resources`. If user lowers `requests.memory: 1Gi`, JVM OOMs. `-Xmx4g` + `MaxRAMPercentage=80.0` contradict each other (Xmx wins). Drive from values, or keep only `MaxRAMPercentage` and let the container memory limit cap the heap. → **fixed**: new top-level `javaOpts` value, defaults to `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` (no more conflicting Xms/Xmx).

---

## Important

- [ ] **7. `cert.yaml` (CR `proxy.otoroshi.io/v1 Certificate`) applied at install time** — needs Otoroshi running to process the CRD. Should be a `post-install` Helm hook, or be documented as eventually-consistent.
- [ ] **8. CRDs limitation not documented** — Helm never upgrades `crds/` content (by design). No `NOTES.txt` mention; doc doesn't warn users.
- [ ] **9. Cluster (Leader/Worker) mode absent** — `kustomize/overlays/cluster/` ships it, recommended in the docs, missing from Helm. Add a `cluster.enabled` mode.
- [ ] **10. No `NOTES.txt`** — no admin URL, no default-credentials warning, no CRD-upgrade instructions.
- [ ] **11. `rbac-gateway.yaml` partly duplicates `rbac.yaml`** core rules (different syntax style). Merge into one canonical `rbac.yaml` once 1a is fixed.

---

## Quality / customization

- [ ] **12. Missing standard pod knobs** — `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `priorityClassName`, `securityContext`, `podSecurityContext`, `imagePullSecrets`, `podAnnotations`, `podLabels`, `extraEnv`, `extraEnvFrom`, `extraVolumes`, `extraVolumeMounts`.
- [ ] **13. `_helpers.tpl` defines `selectorLabels` / `labels` helpers that are never used** — labels are copy-pasted in every template.
- [ ] **14. `serviceAccount.create` checked in helper but not exposed in `values.yaml`** — can't reuse an existing SA.
- [ ] **15. `otoroshi-api-service` = `otoroshi-service`** — same selector, same ports. Redundant.
- [ ] **16. No `PodDisruptionBudget`, no `NetworkPolicy`, no `templates/tests/`**.
- [ ] **17. HPA missing `behavior` (scale up/down policies)** and only CPU/memory metrics.
- [ ] **18. LoadBalancer Service** — no `loadBalancerSourceRanges`, no annotations (cloud providers).
- [ ] **19. `values.yaml` ships default password `password` and `verysecretvaluethatyoumustoverwrite`** — should `{{ required }}` block install, or auto-generate.
- [ ] **20. `Chart.yaml`** — chart version should follow its own SemVer (decouple from appVersion); add `kubeVersion: ">=1.25.0"`.
- [ ] **21. Doc `kubernetes.mdx:39-42`** — Helm install instructions are too minimal: no `--set env.password=…`, no mention of important values, no CRD upgrade procedure.

---

## Recommended order

1. ✅ Block 1–6 (this PR): unblock install + plug obvious holes. **— DONE.**
2. Block 7–11: cluster mode + post-install hooks + NOTES.txt + doc.
3. Block 12–21: full production-grade chart polish.

---

## Notes on the 1–6 batch (validation)

- `helm lint otoroshi` → 0 chart(s) failed.
- `helm template … --set autoscaling.enabled=true --set gatewayApi.enabled=true` renders HPA as `autoscaling/v2`, correctly targets `otoroshi-deployment`, and adds the Gateway API job + config block.
- `helm template … --set env.redisURL=redis://my-external:6379/0 --set env.existingSecret=my-creds` correctly routes credentials to `my-creds` and `REDIS_URL` to the external URL.
- Bonus fix on the way: `LoadBalancer.enabled=false` branch was setting `type: ClusterIP` while keeping `nodePort` on the ports (incoherent). Switched to `type: NodePort` in that branch.
