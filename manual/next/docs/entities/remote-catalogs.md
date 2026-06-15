---
title: Remote Catalogs
sidebar_position: 14
---
# Remote Catalogs

Remote Catalogs bring a **GitOps and infrastructure-as-code approach** to Otoroshi configuration management. Instead of manually configuring routes, backends, API keys, and other entities through the admin UI or API, you define them as JSON or YAML files in an external source of truth -- a Git repository, an S3 bucket, a Consul KV store, or any HTTP endpoint -- and let Otoroshi synchronize them automatically.

### The problem they solve

In production environments, managing API gateway configuration through a UI or imperative API calls can lead to configuration drift, lack of auditability, and difficulty reproducing environments. Remote Catalogs solve this by treating Otoroshi configuration as code: entity definitions are versioned, reviewed, and deployed through the same workflows you already use for application code.

### How they work

When a Remote Catalog is deployed (either manually or on a schedule), Otoroshi performs a **full reconciliation cycle**:

1. **Fetch** -- Otoroshi connects to the configured external source and retrieves entity definitions
2. **Parse** -- Each file is parsed as JSON or YAML (with support for multi-document YAML files separated by `---`)
3. **Compare** -- Fetched entities are compared with the current state of entities in Otoroshi that were previously managed by this catalog
4. **Reconcile** -- Entities are **created**, **updated**, or **deleted** to make the Otoroshi state match the external source of truth

This is not a one-way import: if an entity is removed from the external source, it will also be removed from Otoroshi on the next sync. Otoroshi tracks which entities were created by each catalog through metadata tagging, so manually created entities are never affected.

### Supported sources

Remote Catalogs support a wide range of external sources:

- **Git hosting platforms**: GitHub, GitLab, Bitbucket, Gitea, Forgejo, Codeberg (using their respective APIs with token-based authentication)
- **Git repositories**: any Git repository cloned directly
- **Object storage**: S3-compatible buckets (AWS S3, MinIO, etc.)
- **HTTP endpoints**: any URL returning entity definitions (custom config servers, CI/CD artifact stores, etc.)
- **Key-value stores**: Consul KV
- **Local filesystem**: file paths on the Otoroshi host

### Key features

- **Full reconciliation**: not just creates, but also updates and deletes -- the external source is the single source of truth
- **Flexible scheduling**: fixed-interval polling, cron expressions, or manual deployment
- **Dry-run mode**: test what a deployment would do before applying changes
- **Undeploy**: cleanly remove all entities that were created by a catalog
- **Multi-format**: JSON and YAML, including multi-document YAML files and Kubernetes-style manifests
- **Webhook triggers**: some sources support webhook-based deployment in addition to polling
- **Deploy listings**: use a listing file to control exactly which entity files are deployed and in what order

### When to use Remote Catalogs

- **GitOps workflows**: store your Otoroshi configuration alongside your application code in Git, and have changes deployed automatically when merged
- **CI/CD pipelines**: generate or update entity definitions as part of your build pipeline and push them to a source that Otoroshi watches
- **Environment synchronization**: share a common configuration baseline across development, staging, and production environments
- **Disaster recovery**: rebuild an Otoroshi instance from a known, versioned configuration stored externally
- **Multi-team collaboration**: let different teams manage their own route and API key definitions through pull requests, with review and approval workflows

The underlying philosophy is straightforward: Otoroshi configuration should be treated like any other piece of infrastructure -- defined declaratively, stored in version control, reviewed through standard processes, and deployed automatically.

## UI page

You can find all remote catalogs [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/remote-catalogs)

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | Unique identifier of the remote catalog |
| `name` | string | Display name |
| `description` | string | Description of the catalog |
| `enabled` | boolean | Whether the catalog is active |
| `source_kind` | string | The type of source to fetch entities from (see below) |
| `source_config` | object | Source-specific configuration |
| `scheduling` | object | Optional auto-sync scheduling configuration |
| `test_deploy_args` | object | Arguments used for manual test/dry-run |
| `tags` | array of string | Tags |
| `metadata` | object | Key/value metadata |

## Source kinds

