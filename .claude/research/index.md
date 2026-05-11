# Claude Research Notes

This folder contains research notes to facilitate documentation and exploration of the Otoroshi project.

## Notes Index

### General Architecture
- [Project Overview](./architecture-overview.md) - General structure and components

### `app/` Directory - Scala Backend (complete)

| Note | Directory(ies) | Description |
|------|----------------|-------------|
| [app-actions.md](./app-actions.md) | `actions/` | Play Action Builders for auth (API, BackOffice, PrivateApps) |
| [app-api.md](./app-api.md) | `api/` | Generic CRUD framework and OpenAPI generation |
| [app-auth.md](./app-auth.md) | `auth/` | Authentication modules (OAuth, LDAP, SAML, Basic, WASM) |
| [app-cluster.md](./app-cluster.md) | `cluster/` | Distributed cluster mode (Leader/Worker) |
| [app-controllers.md](./app-controllers.md) | `controllers/` | Play HTTP Controllers (admin API, backoffice, etc.) |
| [app-el-env-health.md](./app-el-env-health.md) | `el/`, `env/`, `health/` | Expression Language, Global Environment, Health checks |
| [app-events.md](./app-events.md) | `events/` | Event system and Data Exporters |
| [app-gateway.md](./app-gateway.md) | `gateway/` | Legacy HTTP proxy core (circuit breakers, chaos) |
| [app-greenscore-jobs-metrics.md](./app-greenscore-jobs-metrics.md) | `greenscore/`, `jobs/`, `metrics/` | GreenScore, scheduled jobs, metrics |
| [app-models.md](./app-models.md) | `models/` | Data entities (ApiKey, ServiceDescriptor, etc.) |
| [app-netty-openapi-security.md](./app-netty-openapi-security.md) | `netty/`, `openapi/`, `security/` | Netty/HTTP3 server, OpenAPI generation, crypto |
| [app-next.md](./app-next.md) | `next/` | **New proxy engine** (NgRoute, NgBackend, plugins) |
| [app-plugins-script-tcp-utils-wasm.md](./app-plugins-script-tcp-utils-wasm.md) | `plugins/`, `script/`, `tcp/`, `utils/`, `wasm/` | Plugins, script framework, TCP proxy, WASM |
| [app-ssl-storage.md](./app-ssl-storage.md) | `ssl/`, `storage/` | PKI/TLS/ACME and multi-backend abstraction |

## Quick Directory Overview

```
app/
├── actions/        → Auth interceptors (API, BackOffice, PrivateApps)
├── api/            → Generic CRUD framework + OpenAPI
├── auth/           → Auth modules (OAuth, OIDC, LDAP, SAML, Basic, WASM)
├── cluster/        → Distributed Leader/Worker mode
├── controllers/    → 9 controllers + 24 admin API
├── el/             → Expression Language (${req.path}, ${ctx.field})
├── env/            → Global environment (Env.scala)
├── events/         → Analytics, alerts, audit, exporters
├── gateway/        → Legacy HTTP proxy (handlers, circuit breakers)
├── greenscore/     → Route ecological score
├── health/         → Backend health checks
├── jobs/           → Scheduled jobs (certs, rotation, sync)
├── metrics/        → Prometheus, OpenTelemetry
├── models/         → Legacy entities (ServiceDescriptor, ApiKey, etc.)
├── netty/          → Netty HTTP server (HTTP/3)
├── next/           → NEW ENGINE (NgRoute, NgBackend, 70+ plugins)
├── openapi/        → OpenAPI generation + K8s CRDs
├── plugins/        → 40+ legacy plugins + K8s integration
├── script/         → Plugin framework, dynamic compilation
├── security/       → OtoroshiClaim, IdGenerator, crypto
├── ssl/            → PKI, certificates, ACME, OCSP
├── storage/        → Multi-backend abstraction (Redis, PG, Cassandra)
├── tcp/            → TCP proxy (non-HTTP)
├── utils/          → Shared utilities
└── wasm/           → WebAssembly plugin support
```

## How to Use These Notes

1. **Before any task**: Check existing notes for the relevant domain
2. **During exploration**: Update notes with new discoveries
3. **For documentation**: Use these notes as a base to improve official docs

## Conventions

- One file per directory or group of related directories
- Naming: `app-<directory>.md`
- Structure: Overview → Detailed files → Relationships → Key points

## Recommended Entry Points

To understand the project:
1. **Start with** `architecture-overview.md` for the overview
2. **For routing** → `app-next.md` (new engine) or `app-gateway.md` (legacy)
3. **For auth** → `app-auth.md` + `app-actions.md`
4. **For storage** → `app-ssl-storage.md`
5. **For plugins** → `app-next.md` (plugins section) + `app-plugins-script-tcp-utils-wasm.md`
