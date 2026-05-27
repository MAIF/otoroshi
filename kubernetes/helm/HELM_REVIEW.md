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

- [x] **7. `cert.yaml` (CR `proxy.otoroshi.io/v1 Certificate`) applied at install time** — needs Otoroshi running to process the CRD. Should be a `post-install` Helm hook, or be documented as eventually-consistent. → **documented in NOTES.txt §4** (eventually consistent — applied as a regular resource; a Helm hook would orphan it on upgrade). `cert.yaml` was also updated to emit the correct SANs in cluster mode.
- [x] **8. CRDs limitation not documented** — Helm never upgrades `crds/` content (by design). No `NOTES.txt` mention; doc doesn't warn users. → **documented in NOTES.txt §3** (manual `kubectl apply -f crds/` or `otoroshictl resources crds | kubectl apply -f -`).
- [x] **9. Cluster (Leader/Worker) mode absent** — `kustomize/overlays/cluster/` ships it, recommended in the docs, missing from Helm. Add a `cluster.enabled` mode. → **added**: new `cluster.*` values block + `templates/deployment-cluster.yaml` that renders Leader + Worker Deployments and 5 Services (`leader-api`, `leader`, `worker`, `leader-external`, `worker-external`), gated on `cluster.enabled`. Single deployment, single-mode Services, and HPA are gated on `not cluster.enabled`. `webhooks.yaml` retargets the validating/sidecar-injector webhooks to `cluster.leaderApiServiceName` when in cluster mode. `cert.yaml` SANs adapt to cluster service names.
- [x] **10. No `NOTES.txt`** — no admin URL, no default-credentials warning, no CRD-upgrade instructions. → **added** `templates/NOTES.txt` covering admin URL (adapts to single vs cluster mode), default-credentials warnings (conditional on values), CRD upgrade workflow, Certificate eventual-consistency note, conditional Gateway API and cluster mode sections.
- [x] **11. `rbac-gateway.yaml` partly duplicates `rbac.yaml`** core rules (different syntax style). Merge into one canonical `rbac.yaml` once 1a is fixed. → **already resolved by 1a**.

---

## Quality / customization

