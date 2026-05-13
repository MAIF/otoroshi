# Getting started

Navigate to the resource/kubernetes folder

# Run K3s
```
docker run -d \
  --name k3s \
  --privileged \
  -p 6443:6443 \
  rancher/k3s:latest server --disable=traefik
```

# Cluster Initialization

```
docker cp common/serviceAccount.yaml k3s:/tmp/serviceAccount.yaml && \
docker cp common/crds.yaml k3s:/tmp/crds.yaml && \
docker cp common/rbac.yaml k3s:/tmp/rbac.yaml && \
docker cp common/redis.yaml k3s:/tmp/redis.yaml

docker exec k3s kubectl apply -f /tmp/serviceAccount.yaml && \
docker exec k3s kubectl apply -f /tmp/crds.yaml && \
docker exec k3s kubectl apply -f /tmp/rbac.yaml && \
docker exec k3s kubectl apply -f /tmp/redis.yaml
```

# Get a new token

```
docker cp dev-cluster/token-secret.yaml k3s:/tmp/ &&
docker exec k3s kubectl apply -f /tmp/token-secret.yaml
```

```
docker exec k3s kubectl get secret otoroshi-admin-user-token -o jsonpath='{.data.token}' | base64 -d
```

# Configure Otoroshi in the danger zone

In plugin sections
```
"KubernetesConfig": {
    "endpoint": "https://127.0.0.1:6443",
    "token": "<token>",
    "trust": true,
    "namespaces": [
      "foo"
    ],
    ...
```

# Create the foo namespace

```
docker cp dev-cluster/foo-namespace.yaml k3s:/tmp/foo-namespace.yaml &&
docker exec k3s kubectl apply -f /tmp/foo-namespace.yaml
```


