# webhooks component

Validating + Mutating admission webhooks for Otoroshi.

- **Validating** (`otoroshi-admission-webhook-validation`) — kube-apiserver calls Otoroshi on every CREATE/UPDATE of a `proxy.otoroshi.io/v*` resource so Otoroshi can reject malformed CRs before they hit etcd.
- **Mutating** (`otoroshi-admission-webhook-injector`) — injects an otoroshi-sidecar container into pods labeled `otoroshi.io/sidecar: inject`.

Both `caBundle` and `failurePolicy` are left as placeholders and patched at runtime by Otoroshi (`KubernetesAdmissionWebhookCRDValidator` + `KubernetesAdmissionWebhookSidecarInjector` sinks — enabled in `initial-customization.json` by default).

Both webhooks point at the in-cluster `otoroshi-service` in the `otoroshi` namespace. If you deploy Otoroshi in a different namespace, patch `webhooks[].clientConfig.service.namespace` (or use this component as inspiration and copy + tweak).

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/webhooks
```
