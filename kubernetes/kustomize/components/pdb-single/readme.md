# pdb-single component

A single `PodDisruptionBudget` (`policy/v1`) that keeps at least 1 Otoroshi
pod up during voluntary disruptions (node drain, kubelet upgrade, etc.).

The selector matches `run: otoroshi-deployment`, which is the workload label
shipped by `components/single-deployment` and by the
`simple-baremetal-daemonset` overlay's DaemonSet.

For cluster mode, use [`pdb-cluster`](../pdb-cluster/) — it ships one PDB per
role (leader + worker).

## Enable

```yaml
components:
  - ../../components/pdb-single
```
