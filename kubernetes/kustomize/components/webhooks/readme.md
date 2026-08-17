# webhooks component

Validating + Mutating admission webhooks for Otoroshi.

- **Validating** (`otoroshi-admission-webhook-validation`) — kube-apiserver calls Otoroshi on every CREATE/UPDATE of a `proxy.otoroshi.io/v*` resource so Otoroshi can reject malformed CRs before they hit etcd.
- **Mutating** (`otoroshi-admission-webhook-injector`) — injects an otoroshi-sidecar container into pods labeled `otoroshi.io/sidecar: inject`.

Both `caBundle` and `failurePolicy` are left as placeholders and patched at runtime by Otoroshi (`KubernetesAdmissionWebhookCRDValidator` + `KubernetesAdmissionWebhookSidecarInjector` sinks — enabled in `initial-customization.json` by default, both as next-gen `plugins.config.ng` entries and as legacy `scripts.sinkRefs`).

Note that `failurePolicy` is flipped from `Ignore` to `Fail` by the CRDs controller job on its first sync loop. Until that happens a broken webhook goes unnoticed (the apiserver swallows the error); afterwards every `kubectl apply` of a `proxy.otoroshi.io` resource fails hard. So always validate the setup right after enabling this component, not before.

Both webhooks point at the in-cluster `otoroshi-service`. The namespace is automatically rewritten from the overlay's top-level `namespace:` field via a custom `kustomizeconfig.yaml` (kustomize's built-in NamespaceTransformer doesn't cover `webhooks[].clientConfig.service.namespace`).

## The service name must match `otoroshiServiceName`

The sinks answering these calls only match a Host of `<otoroshiServiceName>.<namespace>.svc[.cluster.local]`, and the certificate Otoroshi generates for the endpoint is issued for exactly those two SANs. `webhooks[].clientConfig.service.name` must therefore be the same service as `KubernetesConfig.otoroshiServiceName` in `initial-customization.json`.

That holds out of the box for the `simple*` overlays (`otoroshi-service` on both sides). The `cluster*` overlays use `otoroshi-worker-service`, so add a patch alongside the component:

```yaml
# overlays/cluster/kustomization.yaml
components:
  - ../../components/webhooks

patches:
  - target:
      group: admissionregistration.k8s.io
      kind: (Validating|Mutating)WebhookConfiguration
    patch: |-
      - op: replace
        path: /webhooks/0/clientConfig/service/name
        value: otoroshi-worker-service
```

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/webhooks
```
