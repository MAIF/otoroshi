---
title: Managing Otoroshi with otoroshictl
sidebar_position: 28
---
# Managing Otoroshi with otoroshictl

[`otoroshictl`](https://cloud-apim.github.io/otoroshictl/) is a command-line tool for managing Otoroshi clusters developped by the folks at [Cloud APIM](https://www.cloud-apim.com/). It works similarly to `kubectl` for Kubernetes: you configure contexts pointing to different Otoroshi clusters, then use commands to query, create, update, delete, and synchronize resources. It is particularly useful for managing production environments and integrating Otoroshi into CI/CD pipelines.

## Installation

You can download the latest release of `otoroshictl` from the [GitHub releases page](https://github.com/cloud-apim/otoroshictl/releases). Binaries are available for Linux, macOS, and Windows.

```sh
# macOS (Apple Silicon)
curl -L -o otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-aarch64-apple-darwin
chmod +x otoroshictl
sudo mv otoroshictl /usr/local/bin/

# macOS (Intel)
curl -L -o otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-x86_64-apple-darwin
chmod +x otoroshictl
sudo mv otoroshictl /usr/local/bin/

# Linux (x86_64)
curl -L -o otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-x86_64-unknown-linux-gnu
chmod +x otoroshictl
sudo mv otoroshictl /usr/local/bin/
```

Verify the installation:

```sh
otoroshictl version
```

## Configuration concepts

`otoroshictl` uses a configuration file stored at `~/.config/io.otoroshi.otoroshictl/config.yaml`. This file contains three types of objects that work together, just like `kubectl`:

- **Clusters**: connection information for an Otoroshi instance (hostname, port, TLS)
- **Users**: authentication credentials (client ID, client secret, health key)
- **Contexts**: a named combination of a cluster and a user

You can have multiple contexts configured and switch between them to manage different environments (dev, staging, production, etc.).

## Setting up your first cluster

### Using the `config add` shortcut

The simplest way to configure a new cluster is the `config add` command, which creates a cluster, a user, and a context in one step:

```sh
otoroshictl config add prod-cluster \
  --hostname otoroshi-api.prod.example.com \
  --port 443 \
  --tls \
  --client-id admin-apikey-id \
  --client-secret admin-apikey-secret \
  --current
```

The `--current` flag makes this context active immediately.

### Step-by-step configuration

You can also configure each component individually, which is useful when multiple users share the same cluster:

```sh
# 1. Create a cluster entry
otoroshictl config set-cluster prod \
  --hostname otoroshi-api.prod.example.com \
  --port 443 \
  --tls

# 2. Create a user entry
otoroshictl config set-user prod-admin \
  --client-id admin-apikey-id \
  --client-secret admin-apikey-secret \
  --health-key my-health-access-key

# 3. Create a context linking cluster and user
otoroshictl config set-context prod \
  --cluster prod \
  --user prod-admin

# 4. Switch to the new context
otoroshictl config use prod
```

### Importing a context from a file

If someone shares a context configuration, you can import it directly:

```sh
# Import from a YAML file
otoroshictl config import -n prod-context --current --overwrite < context.yaml

# Import from stdin
cat context.yaml | otoroshictl config import --stdin -n prod-context --current
```

## Managing multiple environments

A typical production setup involves multiple Otoroshi clusters. Here is how to configure and switch between them:

```sh
# Add development environment
otoroshictl config add dev \
  --hostname otoroshi-api.dev.example.com \
  --port 443 --tls \
  --client-id dev-id --client-secret dev-secret

# Add staging environment
otoroshictl config add staging \
  --hostname otoroshi-api.staging.example.com \
  --port 443 --tls \
  --client-id staging-id --client-secret staging-secret

# Add production environment
otoroshictl config add prod \
  --hostname otoroshi-api.prod.example.com \
  --port 443 --tls \
  --client-id prod-id --client-secret prod-secret
```

### Switching contexts

```sh
# See all available contexts
otoroshictl config list

# See the current context
otoroshictl config current-context

# Switch to a different context
otoroshictl config use prod

# See detailed cluster configurations
otoroshictl config list-clusters
otoroshictl config list-users
otoroshictl config list-contexts
```

### Using an alternative config file

You can use a different configuration file for specific operations, which is useful in CI/CD:

```sh
# Use a specific config file
otoroshictl -c /path/to/ci-config.yaml resources get route

# Use a config file from a URL
otoroshictl -c https://vault.example.com/config.yaml resources get route
```

### Inline credentials (no config file)

For CI/CD pipelines or one-off commands, you can bypass the config file entirely:

```sh
otoroshictl \
  --otoroshi-cluster-hostname otoroshi-api.prod.example.com \
  --otoroshi-cluster-port 443 \
  --otoroshi-cluster-tls \
  --otoroshi-user-client-id admin-id \
  --otoroshi-user-client-secret admin-secret \
  resources get route
```

## Gathering cluster information

Before operating on a production cluster, you should check its status:

### Cluster version and info

```sh
# Get the Otoroshi version
otoroshictl version

# Get detailed cluster information (cluster ID, datastore, JVM, OS, etc.)
otoroshictl infos
```

### Health checks

```sh
# Check the health of all components
otoroshictl health
```

This returns the status of each component: Otoroshi, datastore, proxy, storage, event store, certificates, scripts, and cluster mode. Each component reports as `healthy`, `unhealthy`, `down`, or `unreachable`.

### Metrics

```sh
# Get all metrics
otoroshictl metrics

# Filter specific metrics
otoroshictl metrics --filters "jvm.memory,http_requests"

# Select specific columns
otoroshictl metrics --columns name,count,value
```

### List available resource types

```sh
# See all entity types managed by Otoroshi
otoroshictl entities
```

This lists all resource kinds with their group, version, and plural name, useful to know what resource names to use with `resources get`.

## Working with resources

### Listing resources

```sh
# List all routes
otoroshictl resources get route

# List all API keys
otoroshictl resources get apikey

# List all certificates
otoroshictl resources get certificate

# List all backends
otoroshictl resources get backend

# List all auth modules
otoroshictl resources get auth-module
```

### Pagination and filtering

```sh
# Paginate results (page 2, 50 items per page)
otoroshictl resources get route --page 2 --page-size 50

# Filter results
otoroshictl resources get apikey --filters "enabled=true"

# Select specific columns
otoroshictl resources get route --columns id,name,enabled,frontend
```

### Getting a specific resource

```sh
# Get a route by ID
otoroshictl resources get route route_123456

# Output as JSON
otoroshictl -o json resources get route route_123456

# Output as pretty JSON
otoroshictl -o json_pretty resources get route route_123456

# Output as YAML
otoroshictl -o yaml resources get route route_123456
```

### Getting a resource template

When creating a new resource, start from a template:

```sh
# Get a route template
otoroshictl resources template route

# Get a template in Kubernetes manifest format
otoroshictl resources template route --kube

# Save a template to a file
otoroshictl -o yaml resources template route > my-route.yaml
```

### Creating resources

```sh
# Create a route from a YAML file
otoroshictl resources create route -f my-route.yaml

# Create from JSON inline
otoroshictl resources create route --data "name=my-api" --data "frontend.domains[0]=api.example.com"

# Create from stdin
cat my-route.json | otoroshictl resources create route --stdin
```

### Editing resources

```sh
# Edit a route from a file
otoroshictl resources edit route route_123 -f updated-route.yaml

# Edit with inline data
otoroshictl resources edit route route_123 --data "enabled=true"

# Edit from stdin
cat updated-route.json | otoroshictl resources edit route route_123 --stdin
```

### Patching resources

Patching applies a JSON merge patch to an existing resource, updating only the specified fields without replacing the entire resource:

```sh
# Patch with a JSON merge
otoroshictl resources patch route route_123 --merge '{"enabled": false}'

# Patch from a file
otoroshictl resources patch route route_123 -f patch.json

# Patch with data tuples
otoroshictl resources patch route route_123 --data "enabled=false" --data "name=new-name"
```

### Deleting resources

```sh
# Delete a single resource
otoroshictl resources delete route route_123

# Delete multiple resources
otoroshictl resources delete route route_123 route_456 route_789

# Delete resources described in a file
otoroshictl resources delete route -f routes-to-delete.yaml

# Delete all resources from a directory
otoroshictl resources delete route -d ./old-routes/ -r
```

## File synchronization (Otoroshi-as-Code)

This is where `otoroshictl` really shines for production workflows. You can manage your entire Otoroshi configuration as files in a Git repository and synchronize them to your clusters.

### Exporting the current state

Export all resources from a running cluster to files:

```sh
# Export everything to a single JSON file
otoroshictl resources export -f backup.json

# Export to a directory with one file per resource
otoroshictl resources export -d ./otoroshi-config/ --split-files

# Export in Kubernetes manifest format (YAML with metadata wrapper)
otoroshictl resources export -d ./otoroshi-config/ --split-files --kube

# Export as newline-delimited JSON (useful for streaming/processing)
otoroshictl resources export -f export.ndjson --nd-json
```

When using `--split-files`, resources are organized by type and each resource gets its own file, making it easy to version control.

### Applying configuration from files

The `apply` command synchronizes resources from files to the cluster, creating or updating them as needed:

```sh
# Apply a single file
otoroshictl resources apply -f my-route.yaml

# Apply all files in a directory
otoroshictl resources apply -d ./otoroshi-config/

# Apply recursively from nested directories
otoroshictl resources apply -d ./otoroshi-config/ -r

# Watch mode: automatically re-apply when files change
otoroshictl resources apply -d ./otoroshi-config/ -r -w
```

The apply command supports both JSON and YAML files, including multi-document YAML files (separated by `---`). It also understands Kubernetes manifest format (with `apiVersion`, `kind`, `metadata`, and `spec` fields).

### Importing a full export

```sh
# Import from a previously exported file
otoroshictl resources import -f backup.json

# Import from ndjson format
otoroshictl resources import -f export.ndjson --nd-json

# Import from stdin
cat backup.json | otoroshictl resources import --stdin
```

### File format examples

**Otoroshi native JSON format:**

```json
{
  "id": "route_my-api",
  "name": "My API",
  "kind": "Route",
  "enabled": true,
  "frontend": {
    "domains": ["api.example.com"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "backend.internal",
        "port": 8080
      }
    ]
  },
  "plugins": []
}
```

**Otoroshi native YAML format:**

```yaml
id: route_my-api
name: My API
kind: Route
enabled: true
frontend:
  domains:
    - api.example.com
backend:
  targets:
    - hostname: backend.internal
      port: 8080
plugins: []
```

**Kubernetes manifest format:**

```yaml
apiVersion: proxy.otoroshi.io/v1
kind: Route
metadata:
  name: my-api
spec:
  id: route_my-api
  name: My API
  enabled: true
  frontend:
    domains:
      - api.example.com
  backend:
    targets:
      - hostname: backend.internal
        port: 8080
  plugins: []
```

**Multi-document YAML (multiple resources in one file):**

```yaml
apiVersion: proxy.otoroshi.io/v1
kind: Route
metadata:
  name: api-v1
spec:
  id: route_api-v1
  name: API v1
  enabled: true
  frontend:
    domains:
      - v1.api.example.com
  backend:
    targets:
      - hostname: backend-v1.internal
        port: 8080
  plugins: []
---
apiVersion: proxy.otoroshi.io/v1
kind: Route
metadata:
  name: api-v2
spec:
  id: route_api-v2
  name: API v2
  enabled: true
  frontend:
    domains:
      - v2.api.example.com
  backend:
    targets:
      - hostname: backend-v2.internal
        port: 8080
  plugins: []
```

## CI/CD integration

`otoroshictl` is designed to integrate seamlessly into CI/CD pipelines for GitOps-style Otoroshi configuration management.

### Recommended Git repository structure

```
otoroshi-config/
├── environments/
│   ├── dev/
│   │   ├── routes/
│   │   │   ├── api-v1.yaml
│   │   │   └── api-v2.yaml
│   │   ├── apikeys/
│   │   │   └── frontend-key.yaml
│   │   └── backends/
│   │       └── backend-pool.yaml
│   ├── staging/
│   │   ├── routes/
│   │   ├── apikeys/
│   │   └── backends/
│   └── prod/
│       ├── routes/
│       ├── apikeys/
│       └── backends/
└── shared/
    ├── auth-modules/
    │   └── corporate-oauth.yaml
    ├── certificates/
    │   └── wildcard-cert.yaml
    └── jwt-verifiers/
        └── internal-jwt.yaml
```

### GitHub Actions example

```yaml
name: Deploy Otoroshi Config
on:
  push:
    branches: [main]
    paths:
      - 'otoroshi-config/**'

jobs:
  deploy-staging:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Install otoroshictl
        run: |
          curl -L -o otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-x86_64-unknown-linux-gnu
          chmod +x otoroshictl
          sudo mv otoroshictl /usr/local/bin/

      - name: Deploy shared resources
        run: |
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.STAGING_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.STAGING_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.STAGING_CLIENT_SECRET }} \
            resources apply -d ./otoroshi-config/shared/ -r

      - name: Deploy staging resources
        run: |
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.STAGING_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.STAGING_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.STAGING_CLIENT_SECRET }} \
            resources apply -d ./otoroshi-config/environments/staging/ -r

      - name: Verify deployment
        run: |
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.STAGING_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.STAGING_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.STAGING_CLIENT_SECRET }} \
            health

  deploy-prod:
    runs-on: ubuntu-latest
    needs: deploy-staging
    environment: production
    steps:
      - uses: actions/checkout@v4

      - name: Install otoroshictl
        run: |
          curl -L -o otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-x86_64-unknown-linux-gnu
          chmod +x otoroshictl
          sudo mv otoroshictl /usr/local/bin/

      - name: Backup current prod config
        run: |
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.PROD_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.PROD_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.PROD_CLIENT_SECRET }} \
            resources export -f backup-$(date +%Y%m%d-%H%M%S).json

      - name: Deploy to production
        run: |
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.PROD_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.PROD_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.PROD_CLIENT_SECRET }} \
            resources apply -d ./otoroshi-config/shared/ -r
          otoroshictl \
            --otoroshi-cluster-hostname ${{ secrets.PROD_HOST }} \
            --otoroshi-cluster-port 443 \
            --otoroshi-cluster-tls \
            --otoroshi-user-client-id ${{ secrets.PROD_CLIENT_ID }} \
            --otoroshi-user-client-secret ${{ secrets.PROD_CLIENT_SECRET }} \
            resources apply -d ./otoroshi-config/environments/prod/ -r
```

### GitLab CI example

```yaml
stages:
  - deploy

variables:
  OTOROSHICTL_VERSION: "latest"

.deploy_template: &deploy_template
  image: ubuntu:latest
  before_script:
    - apt-get update && apt-get install -y curl
    - curl -L -o /usr/local/bin/otoroshictl https://github.com/cloud-apim/otoroshictl/releases/latest/download/otoroshictl-x86_64-unknown-linux-gnu
    - chmod +x /usr/local/bin/otoroshictl

deploy-staging:
  <<: *deploy_template
  stage: deploy
  script:
    - otoroshictl
        --otoroshi-cluster-hostname $STAGING_HOST
        --otoroshi-cluster-port 443
        --otoroshi-cluster-tls
        --otoroshi-user-client-id $STAGING_CLIENT_ID
        --otoroshi-user-client-secret $STAGING_CLIENT_SECRET
        resources apply -d ./otoroshi-config/shared/ -r
    - otoroshictl
        --otoroshi-cluster-hostname $STAGING_HOST
        --otoroshi-cluster-port 443
        --otoroshi-cluster-tls
        --otoroshi-user-client-id $STAGING_CLIENT_ID
        --otoroshi-user-client-secret $STAGING_CLIENT_SECRET
        resources apply -d ./otoroshi-config/environments/staging/ -r
  only:
    - main

deploy-prod:
  <<: *deploy_template
  stage: deploy
  script:
    - otoroshictl
        --otoroshi-cluster-hostname $PROD_HOST
        --otoroshi-cluster-port 443
        --otoroshi-cluster-tls
        --otoroshi-user-client-id $PROD_CLIENT_ID
        --otoroshi-user-client-secret $PROD_CLIENT_SECRET
        resources export -f backup-$(date +%Y%m%d).json
    - otoroshictl
        --otoroshi-cluster-hostname $PROD_HOST
        --otoroshi-cluster-port 443
        --otoroshi-cluster-tls
        --otoroshi-user-client-id $PROD_CLIENT_ID
        --otoroshi-user-client-secret $PROD_CLIENT_SECRET
        resources apply -d ./otoroshi-config/shared/ -r
    - otoroshictl
        --otoroshi-cluster-hostname $PROD_HOST
        --otoroshi-cluster-port 443
        --otoroshi-cluster-tls
        --otoroshi-user-client-id $PROD_CLIENT_ID
        --otoroshi-user-client-secret $PROD_CLIENT_SECRET
        resources apply -d ./otoroshi-config/environments/prod/ -r
  when: manual
  only:
    - main
```

### Using a config file in CI/CD

Instead of passing all flags inline, you can generate a config file in the pipeline:

```yaml
# ci-config.yaml (generated from secrets)
apiVersion: v1
kind: OtoroshiCtlConfig
current_context: target
clusters:
  - name: target
    hostname: otoroshi-api.prod.example.com
    port: 443
    tls: true
users:
  - name: deployer
    client_id: "${OTOROSHI_CLIENT_ID}"
    client_secret: "${OTOROSHI_CLIENT_SECRET}"
contexts:
  - name: target
    cluster: target
    user: deployer
```

Then use it:

```sh
otoroshictl -c ./ci-config.yaml resources apply -d ./config/ -r
```

## Backup and disaster recovery

### Regular backups

```sh
# Full backup as a single JSON file
otoroshictl resources export -f "backup-$(date +%Y%m%d-%H%M%S).json"

# Full backup as split files (easier to diff)
otoroshictl resources export -d "./backups/$(date +%Y%m%d)/" --split-files

# Backup in Kubernetes manifest format
otoroshictl resources export -d "./backups/$(date +%Y%m%d)/" --split-files --kube
```

### Restore from backup

```sh
# Restore from a full export file
otoroshictl resources import -f backup-20250115-143022.json

# Restore from a directory of split files
otoroshictl resources apply -d ./backups/20250115/ -r
```

### Migrating between clusters

```sh
# Export from source cluster
otoroshictl config use source-cluster
otoroshictl resources export -d ./migration/ --split-files

# Import to target cluster
otoroshictl config use target-cluster
otoroshictl resources apply -d ./migration/ -r
```

## mTLS authentication

For enhanced security, `otoroshictl` supports mutual TLS authentication with client certificates:

```sh
otoroshictl config set-cluster prod-mtls \
  --hostname otoroshi-api.prod.example.com \
  --port 443 \
  --tls
```

Client certificate paths can be configured in the config file directly:

```yaml
clusters:
  - name: prod-mtls
    hostname: otoroshi-api.prod.example.com
    port: 443
    tls: true
    cert_location: /path/to/client.crt
    key_location: /path/to/client.key
    ca_location: /path/to/ca.crt
```

Or using inline PEM values:

```yaml
clusters:
  - name: prod-mtls
    hostname: otoroshi-api.prod.example.com
    port: 443
    tls: true
    cert_value: |
      -----BEGIN CERTIFICATE-----
      ...
      -----END CERTIFICATE-----
    key_value: |
      -----BEGIN PRIVATE KEY-----
      ...
      -----END PRIVATE KEY-----
    ca_value: |
      -----BEGIN CERTIFICATE-----
      ...
      -----END CERTIFICATE-----
```

## Exposing local services with tunnels

`otoroshictl` includes tunneling capabilities to expose local services through a remote Otoroshi instance. This is useful for development, demos, or temporarily exposing internal services.

### Remote HTTP tunnels

Expose a local HTTP service through Otoroshi:

```sh
# Expose a local app running on port 3000
otoroshictl remote-tunnel \
  --local-port 3000 \
  --expose \
  --remote-subdomain my-local-app \
  --tls

# Expose with a custom domain
otoroshictl remote-tunnel \
  --local-port 8080 \
  --remote-domain my-app.example.com \
  --tls

# Expose a local HTTPS service
otoroshictl remote-tunnel \
  --local-port 3443 \
  --local-tls \
  --expose \
  --remote-subdomain my-secure-app
```

### TCP tunnels

Forward TCP traffic through Otoroshi (useful for SSH, databases, etc.):

```sh
# Expose local SSH via TCP tunnel
otoroshictl tcp-tunnel \
  --host gateway.example.com \
  --local-port 22 \
  --remote-port 2222 \
  --tls \
  --access-type apikey \
  --apikey-client-id my-key-id \
  --apikey-client-secret my-key-secret
```

### UDP tunnels

Forward UDP traffic (useful for DNS or other UDP services):

```sh
# Expose local DNS service
otoroshictl udp-tunnel \
  --host gateway.example.com \
  --local-port 53 \
  --remote-port 1053 \
  --tls
```

## Kubernetes integration

`otoroshictl` can generate Kubernetes resources for deploying Otoroshi with its operator:

### Generate CRDs

```sh
# Generate Kubernetes Custom Resource Definitions
otoroshictl resources crds

# Save CRDs to a file for kubectl apply
otoroshictl resources crds -f otoroshi-crds.yaml
```

### Generate RBAC

```sh
# Generate ServiceAccount, ClusterRole, and ClusterRoleBinding
otoroshictl resources rbac --namespace otoroshi --username otoroshi-admin
```

### Export as Kubernetes manifests

```sh
# Export all resources wrapped in Kubernetes manifest format
otoroshictl resources export -d ./k8s-manifests/ --split-files --kube
```

This wraps each Otoroshi resource in a proper Kubernetes manifest with `apiVersion`, `kind`, `metadata`, and `spec`, ready to be applied with `kubectl apply`.

## Toolbox utilities

### Open the backoffice

```sh
# Open the Otoroshi backoffice in your default browser
otoroshictl toolbox open
```

### Configure mTLS mode

```sh
# Set mTLS mode on the current cluster
otoroshictl toolbox mtls -m Need   # require client certificates
otoroshictl toolbox mtls -m Want   # request but don't require
otoroshictl toolbox mtls -m None   # disable mTLS
```

### Configure a mailer

```sh
# Set up SMTP mailer for alerting
otoroshictl toolbox add-mailer \
  --host smtp.example.com \
  --port 587 \
  --user alerts@example.com \
  --starttls
```

## Configuration management

### Viewing and editing the config file

```sh
# Show the config file content
otoroshictl config current-config

# Show the config file path
otoroshictl config current-location

# Open the config file in your default editor
otoroshictl config edit-current-config
```

### Renaming and deleting contexts

```sh
# Rename a context
otoroshictl config rename-context old-name new-name

# Delete a full context (cluster + user + context)
otoroshictl config delete my-old-context

# Delete individual components
otoroshictl config delete-cluster unused-cluster
otoroshictl config delete-user old-user
otoroshictl config delete-context stale-context

# Reset all configuration
otoroshictl config reset
```

## Output formats

All commands support multiple output formats via the `-o` flag:

| Format | Flag | Description |
|--------|------|-------------|
| Table | `-o table` | Default. Colored ASCII table |
| JSON | `-o json` | Compact JSON |
| Pretty JSON | `-o json_pretty` | Indented JSON |
| YAML | `-o yaml` | YAML format |
| Raw | `-o raw` | Plain text (version only) |

```sh
# Get routes as a table (default)
otoroshictl resources get route

# Get routes as JSON for scripting
otoroshictl -o json resources get route

# Get a specific route as YAML
otoroshictl -o yaml resources get route route_123

# Pretty JSON for readability
otoroshictl -o json_pretty resources get route route_123
```

## Global flags reference

| Flag | Short | Description |
|------|-------|-------------|
| `--verbose` | `-v` | Enable debug logging |
| `--ouput FORMAT` | `-o` | Output format (json, json_pretty, yaml, raw, table) |
| `--config-file PATH` | `-c` | Use a specific config file or URL |
| `--otoroshi-cluster-hostname` | | Override cluster hostname |
| `--otoroshi-cluster-port` | | Override cluster port |
| `--otoroshi-cluster-tls` | | Enable TLS for the connection |
| `--otoroshi-user-client-id` | | Override client ID |
| `--otoroshi-user-client-secret` | | Override client secret |
| `--otoroshi-user-health-key` | | Override health access key |

## Command aliases

Several commands have shorter aliases for convenience:

| Command | Alias |
|---------|-------|
| `resources` | `rs` |
| `config` | `cfg` |
| `sidecar` | `sc` |
| `tcp-tunnel` | `tt` |
| `udp-tunnel` | `ut` |
| `remote-tunnel` | `rt` |
| `toolbox` | `tb` |
| `cloud-apim` | `ca` |
| `challenge` | `ch` |

```sh
# These are equivalent
otoroshictl resources get route
otoroshictl rs get route

otoroshictl config use prod
otoroshictl cfg use prod
```

## Recipes

### Compare configurations between environments

```sh
# Export from both environments
otoroshictl config use staging
otoroshictl resources export -d ./staging-export/ --split-files

otoroshictl config use prod
otoroshictl resources export -d ./prod-export/ --split-files

# Diff the configurations
diff -r ./staging-export/ ./prod-export/
```

### Promote a route from staging to production

```sh
# Export the specific route from staging
otoroshictl config use staging
otoroshictl -o yaml resources get route route_my-api > route-to-promote.yaml

# Review and edit if needed (change domains, backends, etc.)
# Then apply to production
otoroshictl config use prod
otoroshictl resources apply -f route-to-promote.yaml
```

### Bulk-disable routes for maintenance

```sh
# Disable a route
otoroshictl resources patch route route_123 --merge '{"enabled": false}'

# Re-enable it
otoroshictl resources patch route route_123 --merge '{"enabled": true}'
```

### Watch mode for local development

During development, you can have `otoroshictl` automatically apply changes as you edit files:

```sh
# Watch a directory and auto-apply changes
otoroshictl resources apply -d ./my-routes/ -r -w
```

This watches the directory for file changes and re-applies the configuration whenever a file is modified, created, or deleted. This is particularly useful when developing route configurations locally against a dev Otoroshi instance.

### Scripting with JSON output

```sh
# Get route IDs for scripting
otoroshictl -o json resources get route | jq '.[].id'

# Count resources
otoroshictl -o json resources get apikey | jq 'length'

# Find routes matching a domain
otoroshictl -o json resources get route | jq '[.[] | select(.frontend.domains[] | contains("api.example.com"))]'
```
