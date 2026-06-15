# sidecar-app example

Reference example for the otoroshi-sidecar pattern. **This is a demo, not a building block** — copy it into your own setup, don't depend on it from production overlays.

It deploys a small `containous/whoami` backend with:

- `otoroshi.io/sidecar: inject` pod label → triggers the mutating webhook (from `components/webhooks/`) to add the otoroshi-sidecar container
- Pod annotations pointing at the ApiKey + Certificates Otoroshi should use for mTLS to and from the sidecar
- An `ApiKey` CR, several `Certificate` CRs, and a `ServiceDescriptor` exposing the backend at `https://backend.oto.tools`

## Try it

Apply on top of an Otoroshi install that includes the `webhooks` component:

```sh
kubectl apply -f examples/sidecar-app/app.yaml
```

Then `curl -k https://backend.oto.tools` (after pointing the domain at your cluster's external IP).

## Adapt to your setup

- Change the `hosts:` in the `ServiceDescriptor` and the `frontend-cert` to your own domain.
- The `default.otoroshi.mesh` wildcard cert assumes your apps live in the `default` namespace; change it for any other namespace.
- The `secret` shared-secret in `secComSettings` is the demo value — pick a real one and reuse it as the `otoroshi.io/token-secret` annotation on the pod.
