#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 03-build-and-deploy.sh
# Builds Otoroshi (sbt assembly), builds a local Docker image,
# loads it into Kind, and restarts the Otoroshi deployment.
# Run this each time you change Otoroshi source code.
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OTOROSHI_DIR="${PROJECT_ROOT}/otoroshi"
DOCKER_BUILD_DIR="${PROJECT_ROOT}/docker/build"

CLUSTER_NAME="${KIND_CLUSTER_NAME:-otoroshi-gateway-test}"
NAMESPACE="${OTOROSHI_NAMESPACE:-otoroshi}"
IMAGE_NAME="${OTOROSHI_IMAGE:-otoroshi-local:latest}"
SKIP_BUILD="${SKIP_BUILD:-false}"

# Check prerequisites
for cmd in kind kubectl docker sbt; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: '$cmd' is required but not found in PATH"
    exit 1
  fi
done

# ─── 1. Build Otoroshi jar (sbt assembly) ───────────────────────────────────

JAR_PATH="${OTOROSHI_DIR}/target/scala-2.12/otoroshi.jar"

if [ "${SKIP_BUILD}" = "true" ] && [ -f "${JAR_PATH}" ]; then
  echo "Skipping build (SKIP_BUILD=true, jar exists)"
else
  echo "Building Otoroshi (sbt assembly)... this will take a few minutes"
  cd "${OTOROSHI_DIR}"
  sbt ";clean;assembly"
  cd "${PROJECT_ROOT}"
fi

if [ ! -f "${JAR_PATH}" ]; then
  echo "ERROR: Assembly jar not found at ${JAR_PATH}"
  exit 1
fi

echo "Jar built: $(du -h "${JAR_PATH}" | cut -f1) at ${JAR_PATH}"

# ─── 2. Build Docker image ──────────────────────────────────────────────────

echo ""
echo "Building Docker image '${IMAGE_NAME}'..."

TMPDIR="$(mktemp -d)"
trap 'rm -rf "${TMPDIR}"' EXIT

cp "${JAR_PATH}" "${TMPDIR}/otoroshi.jar"
cp "${DOCKER_BUILD_DIR}/entrypoint-jar.sh" "${TMPDIR}/entrypoint-jar.sh"
cp "${DOCKER_BUILD_DIR}/Dockerfile" "${TMPDIR}/Dockerfile"

docker build -t "${IMAGE_NAME}" "${TMPDIR}"

echo "Docker image '${IMAGE_NAME}' built successfully"

# ─── 3. Load image into Kind ────────────────────────────────────────────────

echo ""
echo "Loading image into Kind cluster '${CLUSTER_NAME}'..."
kind load docker-image "${IMAGE_NAME}" --name "${CLUSTER_NAME}"

# ─── 4. Restart Otoroshi deployment ─────────────────────────────────────────

echo ""
echo "Restarting Otoroshi deployment..."
kubectl -n "${NAMESPACE}" rollout restart deployment/otoroshi
kubectl -n "${NAMESPACE}" rollout status deployment/otoroshi --timeout=120s

echo ""
echo "======================================================================="
echo "Otoroshi redeployed with fresh build!"
echo ""
echo "  Pod logs:  kubectl -n ${NAMESPACE} logs -f deploy/otoroshi"
echo "  Test:      curl -H 'Host: echo.oto.tools' http://localhost:9880"
echo ""
echo "  Tip: set SKIP_BUILD=true to skip sbt assembly if jar is up to date"
echo "======================================================================="