- [x] **12. Missing standard pod knobs** — `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `priorityClassName`, `securityContext`, `podSecurityContext`, `imagePullSecrets`, `podAnnotations`, `podLabels`, `extraEnv`, `extraEnvFrom`, `extraVolumes`, `extraVolumeMounts`. → **added**: new `otoroshi.podSchedulingFields` helper in `_helpers.tpl` plus inline blocks in single + cluster (leader, worker) deployments. Also added `extraArgs`.
- [x] **13. `_helpers.tpl` defines `selectorLabels` / `labels` helpers that are never used** — labels are copy-pasted in every template. → **fixed**: deployments, services, cert, rbac, hpa, webhooks, secret, pdb, networkpolicy, tests now use `include "otoroshi.labels"` and `include "otoroshi.selectorLabels"`. The noisy `meta.helm.sh/*` labels were dropped from rendered output (Helm adds the equivalent annotations itself when adopting resources).
- [x] **14. `serviceAccount.create` checked in helper but not exposed in `values.yaml`** — can't reuse an existing SA. → **fixed**: `serviceAccount.create` exposed (default true) and `rbac.yaml` gates the SA resource on it. `serviceAccount.annotations` added (IRSA / Workload Identity).
- [x] **15. `otoroshi-api-service` = `otoroshi-service`** — same selector, same ports. Redundant. → **kept + documented in values.yaml**: it's referenced as `ADMIN_API_ADDITIONAL_EXPOSED_DOMAIN` for the admin/CRD API, so it has a real purpose. Comment clarifies why.
- [x] **16. No `PodDisruptionBudget`, no `NetworkPolicy`, no `templates/tests/`**. → **added**: `templates/pdb.yaml` (adapts to single vs leader+worker), `templates/networkpolicy.yaml` (default: open ingress on Otoroshi ports + DNS + open egress, with `extraIngress`/`extraEgress` for tightening), `templates/tests/test-connection.yaml` (curl Pod hitting `/ready`).
- [x] **17. HPA missing `behavior` (scale up/down policies)** and only CPU/memory metrics. → **already done in 1b**: `autoscaling.extraMetrics` (verbatim v2 metrics) and `autoscaling.behavior` blocks ship in `values.yaml` and are wired into `hpa.yaml`.
- [x] **18. LoadBalancer Service** — no `loadBalancerSourceRanges`, no annotations (cloud providers). → **added**: `loadbalancer.annotations`, `loadbalancer.sourceRanges`, `loadbalancer.loadBalancerClass`. Threaded into both single and cluster (leader-external, worker-external) services.
- [x] **19. `values.yaml` ships default password `password` and `verysecretvaluethatyoumustoverwrite`** — should `{{ required }}` block install, or auto-generate. → **fixed**: defaults are now empty strings. New `otoroshi.adminCredentials` helper auto-generates via `randAlphaNum` AND persists across `helm upgrade` via `lookup` of the existing Secret. NOTES.txt explains the workflow.
- [x] **20. `Chart.yaml`** — chart version should follow its own SemVer (decouple from appVersion); add `kubeVersion: ">=1.25.0"`. → **added `kubeVersion: ">=1.25.0-0"`**. Chart version is kept aligned with appVersion (maintainer preference — easier to track Otoroshi releases at a glance).
- [x] **21. Doc `kubernetes.mdx:39-42`** — Helm install instructions are too minimal: no `--set env.password=…`, no mention of important values, no CRD upgrade procedure. → **rewritten Helm section** in `manual/next/docs/deploy/kubernetes.mdx`: quick install, credentials handling (auto-gen vs existingSecret), cluster mode, Gateway API, external Redis, cloud LB annotations, CRD upgrade warning + procedure.

---

## Recommended order

1. ✅ Block 1–6: unblock install + plug obvious holes. **— DONE.**
2. ✅ Block 7–11: cluster mode + post-install hooks + NOTES.txt + doc. **— DONE.**
3. ✅ Block 12–21: full production-grade chart polish. **— DONE.**

---

## Notes on the 1–6 batch (validation)

- `helm lint otoroshi` → 0 chart(s) failed.
- `helm template … --set autoscaling.enabled=true --set gatewayApi.enabled=true` renders HPA as `autoscaling/v2`, correctly targets `otoroshi-deployment`, and adds the Gateway API job + config block.
- `helm template … --set env.redisURL=redis://my-external:6379/0 --set env.existingSecret=my-creds` correctly routes credentials to `my-creds` and `REDIS_URL` to the external URL.
- Bonus fix on the way: `LoadBalancer.enabled=false` branch was setting `type: ClusterIP` while keeping `nodePort` on the ports (incoherent). Switched to `type: NodePort` in that branch.

---

## Notes on the 7–11 batch (validation)

- `helm lint otoroshi` → 0 chart(s) failed.
- Default render (single mode): 1 Deployment + 3 Otoroshi Services (otoroshi-service, otoroshi-api-service, otoroshi-external-service) + 1 Certificate + 1 ValidatingWebhookConfiguration + RBAC + Secret + Redis subchart.
- `helm template … --set cluster.enabled=true`: 2 Deployments (`otoroshi-leader-deployment` + `otoroshi-worker-deployment`) + 5 Otoroshi Services. `CLUSTER_MODE=Leader/Worker`, `CLUSTER_LEADER_URL` resolves to `otoroshi-leader-api-service.<ns>.svc.cluster.local:<service.https>`. Validating webhook retargets `otoroshi-leader-api-service`.
- NOTES.txt adapts: in cluster mode it points users to `otoroshi-worker-external-service` for ingress and `otoroshi-leader-service` for the admin port-forward.
- HPA is gated on `not cluster.enabled` for now (cluster-mode HPA would need per-deployment leader/worker config — left for later).

---

## Notes on the 12–21 batch (validation)

- `helm lint otoroshi` → 0 chart(s) failed in every combination tested.
- Default render: 30+ resources including the new Secret (with auto-generated 32-char `password`, 16-char `clientId`, 32-char `clientSecret`, 64-char `otoroshiSecret`).
- `--set pdb.enabled=true`: one PDB in single mode, two in cluster mode (leader + worker, each with the right selector).
- `--set networkPolicy.enabled=true`: NetworkPolicy applies to all Otoroshi pods (single + leader + worker via shared selector labels).
- `--set autoscaling.enabled=true --set cluster.enabled=true`: HPA correctly **not** rendered (gated; cluster mode left to manual HPAs for now).
- `--set 'loadbalancer.annotations.service\.beta\.kubernetes\.io/aws-load-balancer-type=nlb' --set 'loadbalancer.sourceRanges[0]=10.0.0.0/8'`: annotations and `loadBalancerSourceRanges` appear on the external Service in both modes.
- `--set 'extraEnv[0].name=FOO' --set 'extraEnv[0].value=bar' --set nodeSelector.role=otoroshi --set 'tolerations[0].key=foo' --set 'tolerations[0].operator=Exists'`: all threaded into the pod spec.
- `helm test`: smoke-test Pod (`curl --fail /ready` against the right Service per mode) is rendered with `helm.sh/hook: test`.
