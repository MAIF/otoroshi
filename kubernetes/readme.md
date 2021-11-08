# Deploy to kubernetes

## Deploy with Helm

Jump to the otoroshi folder that contains the chart helm.

```sh
cd kubernetes/helm/otoroshi
```

Then run the following command.

```sh
helm install my-otoroshi . --namespace otoroshi
```

This should output :

```sh
NAME: my-otoroshi
LAST DEPLOYED: *****
NAMESPACE: otoroshi
STATUS: deployed
REVISION: 1
TEST SUITE: None
```