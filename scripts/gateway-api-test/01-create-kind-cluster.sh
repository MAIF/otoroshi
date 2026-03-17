#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 01-create-kind-cluster.sh
# Creates a Kind cluster for testing the Kubernetes Gateway API integration.
# One-shot script — run once, then use 02 and 03 scripts as needed.
# ─────────────────────────────────────────────────────────────────────────────

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"

# Check prerequisites
for cmd in kind kubectl docker; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: '$cmd' is required but not found in PATH"
    exit 1
  fi
done

# Delete existing cluster if present
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "Cluster '${CLUSTER_NAME}' already exists. Deleting..."
  kind delete cluster --name "${CLUSTER_NAME}"
fi

echo "Creating Kind cluster '${CLUSTER_NAME}'..."

kind create cluster --name "${CLUSTER_NAME}" --config=- <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  extraPortMappings:
  # Otoroshi HTTP  -> localhost:9880
  - containerPort: 30080
    hostPort: 9880
    protocol: TCP
  # Otoroshi HTTPS -> localhost:9843
  - containerPort: 30443
    hostPort: 9843
    protocol: TCP
EOF

kubectl cluster-info --context "kind-${CLUSTER_NAME}"
echo ""
echo "Kind cluster '${CLUSTER_NAME}' is ready."
echo "  HTTP  -> localhost:9880 (NodePort 30080)"
echo "  HTTPS -> localhost:9843 (NodePort 30443)"
echo ""
echo "Next step: run ./02-setup-gateway-api.sh"
