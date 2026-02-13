#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 11-logs.sh
# Follow Otoroshi pod logs in the Kind test cluster.
# ─────────────────────────────────────────────────────────────────────────────

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true
kubectl -n "${NAMESPACE}" logs -f deploy/otoroshi --tail=200 "$@"
