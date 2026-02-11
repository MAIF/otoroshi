#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 07-deploy.sh
# Deploy the Otoroshi deployment in the Kind test cluster.
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"
OTOROSHI_IMAGE="${OTOROSHI_IMAGE:-otoroshi-local:latest}"

export OTOROSHI_IMAGE
export OTOROSHI_NAMESPACE="${NAMESPACE}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true
echo "Deploy Otoroshi deployment..."
kubectl -n "${NAMESPACE}" apply -f ./otoroshi-deployment.yaml
envsubst '${OTOROSHI_IMAGE} ${OTOROSHI_NAMESPACE}' < "${SCRIPT_DIR}/otoroshi-deployment.yaml" | kubectl apply -n "${NAMESPACE}" -f -

echo "Done."
