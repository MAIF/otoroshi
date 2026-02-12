#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 08-cleanup-conformance-resources.sh
# Cleanup resources from conformance test suite when it crashes dans does not cleanup everything it created
# ─────────────────────────────────────────────────────────────────────────────

kubectl api-resources --namespaced -o name | xargs -n 1 kubectl delete -n "gateway-conformance-app-backend" --all --ignore-not-found
kubectl delete pvc -n "gateway-conformance-app-backend" --all --ignore-not-found
kubectl delete secret,configmap -n "gateway-conformance-app-backend" --all --ignore-not-found
kubectl delete namespace gateway-conformance-app-backend

kubectl api-resources --namespaced -o name | xargs -n 1 kubectl delete -n "gateway-conformance-infra" --all --ignore-not-found 
kubectl delete pvc -n "gateway-conformance-infra" --all --ignore-not-found
kubectl delete secret,configmap -n "gateway-conformance-infra" --all --ignore-not-found
kubectl delete namespace gateway-conformance-infra

kubectl api-resources --namespaced -o name | xargs -n 1 kubectl delete -n "gateway-conformance-web-backend" --all --ignore-not-found
kubectl delete pvc -n "gateway-conformance-web-backend" --all --ignore-not-found
kubectl delete secret,configmap -n "gateway-conformance-web-backend" --all --ignore-not-found
kubectl delete namespace gateway-conformance-web-backend