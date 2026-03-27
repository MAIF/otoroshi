#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# run conformance test suite
# ─────────────────────────────────────────────────────────────────────────────

ORIG_DIR="$(pwd)"
REPO_DIR="gateway-api-conformance-suite"
PATCH_FILE="$ORIG_DIR/oto.ptch"

if [ ! -d "$REPO_DIR/.git" ]; then
  echo "Cloning repository..."
  git clone git@github.com:kubernetes-sigs/gateway-api.git "$REPO_DIR"
else
  echo "Repository already exists → pulling latest changes"
  git -C "$REPO_DIR" pull --ff-only
fi

cd "$REPO_DIR"

# Apply patch if needed
if [ -f "$PATCH_FILE" ]; then
  echo "Checking patch state..."

  if git apply --reverse --check "$PATCH_FILE" >/dev/null 2>&1; then
    echo "Patch already applied."
  else
    echo "Applying patch..."
    git apply "$PATCH_FILE"
    echo "Patch applied."
  fi
else
  echo "Patch file not found: $PATCH_FILE"
fi

echo "Running Gateway API conformance tests..."

# need to refer to https://gateway-api.sigs.k8s.io/implementations/v1.4/#httproute and https://gateway-api.sigs.k8s.io/implementations/v1.4/#grpcroute
TESTS=(
    # BackendTLSPolicyConflictResolution
    # BackendTLSPolicyInvalidCACertificateRef
    # BackendTLSPolicyInvalidKind
    # BackendTLSPolicyObservedGenerationBump
    # BackendTLSPolicySANValidation
    # BackendTLSPolicy
    # GRPCExactMethodMatching
    # GRPCRouteHeaderMatching
    # GRPCRouteListenerHostnameMatching
    # GRPCRouteNamedRule
    # GRPCRouteWeight
    # HTTPRoute303Redirect
    # HTTPRoute307Redirect
    # HTTPRoute308Redirect
    # HTTPRouteBackendProtocolH2C
    # HTTPRouteBackendProtocolWebSocket
    # HTTPRouteCORSAllowCredentialsBehavior
    # HTTPRouteCORS
    # HTTPRouteCrossNamespace
    # HTTPRouteDisallowedKind
    # HTTPRouteExactPathMatching
    # HTTPRouteHeaderMatching
    # HTTPRouteHostnameIntersection
    # HTTPRouteHTTPSListenerDetectMisdirectedRequests
    # HTTPRouteHTTPSListener
    # HTTPRouteInvalidBackendRefUnknownKind
    # HTTPRouteInvalidCrossNamespaceBackendRef
    # HTTPRouteInvalidCrossNamespaceParentRef
    # HTTPRouteInvalidNonExistentBackendRef
    # HTTPRouteInvalidParentRefNotMatchingListenerPort
    # HTTPRouteInvalidParentRefNotMatchingSectionName
    # HTTPRouteInvalidParentRefSectionNameNotMatchingPort
    # HTTPRouteInvalidReferenceGrant
    # HTTPRouteListenerHostnameMatching
    # HTTPRouteListenerPortMatching
    # HTTPRouteMatchingAcrossRoutes
    HTTPRouteMatching
    HTTPRouteMethodMatching
    # HTTPRouteNamedRule
    # HTTPRouteObservedGenerationBump
    # HTTPRoutePartiallyInvalidViaInvalidReferenceGrant
    # HTTPRoutePathMatchOrder
    # HTTPRouteQueryParamMatching
    # HTTPRouteRedirectHostAndStatus
    # HTTPRouteRedirectPath
    # HTTPRouteRedirectPortAndScheme
    # HTTPRouteRedirectPort
    # HTTPRouteRedirectScheme
    # HTTPRouteReferenceGrant
    # HTTPRouteRequestHeaderModifierBackendWeights
    # HTTPRouteRequestHeaderModifier
    # HTTPRouteBackendRequestHeaderModifier
    # HTTPRouteRequestMirror
    # HTTPRouteRequestMultipleMirrors
    # HTTPRouteRequestPercentageMirror
    # HTTPRouteResponseHeaderModifier
    # HTTPRouteRewriteHost
    # HTTPRouteRewritePath
    # HTTPRouteServiceTypes
    # HTTPRouteSimpleSameNamespace
    # HTTPRouteTimeoutBackendRequest
    # HTTPRouteTimeoutRequest
    # HTTPRouteWeight
)

mkdir -p "$ORIG_DIR/conformance-reports"

for TEST in "${TESTS[@]}"; do
  echo "Running test: $TEST"

  # go test ./conformance -timeout 30m -v -run TestConformance -args -debug \
  #   --gateway-class=gateway-conformance \
  #   --run-test "$TEST" \
  #   --supported-features=Gateway,HTTPRoute,GRPCRoute

  go test ./conformance -timeout 30m -run TestConformance -args \
    --gateway-class=gateway-conformance \
    --run-test "$TEST" \
    --supported-features=Gateway,HTTPRoute,GRPCRoute > "$ORIG_DIR/conformance-reports/${TEST}-report.txt"

done

# go test ./conformance -timeout 30m -v -run TestConformance -args -debug \
#   --gateway-class=gateway-conformance \
#   -run-test HTTPRouteMatching \
#   --supported-features=Gateway,HTTPRoute,GRPCRoute

## full run
#
# go test ./conformance -timeout 30m -v -run TestConformance -args -debug \
#   --gateway-class=gateway-conformance \
#   --supported-features=Gateway,HTTPRoute,GRPCRoute
#   --unsupported-features=HT...
#   --organization=otoroshi \
#   --project=otoroshi \
#   --url=https://github.com/MAIF/otoroshi \
#   --version=17.13.0 \
#   --contact=oss@otoroshi.io \
#   --report-output=./report-oto.yaml

cd "$ORIG_DIR"
echo "Done."