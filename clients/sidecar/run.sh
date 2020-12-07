docker run -p "8443:8443" \
  -e "OTOROSHI_DOMAIN=otoroshi.mesh" \
  -e "OTOROSHI_HOST=otoroshi-service.otoroshi.svc.cluster.local" \
  -e "OTOROSHI_PORT=8443" \
  -e "LOCAL_PORT=8081" \
  -e "TOKEN_SECRET=secret" \
  -e "EXTERNAL_PORT=8443" \
  -e "INTERNAL_PORT=8080" \
  -e "REQUEST_CERT=false" \
  -e "DISABLE_TOKENS_CHECK=true" \
  -e "REJECT_UNAUTHORIZED=false" \
  -e "ENABLE_ORIGIN_CHECK=false" \
  -e "DISPLAY_ENV=true" \
  -e "ENABLE_TRACE=true" \
  -v $(pwd)/certs/foo:/var/run/secrets/kubernetes.io/otoroshi.io/apikeys/clientId \
  -v $(pwd)/certs/foo:/var/run/secrets/kubernetes.io/otoroshi.io/apikeys/clientSecret \
  -v $(pwd)/certs/ca.cer:/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/ca.crt \
  -v $(pwd)/certs/client.cer:/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/tls.crt \
  -v $(pwd)/certs/client.key:/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/tls.key \
  -v $(pwd)/certs/ca.cer:/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/ca.crt \
  -v $(pwd)/certs/backend.cer:/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/tls.crt \
  -v $(pwd)/certs/backend.key:/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/tls.key \
  maif/otoroshi-sidecar:14

  