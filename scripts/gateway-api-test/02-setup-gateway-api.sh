#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 02-setup-gateway-api.sh
# Installs Gateway API CRDs (v1.4 standard channel), RBAC for Otoroshi,
# and deploys Otoroshi configured with the Gateway API controller job.
# Uses in-memory storage (no Redis needed).
# ─────────────────────────────────────────────────────────────────────────────

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"
OTOROSHI_IMAGE="${OTOROSHI_IMAGE:-otoroshi-local:latest}"
GATEWAY_API_VERSION="${GATEWAY_API_VERSION:-v1.4.1}"

kubectl config use-context "kind-${CLUSTER_NAME}" 2>/dev/null || true

# ─── 1. Install Gateway API CRDs (standard channel) ─────────────────────────

echo "Installing Gateway API CRDs ${GATEWAY_API_VERSION} (standard channel)..."
kubectl apply -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/standard-install.yaml"
echo ""

# ─── 2. Create namespace ────────────────────────────────────────────────────

echo "Creating namespace '${NAMESPACE}'..."
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

# ─── 3. RBAC — ServiceAccount + ClusterRole + ClusterRoleBinding ────────────

echo "Applying RBAC..."
kubectl apply -f - <<EOF
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: otoroshi-admin-user
  namespace: ${NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: otoroshi-admin-user
rules:
  # Core resources (existing Otoroshi needs)
  - apiGroups: [""]
    resources: [services, endpoints, secrets, configmaps, namespaces, pods]
    verbs: [get, list, watch]
  - apiGroups: ["apps"]
    resources: [deployments]
    verbs: [get, list, watch]
  - apiGroups: [""]
    resources: [secrets, configmaps]
    verbs: [update, create, delete]
  # Ingress
  - apiGroups: [extensions, networking.k8s.io]
    resources: [ingresses, ingressclasses]
    verbs: [get, list, watch]
  - apiGroups: [extensions, networking.k8s.io]
    resources: [ingresses/status]
    verbs: [update]
  # Otoroshi CRDs
  - apiGroups: [proxy.otoroshi.io]
    resources: ["*"]
    verbs: [get, list, watch]
  # Gateway API — read resources
  - apiGroups: [gateway.networking.k8s.io]
    resources: [gatewayclasses, gateways, httproutes, referencegrants]
    verbs: [get, list, watch]
  # Gateway API — update status subresources
  - apiGroups: [gateway.networking.k8s.io]
    resources: [gatewayclasses/status, gateways/status, httproutes/status]
    verbs: [get, update, patch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: otoroshi-admin-user
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: otoroshi-admin-user
subjects:
- kind: ServiceAccount
  name: otoroshi-admin-user
  namespace: ${NAMESPACE}
EOF
echo ""

# ─── 4. Deploy Otoroshi ─────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

export OTOROSHI_IMAGE
export OTOROSHI_NAMESPACE="${NAMESPACE}"

echo "Deploying Otoroshi (image: ${OTOROSHI_IMAGE})..."
envsubst '${OTOROSHI_IMAGE} ${OTOROSHI_NAMESPACE}' < "${SCRIPT_DIR}/otoroshi-deployment.yaml" | kubectl apply -n "${NAMESPACE}" -f -
echo ""

# ─── 5. Deploy a test backend (echo server) ─────────────────────────────────

echo "Deploying test echo backend..."
kubectl apply -n default -f - <<EOF
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: echo-server
spec:
  replicas: 1
  selector:
    matchLabels:
      app: echo-server
  template:
    metadata:
      labels:
        app: echo-server
    spec:
      containers:
      - name: echo
        image: hashicorp/http-echo:0.2.3
        args: ["-text=hello from echo-server"]
        ports:
        - containerPort: 5678
---
apiVersion: v1
kind: Service
metadata:
  name: echo-service
spec:
  selector:
    app: echo-server
  ports:
  - port: 80
    targetPort: 5678
EOF
echo ""

# ─── 6. Deploy sample Gateway API resources ─────────────────────────────────

echo "Deploying sample Gateway API resources..."
kubectl apply -f - <<EOF
---
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: otoroshi
spec:
  controllerName: otoroshi.io/gateway-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: my-gateway
  namespace: default
spec:
  gatewayClassName: otoroshi
  listeners:
  - name: http
    protocol: HTTP
    port: 8080
    hostname: "*.oto.tools"
    allowedRoutes:
      namespaces:
        from: Same
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: echo-route
  namespace: default
spec:
  parentRefs:
  - name: my-gateway
    sectionName: http
  hostnames:
  - "echo.oto.tools"
  rules:
  - matches:
    - path:
        type: PathPrefix
        value: /
    backendRefs:
    - name: echo-service
      port: 80
      weight: 1
EOF
echo ""

echo "======================================================================="
echo "Setup complete!"
echo ""
echo "  Otoroshi is starting in namespace '${NAMESPACE}'"
echo "  Watch the pod:  kubectl -n ${NAMESPACE} get pods -w"
echo "  Pod logs:       kubectl -n ${NAMESPACE} logs -f deploy/otoroshi"
echo ""
echo "  Once ready, test the echo route:"
echo "    curl -H 'Host: echo.oto.tools' http://localhost:9880"
echo ""
echo "  Otoroshi backoffice:"
echo "    https://otoroshi.oto.tools:9880/bo/dashboard
echo "    Credentials: admin@oto.tools / password
echo ""
echo "  Check Gateway API resources:"
echo "    kubectl get gatewayclasses,gateways,httproutes -A"
echo "    kubectl get gatewayclasses otoroshi -o yaml  # check status"
echo "    kubectl get gateway my-gateway -o yaml       # check status"
echo "    kubectl get httproute echo-route -o yaml     # check status"
echo "======================================================================="
