
export OTOROSHI_DOMAIN=otoroshi.mesh
export OTOROSHI_HOST=otoroshi-service.otoroshi.svc.cluster.local
export OTOROSHI_PORT=8443
export LOCAL_PORT=8081
export TOKEN_SECRET=secret
export EXTERNAL_PORT=8443
export INTERNAL_PORT=8080
export REQUEST_CERT=true
export DISABLE_TOKENS_CHECK=true
export REJECT_UNAUTHORIZED=true
export ENABLE_ORIGIN_CHECK=false
export DISPLAY_ENV=false
export ENABLE_TRACE=true
export CLIENT_ID_PATH=$(pwd)/certs/foo
export CLIENT_SECRET_PATH=$(pwd)/certs/foo
export CLIENT_CA_PATH=$(pwd)/certs/ca.cer
export CLIENT_CERT_PATH=$(pwd)/certs/client.cer
export CLIENT_KEY_PATH=$(pwd)/certs/client.key
export BACKEND_CA_PATH=$(pwd)/certs/ca.cer
export BACKEND_CERT_PATH=$(pwd)/certs/backend.cer
export BACKEND_KEY_PATH=$(pwd)/certs/backend.key

node ./src/sidecar.js