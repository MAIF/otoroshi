---
title: Remote Catalogs
sidebar_position: 14
---
# Remote Catalogs

A Remote Catalog allows Otoroshi to synchronize entities from external sources such as GitHub, GitLab, Bitbucket, Gitea, Forgejo, Codeberg, S3, HTTP endpoints, Git repositories, Consul KV stores, or local files. It provides an infrastructure-as-code approach where entity definitions are stored externally and deployed into Otoroshi with full reconciliation (create, update, delete).

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
