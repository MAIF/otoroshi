---
title: Admin REST API
sidebar_label: "Admin REST API"
sidebar_position: 90
---
# Admin REST API

Otoroshi provides a fully featured REST admin API to perform almost every operation possible in the Otoroshi dashboard. The Otoroshi dashbaord is just a regular consumer of the admin API.

Using the admin API, you can do whatever you want and enhance your Otoroshi instances with a lot of features that will feet your needs.

## OpenAPI descriptor

The Otoroshi admin API is described using OpenAPI format and is available at :

https://maif.github.io/otoroshi/manual/code/openapi.json

Every Otoroshi instance provides its own embedded OpenAPI descriptor at :

http://otoroshi.oto.tools:8080/apis/openapi.json

## OpenAPI documentation

You can read the OpenAPI descriptor in a more human friendly fashion using `Swagger UI`. The swagger UI documentation of the Otoroshi admin API is available [here](../api-reference)

## Documentation resources

Otoroshi also exposes a set of endpoints that describe the instance itself (admin API, workflow DSL, available plugins). These endpoints are intended to be consumed by tooling (documentation sites, code generators, IDE integrations, etc.).

| Endpoint                                         | Description                                  |
|--------------------------------------------------|----------------------------------------------|
| `GET /apis/openapi`                              | OpenAPI descriptor (content negotiated)      |
| `GET /apis/openapi.json`                         | OpenAPI descriptor as JSON                   |
| `GET /apis/openapi.yaml` / `.yml`                | OpenAPI descriptor as YAML                   |
| `GET /apis/openapi/ui`                           | Embedded Swagger UI for the descriptor       |
| `GET /apis/workflows/doc`                        | Workflow DSL reference (HTML)                |
| `GET /apis/workflows/doc.json`                   | Workflow DSL descriptor as JSON              |
| `GET /apis/workflows/doc.yaml` / `.yml`          | Workflow DSL descriptor as YAML              |
| `GET /apis/workflows/doc.md`                     | Workflow DSL reference as Markdown           |
| `GET /apis/plugins.json`                         | Catalog of available plugins as JSON         |
| `GET /apis/plugins.yaml` / `.yml`                | Catalog of available plugins as YAML         |

### Protecting documentation resources

By default, those endpoints are publicly accessible, which may leak information about the admin API surface, the enabled plugins or the workflow DSL. You can protect them behind a shared secret by setting:

```conf
otoroshi.doc-resources.accessKey = "a-very-strong-secret"
```

or through the environment variable `OTOROSHI_DOC_RESOURCES_ACCESS_KEY`.

Once configured, clients must provide the secret using either:

- HTTP Basic Auth, with username `doc` and the configured secret as the password:

  ```
  Authorization: Basic <base64("doc:<secret>")>
  ```

- or the `doc_secret` query parameter:

  ```
  GET /apis/openapi.json?doc_secret=<secret>
  ```

Requests without valid credentials are answered with a `401 Unauthorized` and a `WWW-Authenticate: Basic` challenge.

:::note
When Otoroshi runs in development mode, documentation resources are always accessible without credentials. When no `accessKey` is configured, the endpoints remain open to preserve backward compatibility.
:::

