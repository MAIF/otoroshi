# gateway-api component

Lets Otoroshi act as a [Gateway API](https://gateway-api.sigs.k8s.io/) controller — `Gateway` / `HTTPRoute` / `GRPCRoute` / `ReferenceGrant` resources you create will be reconciled into Otoroshi routes.

## What it does

- **Patches** the base `otoroshi-admin-user` ClusterRole to add read access on
  `gateway.networking.k8s.io` resources + update access on their `/status` subresources.

## What it does **not** do

- **Does not install the Gateway API CRDs.** They live in a separate
  upstream project on their own release cadence. Install them before deploying
  this component:

  ```sh
  kubectl apply -f \
    https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/standard-install.yaml
  ```

- **Does not register the Otoroshi controller job.** You must add it to your
  overlay's `initial-customization.json`:

  ```json
  {
    "scripts": {
      "jobRefs": [
        "cp:otoroshi.plugins.jobs.kubernetes.KubernetesOtoroshiCRDsControllerJob",
        "cp:otoroshi.plugins.jobs.kubernetes.KubernetesGatewayApiControllerJob"
      ]
    }
  }
  ```

- **Does not create a `GatewayClass`.** You'll typically want one pointing at
  Otoroshi's controllerName — define it in your own overlay or example.

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/gateway-api
```
