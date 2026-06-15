# initial-customization-cluster component

Provides the `otoroshi-initial-customization` ConfigMap mounted at
`/etc/otoroshi/initial-customization.json` in Leader-mode Otoroshi pods.

Points the `KubernetesOtoroshiCRDsControllerJob` at `otoroshi-worker-service`
(traffic is fanned out by the leader to the worker fleet). For single-mode
setups, use [`initial-customization-single`](../initial-customization-single/) instead.

This component is enabled by default in the `cluster*` overlays.

## Customize

Edit `initial-customization.json` next to this `kustomization.yaml` to tweak
the Kubernetes integration (watched namespaces, ingress class, sync interval,
webhook names, …). Because the generated ConfigMap is hash-suffixed, any
change triggers a rolling restart of the Otoroshi pods automatically.

When deploying in a namespace other than `otoroshi`, update the JSON's
`otoroshiNamespace` field to match.
