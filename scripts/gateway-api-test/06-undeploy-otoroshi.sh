#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 06-undeploy.sh
# Undeploy the Otoroshi deployment in the Kind test cluster.
# ─────────────────────────────────────────────────────────────────────────────

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true
echo "Undeploy Otoroshi deployment..."
kubectl -n "${NAMESPACE}" delete -f ./otoroshi-deployment.yaml
echo "Done."
