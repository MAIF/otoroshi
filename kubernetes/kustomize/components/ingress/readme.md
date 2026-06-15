# ingress component

`Ingress` (`networking.k8s.io/v1`) pointing at the in-cluster `otoroshi-service`. Use when your cluster already has an ingress controller (ingress-nginx, traefik, haproxy-ingress, …) and you don't want to spin up a dedicated cloud LoadBalancer for Otoroshi.

## What it ships

- 3 default rules: `otoroshi.oto.tools`, `otoroshi-api.oto.tools`, `privateapps.oto.tools` (mirrors the SANs in the bundled `otoroshi-service-certificate`)
- All route to `otoroshi-service:8443` (HTTPS — Otoroshi terminates TLS itself)
- `ingressClassName: nginx` + protocol-hint annotations for ingress-nginx and Traefik (drop the irrelevant one)

## Enable

```yaml
components:
  - ../../components/ingress
```

## Pick your domain

```yaml
# your-overlay/kustomization.yaml
patches:
  - target:
      kind: Ingress
      name: otoroshi
    patch: |-
      - op: replace
        path: /spec/rules
        value:
          - host: otoroshi.acme.io
            http:
              paths:
                - path: /
                  pathType: Prefix
                  backend:
                    service:
                      name: otoroshi-service
                      port: { number: 8443 }
          - host: otoroshi-api.acme.io
            http:
              paths:
                - { path: /, pathType: Prefix, backend: { service: { name: otoroshi-service, port: { number: 8443 } } } }
      - op: replace
        path: /spec/tls/0/hosts
        value: [otoroshi.acme.io, otoroshi-api.acme.io]
```

## Pick your ingress controller

```yaml
patches:
  - target:
      kind: Ingress
      name: otoroshi
    patch: |-
      - op: replace
        path: /spec/ingressClassName
        value: traefik
```

## TLS via cert-manager

Uncomment `secretName: otoroshi-ingress-tls` in the manifest and add a cert-manager `Certificate` CR pointing at that Secret name:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: otoroshi-ingress-tls
spec:
  secretName: otoroshi-ingress-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
    - otoroshi.acme.io
    - otoroshi-api.acme.io
    - privateapps.acme.io
```

## Conflict with LoadBalancer overlays

The `simple` / `cluster` overlays ship a `Service: LoadBalancer` for external exposure. Adding the `ingress` component on top works but you end up with two paths to Otoroshi (LB + Ingress). If you don't want the LB, patch the external Service to `type: ClusterIP` in your overlay.
