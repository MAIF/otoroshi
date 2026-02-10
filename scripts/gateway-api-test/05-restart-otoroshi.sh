#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 04-restart.sh
# Restarts the Otoroshi deployment in the Kind test cluster.
# ─────────────────────────────────────────────────────────────────────────────

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true
echo "Restarting Otoroshi deployment..."
kubectl -n "${NAMESPACE}" rollout restart deployment/otoroshi
kubectl -n "${NAMESPACE}" rollout status deployment/otoroshi --timeout=120s
echo "Done."
