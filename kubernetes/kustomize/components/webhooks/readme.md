# webhooks component

Validating + Mutating admission webhooks for Otoroshi.

- **Validating** (`otoroshi-admission-webhook-validation`) — kube-apiserver calls Otoroshi on every CREATE/UPDATE of a `proxy.otoroshi.io/v*` resource so Otoroshi can reject malformed CRs before they hit etcd.
- **Mutating** (`otoroshi-admission-webhook-injector`) — injects an otoroshi-sidecar container into pods labeled `otoroshi.io/sidecar: inject`.

Both `caBundle` and `failurePolicy` are left as placeholders and patched at runtime by Otoroshi (`KubernetesAdmissionWebhookCRDValidator` + `KubernetesAdmissionWebhookSidecarInjector` sinks — enabled in `initial-customization.json` by default).

Both webhooks point at the in-cluster `otoroshi-service`. The namespace is automatically rewritten from the overlay's top-level `namespace:` field via a custom `kustomizeconfig.yaml` (kustomize's built-in NamespaceTransformer doesn't cover `webhooks[].clientConfig.service.namespace`).

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/webhooks
```