| Source | Description |
|--------|-------------|
| `http` | Fetch entities from an HTTP/HTTPS endpoint |
| `file` | Read entities from a local file path |
| `github` | Sync from a GitHub repository |
| `gitlab` | Sync from a GitLab repository |
| `bitbucket` | Sync from a Bitbucket repository |
| `git` | Sync from any Git repository via clone |
| `s3` | Fetch entities from an S3-compatible bucket |
| `consulkv` | Read entities from a Consul KV store |
| `gitea` | Sync from a Gitea repository |
| `forgejo` | Sync from a Forgejo repository |
| `codeberg` | Sync from a Codeberg repository |

### Source configuration

Each source kind requires specific configuration. Common fields include:

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | Source URL or endpoint |
| `token` | string | Authentication token (for Git providers) |
| `branch` | string | Git branch to sync from (default: `main`) |
| `path` | string | Path or prefix within the source |
| `headers` | object | Additional HTTP headers (for HTTP sources) |

## Scheduling

The catalog can be deployed manually or on a schedule:

| Property | Type | Description |
|----------|------|-------------|
| `enabled` | boolean | Enable scheduled deployment |
| `kind` | string | `ScheduledEvery` (fixed interval) or `CronExpression` |
| `interval` | number | Interval in milliseconds (for `ScheduledEvery`) |
| `cron_expression` | string | Cron expression (for `CronExpression`) |
| `initial_delay` | number | Initial delay in milliseconds before first run |
| `deploy_args` | object | Arguments passed during each scheduled deploy |

## Reconciliation

When a catalog is deployed, Otoroshi performs a full reconciliation:

1. **Fetch**: Entities are fetched from the configured source
2. **Parse**: Each entity is parsed (JSON or YAML format, with support for multi-document YAML separated by `---`)
3. **Compare**: Fetched entities are compared with existing entities in Otoroshi
4. **Reconcile**: Entities are created, updated, or deleted to match the remote state

This ensures that the Otoroshi state always matches the external source of truth.

## JSON example

A GitHub-based remote catalog syncing every 5 minutes:

```json
{
  "id": "catalog_github_infra",
  "name": "Infrastructure catalog",
  "description": "Sync routes and backends from GitHub infrastructure repo",
  "enabled": true,
  "source_kind": "github",
  "source_config": {
    "url": "https://github.com/my-org/otoroshi-config",
    "token": "${vault://github_token}",
    "branch": "main",
    "path": "otoroshi/"
  },
  "scheduling": {
    "enabled": true,
    "kind": "ScheduledEvery",
    "interval": 300000,
    "initial_delay": 10000,
    "deploy_args": {}
  },
  "test_deploy_args": {},
  "tags": ["infra", "gitops"],
  "metadata": {}
}
```

An HTTP-based catalog for a simple endpoint:

```json
{
  "id": "catalog_http_config",
  "name": "Config server catalog",
  "description": "Fetch entities from internal config server",
  "enabled": true,
  "source_kind": "http",
  "source_config": {
    "url": "https://config-server.internal/otoroshi/entities",
    "headers": {
      "Authorization": "Bearer ${vault://config_token}"
    }
  },
  "scheduling": {
    "enabled": true,
    "kind": "CronExpression",
    "cron_expression": "0 */10 * * * ?",
    "initial_delay": 5000,
    "deploy_args": {}
  },
  "tags": [],
  "metadata": {}
}
```

## Admin API

The admin API is available at:

```
GET    /apis/catalogs.otoroshi.io/v1/remote-catalogs           # List all catalogs
POST   /apis/catalogs.otoroshi.io/v1/remote-catalogs           # Create a catalog
GET    /apis/catalogs.otoroshi.io/v1/remote-catalogs/:id       # Get a catalog
PUT    /apis/catalogs.otoroshi.io/v1/remote-catalogs/:id       # Update a catalog
DELETE /apis/catalogs.otoroshi.io/v1/remote-catalogs/:id       # Delete a catalog
```

Additional action endpoints:

```
POST /api/extensions/remote-catalogs/_deploy     # Deploy one or more catalogs
POST /api/extensions/remote-catalogs/_undeploy   # Undeploy one or more catalogs
POST /extensions/remote-catalogs/_test           # Dry-run a catalog
```

## Learn more

For detailed information about entity format, supported sources, reconciliation, webhook deployment, and available plugins, see [Remote Catalogs detailed topic](../topics/remote-catalogs.md).
