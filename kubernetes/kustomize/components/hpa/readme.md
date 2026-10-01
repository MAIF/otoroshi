# hpa component

`HorizontalPodAutoscaler` (autoscaling/v2) for the Otoroshi Deployment.

- Targets `otoroshi-deployment` (the name used by the `simple*` overlays).
- 2 ↔ 10 replicas, scales on CPU (80% of the cpu request of the pods).
- No memory target: the heap of a JVM fills up to its maximum whatever the traffic and stays there, so a memory
  target would be reached for good and keep the deployment at `maxReplicas`.
- Conservative scale-down (5-minute stabilization), aggressive scale-up.
- Requires the **metrics-server** in your cluster — without it the HPA reports `<unknown>` metrics and never scales.

## Enable

```yaml
# overlays/your-overlay/kustomization.yaml
components:
  - ../../components/hpa
```

## Cluster mode

The default `scaleTargetRef.name` (`otoroshi-deployment`) doesn't exist in
`cluster*` overlays — they have `otoroshi-leader-deployment` + `otoroshi-worker-deployment` instead. To use this component in cluster mode, patch the HPA
target name in your overlay:

```yaml
# your-overlay/kustomization.yaml
patches:
  - target:
      kind: HorizontalPodAutoscaler
      name: otoroshi-hpa
    patch: |-
      - op: replace
        path: /spec/scaleTargetRef/name
        value: otoroshi-worker-deployment
```

Or duplicate the manifest as a second HPA if you want both leader and worker autoscaled independently.
