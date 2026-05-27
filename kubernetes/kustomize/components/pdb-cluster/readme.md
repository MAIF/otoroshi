# pdb-cluster component

Two `PodDisruptionBudget`s (`policy/v1`) — one for leader pods, one for worker
pods — each keeping at least 1 pod up during voluntary disruptions.

Selectors match `run: otoroshi-leader-deployment` and `run:
otoroshi-worker-deployment` — the workload labels shipped by
`components/cluster-deployments` and the `cluster-baremetal-daemonset`
overlay's DaemonSets.

For single-mode setups, use [`pdb-single`](../pdb-single/).

## Enable

```yaml
components:
  - ../../components/pdb-cluster
```
