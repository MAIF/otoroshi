# initial-customization-cluster component

Provides the `otoroshi-initial-customization` ConfigMap mounted at
`/etc/otoroshi/initial-customization.json` in Leader-mode Otoroshi pods.

Points the `KubernetesOtoroshiCRDsControllerJob` at `otoroshi-worker-service`
(traffic is fanned out by the leader to the worker fleet). For single-mode
setups, use [`initial-customization-single`](../initial-customization-single/) instead.

This component is enabled by default in the `cluster*` overlays.

## Shape of the file

The whole payload lives under a top-level `config` key — that is the only key
Otoroshi merges into the global config (a sibling top-level `scripts` key means
something else entirely: a list of Script *entities* to import).

The admission-webhook request sinks are declared twice on purpose:

- under `config.plugins.config.ng`, wrapped in `RequestSinkWrapper`, because the
  next-gen proxy engine — the default — only reads its request sinks from there;
- under `config.scripts.sinkRefs`, for the legacy engine.

`config.plugins.config.ng` *replaces* Otoroshi's built-in list rather than
merging into it (a JSON deep merge does not merge arrays), which is why the
default `NgClientCredentials` entry is repeated in the file.

This JSON is only applied on the very first boot, while the datastore is still
empty. On an upgrade — or against a pre-existing Redis — Otoroshi keeps the
global config it already has, so changes here must also be applied by hand on
existing installs (Danger zone → Global plugins).

Because `otoroshiServiceName` is `otoroshi-worker-service` here, the
[`webhooks`](../webhooks/) component needs its `clientConfig.service.name`
patched to that same service — see its readme.

## Customize

Edit `initial-customization.json` next to this `kustomization.yaml` to tweak
the Kubernetes integration (watched namespaces, ingress class, sync interval,
webhook names, …). Because the generated ConfigMap is hash-suffixed, any
change triggers a rolling restart of the Otoroshi pods automatically.

When deploying in a namespace other than `otoroshi`, update the JSON's
`otoroshiNamespace` field to match.
