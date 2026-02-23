# Remote Catalogs

A Remote Catalog allows Otoroshi to synchronize entities from external sources such as GitHub, GitLab, Bitbucket, S3, HTTP endpoints, Git repositories, Consul KV stores, or local files. It provides an infrastructure-as-code approach where entity definitions are stored externally and deployed into Otoroshi with full reconciliation (create, update, delete).

## UI page

You can find all remote catalogs [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/remote-catalogs)

## Properties

* `id`: unique identifier of the remote catalog
* `name`: display name
* `description`: description of the catalog
* `enabled`: whether the catalog is active
* `source_kind`: the type of source to fetch entities from (`http`, `file`, `github`, `gitlab`, `bitbucket`, `git`, `s3`, `consulkv`)
* `source_config`: source-specific configuration (URL, token, path, branch, etc.)
* `scheduling`: optional auto-sync scheduling configuration
    * `enabled`: enable scheduled deployment
    * `kind`: `ScheduledEvery` (fixed interval) or `CronExpression`
    * `interval`: interval in milliseconds (for `ScheduledEvery`)
    * `cron_expression`: cron expression (for `CronExpression`)
    * `initial_delay`: initial delay in milliseconds before first run
    * `deploy_args`: arguments passed during each scheduled deploy
* `test_deploy_args`: arguments used for manual test/dry-run

## API

The admin API is available at:

```
http://otoroshi-api.oto.tools:8080/apis/catalogs.otoroshi.io/v1/remote-catalogs
```

Additional action endpoints:

* `POST /api/extensions/remote-catalogs/_deploy` - deploy one or more catalogs
* `POST /api/extensions/remote-catalogs/_undeploy` - undeploy one or more catalogs
* `POST /extensions/remote-catalogs/_test` - dry-run a catalog

## Learn more

For detailed information about entity format, supported sources, reconciliation, webhook deployment, and available plugins, see @ref:[Remote Catalogs detailed topic](../topics/remote-catalogs.md).
