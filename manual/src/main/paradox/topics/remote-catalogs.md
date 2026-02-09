# Remote Catalogs

Remote Catalogs brings **Infrastructure as Code (IaC)** and **GitOps** capabilities to Otoroshi. 
It allows you to define your API gateway configuration (routes, backends, apikeys, certificates, etc.) as declarative files stored in Git repositories, S3 buckets, Consul KV, or any HTTP endpoint, and have Otoroshi automatically synchronize and reconcile the desired state.

This enables DevOps teams to manage their API gateway configuration using the same workflows they use for application code: version control, pull requests, code review, CI/CD pipelines, and automated deployments. 
Changes pushed to a Git repository can be automatically deployed to Otoroshi via webhooks or scheduled polling, ensuring that the running configuration always matches the declared state.

## How it works

A Remote Catalog is an entity that defines:

- A **source** where entity definitions are stored (GitHub, GitLab, Bitbucket, S3, HTTP, local file, Git repository, Consul KV)
- An optional **scheduling** configuration to automatically sync entities at regular intervals
- A **reconciliation** engine that creates, updates, and deletes local entities to match the remote desired state

When a catalog is deployed, Otoroshi fetches the entity definitions from the remote source, then reconciles them with the local state using a declarative approach similar to tools like Terraform or Kubernetes: the remote source represents the desired state, and Otoroshi converges towards it. Entities managed by a catalog are tagged with a `created_by` metadata field set to `remote_catalog=<catalog_id>`, which allows Otoroshi to track ownership and lifecycle of managed entities.

## Entity format

Remote entities can be defined in JSON or YAML format. Multi-document YAML files (using `---` as separator) are supported. Each entity must contain at least an `id` field and a `kind` field. The `kind` field should match one of the existing Otoroshi resource kinds (e.g., `Route`, `Backend`, `ApiKey`, `Certificate`, etc.). You can also use the fully qualified kind with group prefix, like `proxy.otoroshi.io/Route`. This is the RECOMMENDED format to perform well !

```json
{
  "id": "my-route-1",
  "kind": "Route",
  "name": "My Route",
  "frontend": {
    "domains": ["myapi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "my-backend.internal",
        "port": 8080
      }
    ]
  }
}
```

or in YAML:

```yaml
id: my-route-1
kind: Route
name: My Route
frontend:
  domains:
    - myapi.oto.tools
backend:
  targets:
    - hostname: my-backend.internal
      port: 8080
```

Multiple entities can be placed in a single file:

```yaml
id: my-route-1
kind: Route
name: My Route 1
frontend:
  domains:
    - api1.oto.tools
backend:
  targets:
    - hostname: backend1.internal
      port: 8080
---
id: my-route-2
kind: Route
name: My Route 2
frontend:
  domains:
    - api2.oto.tools
backend:
  targets:
    - hostname: backend2.internal
      port: 8080
```

## Path modes

When configuring a source, the `path` parameter determines how entities are fetched:

- **File path** (e.g., `entities/routes.json`): fetches a single file and parses it for entities
- **Directory path** (e.g., `entities/`): lists all `.json`, `.yaml`, and `.yml` files in the directory and parses each one
- **Catalog listing file** (e.g., `catalog.json`): a JSON file containing an array of relative file paths to fetch

A catalog listing file looks like this:

```json
[
  "./routes/route1.json",
  "./route2.yaml",
  "./backends/backend1.json",
  "./apikeys/key1.yaml"
]
```

This allows fine-grained control over which files should be imported during deployment.

## Remote Catalog configuration

A Remote Catalog entity has the following structure:

```javascript
{
  "id": "my-catalog",                // unique identifier
  "name": "My Remote Catalog",       // display name
  "description": "...",              // description
  "enabled": true,                   // is the catalog active
  "source_kind": "github",          // source type (see below)
  "source_config": { ... },         // source-specific configuration
  "scheduling": {                    // optional auto-sync schedule
    "enabled": false,
    "kind": "ScheduledEvery",        // ScheduledEvery or CronExpression
    "interval": 60000,               // interval in milliseconds (for ScheduledEvery)
    "cron_expression": "0 */5 * * * ?", // cron expression (for CronExpression)
    "initial_delay": 10000,          // initial delay in milliseconds
    "deploy_args": {}                // arguments passed during scheduled deploy
  },
  "test_deploy_args": {}             // arguments for manual test/dry-run
}
```

## Supported sources

### HTTP

Fetches entities from an HTTP endpoint.

```javascript
{
  "source_kind": "http",
  "source_config": {
    "url": "https://my-server.com/entities.json",  // URL to fetch
    "headers": {                                     // optional HTTP headers
      "Authorization": "Bearer my-token"
    },
    "timeout": 30000                                 // request timeout in ms (default: 30000)
  }
}
```

### File

Reads entities from the local filesystem.

```javascript
{
  "source_kind": "file",
  "source_config": {
    "path": "/path/to/entities",          // file or directory path
    "pre_command": ["sh", "-c", "..."]    // optional command to run before reading
  }
}
```

The `pre_command` can be used to run a script before reading (e.g., to pull from a repository).

### GitHub

