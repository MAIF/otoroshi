git clone git@github.com:kubernetes-sigs/gateway-api.git
cd gateway-api

go test ./conformance -timeout 30m0s -v -run TestConformance -args -debug \
    --gateway-class=gateway-conformance \
    --supported-features=Gateway,HTTPRoute,GRPCRoute \
    --organization=CloudAPIM \
    --project=otoroshi \
    --url=https://github.com/MAIF/otoroshi \
    --version=17.13.0 \
    --contact=contact@cloud-apim.com \
    --report-output=./report.txt