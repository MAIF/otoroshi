# network-policy component

A single `NetworkPolicy` (`networking.k8s.io/v1`) that targets every Otoroshi
pod via the `app.kubernetes.io/name: otoroshi` label (set by the overlay's
`labels:` block — applies to single, leader, and worker).

Defaults are **permissive on purpose** — open ingress on Otoroshi's four
ports (8080, 8443, 80, 443), DNS egress, all-other egress open. This is a
starting point that's safe to enable without breaking anything; tighten with
`patches:` in your overlay.

## Enable

```yaml
components:
  - ../../components/network-policy
```

## Tighten ingress (restrict source namespaces / pods)

```yaml
# your-overlay/kustomization.yaml
patches:
  - target:
      kind: NetworkPolicy
      name: otoroshi-default
    patch: |-
      - op: replace
        path: /spec/ingress/0
        value:
          ports:
            - { protocol: TCP, port: 8080 }
            - { protocol: TCP, port: 8443 }
            - { protocol: TCP, port: 80 }
            - { protocol: TCP, port: 443 }
          from:
            # Allow ingress only from your ingress-controller namespace.
            - namespaceSelector:
                matchLabels:
                  kubernetes.io/metadata.name: ingress-nginx
            # …and from the cluster's monitoring namespace (for /metrics scrapes).
            - namespaceSelector:
                matchLabels:
                  kubernetes.io/metadata.name: monitoring
```

## Tighten egress (deny-by-default)

```yaml
patches:
  - target:
      kind: NetworkPolicy
      name: otoroshi-default
    patch: |-
      - op: replace
        path: /spec/egress
        value:
          # DNS
          - ports:
              - { protocol: UDP, port: 53 }
              - { protocol: TCP, port: 53 }
          # Redis
          - to:
              - podSelector:
                  matchLabels:
                    component: redis
            ports:
              - { protocol: TCP, port: 6379 }
          # kube-apiserver (for the K8s integration plugin) — the IP varies
          # per cluster; on EKS/GKE/AKS this is the cluster's API endpoint.
          - to:
              - ipBlock:
                  cidr: 10.0.0.1/32
            ports:
              - { protocol: TCP, port: 443 }
          # Upstream backends — adapt per env.
          - to:
              - ipBlock:
                  cidr: 0.0.0.0/0
                  except:
                    - 10.0.0.0/8
                    - 172.16.0.0/12
                    - 192.168.0.0/16
```
