#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 04-redeploy-otoroshi.sh
# Deletes and re-deploys Otoroshi from otoroshi-deployment.yaml.
# Useful to get a fresh Otoroshi instance (clean in-memory state).
# Edit otoroshi-deployment.yaml to change the config, then re-run this script.
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"
OTOROSHI_IMAGE="${OTOROSHI_IMAGE:-otoroshi-local:latest}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true

export OTOROSHI_IMAGE
export OTOROSHI_NAMESPACE="${NAMESPACE}"

echo "Deleting current Otoroshi deployment..."
envsubst '${OTOROSHI_IMAGE} ${OTOROSHI_NAMESPACE}' < "${SCRIPT_DIR}/otoroshi-deployment.yaml" | kubectl delete -n "${NAMESPACE}" -f - --ignore-not-found
echo ""

echo "Waiting for pod termination..."
kubectl -n "${NAMESPACE}" wait --for=delete pod -l app=otoroshi --timeout=60s 2>/dev/null || true
echo ""

echo "Re-deploying Otoroshi (image: ${OTOROSHI_IMAGE})..."
envsubst '${OTOROSHI_IMAGE} ${OTOROSHI_NAMESPACE}' < "${SCRIPT_DIR}/otoroshi-deployment.yaml" | kubectl apply -n "${NAMESPACE}" -f -
echo ""

echo "Waiting for rollout..."
kubectl -n "${NAMESPACE}" rollout status deployment/otoroshi --timeout=120s

echo ""
echo "======================================================================="
echo "Otoroshi redeployed with fresh state."
echo ""
echo "  Pod logs:  ./logs.sh"
echo "  Test:      curl -H 'Host: echo.oto.tools' http://localhost:9880"
echo "======================================================================="