Fetches entities from a GitHub repository using the GitHub API.

```javascript
{
  "source_kind": "github",
  "source_config": {
    "repo": "https://github.com/owner/repo",  // repository URL
    "branch": "main",                          // branch name (default: main)
    "path": "entities/",                       // file or directory path in the repo
    "token": "ghp_xxx",                        // optional personal access token
    "base_url": "https://api.github.com"       // API base URL (for GitHub Enterprise)
  }
}
```

Supports webhook-triggered deployments (see the [Webhook deployment](#webhook-deployment) section).

### GitLab

Fetches entities from a GitLab repository using the GitLab API.

```javascript
{
  "source_kind": "gitlab",
  "source_config": {
    "repo": "group/project",                    // project path (URL-encoded automatically)
    "branch": "main",                           // branch name (default: main)
    "path": "entities/",                        // file or directory path in the repo
    "token": "glpat-xxx",                       // optional private token
    "base_url": "https://gitlab.com"            // GitLab instance URL (for self-hosted)
  }
}
```

Supports webhook-triggered deployments (see the [Webhook deployment](#webhook-deployment) section).

### Bitbucket

Fetches entities from a Bitbucket Cloud repository using the Bitbucket API 2.0.

```javascript
{
  "source_kind": "bitbucket",
  "source_config": {
    "repo": "https://bitbucket.org/workspace/repo",  // repository URL
    "branch": "main",                                  // branch name (default: main)
    "path": "entities/",                               // file or directory path
    "token": "xxx",                                    // app password or OAuth token
    "username": "my-user",                             // username (for Basic auth with app password)
    "base_url": "https://api.bitbucket.org"            // API base URL (for Bitbucket Server)
  }
}
```

If `username` is provided, authentication uses Basic auth (`username:token`). Otherwise, Bearer token authentication is used.

Supports webhook-triggered deployments (see the [Webhook deployment](#webhook-deployment) section).

### Git (generic)

Clones a Git repository and reads entities from the local clone. Works with any Git hosting provider or self-hosted repository.

```javascript
{
  "source_kind": "git",
  "source_config": {
    "repo": "https://github.com/owner/repo.git",  // repository URL (HTTPS or SSH)
    "branch": "main",                               // branch name (default: main)
    "path": "entities/",                            // file or directory path in the repo
    "token": "ghp_xxx",                             // optional token (injected in HTTPS URL)
    "username": "oauth2",                           // optional username for HTTPS auth
    "ssh_private_key_path": "/path/to/id_rsa"       // SSH private key path (for SSH URLs)
  }
}
```

The git source uses shallow clones (`--depth 1`) for efficiency. Subsequent fetches reuse the existing clone directory and do a `git fetch --all` + `git reset --hard` to update.

For SSH authentication, the `ssh_private_key_path` is passed via `GIT_SSH_COMMAND` environment variable with `StrictHostKeyChecking=no`.

### S3

Fetches entities from an Amazon S3 (or compatible) bucket.

```javascript
{
  "source_kind": "s3",
  "source_config": {
    "bucket": "my-bucket",                          // S3 bucket name
    "key": "entities/deploy.json",                  // object key (file path)
    "endpoint": "https://s3.amazonaws.com",         // S3 endpoint URL
    "region": "eu-west-1",                          // AWS region
    "access": "AKIAIOSFODNN7EXAMPLE",               // AWS access key ID
    "secret": "wJalrXUtnFEMI/K7MDENG/bPxRfiCY..."   // AWS secret access key
  }
}
```

Works with any S3-compatible storage (MinIO, DigitalOcean Spaces, etc.) by changing the `endpoint` URL.

### Consul KV

Fetches entities from a Consul KV store.

```javascript
{
  "source_kind": "consulkv",
  "source_config": {
    "endpoint": "http://localhost:8500",  // Consul HTTP API endpoint
    "prefix": "otoroshi/entities",       // KV key prefix (file or directory)
    "token": "xxx",                      // optional ACL token
    "dc": "dc1"                          // optional datacenter
  }
}
```

When `prefix` points to a key with a file extension (e.g., `otoroshi/entities/routes.json`), it fetches that single key. Otherwise, it lists all keys under the prefix and fetches each one that has a `.json`, `.yaml`, or `.yml` extension.

## Deploy, dry-run, and undeploy

### Deploy

Deploying a catalog fetches entities from the remote source and reconciles them with the local state:

- **New entities** are created with `created_by: remote_catalog=<catalog_id>` metadata
- **Existing entities** (same ID) are updated
- **Orphaned entities** (present locally with the catalog's metadata but absent from remote) are deleted

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/extensions/remote-catalogs/_deploy' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' \
  -d '[{"id": "my-catalog", "args": {}}]'
```

You can deploy multiple catalogs in a single request by adding more entries to the array.

### Dry-run (test)

A dry-run performs the same fetch and reconciliation logic but without actually writing any changes. It returns a report showing what would be created, updated, or deleted.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/extensions/remote-catalogs/_test' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' \
  -d '{"id": "my-catalog", "args": {}}'
```

### Undeploy

Undeploying a catalog removes all entities that were created by that catalog (identified by the `created_by` metadata).

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/extensions/remote-catalogs/_undeploy' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' \
  -d '[{"id": "my-catalog"}]'
```

### Deploy report

All operations return a deploy report with per-entity-kind counts:

```json
{
  "catalog_id": "my-catalog",
  "results": [
    {
      "entity_kind": "proxy.otoroshi.io/Route",
      "created": 2,
      "updated": 1,
      "deleted": 0,
      "errors": []
    },
    {
      "entity_kind": "proxy.otoroshi.io/Backend",
      "created": 1,
      "updated": 0,
      "deleted": 1,
      "errors": []
    }
  ],
  "timestamp": "2024-01-01T00:00:00.000Z"
}
```

## Scheduling

Remote catalogs can be configured to automatically deploy at regular intervals. When scheduling is enabled, a job is registered in the Otoroshi job manager.

Two scheduling modes are available:

- **ScheduledEvery**: runs the deploy at a fixed interval (e.g., every 60 seconds)
- **CronExpression**: runs the deploy according to a cron expression

```javascript
{
  "scheduling": {
    "enabled": true,
    "kind": "ScheduledEvery",
    "interval": 60000,         // every 60 seconds
    "initial_delay": 10000,    // wait 10 seconds before first run
    "deploy_args": {}          // arguments passed to deploy
  }
}
```

```javascript
{
  "scheduling": {
    "enabled": true,
    "kind": "CronExpression",
    "cron_expression": "0 */5 * * * ?",  // every 5 minutes
    "deploy_args": {}
  }
}
```

## Webhook deployment

For Git-based sources (GitHub, GitLab, Bitbucket), you can set up webhook-triggered deployments. This allows Otoroshi to automatically deploy entities when changes are pushed to the repository.

To set this up, create a route in Otoroshi with the `Remote Catalog Deploy Webhook` plugin and expose it to your Git provider as a webhook URL.

### Webhook plugin configuration

```javascript
{
  "plugin": "cp:otoroshi.next.catalogs.RemoteCatalogDeployWebhook",
  "config": {
    "catalog_refs": ["my-github-catalog", "my-gitlab-catalog"],  // catalogs to consider
    "source_type": "github"                                       // or "gitlab", "bitbucket"
  }
}
```

When a push event is received:

1. The plugin identifies the source type and parses the webhook payload
2. It matches the repository and branch against configured catalogs
3. Matched catalogs are deployed automatically

### GitHub webhook

Set up a webhook in your GitHub repository settings pointing to your Otoroshi route. The plugin matches on `repository.full_name` and `ref` (branch) from the push event payload.

### GitLab webhook

Set up a webhook in your GitLab project settings. The plugin matches on `project.web_url` and `ref` from the push event payload.

### Bitbucket webhook

Set up a webhook in your Bitbucket repository settings. The plugin matches on `repository.full_name` and `push.changes[].new.name` from the push event payload.

## Other plugins

In addition to the webhook plugin, two other plugins are available for programmatic deployment:

### Remote Catalog Deploy Single

Deploys entities from a single catalog. The request body is passed as deploy arguments.

```javascript
{
  "plugin": "cp:otoroshi.next.catalogs.RemoteCatalogDeploySingle",
  "config": {
    "catalog_ref": "my-catalog"
  }
}
```

### Remote Catalog Deploy Many

Deploys entities from multiple catalogs. The request body should be an array of `{id, args}` objects. Only catalogs listed in `catalog_refs` are allowed.

```javascript
{
  "plugin": "cp:otoroshi.next.catalogs.RemoteCatalogDeployMany",
  "config": {
    "catalog_refs": ["catalog-1", "catalog-2", "catalog-3"]
  }
}
```

Request body:

```json
[
  {"id": "catalog-1", "args": {}},
  {"id": "catalog-2", "args": {}}
]
```

## Admin API

Remote catalogs are managed through the standard Otoroshi admin API:

```sh
# List all remote catalogs
curl 'http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs' \
  -u admin-api-apikey-id:admin-api-apikey-secret

# Get a specific remote catalog
curl 'http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs/my-catalog' \
  -u admin-api-apikey-id:admin-api-apikey-secret

# Create a remote catalog
curl -X POST 'http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "my-catalog",
    "name": "My HTTP Catalog",
    "enabled": true,
    "source_kind": "http",
    "source_config": {
      "url": "https://my-server.com/entities.json"
    }
  }'

# Update a remote catalog
curl -X PUT 'http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs/my-catalog' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -H 'Content-Type: application/json' \
  -d '{ ... }'

# Delete a remote catalog
curl -X DELETE 'http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs/my-catalog' \
  -u admin-api-apikey-id:admin-api-apikey-secret
```

## Backoffice UI

Remote catalogs can be managed from the Otoroshi admin backoffice. Navigate to the extensions section and select "Remote Catalogs". From there you can:

- Create, edit, and delete remote catalogs
- Configure the source type and source-specific settings
- Deploy a catalog manually using the deploy button
- Test a catalog with a dry-run to see what would change
- Undeploy a catalog to remove all its managed entities
- Configure scheduling for automatic synchronization
