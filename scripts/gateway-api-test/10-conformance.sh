git clone git@github.com:kubernetes-sigs/gateway-api.git
cd gateway-api

go test ./conformance -timeout 30m0s -v -run TestConformance -args -debug \
    --gateway-class=gateway-conformance \
    --supported-features=Gateway,HTTPRoute,GRPCRoute 